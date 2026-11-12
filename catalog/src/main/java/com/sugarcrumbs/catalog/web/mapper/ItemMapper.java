package com.sugarcrumbs.catalog.web.mapper;

import com.sugarcrumbs.catalog.domain.Item;
import com.sugarcrumbs.catalog.web.dto.ItemResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring", uses = CategoryMapper.class)
public interface ItemMapper {

    ItemResponse toResponse(Item item);
}
