package com.sugarcrumbs.ordering.web;

import com.sugarcrumbs.ordering.application.BlockedDateService;
import com.sugarcrumbs.ordering.domain.BlockedDate;
import com.sugarcrumbs.ordering.web.dto.BlockedDateRequest;
import com.sugarcrumbs.ordering.web.dto.BlockedDateResponse;
import com.sugarcrumbs.ordering.web.mapper.BlockedDateMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/availability/blocked-dates")
public class BlockedDateController {

    private final BlockedDateService blockedDateService;
    private final BlockedDateMapper blockedDateMapper;

    public BlockedDateController(BlockedDateService blockedDateService, BlockedDateMapper blockedDateMapper) {
        this.blockedDateService = blockedDateService;
        this.blockedDateMapper = blockedDateMapper;
    }

    @PostMapping
    public ResponseEntity<BlockedDateResponse> create(@Valid @RequestBody BlockedDateRequest request,
                                                      UriComponentsBuilder uriBuilder) {
        BlockedDate created = blockedDateService.create(request);
        URI location = uriBuilder.path("/api/availability/blocked-dates/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(blockedDateMapper.toResponse(created));
    }

    @GetMapping
    public List<BlockedDateResponse> list() {
        return blockedDateService.listAll().stream()
                .map(blockedDateMapper::toResponse)
                .collect(Collectors.toList());
    }

    @PutMapping("/{id}")
    public BlockedDateResponse update(@PathVariable UUID id, @Valid @RequestBody BlockedDateRequest request) {
        return blockedDateMapper.toResponse(blockedDateService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        blockedDateService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

