package com.sugarcrumbs.server.controller;
import com.sugarcrumbs.server.dto.request.CategoryRequest;
import com.sugarcrumbs.server.dto.response.CategoryResponse;
import com.sugarcrumbs.server.entity.Category;
import com.sugarcrumbs.server.mapper.CategoryMapper;
import com.sugarcrumbs.server.service.CategoryService;
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
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final CategoryMapper categoryMapper;

    public CategoryController(CategoryService categoryService, CategoryMapper categoryMapper) {
        this.categoryService = categoryService;
        this.categoryMapper = categoryMapper;
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request,
                                                   UriComponentsBuilder uriBuilder) {
        Category created = categoryService.create(request);
        URI location = uriBuilder.path("/api/categories/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(categoryMapper.toResponse(created));
    }

    @GetMapping
    public List<CategoryResponse> list() {
        return categoryService.listAll().stream()
                .map(categoryMapper::toResponse)
                .collect(Collectors.toList());
    }

    @PutMapping("/{id}")
    public CategoryResponse rename(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        return categoryMapper.toResponse(categoryService.rename(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
