package com.sugarcrumbs.server.service;

import com.sugarcrumbs.server.dto.request.CategoryRequest;
import com.sugarcrumbs.server.entity.Category;
import com.sugarcrumbs.server.exception.ConflictException;
import com.sugarcrumbs.server.exception.NotFoundException;
import com.sugarcrumbs.server.repository.item.CategoryRepository;
import com.sugarcrumbs.server.repository.item.ItemRepository;
import com.sugarcrumbs.server.repository.item.ItemSpecifications;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ItemRepository itemRepository;

    public CategoryService(CategoryRepository categoryRepository, ItemRepository itemRepository) {
        this.categoryRepository = categoryRepository;
        this.itemRepository = itemRepository;
    }

    public Category create(CategoryRequest request) {
        if (categoryRepository.existsByNameIgnoreCase(request.name())) {
            throw new ConflictException("a category named '" + request.name() + "' already exists");
        }
        return categoryRepository.save(new Category(request.name()));
    }

    @Transactional(readOnly = true)
    public List<Category> listAll() {
        return categoryRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Category getOrThrow(UUID id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> NotFoundException.forEntity("Category", id));
    }

    public Category rename(UUID id, CategoryRequest request) {
        Category category = getOrThrow(id);
        if (!category.getName().equalsIgnoreCase(request.name())
                && categoryRepository.existsByNameIgnoreCase(request.name())) {
            throw new ConflictException("a category named '" + request.name() + "' already exists");
        }
        category.rename(request.name());
        return category;
    }

    /**
     * A category with items still assigned to it can't be deleted outright
     * — that would either cascade-delete menu items or orphan a foreign
     * key, neither of which is a silent failure the owner would want.
     * They must move or remove the items first.
     */
    public void delete(UUID id) {
        Category category = getOrThrow(id);
        boolean hasItems = itemRepository.count(ItemSpecifications.categoryIdEquals(id)) > 0;
        if (hasItems) {
            throw new ConflictException("cannot delete category '" + category.getName()
                    + "': it still has items assigned to it");
        }
        categoryRepository.delete(category);
    }
}
