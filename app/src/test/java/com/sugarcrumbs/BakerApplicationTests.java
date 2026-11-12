package com.sugarcrumbs;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Runs against a Testcontainers Postgres; needs Docker, not a local database. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BakerApplicationTests {

    @Test
    void contextLoads() {
    }
}
