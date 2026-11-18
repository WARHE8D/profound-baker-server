package com.sugarcrumbs.catalog.web.mapper;

import com.sugarcrumbs.catalog.domain.Item;
import com.sugarcrumbs.catalog.web.dto.ItemResponse;
import com.sugarcrumbs.catalog.web.dto.PublicItemResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = {CategoryMapper.class, ThumbnailMapper.class})
public interface ItemMapper {

    @Mapping(target = "thumbnailUrls", source = "imageUrls", qualifiedByName = "thumbnails")
    ItemResponse toResponse(Item item);

    @Mapping(target = "thumbnailUrls", source = "imageUrls", qualifiedByName = "thumbnails")
    PublicItemResponse toPublicResponse(Item item);
}
