package com.sugarcrumbs.server.mapper;

import com.sugarcrumbs.server.dto.response.BlockedDateResponse;
import com.sugarcrumbs.server.entity.BlockedDate;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface BlockedDateMapper {

    BlockedDateResponse toResponse(BlockedDate blockedDate);
}
