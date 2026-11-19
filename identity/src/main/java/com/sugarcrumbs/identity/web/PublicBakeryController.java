package com.sugarcrumbs.identity.web;

import com.sugarcrumbs.identity.application.OwnerService;
import com.sugarcrumbs.identity.web.dto.PublicBakeryResponse;
import com.sugarcrumbs.identity.web.mapper.OwnerMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The bakery as the storefront shows it. Public: a guest needs the time zone to label pickup times and to
 * know the bakery's "today", and the name, logo and contact details for the page itself.
 */
@RestController
@RequestMapping("/api/public/bakery")
public class PublicBakeryController {

    private final OwnerService ownerService;
    private final OwnerMapper ownerMapper;

    public PublicBakeryController(OwnerService ownerService, OwnerMapper ownerMapper) {
        this.ownerService = ownerService;
        this.ownerMapper = ownerMapper;
    }

    @GetMapping
    public PublicBakeryResponse bakery() {
        return ownerMapper.toPublicBakeryResponse(ownerService.getTheOwner());
    }
}
