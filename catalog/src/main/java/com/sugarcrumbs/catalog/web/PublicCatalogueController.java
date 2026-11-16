package com.sugarcrumbs.catalog.web;

import com.sugarcrumbs.catalog.application.CategoryService;
import com.sugarcrumbs.catalog.application.ItemService;
import com.sugarcrumbs.catalog.web.dto.CategoryResponse;
import com.sugarcrumbs.catalog.web.dto.PublicItemResponse;
import com.sugarcrumbs.catalog.web.mapper.CategoryMapper;
import com.sugarcrumbs.catalog.web.mapper.ItemMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The storefront's read-only view of the catalogue: no authentication, and only
 * items the Owner has made available. Owner management lives under
 * {@code /api/catalogue/**} ({@link ItemController}, {@link CategoryController}).
 */
@RestController
@RequestMapping("/api/public/catalogue")
public class PublicCatalogueController {

    /** Shoppers may sort only by what the storefront shows. */
    private static final Set<String> SORTABLE = Set.of("name", "price", "leadTimeHours");

    private final ItemService itemService;
    private final CategoryService categoryService;
    private final ItemMapper itemMapper;
    private final CategoryMapper categoryMapper;

    public PublicCatalogueController(ItemService itemService, CategoryService categoryService,
                                     ItemMapper itemMapper, CategoryMapper categoryMapper) {
        this.itemService = itemService;
        this.categoryService = categoryService;
        this.itemMapper = itemMapper;
        this.categoryMapper = categoryMapper;
    }

    /** Available items only: /api/public/catalogue/items?categoryId=..&search=cake&page=0&size=20&sort=price,asc */
    @GetMapping("/items")
    public Page<PublicItemResponse> items(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new IllegalArgumentException("items can only be sorted by name, price or leadTimeHours");
            }
        }
        return itemService.searchVisible(categoryId, search, pageable).map(itemMapper::toPublicResponse);
    }

    /** 404 for an unknown item and for one the Owner has hidden alike. */
    @GetMapping("/items/{id}")
    public PublicItemResponse item(@PathVariable UUID id) {
        return itemMapper.toPublicResponse(itemService.getVisibleOrThrow(id));
    }

    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return categoryService.listAll().stream().map(categoryMapper::toResponse).toList();
    }
}
