package com.sugarcrumbs;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Guards the module boundaries that make a later split into services
 * cheap. Fails the build when a module reaches into another module's
 * internal packages, depends on a module it hasn't declared in its
 * {@code package-info.java}, or forms a dependency cycle. Needs no
 * database or Spring context.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(BakerApplication.class);

    @Test
    void modulesRespectTheirBoundaries() {
        modules.verify();
    }

    /** Regenerates module diagrams (PlantUML/C4) into target/spring-modulith-docs. */
    @Test
    void writeDocumentation() {
        new Documenter(modules).writeDocumentation();
    }
}
