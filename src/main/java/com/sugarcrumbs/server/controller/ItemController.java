package com.sugarcrumbs.server.controller;

import com.sugarcrumbs.server.dto.request.*;
import com.sugarcrumbs.server.dto.response.ItemResponse;
import com.sugarcrumbs.server.entity.Item;
import com.sugarcrumbs.server.mapper.ItemMapper;
import com.sugarcrumbs.server.service.ItemService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Every mutation beyond "replace the descriptive fields"
 * (availability, stock, price, images) gets its own narrow endpoint
 * rather than a single generic PATCH — mirrors {@code Item}'s own
 * design: each action has a distinct invariant, so each gets a
 * distinct, intent-revealing HTTP operation.
 */
@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemService itemService;
    private final ItemMapper itemMapper;

    public ItemController(ItemService itemService, ItemMapper itemMapper) {
        this.itemService = itemService;
        this.itemMapper = itemMapper;
    }

    @PostMapping
    public ResponseEntity<ItemResponse> create(@Valid @RequestBody ItemRequest request,
                                               UriComponentsBuilder uriBuilder) {
        Item created = itemService.create(request);
        URI location = uriBuilder.path("/api/items/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(itemMapper.toResponse(created));
    }

    @GetMapping("/{id}")
    public ItemResponse get(@PathVariable UUID id) {
        return itemMapper.toResponse(itemService.getOrThrow(id));
    }

    /** All filters optional: /api/items?categoryId=..&available=true&search=cake&page=0&size=20&sort=name,asc */
    @GetMapping
    public Page<ItemResponse> search(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Boolean available,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return itemService.search(categoryId, available, search, pageable).map(itemMapper::toResponse);
    }

    @PutMapping("/{id}")
    public ItemResponse updateDetails(@PathVariable UUID id, @Valid @RequestBody ItemRequest request) {
        return itemMapper.toResponse(itemService.updateDetails(id, request));
    }

    @PatchMapping("/{id}/availability")
    public ItemResponse changeAvailability(@PathVariable UUID id, @Valid @RequestBody AvailabilityRequest request) {
        return itemMapper.toResponse(itemService.changeAvailability(id, request.available()));
    }

    @PatchMapping("/{id}/stock")
    public ItemResponse adjustStock(@PathVariable UUID id, @Valid @RequestBody StockAdjustmentRequest request) {
        return itemMapper.toResponse(itemService.adjustStock(id, request.delta()));
    }

    @PatchMapping("/{id}/price")
    public ItemResponse reprice(@PathVariable UUID id, @Valid @RequestBody RepriceRequest request) {
        return itemMapper.toResponse(itemService.reprice(id, request.price()));
    }

    /** For images already hosted elsewhere (e.g. a URL the owner pasted in). For direct uploads, see {@link #uploadImage}. */
    @PostMapping("/{id}/images")
    public ItemResponse addImageUrl(@PathVariable UUID id, @Valid @RequestBody ImageUrlRequest request) {
        return itemMapper.toResponse(itemService.addImageUrl(id, request.url()));
    }

    @PostMapping(value = "/{id}/images/upload")
    public ItemResponse uploadImage(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return itemMapper.toResponse(itemService.uploadImage(id, file));
    }

    @DeleteMapping("/{id}/images")
    public ItemResponse removeImage(@PathVariable UUID id, @RequestParam String url) {
        return itemMapper.toResponse(itemService.removeImageUrl(id, url));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        itemService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
