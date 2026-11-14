package com.sugarcrumbs.identity.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Key material and codecs for the Owner's tokens. Identity owns the signing key
 * (it becomes auth-service); the {@link JwtDecoder} built here is what the
 * monolith's resource-server filter chain validates bearer tokens with. Once
 * modules are extracted, other services validate against
 * {@code GET /.well-known/jwks.json} instead.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({JwtProperties.class, OwnerBootstrapProperties.class})
public class TokenConfig {

    private static final Logger log = LoggerFactory.getLogger(TokenConfig.class);

    /** CLAUDE.md §5: BCrypt with cost ≥ 12. */
    static final int BCRYPT_STRENGTH = 12;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    @Bean
    RSAKey jwtSigningKey(JwtProperties properties) {
        if (properties.privateKey() == null || properties.privateKey().isBlank()) {
            // Prod requires APP_JWT_PRIVATE_KEY (application-prod.properties has no default), so this is dev/test only.
            log.warn("No app.security.jwt.private-key configured: using a throwaway key; tokens stop working on restart");
            return RsaKeys.generate();
        }
        if (properties.privateKey().startsWith("${")) {
            // Binding leaves an unresolvable placeholder as-is; name the missing variable (not a secret).
            throw new IllegalStateException("app.security.jwt.private-key is not set: " + properties.privateKey()
                    + " is undefined");
        }
        return RsaKeys.parse(properties.privateKey());
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwtSigningKey)));
    }

    /** Checks the RS256 signature, expiry/not-before, and that we issued the token. */
    @Bean
    JwtDecoder jwtDecoder(RSAKey jwtSigningKey, JwtProperties properties) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(jwtSigningKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }
}
