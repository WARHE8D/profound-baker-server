package com.sugarcrumbs;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the production deployment shape (CLAUDE.md §5, §7): a non-root JRE
 * image, only Caddy reachable from outside, Actuator never proxied, and every
 * variable the compose files need documented in {@code .env.example}.
 * Reads the files only; building and running the stack is a manual check.
 */
class DeploymentFilesTests {

    private static final Path REPO_ROOT = findRepoRoot();

    @Test
    void imageRunsAsNonRootOnAJreOnlyBase() throws IOException {
        List<String> lines = Files.readAllLines(REPO_ROOT.resolve("Dockerfile"));

        List<String> froms = lines.stream().filter(l -> l.startsWith("FROM ")).toList();
        assertThat(froms.getLast()).startsWith("FROM eclipse-temurin:21-jre");

        List<String> users = lines.stream().filter(l -> l.startsWith("USER ")).toList();
        assertThat(users).isNotEmpty();
        assertThat(users.getLast()).isNotIn("USER root", "USER 0");
    }

    @Test
    void imageHasAHealthCheckOnTheInternalManagementPort() throws IOException {
        assertThat(Files.readString(REPO_ROOT.resolve("Dockerfile")))
                .containsPattern("HEALTHCHECK[\\s\\S]*localhost:9080/actuator/health");
    }

    @Test
    void secretsAndLocalStateStayOutOfTheBuildContext() throws IOException {
        assertThat(Files.readAllLines(REPO_ROOT.resolve(".dockerignore")))
                .contains(".env", "**/target/", ".git/", "uploads/");
    }

    @Test
    void onlyCaddyPublishesPortsInProduction() throws IOException {
        Map<String, Map<String, Object>> services = services("docker-compose.prod.yml");

        assertThat(services).containsKeys("postgres", "app", "caddy");
        services.forEach((name, service) -> {
            if (!name.equals("caddy")) {
                assertThat(service).as(name + " publishes no ports").doesNotContainKey("ports");
            }
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void appRunsTheProdProfileAndKeepsUploadsOnAVolume() throws IOException {
        Map<String, Object> app = services("docker-compose.prod.yml").get("app");

        assertThat((Map<String, Object>) app.get("environment")).containsEntry("SPRING_PROFILES_ACTIVE", "prod");
        assertThat((List<Object>) app.get("volumes")).contains("uploads:/data/uploads");
    }

    @Test
    void caddyProxiesTheApiPortButNeverActuator() throws IOException {
        assertThat(caddyDirectives()).contains("reverse_proxy app:8080").doesNotContain("9080");
    }

    @Test
    @SuppressWarnings("unchecked")
    void caddyServesTheUploadsVolumeReadOnly() throws IOException {
        Map<String, Object> caddy = services("docker-compose.prod.yml").get("caddy");
        assertThat((List<Object>) caddy.get("volumes")).contains("uploads:/srv/uploads:ro");

        assertThat(caddyDirectives())
                .containsPattern("handle_path /uploads/\\*\\s*\\{[^}]*root \\* /srv/uploads[^}]*file_server\\s")
                .doesNotContain("browse");
    }

    @Test
    void everyComposeVariableIsDocumentedInEnvExample() throws IOException {
        Pattern variable = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)");
        String envExample = Files.readString(REPO_ROOT.resolve(".env.example"));

        for (String file : List.of("docker-compose.yml", "docker-compose.prod.yml")) {
            Matcher matcher = variable.matcher(Files.readString(REPO_ROOT.resolve(file)));
            Set<String> names = matcher.results().map(m -> m.group(1)).collect(Collectors.toSet());

            assertThat(names).as(file).isNotEmpty().allSatisfy(name ->
                    assertThat(envExample).as("%s from %s in .env.example", name, file)
                            .containsPattern("(?m)^#?\\s*" + name + "="));
        }
    }

    /** The Caddyfile without its comment lines, so prose never satisfies or breaks a check. */
    private static String caddyDirectives() throws IOException {
        return Files.readAllLines(REPO_ROOT.resolve("infra/caddy/Caddyfile")).stream()
                .filter(line -> !line.strip().startsWith("#"))
                .collect(Collectors.joining("\n"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> services(String composeFile) throws IOException {
        Map<String, Object> compose = new Yaml().load(Files.readString(REPO_ROOT.resolve(composeFile)));
        return (Map<String, Map<String, Object>>) compose.get("services");
    }

    private static Path findRepoRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".env.example"))) {
                return dir;
            }
        }
        throw new IllegalStateException(".env.example not found above " + Path.of("").toAbsolutePath());
    }
}
