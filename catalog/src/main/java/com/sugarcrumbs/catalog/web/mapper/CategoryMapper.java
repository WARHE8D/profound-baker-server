package com.sugarcrumbs.catalog.web.mapper;

import com.sugarcrumbs.catalog.domain.Category;
import com.sugarcrumbs.catalog.web.dto.CategoryResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CategoryMapper {

    CategoryResponse toResponse(Category category);
}
