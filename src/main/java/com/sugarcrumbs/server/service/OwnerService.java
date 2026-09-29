package com.sugarcrumbs.server.service;


import com.sugarcrumbs.server.entity.Owner;
import com.sugarcrumbs.server.repository.owner.OwnerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Resolves "the" owner for this single-tenant system.
 *
 * <p>This is a placeholder for the real thing: once Sprint 1 (Auth)
 * exists, callers should resolve the owner from the authenticated
 * principal (e.g. {@code SecurityContextHolder}), not by scanning the
 * table. Kept here now, ahead of auth, purely so Sprint 3's
 * {@code WorkingHours}/{@code BlockedDate} services have a legitimate
 * {@link Owner} to attach records to — every FK in this system should
 * point at a real row, not a hardcoded placeholder ID.
 *
 * <p>{@link #getTheOwner()} fails loudly rather than silently picking
 * "the first one" if the single-owner invariant is ever violated —
 * see the note on {@link Owner} itself for why that invariant isn't
 * enforced at the DB/entity level.
 */
@Service
@Transactional(readOnly = true)
public class OwnerService {

    private final OwnerRepository ownerRepository;

    public OwnerService(OwnerRepository ownerRepository) {
        this.ownerRepository = ownerRepository;
    }

    public Owner getTheOwner() {
        List<Owner> owners = ownerRepository.findAll();
        if (owners.isEmpty()) {
            throw new IllegalStateException("no Owner has been set up yet — complete onboarding first");
        }
        if (owners.size() > 1) {
            throw new IllegalStateException(
                    "expected exactly one Owner but found " + owners.size() + " — this system is single-tenant");
        }
        return owners.get(0);
    }
}
