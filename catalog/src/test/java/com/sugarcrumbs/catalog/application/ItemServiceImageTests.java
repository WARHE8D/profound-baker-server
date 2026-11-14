package com.sugarcrumbs.catalog.application;

import com.sugarcrumbs.catalog.ItemReferenceGuard;
import com.sugarcrumbs.catalog.domain.Category;
import com.sugarcrumbs.catalog.domain.Item;
import com.sugarcrumbs.catalog.persistence.ItemRepository;
import com.sugarcrumbs.catalog.storage.ImageStorageService;
import com.sugarcrumbs.common.exception.ConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Files on disk follow the item's committed state: removed images are
 * deleted only after commit, and only when no item still shows them.
 * Transactions are simulated with {@link TransactionSynchronizationManager}.
 */
class ItemServiceImageTests {

    private static final String UPLOADED = "/uploads/items/a.png";
    private static final String HOSTED = "https://cdn.example.com/b.png";

    private final ItemRepository itemRepository = mock(ItemRepository.class);
    private final ImageStorageService storage = mock(ImageStorageService.class);
    private final ItemReferenceGuard guard = mock(ItemReferenceGuard.class);
    private final ItemService service =
            new ItemService(itemRepository, mock(CategoryService.class), storage, List.of(guard));

    private final UUID itemId = UUID.randomUUID();
    private Item item;

    @BeforeEach
    void setUp() {
        item = Item.builder()
                .name("Sourdough")
                .price(new BigDecimal("6.50"))
                .category(mock(Category.class))
                .imageUrls(List.of(UPLOADED, HOSTED))
                .build();
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void removingAnImageDeletesItsFileOnlyAfterCommit() {
        service.removeImageUrl(itemId, UPLOADED);

        assertThat(item.getImageUrls()).containsExactly(HOSTED);
        verify(storage, never()).delete(anyString());

        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(storage).delete(UPLOADED);
    }

    @Test
    void removingAnImageKeepsTheFileIfTheTransactionRollsBack() {
        service.removeImageUrl(itemId, UPLOADED);

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void removingAUrlTheItemDoesNotHoldDeletesNothing() {
        service.removeImageUrl(itemId, "/uploads/items/someone-elses.png");

        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void removingAnImageAnotherItemStillShowsKeepsTheFile() {
        when(itemRepository.existsByImageUrl(UPLOADED)).thenReturn(true);

        service.removeImageUrl(itemId, UPLOADED);

        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void deletingAnItemDeletesAllItsImagesAfterCommit() {
        service.delete(itemId);

        verify(itemRepository).delete(item);
        verify(storage, never()).delete(anyString());

        complete(TransactionSynchronization.STATUS_COMMITTED);
        // The storage ignores URLs it didn't issue, so hosted ones are passed through safely.
        verify(storage).delete(UPLOADED);
        verify(storage).delete(HOSTED);
    }

    @Test
    void anItemThatCannotBeDeletedKeepsItsImages() {
        when(guard.isReferenced(itemId)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(itemId)).isInstanceOf(ConflictException.class);

        verify(storage, never()).delete(anyString());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void anUploadIsDeletedAgainIfTheTransactionRollsBack() {
        MockMultipartFile file = new MockMultipartFile("file", "c.png", "image/png", new byte[]{1});
        when(storage.store(any(), eq("items"))).thenReturn("/uploads/items/new.png");

        service.uploadImage(itemId, file);
        assertThat(item.getImageUrls()).contains("/uploads/items/new.png");

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storage).delete("/uploads/items/new.png");
    }

    @Test
    void aCommittedUploadIsKept() {
        MockMultipartFile file = new MockMultipartFile("file", "c.png", "image/png", new byte[]{1});
        when(storage.store(any(), eq("items"))).thenReturn("/uploads/items/new.png");

        service.uploadImage(itemId, file);

        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(storage, never()).delete(anyString());
    }

    private static void complete(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(status));
    }
}
