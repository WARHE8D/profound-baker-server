package com.sugarcrumbs.ordering.web.mapper;

import com.sugarcrumbs.ordering.domain.WorkingHours;
import com.sugarcrumbs.ordering.web.dto.WorkingHoursResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface WorkingHoursMapper {

    WorkingHoursResponse toResponse(WorkingHours workingHours);
}
