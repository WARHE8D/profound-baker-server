package com.sugarcrumbs.server.repository.item;

import com.sugarcrumbs.server.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

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
}
