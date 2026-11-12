package com.sugarcrumbs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots every business module (catalog, ordering, identity) as one
 * deployable — a modular monolith. Each module lives in its own Maven
 * module under its own top-level package ({@code com.sugarcrumbs.<module>}),
 * so this class sitting at the root is what lets component, entity and
 * repository scanning reach all of them. See CLAUDE.md §3 for the rules
 * that keep the modules extractable into separate services.
 */
@SpringBootApplication
public class BakerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BakerApplication.class, args);
    }
}
