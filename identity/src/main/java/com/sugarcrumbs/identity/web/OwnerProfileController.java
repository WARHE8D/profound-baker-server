package com.sugarcrumbs.identity.web;

import com.sugarcrumbs.identity.application.AuthService;
import com.sugarcrumbs.identity.application.OwnerService;
import com.sugarcrumbs.identity.web.dto.ChangePasswordRequest;
import com.sugarcrumbs.identity.web.dto.OwnerProfileRequest;
import com.sugarcrumbs.identity.web.dto.OwnerProfileResponse;
import com.sugarcrumbs.identity.web.mapper.OwnerMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The signed-in Owner's own profile and password. Owner only. */
@RestController
@RequestMapping("/api/owner")
public class OwnerProfileController {

    private final OwnerService ownerService;
    private final AuthService authService;
    private final OwnerMapper ownerMapper;

    public OwnerProfileController(OwnerService ownerService, AuthService authService, OwnerMapper ownerMapper) {
        this.ownerService = ownerService;
        this.authService = authService;
        this.ownerMapper = ownerMapper;
    }

    @GetMapping("/profile")
    public OwnerProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
        return ownerMapper.toProfileResponse(ownerService.getOwner(ownerId(jwt)));
    }

    /** 400 for an unknown or fixed-offset time zone. Changing the zone moves where every slot falls. */
    @PutMapping("/profile")
    public OwnerProfileResponse updateProfile(@AuthenticationPrincipal Jwt jwt,
                                              @Valid @RequestBody OwnerProfileRequest request) {
        return ownerMapper.toProfileResponse(ownerService.updateProfile(ownerId(jwt),
                request.businessName(), request.ownerName(), request.phone(), request.logoUrl(),
                ownerMapper.toAddress(request.address()), request.timeZone()));
    }

    /**
     * 204 on success, after which every refresh token is revoked: all sessions, this one
     * included, must log in again. 400 if the current password is wrong.
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(ownerId(jwt), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    private static UUID ownerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
