package com.sugarcrumbs.identity.web;

import com.sugarcrumbs.identity.application.AuthService;
import com.sugarcrumbs.identity.application.AuthService.IssuedTokens;
import com.sugarcrumbs.identity.domain.Owner;
import com.sugarcrumbs.identity.application.OwnerService;
import com.sugarcrumbs.identity.web.dto.LoginRequest;
import com.sugarcrumbs.identity.web.dto.OwnerResponse;
import com.sugarcrumbs.identity.web.dto.RefreshRequest;
import com.sugarcrumbs.identity.web.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final OwnerService ownerService;

    public AuthController(AuthService authService, OwnerService ownerService) {
        this.authService = authService;
        this.ownerService = ownerService;
    }

    /** 401 with the same message whether the email or the password is wrong. */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return noStore(authService.login(request.email(), request.password()));
    }

    /** The presented refresh token is spent; presenting it again later revokes every token from that login. */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return noStore(authService.refresh(request.refreshToken()));
    }

    /** Revokes the session the refresh token belongs to. Owner only. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RefreshRequest request) {
        authService.logout(ownerId(jwt), request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /** The signed-in Owner. Owner only. */
    @GetMapping("/me")
    public OwnerResponse me(@AuthenticationPrincipal Jwt jwt) {
        Owner owner = ownerService.getOwner(ownerId(jwt));
        return new OwnerResponse(owner.getId(), owner.getEmail(), owner.getOwnerName(), owner.getBusinessName());
    }

    private static UUID ownerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /** Tokens must never land in a browser or proxy cache. */
    private static ResponseEntity<TokenResponse> noStore(IssuedTokens tokens) {
        long expiresIn = Duration.between(Instant.now(), tokens.accessTokenExpiresAt()).toSeconds();
        TokenResponse body = new TokenResponse(tokens.accessToken(), "Bearer", Math.max(expiresIn, 0),
                tokens.refreshToken(), tokens.refreshTokenExpiresAt());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
