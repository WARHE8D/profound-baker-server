package com.sugarcrumbs;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Needs a running Postgres (docker compose up) until Testcontainers lands in #9. */
@SpringBootTest
class BakerApplicationTests {

    @Test
    void contextLoads() {
    }
}
