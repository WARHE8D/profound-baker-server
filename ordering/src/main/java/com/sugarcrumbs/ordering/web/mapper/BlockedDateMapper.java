package com.sugarcrumbs.ordering.web.mapper;

import com.sugarcrumbs.ordering.domain.BlockedDate;
import com.sugarcrumbs.ordering.web.dto.BlockedDateResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface BlockedDateMapper {

    BlockedDateResponse toResponse(BlockedDate blockedDate);
}
