package com.sugarcrumbs.catalog.web.mapper;

import com.sugarcrumbs.catalog.storage.ImageStorageService;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;

import java.util.List;

/** Pairs each image URL with its thumbnail's (#25), for {@link ItemMapper}. */
@Component
public class ThumbnailMapper {

    private final ImageStorageService imageStorageService;

    public ThumbnailMapper(ImageStorageService imageStorageService) {
        this.imageStorageService = imageStorageService;
    }

    @Named("thumbnails")
    public List<String> thumbnails(List<String> imageUrls) {
        return imageUrls == null ? List.of() : imageUrls.stream().map(imageStorageService::thumbnailUrl).toList();
    }
}
