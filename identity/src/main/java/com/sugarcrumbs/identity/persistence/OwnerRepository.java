package com.sugarcrumbs.identity.persistence;

import com.sugarcrumbs.identity.domain.Owner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OwnerRepository extends JpaRepository<Owner, UUID> {

    /** Emails are stored lower-cased (see {@link Owner}), so callers pass a lower-cased email. */
    Optional<Owner> findByEmail(String email);
}
