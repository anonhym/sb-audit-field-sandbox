package com.example.versionsandbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Context-load smoke test. Test resources disable docker-compose and the startup seeder, so this
 * runs without a live Mongo (the Mongo client connects lazily and is never exercised here).
 * For tests that actually hit Mongo, add Testcontainers and remove those overrides.
 */
@SpringBootTest
class SbVersionSandboxApplicationTests {

    @Test
    void contextLoads() {
    }

}
