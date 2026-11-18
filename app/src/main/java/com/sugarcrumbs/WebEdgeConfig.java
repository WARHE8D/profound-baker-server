package com.sugarcrumbs;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * What the browser-facing edge allows: CORS for the SPA's origin only, and rate
 * limits on login and checkout. Both cover the whole deployable, so they sit next
 * to {@link SecurityConfig} rather than in a business module.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(RateLimitProperties.class)
class WebEdgeConfig {

    /** Just ahead of Spring Security's filter chain (order -100), so a throttled request does no auth work. */
    static final int RATE_LIMIT_FILTER_ORDER = -105;

    /** Right after Spring's observation filter (highest precedence + 1), which opens the request's span. */
    static final int REQUEST_ID_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    /**
     * Only the configured origins ({@code app.cors.allowed-origins}, comma-separated, exact
     * matches). No credentials mode: the SPA sends its token in the Authorization header and
     * the API sets no cookies (see StatelessAuthGuardTests). With no origin configured,
     * browsers on other origins are refused, which is the safe default.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException("app.cors.allowed-origins must list exact origins, not wildcards");
        }

        CorsConfiguration api = new CorsConfiguration();
        api.setAllowedOrigins(origins);
        api.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        api.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, RequestIdFilter.HEADER));
        // X-Request-Id: so the SPA can show it with an error, for the Owner to pass on.
        api.setExposedHeaders(List.of(HttpHeaders.LOCATION, HttpHeaders.RETRY_AFTER, "X-RateLimit-Remaining",
                RequestIdFilter.HEADER));
        api.setAllowCredentials(false);
        api.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", api);
        return source;
    }

    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            RateLimitProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(properties, resolver));
        registration.setOrder(RATE_LIMIT_FILTER_ORDER);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(REQUEST_ID_FILTER_ORDER);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }
}
