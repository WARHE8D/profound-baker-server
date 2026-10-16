package com.sugarcrumbs.server.dto.response;

public record GuestContactResponse(
        String name,
        String email,
        String phone
) {
}
