package com.sugarcrumbs.catalog.persistence;

import com.sugarcrumbs.catalog.domain.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * {@link JpaSpecificationExecutor} is pulled in specifically so the
 * "browse items with filters" story (category + availability + search
 * text, any combination, all optional) can be served by one composable
 * query builder ({@link ItemSpecifications}) instead of a derived
 * method for every filter combination
 * ({@code findByCategoryAndAvailableAndNameContaining...}, etc.).
 */
public interface ItemRepository extends JpaRepository<Item, UUID>, JpaSpecificationExecutor<Item> {

    /** Whether any item still shows this image, so a shared file isn't deleted from under it. */
    @Query("select count(i) > 0 from Item i join i.imageUrls url where url = :url")
    boolean existsByImageUrl(@Param("url") String url);
}
