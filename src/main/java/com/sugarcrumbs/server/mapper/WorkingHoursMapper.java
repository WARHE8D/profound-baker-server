package com.sugarcrumbs.server.mapper;

import com.sugarcrumbs.server.dto.response.WorkingHoursResponse;
import com.sugarcrumbs.server.entity.WorkingHours;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface WorkingHoursMapper {

    WorkingHoursResponse toResponse(WorkingHours workingHours);
}
