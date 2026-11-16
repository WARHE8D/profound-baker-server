package com.sugarcrumbs.identity.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.ZoneId;

/** Stores a {@link ZoneId} as its IANA id ({@code Europe/London}). */
@Converter
class ZoneIdConverter implements AttributeConverter<ZoneId, String> {

    @Override
    public String convertToDatabaseColumn(ZoneId zone) {
        return zone == null ? null : zone.getId();
    }

    @Override
    public ZoneId convertToEntityAttribute(String id) {
        return id == null ? null : ZoneId.of(id);
    }
}
