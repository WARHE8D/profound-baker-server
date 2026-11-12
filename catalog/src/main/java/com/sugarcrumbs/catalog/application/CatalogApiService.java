package com.sugarcrumbs.catalog.application;

import com.sugarcrumbs.catalog.CatalogApi;
import com.sugarcrumbs.catalog.ItemSnapshot;
import com.sugarcrumbs.catalog.domain.Item;
import com.sugarcrumbs.catalog.persistence.ItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * In-process implementation of {@link CatalogApi}. Joins the caller's
 * transaction while everything runs in one process; once the catalog is a
 * separate service, its {@code /internal/**} controllers delegate here.
 */
@Service
@Transactional
class CatalogApiService implements CatalogApi {

    private final ItemService itemService;
    private final ItemRepository itemRepository;

    CatalogApiService(ItemService itemService, ItemRepository itemRepository) {
        this.itemService = itemService;
        this.itemRepository = itemRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public ItemSnapshot getItem(UUID itemId) {
        return toSnapshot(itemService.getOrThrow(itemId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ItemSnapshot> getItems(Collection<UUID> itemIds) {
        return itemRepository.findAllById(itemIds).stream()
                .map(CatalogApiService::toSnapshot)
                .collect(Collectors.toMap(ItemSnapshot::id, Function.identity()));
    }

    @Override
    public void reserveStock(UUID itemId, int quantity) {
        adjustTrackedStock(itemId, -quantity);
    }

    @Override
    public void releaseStock(UUID itemId, int quantity) {
        adjustTrackedStock(itemId, quantity);
    }

    private void adjustTrackedStock(UUID itemId, int delta) {
        Item item = itemService.getOrThrow(itemId);
        // Items with unlimited stock (stockQuantity == null) have nothing to reserve or release.
        if (item.getStockQuantity() != null) {
            item.adjustStock(delta);
        }
    }

    private static ItemSnapshot toSnapshot(Item item) {
        return new ItemSnapshot(item.getId(), item.getName(), item.getPrice(), item.getLeadTimeHours(),
                item.isOrderable(), item.getStockQuantity() != null);
    }
}
