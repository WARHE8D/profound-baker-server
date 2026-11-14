package com.sugarcrumbs.identity.web;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Publishes the public half of the signing key, so services extracted later (and the
 * gateway) can validate the Owner's access tokens without sharing a secret.
 */
@RestController
public class JwksController {

    private final JWKSet publicKeys;

    public JwksController(RSAKey jwtSigningKey) {
        // toPublicJWK() drops every private parameter; only n, e, kid, use and alg remain.
        this.publicKeys = new JWKSet(jwtSigningKey.toPublicJWK());
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return publicKeys.toJSONObject();
    }
}
