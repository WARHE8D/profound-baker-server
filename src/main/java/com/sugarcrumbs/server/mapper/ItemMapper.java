package com.sugarcrumbs.server.mapper;

import com.sugarcrumbs.server.dto.response.ItemResponse;
import com.sugarcrumbs.server.entity.Item;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring", uses = CategoryMapper.class)
public interface ItemMapper {

    ItemResponse toResponse(Item item);
}
