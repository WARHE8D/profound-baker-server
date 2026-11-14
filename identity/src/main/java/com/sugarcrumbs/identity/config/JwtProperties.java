package com.sugarcrumbs.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for the Owner's tokens ({@code app.security.jwt.*}).
 *
 * @param issuer          {@code iss} claim written into access tokens and required when validating them
 * @param accessTokenTtl  lifetime of an access token; short, because it cannot be revoked
 * @param refreshTokenTtl lifetime of a refresh token; every refresh issues a new one with a fresh lifetime
 * @param privateKey      RS256 signing key: PKCS#8, as PEM or as base64 of the DER bytes. Required in prod
 *                        ({@code APP_JWT_PRIVATE_KEY}); when blank elsewhere a throwaway key is generated at
 *                        startup, so tokens stop working after a restart
 */
@ConfigurationProperties("app.security.jwt")
public record JwtProperties(
        @DefaultValue("sugarcrumbs") String issuer,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("14d") Duration refreshTokenTtl,
        String privateKey
) {
}
