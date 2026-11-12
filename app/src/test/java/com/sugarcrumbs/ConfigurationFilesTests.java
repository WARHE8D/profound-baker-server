package com.sugarcrumbs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards CLAUDE.md §5 "secrets come from env vars only": committed config never
 * holds a credential, every env var the app reads is documented in
 * {@code .env.example}, and {@code .env} itself stays out of git. No Spring
 * context needed.
 */
class ConfigurationFilesTests {

    private static final List<String> CONFIG_FILES =
            List.of("application.properties", "application-dev.properties", "application-prod.properties");

    /** Property keys whose value would be a credential if it were written down literally. */
    private static final Pattern SENSITIVE_KEY = Pattern.compile("(?i).*(password|secret|username|token|private-key).*");

    /** An env-var placeholder with no default, e.g. {@code ${DB_PASSWORD}}. */
    private static final Pattern REQUIRED_PLACEHOLDER = Pattern.compile("\\$\\{[A-Z0-9_]+}");

    private static final Pattern ENV_VAR_REFERENCE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)");

    private static final Path REPO_ROOT = findRepoRoot();

    @ParameterizedTest
    @ValueSource(strings = {"application.properties", "application-dev.properties", "application-prod.properties"})
    void sensitivePropertiesOnlyReferenceEnvVarsWithNoDefault(String file) {
        Properties properties = load(file);

        properties.stringPropertyNames().stream()
                .filter(key -> SENSITIVE_KEY.matcher(key).matches())
                .forEach(key -> assertThat(properties.getProperty(key))
                        .as("%s in %s", key, file)
                        .matches(REQUIRED_PLACEHOLDER));
    }

    @Test
    void prodNeverLogsSql() {
        assertThat(load("application.properties").getProperty("spring.jpa.show-sql")).isEqualTo("false");
        assertThat(load("application-prod.properties").getProperty("spring.jpa.show-sql")).isEqualTo("false");
    }

    @Test
    void everyEnvVarTheAppReadsIsDocumentedInEnvExample() throws IOException {
        Set<String> referenced = CONFIG_FILES.stream()
                .flatMap(file -> load(file).values().stream())
                .map(Object::toString)
                .flatMap(ConfigurationFilesTests::envVarReferences)
                .collect(Collectors.toSet());

        String envExample = Files.readString(REPO_ROOT.resolve(".env.example"));

        assertThat(referenced).isNotEmpty().allSatisfy(name ->
                assertThat(envExample).as(".env.example documents %s", name)
                        .containsPattern("(?m)^#?\\s*" + name + "="));
    }

    @Test
    void localEnvFileIsGitIgnored() throws IOException {
        assertThat(Files.readAllLines(REPO_ROOT.resolve(".gitignore"))).contains(".env");
    }

    @Test
    void dockerComposeTakesCredentialsFromTheEnvironment() throws IOException {
        String compose = Files.readString(REPO_ROOT.resolve("docker-compose.yml"));

        assertThat(compose)
                .contains("POSTGRES_USER: ${DB_USERNAME:?")
                .contains("POSTGRES_PASSWORD: ${DB_PASSWORD:?");
    }

    private static Stream<String> envVarReferences(String value) {
        Matcher matcher = ENV_VAR_REFERENCE.matcher(value);
        Stream.Builder<String> names = Stream.builder();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names.build();
    }

    private static Properties load(String file) {
        try (InputStream in = ConfigurationFilesTests.class.getClassLoader().getResourceAsStream(file)) {
            assertThat(in).as(file + " on the classpath").isNotNull();
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Tests run from app/ under Maven and from the repo root in some IDE setups. */
    private static Path findRepoRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".env.example"))) {
                return dir;
            }
        }
        throw new IllegalStateException(".env.example not found above " + Path.of("").toAbsolutePath());
    }
}
