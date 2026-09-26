package com.sugarcrumbs.server.service;
import com.sugarcrumbs.server.dto.request.ItemRequest;
import com.sugarcrumbs.server.entity.Category;
import com.sugarcrumbs.server.entity.Item;
import com.sugarcrumbs.server.exception.NotFoundException;
import com.sugarcrumbs.server.repository.item.ItemRepository;
import com.sugarcrumbs.server.repository.item.ItemSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Transactional
public class ItemService {

    private static final String IMAGE_SUBFOLDER = "items";

    private final ItemRepository itemRepository;
    private final CategoryService categoryService;
    private final ImageStorageService imageStorageService;

    public ItemService(ItemRepository itemRepository, CategoryService categoryService,
                       ImageStorageService imageStorageService) {
        this.itemRepository = itemRepository;
        this.categoryService = categoryService;
        this.imageStorageService = imageStorageService;
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

    /** Uploads the file to storage, then attaches the resulting URL to the item — one transaction, one API call for the owner. */
    public Item uploadImage(UUID id, MultipartFile file) {
        Item item = getOrThrow(id);
        String url = imageStorageService.store(file, IMAGE_SUBFOLDER);
        item.addImage(url);
        return item;
    }

    public Item removeImageUrl(UUID id, String url) {
        Item item = getOrThrow(id);
        item.removeImage(url);
        return item;
    }

    /**
     * Hard delete. Deliberately NOT guarded against existing bookings yet
     * — {@code Booking} references {@code Item} by a non-nullable foreign
     * key, so the database will reject this with a constraint violation
     * (surfaced as 409 by {@code GlobalExceptionHandler}) rather than
     * silently orphaning bookings. Once the booking sprint's repository
     * exists, add an explicit pre-check here
     * ({@code bookingRepository.existsByItemId(id)}) so the owner gets a
     * clear message instead of a raw DB error. Prefer
     * {@link #changeAvailability} to retire an item without deleting it.
     */
    public void delete(UUID id) {
        Item item = getOrThrow(id);
        itemRepository.delete(item);
    }
}
