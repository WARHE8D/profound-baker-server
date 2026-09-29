package com.sugarcrumbs.server.service;

import com.sugarcrumbs.server.dto.request.BlockedDateRequest;
import com.sugarcrumbs.server.entity.BlockedDate;
import com.sugarcrumbs.server.entity.Owner;
import com.sugarcrumbs.server.exception.NotFoundException;
import com.sugarcrumbs.server.repository.owner.BlockedDateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class BlockedDateService {

    private final BlockedDateRepository blockedDateRepository;
    private final OwnerService ownerService;

    public BlockedDateService(BlockedDateRepository blockedDateRepository, OwnerService ownerService) {
        this.blockedDateRepository = blockedDateRepository;
        this.ownerService = ownerService;
    }

    public BlockedDate create(BlockedDateRequest request) {
        Owner owner = ownerService.getTheOwner();
        BlockedDate blockedDate = BlockedDate.builder()
                .owner(owner)
                .startDate(request.startDate())
                .endDate(request.endDate())
                .reason(request.reason())
                .recurringAnnually(request.recurringAnnually())
                .build();
        return blockedDateRepository.save(blockedDate);
    }

    @Transactional(readOnly = true)
    public List<BlockedDate> listAll() {
        Owner owner = ownerService.getTheOwner();
        return blockedDateRepository.findAllByOwnerOrderByStartDateAsc(owner);
    }

    @Transactional(readOnly = true)
    public BlockedDate getOrThrow(UUID id) {
        return blockedDateRepository.findById(id)
                .orElseThrow(() -> NotFoundException.forEntity("BlockedDate", id));
    }

    public BlockedDate update(UUID id, BlockedDateRequest request) {
        BlockedDate blockedDate = getOrThrow(id);
        blockedDate.reschedule(request.startDate(), request.endDate());
        blockedDate.updateReason(request.reason());
        blockedDate.setRecurringAnnually(request.recurringAnnually());
        return blockedDate;
    }

    public void delete(UUID id) {
        blockedDateRepository.delete(getOrThrow(id));
    }
}
