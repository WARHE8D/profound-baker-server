package com.sugarcrumbs.identity.application;

import com.sugarcrumbs.common.exception.NotFoundException;
import com.sugarcrumbs.identity.OwnerApi;
import com.sugarcrumbs.identity.domain.Owner;
import com.sugarcrumbs.identity.persistence.OwnerRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Resolves the Owner of this single-tenant system.
 *
 * <p>On an authenticated request the Owner is the token's subject. Public
 * flows (availability, guest checkout) have no principal, so they fall back to
 * {@link #getTheOwner()}, which fails loudly rather than silently picking "the
 * first one" if the single-owner invariant is ever violated (see the note on
 * {@link Owner} for why that invariant isn't enforced in the database).
 * {@link OwnerBootstrap} guarantees an Owner exists once the app is up.
 */
@Service
@Transactional(readOnly = true)
public class OwnerService implements OwnerApi {

    private final OwnerRepository ownerRepository;

    public OwnerService(OwnerRepository ownerRepository) {
        this.ownerRepository = ownerRepository;
    }

    /**
     * The signed-in Owner's id on owner requests; the single Owner otherwise. Callers in
     * other modules never touch Spring Security, so the principal is read here.
     */
    @Override
    public UUID currentOwnerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        return getTheOwner().getId();
    }

    /** The Owner a token was issued to. 404 if it no longer exists. */
    public Owner getOwner(UUID ownerId) {
        return ownerRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("owner not found"));
    }

    public Owner getTheOwner() {
        List<Owner> owners = ownerRepository.findAll();
        if (owners.isEmpty()) {
            throw new IllegalStateException("no Owner has been set up yet");
        }
        if (owners.size() > 1) {
            throw new IllegalStateException(
                    "expected exactly one Owner but found " + owners.size() + " — this system is single-tenant");
        }
        return owners.get(0);
    }
}
