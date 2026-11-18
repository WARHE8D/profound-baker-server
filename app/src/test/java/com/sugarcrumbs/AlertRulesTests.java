package com.sugarcrumbs;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Prometheus configuration and alert rules (#26), checked by promtool from the same image the
 * production stack runs: the config and rules must load, and the rules must fire, and stay quiet,
 * exactly as {@code infra/prometheus/alerts.test.yml} says.
 */
class AlertRulesTests {

    private static final String IMAGE = "prom/prometheus:v3.9.1";

    @Test
    void theConfigAndRulesLoad() {
        assertThat(promtool("check", "config", "/etc/prometheus/prometheus.yml"))
                .contains("SUCCESS: /etc/prometheus/prometheus.yml")
                .contains("SUCCESS: 7 rules found");
    }

    @Test
    void theRulesPassTheirUnitTests() {
        assertThat(promtool("test", "rules", "/etc/prometheus/alerts.test.yml")).contains("SUCCESS");
    }

    /** promtool's output; the container exits non-zero, failing the test, if promtool does. */
    private static String promtool(String... args) {
        Path dir = repoRoot().resolve("infra/prometheus");
        try (GenericContainer<?> container = new GenericContainer<>(IMAGE)
                .withCopyFileToContainer(MountableFile.forHostPath(dir), "/etc/prometheus")
                .withWorkingDirectory("/etc/prometheus")
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("promtool"))
                .withCommand(args)
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(1)))) {
            try {
                container.start();
            } catch (RuntimeException e) {
                throw new AssertionError("promtool " + String.join(" ", args) + " failed:\n" + container.getLogs(), e);
            }
            return container.getLogs();
        }
    }

    private static Path repoRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("infra/prometheus"))) {
                return dir;
            }
        }
        throw new IllegalStateException("infra/prometheus not found above " + Path.of("").toAbsolutePath());
    }
}
