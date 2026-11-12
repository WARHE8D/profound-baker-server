package com.sugarcrumbs.ordering.persistence;

import com.sugarcrumbs.ordering.domain.BlockedDate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BlockedDateRepository extends JpaRepository<BlockedDate, UUID> {

    List<BlockedDate> findAllByOwnerIdOrderByStartDateAsc(UUID ownerId);
}
