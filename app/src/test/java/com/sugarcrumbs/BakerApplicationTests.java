package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/** Runs against a Testcontainers Postgres; needs Docker, not a local database. */
class BakerApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }
}
