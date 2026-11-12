package com.sugarcrumbs.identity.persistence;

import com.sugarcrumbs.identity.domain.Owner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OwnerRepository extends JpaRepository<Owner, UUID> {
}
