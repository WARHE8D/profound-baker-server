package com.sugarcrumbs.server.mapper;

import com.sugarcrumbs.server.dto.response.CategoryResponse;
import com.sugarcrumbs.server.entity.Category;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CategoryMapper {

    CategoryResponse toResponse(Category category);
}
