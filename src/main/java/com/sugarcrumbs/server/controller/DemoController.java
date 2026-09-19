package com.sugarcrumbs.server.controller;

import com.sugarcrumbs.server.exception.ResourceNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;

@RestController
@RequestMapping("/baker")
public class DemoController {

    @GetMapping("/demo")

    public String hi(){
        throw new ResourceNotFoundException("Hi");
    }
}
