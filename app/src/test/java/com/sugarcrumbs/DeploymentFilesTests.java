package com.sugarcrumbs;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /** Grafana is the one exception, and only on the server's loopback (an SSH tunnel away, #26). */
    @Test
    @SuppressWarnings("unchecked")
    void onlyCaddyPublishesPortsInProduction() throws IOException {
        Map<String, Map<String, Object>> services = services("docker-compose.prod.yml");

        assertThat(services).containsKeys("postgres", "app", "caddy");
        services.forEach((name, service) -> {
            if (name.equals("grafana")) {
                assertThat((List<Object>) service.get("ports")).as("grafana: loopback only")
                        .isNotEmpty().allSatisfy(port -> assertThat(port.toString()).startsWith("127.0.0.1:"));
            } else if (!name.equals("caddy")) {
                assertThat(service).as(name + " publishes no ports").doesNotContainKey("ports");
            }
        });
    }

    /** #26: Prometheus, Tempo and Grafana run beside the app, configured only from files in infra/. */
    @Test
    @SuppressWarnings("unchecked")
    void observabilityRunsInProductionConfiguredFromInfraFiles() throws IOException {
        Map<String, Map<String, Object>> services = services("docker-compose.prod.yml");

        assertThat((List<Object>) services.get("prometheus").get("volumes"))
                .contains("./infra/prometheus:/etc/prometheus:ro");
        assertThat((List<Object>) services.get("prometheus").get("command"))
                .contains("--config.file=/etc/prometheus/prometheus.yml", "--web.enable-remote-write-receiver");
        assertThat((List<Object>) services.get("tempo").get("volumes"))
                .contains("./infra/tempo/tempo.yaml:/etc/tempo/tempo.yaml:ro");
        assertThat((List<Object>) services.get("grafana").get("volumes")).contains(
                "./infra/grafana/provisioning:/etc/grafana/provisioning:ro",
                "./infra/grafana/dashboards:/var/lib/grafana/dashboards:ro");
        for (String name : List.of("prometheus", "tempo", "grafana")) {
            assertThat(services.get(name).get("image").toString()).as(name + " pins its version")
                    .matches(".+:v?\\d+\\.\\d+\\.\\d+");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void grafanaNeedsAPasswordAndAdmitsNobodyElse() throws IOException {
        Map<String, Object> environment = (Map<String, Object>) services("docker-compose.prod.yml")
                .get("grafana").get("environment");

        assertThat(environment.get("GF_SECURITY_ADMIN_PASSWORD").toString()).startsWith("${GRAFANA_ADMIN_PASSWORD:?");
        assertThat(environment).containsEntry("GF_USERS_ALLOW_SIGN_UP", "false")
                .containsEntry("GF_AUTH_ANONYMOUS_ENABLED", "false");
    }

    /** Prometheus reaches the app on its internal management port, and the backup job on its metrics port. */
    @Test
    @SuppressWarnings("unchecked")
    void prometheusScrapesTheAppAndTheBackupJobOnTheInternalNetwork() throws IOException {
        Map<String, Object> config = new Yaml().load(Files.readString(REPO_ROOT.resolve("infra/prometheus/prometheus.yml")));
        Map<String, Map<String, Object>> jobs = ((List<Map<String, Object>>) config.get("scrape_configs")).stream()
                .collect(Collectors.toMap(job -> (String) job.get("job_name"), job -> job));

        assertThat((List<Object>) config.get("rule_files")).contains("/etc/prometheus/alerts.yml");
        assertThat(jobs.get("baker")).containsEntry("metrics_path", "/actuator/prometheus");
        assertThat(jobs.get("baker").get("static_configs").toString()).contains("app:9080");
        assertThat(jobs.get("backup").get("static_configs").toString()).contains("backup:9101");
        assertThat(Files.readString(REPO_ROOT.resolve("infra/backup/Dockerfile"))).contains("EXPOSE 9101");
        assertThat(Files.readString(REPO_ROOT.resolve("infra/backup/entrypoint.sh"))).contains("httpd -p 9101");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAppSendsItsTracesToTempo() throws IOException {
        Map<String, Object> environment = (Map<String, Object>) services("docker-compose.prod.yml")
                .get("app").get("environment");
        String tempo = Files.readString(REPO_ROOT.resolve("infra/tempo/tempo.yaml"));

        assertThat(environment).containsEntry("MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT",
                "http://tempo:4318/v1/traces");
        assertThat(tempo).contains("endpoint: \"0.0.0.0:4318\"").contains("http://prometheus:9090/api/v1/write");
    }

    /**
     * Dashboards are files (never built in the UI) and only use the provisioned datasources, by uid,
     * so they load on a fresh Grafana with nothing to click.
     */
    @Test
    @SuppressWarnings("unchecked")
    void dashboardsAreValidAndOnlyUseProvisionedDatasources() throws IOException {
        Map<String, Object> provisioning = new Yaml().load(Files.readString(
                REPO_ROOT.resolve("infra/grafana/provisioning/datasources/datasources.yml")));
        Set<Object> datasourceUids = ((List<Map<String, Object>>) provisioning.get("datasources")).stream()
                .map(datasource -> datasource.get("uid")).collect(Collectors.toSet());
        assertThat(datasourceUids).containsExactlyInAnyOrder("prometheus", "tempo");

        Set<Object> dashboardUids = new HashSet<>();
        try (Stream<Path> files = Files.list(REPO_ROOT.resolve("infra/grafana/dashboards"))) {
            for (Path file : files.toList()) {
                // JSON is YAML, so SnakeYAML parses it (and fails the test on a syntax error).
                Map<String, Object> dashboard = new Yaml().load(Files.readString(file));
                assertThat(dashboardUids.add(dashboard.get("uid"))).as(file + ": unique uid").isTrue();
                assertThat(dashboard).as(file.toString()).containsEntry("editable", false).containsKey("title");
                List<Map<String, Object>> panels = (List<Map<String, Object>>) dashboard.get("panels");
                assertThat(panels).as(file.toString()).isNotEmpty();
                for (Map<String, Object> panel : panels) {
                    if (panel.get("type").equals("row")) {
                        continue;
                    }
                    List<Object> uids = new ArrayList<>();
                    uids.add(((Map<String, Object>) panel.get("datasource")).get("uid"));
                    for (Map<String, Object> target : (List<Map<String, Object>>) panel.get("targets")) {
                        uids.add(((Map<String, Object>) target.get("datasource")).get("uid"));
                        assertThat(target.get("expr").toString()).as(file + ": " + panel.get("title")).isNotBlank();
                    }
                    assertThat(uids).as(file + ": " + panel.get("title")).isSubsetOf(datasourceUids);
                }
            }
        }
        assertThat(dashboardUids).containsExactlyInAnyOrder("baker-service-overview", "baker-business");
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

    /** #24: nightly backups of the database and the images, off the server, and a signal when they stop. */
    @Test
    @SuppressWarnings("unchecked")
    void backupsRunInProductionReadingUploadsWithoutWritingThem() throws IOException {
        Map<String, Object> backup = services("docker-compose.prod.yml").get("backup");

        assertThat(backup).containsEntry("build", "./infra/backup");
        assertThat((List<Object>) backup.get("volumes")).contains("uploads:/data/uploads:ro");
        assertThat((Map<String, Object>) backup.get("environment")).containsKeys(
                "PGHOST", "PGPASSWORD", "BACKUP_S3_BUCKET", "BACKUP_ENCRYPTION_PASSWORD");
        assertThat(Files.readString(REPO_ROOT.resolve("infra/backup/Dockerfile")))
                .contains("FROM postgres:17-alpine")
                .containsPattern("HEALTHCHECK[\\s\\S]*last-success");
    }

    @Test
    void backupsStayOutOfGitAndTheImageAndScriptsKeepUnixLineEndings() throws IOException {
        assertThat(Files.readAllLines(REPO_ROOT.resolve(".gitignore"))).contains("/backups/");
        assertThat(Files.readAllLines(REPO_ROOT.resolve(".dockerignore"))).contains("backups/");
        assertThat(Files.readAllLines(REPO_ROOT.resolve(".gitattributes"))).contains("*.sh text eol=lf");
        for (String script : List.of("backup.sh", "entrypoint.sh")) {
            assertThat(Files.readString(REPO_ROOT.resolve("infra/backup").resolve(script)))
                    .as(script).startsWith("#!/bin/sh\n").doesNotContain("\r");
        }
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

    // ---- The end-to-end stack (#58): docker-compose.e2e.yml on top of the production file ----

    /**
     * A copy of production beside production, sharing nothing with it: its own project (containers,
     * network, volumes), a database in memory, and an app image under another tag, so building it never
     * replaces the image production runs.
     */
    @Test
    @SuppressWarnings("unchecked")
    void e2eStackSharesNoStateWithProduction() throws IOException {
        Map<String, Object> prod = compose("docker-compose.prod.yml");
        Map<String, Object> e2e = compose("docker-compose.e2e.yml");
        Map<String, Map<String, Object>> services = (Map<String, Map<String, Object>>) e2e.get("services");

        assertThat(e2e.get("name")).isEqualTo("sugarcrumbs-e2e").isNotEqualTo(prod.get("name"));
        assertThat(rawE2e()).containsPattern("(?m)^  postgres:\\n(    .*\\n)*?    volumes: !reset \\[]");
        assertThat((List<Object>) services.get("postgres").get("tmpfs")).containsExactly("/var/lib/postgresql");
        assertThat(services.get("app").get("image")).isEqualTo("sugarcrumbs/baker:e2e")
                .isNotEqualTo(services("docker-compose.prod.yml").get("app").get("image"));
        assertThat(services.get("uploads-restore")).containsEntry("image", "sugarcrumbs/baker:e2e")
                .containsEntry("pull_policy", "never");
        assertThat(compose("docker-compose.e2e-web.yml").toString()).contains("image=sugarcrumbs/web:e2e");
    }

    /** Ports replace production's (!override), so the e2e stack never binds 80/443 next to production. */
    @Test
    @SuppressWarnings("unchecked")
    void e2eStackPublishesOnlyTheApiAndTheSiteOnLocalPorts() throws IOException {
        Map<String, Map<String, Object>> services = e2eServices();

        assertThat(rawE2e()).contains("    ports: !override\n      - \"8081:8081\"")
                .contains("    volumes: !override\n      - ./infra/caddy/Caddyfile:/etc/caddy/Caddyfile:ro");
        assertThat((List<Object>) services.get("caddy").get("volumes")).doesNotContain("caddy_data:/data");
        assertThat((Map<String, Object>) services.get("caddy").get("environment"))
                .containsEntry("APP_DOMAIN", "http://localhost:8081");
        assertThat((List<Object>) services.get("app").get("ports")).containsExactly("8080:8080");
        services.forEach((name, service) -> {
            if (!name.equals("caddy") && !name.equals("app")) {
                assertThat(service).as(name + " publishes no ports").doesNotContainKey("ports");
            }
        });
    }

    /**
     * Every production service needs a decision here: run in the e2e stack, or kept out of it. A new
     * production service (another job writing to the database or sending data somewhere) fails this until
     * someone decides.
     */
    @Test
    @SuppressWarnings("unchecked")
    void e2eStackRunsOnlyTheSiteAndKeepsProductionJobsOut() throws IOException {
        Map<String, Map<String, Object>> e2e = e2eServices();
        Set<String> runs = Set.of("postgres", "app", "web", "caddy");

        assertThat(services("docker-compose.prod.yml").keySet()).allSatisfy(name -> {
            if (!runs.contains(name)) {
                assertThat(e2e.get(name)).as(name + " is kept out of the e2e stack").isNotNull();
                assertThat((List<Object>) e2e.get(name).get("profiles")).as(name).containsExactly("prod-only");
            }
        });
        assertThat((Map<String, Object>) e2e.get("app").get("depends_on"))
                .containsKeys("db-restore", "uploads-restore");
    }

    /** Not the prod profile, no traces sent anywhere, and the per-IP limits lifted for the tests. */
    @Test
    @SuppressWarnings("unchecked")
    void e2eAppRunsTheE2eProfileWithoutTempo() throws IOException {
        Map<String, Object> environment = (Map<String, Object>) e2eServices().get("app").get("environment");

        assertThat(environment).containsEntry("SPRING_PROFILES_ACTIVE", "e2e")
                .containsEntry("APP_CORS_ALLOWED_ORIGINS", "http://localhost:4173,http://localhost:8081")
                .containsKeys("APP_RATE_LIMIT_LOGIN_CAPACITY", "APP_RATE_LIMIT_CHECKOUT_CAPACITY",
                        "APP_RATE_LIMIT_MAGIC_LINK_CAPACITY");
        assertThat(rawE2e()).contains("MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT: !reset null");
    }

    /**
     * .env.e2e is committed (test-only values) and documents every variable the e2e files read; its Owner
     * is the one profound-baker-web's e2e/support/env.ts signs in as.
     */
    @Test
    void e2eValuesAreCommittedDocumentedAndMatchTheWebTests() throws IOException {
        String envE2e = Files.readString(REPO_ROOT.resolve(".env.e2e"));
        Pattern variable = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)");

        assertThat(envE2e).contains("APP_OWNER_EMAIL=owner@e2e.test\n")
                .contains("APP_OWNER_INITIAL_PASSWORD=e2e-owner-password\n")
                .contains("APP_OWNER_TIME_ZONE=America/New_York\n");
        Set<String> names = variable.matcher(rawE2e()).results().map(m -> m.group(1)).collect(Collectors.toSet());
        assertThat(names).isNotEmpty().allSatisfy(name ->
                assertThat(envE2e).as("%s in .env.e2e", name).containsPattern("(?m)^#?\\s*" + name + "="));
        assertThat(Files.readAllLines(REPO_ROOT.resolve(".gitignore"))).doesNotContain(".env*", ".env.e2e");
        assertThat(Files.readString(REPO_ROOT.resolve("infra/e2e/restore.sh")))
                .startsWith("#!/bin/sh\n").doesNotContain("\r");
    }

    /** The e2e file with its compose merge tags (!reset, !override) removed, as plain YAML. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> e2eServices() throws IOException {
        return (Map<String, Map<String, Object>>) compose("docker-compose.e2e.yml").get("services");
    }

    private static String rawE2e() throws IOException {
        return Files.readString(REPO_ROOT.resolve("docker-compose.e2e.yml"));
    }

    /** The Caddyfile without its comment lines, so prose never satisfies or breaks a check. */
    private static String caddyDirectives() throws IOException {
        return Files.readAllLines(REPO_ROOT.resolve("infra/caddy/Caddyfile")).stream()
                .filter(line -> !line.strip().startsWith("#"))
                .collect(Collectors.joining("\n"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> services(String composeFile) throws IOException {
        return (Map<String, Map<String, Object>>) compose(composeFile).get("services");
    }

    /** A compose file as YAML; compose's own merge tags are dropped, SnakeYAML doesn't know them. */
    private static Map<String, Object> compose(String composeFile) throws IOException {
        String yaml = Files.readString(REPO_ROOT.resolve(composeFile)).replaceAll("!(reset|override)\\b", "");
        return new Yaml().load(yaml);
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
