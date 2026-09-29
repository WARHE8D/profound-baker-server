package com.sugarcrumbs.server.repository.owner;

import com.sugarcrumbs.server.entity.BlockedDate;
import com.sugarcrumbs.server.entity.Owner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BlockedDateRepository extends JpaRepository<BlockedDate, UUID> {

    List<BlockedDate> findAllByOwnerOrderByStartDateAsc(Owner owner);
}
