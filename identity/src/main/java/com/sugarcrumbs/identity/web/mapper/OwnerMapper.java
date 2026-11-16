package com.sugarcrumbs.identity.web.mapper;

import com.sugarcrumbs.identity.domain.Address;
import com.sugarcrumbs.identity.domain.Owner;
import com.sugarcrumbs.identity.web.dto.AddressDto;
import com.sugarcrumbs.identity.web.dto.OwnerProfileResponse;
import org.mapstruct.Mapper;

import java.time.ZoneId;

@Mapper(componentModel = "spring")
public interface OwnerMapper {

    OwnerProfileResponse toProfileResponse(Owner owner);

    AddressDto toDto(Address address);

    default Address toAddress(AddressDto dto) {
        return dto == null ? null
                : new Address(dto.street(), dto.city(), dto.region(), dto.postalCode(), dto.country());
    }

    default String zoneId(ZoneId zone) {
        return zone == null ? null : zone.getId();
    }
}
