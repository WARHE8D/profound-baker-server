package com.sugarcrumbs.server.repository.owner;

import com.sugarcrumbs.server.entity.Owner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OwnerRepository extends JpaRepository<Owner, UUID> {
}
