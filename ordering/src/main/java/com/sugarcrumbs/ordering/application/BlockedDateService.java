package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.common.exception.NotFoundException;
import com.sugarcrumbs.identity.OwnerApi;
import com.sugarcrumbs.ordering.domain.BlockedDate;
import com.sugarcrumbs.ordering.persistence.BlockedDateRepository;
import com.sugarcrumbs.ordering.web.dto.BlockedDateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class BlockedDateService {

    private final BlockedDateRepository blockedDateRepository;
    private final OwnerApi ownerApi;

    public BlockedDateService(BlockedDateRepository blockedDateRepository, OwnerApi ownerApi) {
        this.blockedDateRepository = blockedDateRepository;
        this.ownerApi = ownerApi;
    }

    public BlockedDate create(BlockedDateRequest request) {
        UUID ownerId = ownerApi.currentOwnerId();
        BlockedDate blockedDate = BlockedDate.builder()
                .ownerId(ownerId)
                .startDate(request.startDate())
                .endDate(request.endDate())
                .reason(request.reason())
                .recurringAnnually(request.recurringAnnually())
                .build();
        return blockedDateRepository.save(blockedDate);
    }

    @Transactional(readOnly = true)
    public List<BlockedDate> listAll() {
        UUID ownerId = ownerApi.currentOwnerId();
        return blockedDateRepository.findAllByOwnerIdOrderByStartDateAsc(ownerId);
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
