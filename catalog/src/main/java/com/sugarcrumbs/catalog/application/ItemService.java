package com.sugarcrumbs.catalog.application;

import com.sugarcrumbs.catalog.ItemReferenceGuard;
import com.sugarcrumbs.catalog.domain.Category;
import com.sugarcrumbs.catalog.domain.Item;
import com.sugarcrumbs.catalog.persistence.ItemRepository;
import com.sugarcrumbs.catalog.persistence.ItemSpecifications;
import com.sugarcrumbs.catalog.storage.ImageStorageService;
import com.sugarcrumbs.catalog.web.dto.ItemRequest;
import com.sugarcrumbs.common.exception.ConflictException;
import com.sugarcrumbs.common.exception.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ItemService {

    private static final String IMAGE_SUBFOLDER = "items";

    private final ItemRepository itemRepository;
    private final CategoryService categoryService;
    private final ImageStorageService imageStorageService;
    private final List<ItemReferenceGuard> referenceGuards;

    public ItemService(ItemRepository itemRepository, CategoryService categoryService,
                       ImageStorageService imageStorageService, List<ItemReferenceGuard> referenceGuards) {
        this.itemRepository = itemRepository;
        this.categoryService = categoryService;
        this.imageStorageService = imageStorageService;
        this.referenceGuards = referenceGuards;
    }

    public Item create(ItemRequest request) {
        Category category = categoryService.getOrThrow(request.categoryId());
        Item item = Item.builder()
                .name(request.name())
                .description(request.description())
                .price(request.price())
                .category(category)
                .leadTimeHours(request.leadTimeHours())
                .stockQuantity(request.stockQuantity())
                .build();
        return itemRepository.save(item);
    }

    @Transactional(readOnly = true)
    public Item getOrThrow(UUID id) {
        return itemRepository.findById(id)
                .orElseThrow(() -> NotFoundException.forEntity("Item", id));
    }

    /**
     * All three filters are optional and independent — see
     * {@link ItemSpecifications#combine} for how they compose. Paginated
     * by design: an owner's menu is small today, but nothing here breaks
     * if it grows.
     */
    @Transactional(readOnly = true)
    public Page<Item> search(UUID categoryId, Boolean available, String searchText, Pageable pageable) {
        return itemRepository.findAll(ItemSpecifications.combine(categoryId, available, searchText), pageable);
    }

    public Item updateDetails(UUID id, ItemRequest request) {
        Item item = getOrThrow(id);
        item.rename(request.name());
        item.reprice(request.price());
        if (!item.getCategory().getId().equals(request.categoryId())) {
            item.moveToCategory(categoryService.getOrThrow(request.categoryId()));
        }
        item.changeLeadTime(request.leadTimeHours());
        // description and stockQuantity have no invariant beyond "nullable", so they're fine to assign directly
        // rather than route through a dedicated business method.
        item.updateDescription(request.description());
        item.updateStockQuantity(request.stockQuantity());
        return item;
    }

    public Item changeAvailability(UUID id, boolean available) {
        Item item = getOrThrow(id);
        if (available) {
            item.markAvailable();
        } else {
            item.markUnavailable();
        }
        return item;
    }

    public Item adjustStock(UUID id, int delta) {
        if (delta == 0) {
            throw new IllegalArgumentException("delta must be non-zero");
        }
        Item item = getOrThrow(id);
        item.adjustStock(delta);
        return item;
    }

    public Item reprice(UUID id, BigDecimal newPrice) {
        Item item = getOrThrow(id);
        item.reprice(newPrice);
        return item;
    }

    public Item addImageUrl(UUID id, String url) {
        Item item = getOrThrow(id);
        item.addImage(url);
        return item;
    }

    /**
     * Uploads the file to storage, then attaches the resulting URL to the
     * item — one transaction, one API call for the owner. If the
     * transaction rolls back, the file is deleted again so it isn't left
     * orphaned on disk.
     */
    public Item uploadImage(UUID id, MultipartFile file) {
        Item item = getOrThrow(id);
        String url = imageStorageService.store(file, IMAGE_SUBFOLDER);
        afterCompletion(false, () -> imageStorageService.delete(url));
        item.addImage(url);
        return item;
    }

    /**
     * Detaches the image and deletes its file once the change is
     * committed. Only a URL the item actually held is deleted, so this
     * endpoint can't be used to remove arbitrary files, and a file another
     * item still shows is kept.
     */
    public Item removeImageUrl(UUID id, String url) {
        Item item = getOrThrow(id);
        if (item.removeImage(url)) {
            deleteUnreferencedFilesAfterCommit(List.of(url));
        }
        return item;
    }

    /**
     * Hard delete, guarded by every {@link ItemReferenceGuard} (today:
     * ordering's bookings) so the catalog never has to know which modules
     * reference its items: an item with any
     * booking history (past or present — cancelled ones included, since
     * those still have real history worth keeping queryable) can't be
     * deleted, so the owner gets a clear 409 instead of the DB rejecting
     * a non-nullable foreign key with an opaque constraint error. Prefer
     * {@link #changeAvailability} to retire an item without deleting it.
     */
    public void delete(UUID id) {
        Item item = getOrThrow(id);
        if (referenceGuards.stream().anyMatch(guard -> guard.isReferenced(id))) {
            throw new ConflictException(
                    "cannot delete item '" + item.getName() + "': it has booking history. Mark it unavailable instead.");
        }
        List<String> imageUrls = List.copyOf(item.getImageUrls());
        itemRepository.delete(item);
        deleteUnreferencedFilesAfterCommit(imageUrls);
    }

    /**
     * Files are deleted only after commit: deleting them inside the
     * transaction would leave items pointing at missing files if it then
     * rolled back. The reference check runs now, while the query still
     * sees this transaction's own changes.
     */
    private void deleteUnreferencedFilesAfterCommit(Collection<String> urls) {
        List<String> unreferenced = urls.stream()
                .distinct()
                .filter(url -> !itemRepository.existsByImageUrl(url))
                .toList();
        if (!unreferenced.isEmpty()) {
            afterCompletion(true, () -> unreferenced.forEach(imageStorageService::delete));
        }
    }

    /** Runs the action after this transaction commits (or rolls back), or right away if no transaction is active. */
    private static void afterCompletion(boolean onCommit, Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            if (onCommit) {
                action.run();
            }
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == (onCommit ? STATUS_COMMITTED : STATUS_ROLLED_BACK)) {
                    action.run();
                }
            }
        });
    }
}
