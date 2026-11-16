package com.sugarcrumbs;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Who may call what, for the whole deployable. The app is a stateless OAuth2
 * resource server: owner routes need a bearer access token carrying
 * {@code ROLE_OWNER} (issued by the identity module's {@code /api/auth/login}),
 * validated by identity's {@code JwtDecoder} bean.
 *
 * <p>Default deny: anything not listed as public needs the Owner. The shopper
 * surface lives under {@code /api/public/**}: read-only, except guest checkout
 * ({@code POST /api/public/orders}), which is validated and rate-limited
 * ({@link RateLimitFilter}). CORS comes from {@link WebEdgeConfig}.
 *
 * <p>401/403 are handed to the MVC exception resolver, so they get the same
 * {@code ApiError} body as every other error ({@code GlobalExceptionHandler}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
                                    @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver)
            throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, ex) ->
                resolver.resolveException(request, response, null, ex);
        AccessDeniedHandler forbidden = (request, response, ex) ->
                resolver.resolveException(request, response, null, ex);

        http
                // No cookies or sessions: credentials travel only in the Authorization header,
                // which a cross-site form cannot set, so CSRF protection has nothing to protect.
                // StatelessAuthGuardTests fails the build if cookie/session/Basic/form auth is ever
                // added; re-enable CSRF before doing that. (CodeQL java/spring-disabled-csrf-protection
                // is dismissed for this line on that basis.)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(Customizer.withDefaults())
                // Uses WebEdgeConfig's CorsConfigurationSource; answers preflights before authorization.
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        // The shopper surface: public reads, and guest checkout as its only write.
                        .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/orders").permitAll()
                        // The guest's own order: the magic-link token in the path is the credential.
                        .requestMatchers(HttpMethod.POST, "/api/public/orders/by-token/*/cancel").permitAll()
                        // Image files (Caddy serves them directly in prod) and API docs.
                        .requestMatchers(HttpMethod.GET, "/uploads/**", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        // Actuator only listens on the internal management port (never proxied), and
                        // exposes only health and info there.
                        .requestMatchers("/actuator/**").permitAll()
                        // Error dispatches carry the original request's status and ApiError body.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().hasRole("OWNER"))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(ownerRolesConverter()))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden));
        return http.build();
    }

    /** Maps the token's {@code roles} claim (e.g. {@code ["OWNER"]}) to {@code ROLE_OWNER}. */
    private static JwtAuthenticationConverter ownerRolesConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
