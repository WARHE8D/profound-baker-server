package com.sugarcrumbs;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The end-to-end stack's restore step ({@code infra/e2e/restore.sh}, #58), run for real in the image
 * {@code db-restore} uses: a copy of "production" goes into a fresh database, the Owner gets the e2e login
 * (a hash the app's own BCrypt accepts), production's refresh tokens are gone, and a second run never
 * overwrites the data a test run is using. Its images step is checked the same way.
 */
class E2eRestoreScriptTests {

    private static final String EMAIL = "owner@e2e.test";
    private static final String PASSWORD = "e2e-owner-password";
    private static final AtomicInteger databases = new AtomicInteger();

    private static final Network network = Network.newNetwork();

    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withNetwork(network)
            .withNetworkAliases("db");

    private static final GenericContainer<?> restore = new GenericContainer<>("postgres:17-alpine")
            .withNetwork(network)
            .withCopyFileToContainer(MountableFile.forHostPath(repoRoot().resolve("infra/e2e/restore.sh")), "/restore.sh")
            // Idle, so each test runs restore.sh itself.
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sleep", "infinity"));

    @BeforeAll
    static void start() throws Exception {
        postgres.start();
        restore.withEnv("PGHOST", "db")
                .withEnv("PGUSER", postgres.getUsername())
                .withEnv("PGPASSWORD", postgres.getPassword())
                .withEnv("E2E_OWNER_EMAIL", EMAIL)
                .withEnv("E2E_OWNER_PASSWORD", PASSWORD)
                .start();
        // "Production": the real Owner with its own login and a live refresh token, and a guest's order.
        ok(sh("createdb prod"));
        ok(psql("prod", "CREATE SCHEMA identity; CREATE SCHEMA ordering;"
                + " CREATE TABLE identity.owners (id int PRIMARY KEY, email text NOT NULL, password_hash text NOT NULL);"
                + " CREATE TABLE identity.refresh_tokens (id int PRIMARY KEY, owner_id int NOT NULL);"
                + " CREATE TABLE ordering.orders (id int PRIMARY KEY, reference text NOT NULL);"
                + " INSERT INTO identity.owners VALUES (1, 'baker@real.example', '$2a$12$notTheE2ePassword');"
                + " INSERT INTO identity.refresh_tokens VALUES (1, 1);"
                + " INSERT INTO ordering.orders VALUES (1, 'SC-OLD01');"));
        ok(sh("mkdir -p /backups && pg_dump -d prod --format=custom --no-owner -f /backups/baker-20261101T023000Z.dump"));
        ok(psql("prod", "UPDATE ordering.orders SET reference = 'SC-NEW02'"));
        ok(sh("pg_dump -d prod --format=custom --no-owner -f /backups/baker-20261102T023000Z.dump"));
        ok(sh("mkdir -p /src/items && echo jpeg > /src/items/cake.jpg"
                + " && tar -czf /backups/uploads-20261102T023000Z.tar.gz -C /src ."));
    }

    @AfterAll
    static void stop() {
        restore.stop();
        postgres.stop();
        network.close();
    }

    @Test
    void theNewestDumpIsRestoredAndTheOwnerSignsInWithTheE2eLogin() throws Exception {
        String db = freshDatabase();

        ok(sh("PGDATABASE=" + db + " sh /restore.sh db"));

        assertThat(query(db, "SELECT reference FROM ordering.orders")).as("the newest dump").isEqualTo("SC-NEW02");
        assertThat(query(db, "SELECT email FROM identity.owners")).isEqualTo(EMAIL);
        String hash = query(db, "SELECT password_hash FROM identity.owners");
        assertThat(new BCryptPasswordEncoder().matches(PASSWORD, hash)).as("the app's BCrypt accepts " + hash).isTrue();
        assertThat(hash).as("cost 12, as the app hashes").startsWith("$2a$12$");
        assertThat(query(db, "SELECT count(*) FROM identity.refresh_tokens")).isEqualTo("0");
        assertThat(query(db, "SELECT count(*) FROM pg_extension WHERE extname = 'pgcrypto'"))
                .as("the schema stays as production has it").isEqualTo("0");
    }

    @Test
    void e2eDumpPicksAnOlderCopyAndAnUnknownOneFails() throws Exception {
        String db = freshDatabase();

        ok(sh("PGDATABASE=" + db + " E2E_DUMP=baker-20261101T023000Z.dump sh /restore.sh db"));
        assertThat(query(db, "SELECT reference FROM ordering.orders")).isEqualTo("SC-OLD01");

        ExecResult unknown = sh("PGDATABASE=" + freshDatabase() + " E2E_DUMP=baker-19990101T000000Z.dump sh /restore.sh db");
        assertThat(unknown.getExitCode()).isNotZero();
        assertThat(unknown.getStdout()).contains("is not in the backup directory");
    }

    /** `up` on a running stack runs the step again: the data the tests are using stays. */
    @Test
    void aSecondRunKeepsTheDatabaseItFilled() throws Exception {
        String db = freshDatabase();
        ok(sh("PGDATABASE=" + db + " sh /restore.sh db"));
        ok(psql(db, "INSERT INTO ordering.orders VALUES (2, 'SC-TEST3')"));

        ExecResult again = ok(sh("PGDATABASE=" + db + " sh /restore.sh db"));

        assertThat(again.getStdout()).contains("already holds data");
        assertThat(query(db, "SELECT count(*) FROM ordering.orders")).isEqualTo("2");
    }

    @Test
    void withoutADumpTheDatabaseStaysEmpty() throws Exception {
        String db = freshDatabase();

        ExecResult run = ok(sh("mkdir -p /empty && BACKUP_DIR=/empty PGDATABASE=" + db + " sh /restore.sh db"));

        assertThat(run.getStdout()).contains("starting with an empty database");
        assertThat(query(db, "SELECT count(*) FROM pg_namespace WHERE nspname = 'identity'")).isEqualTo("0");
    }

    @Test
    void theImagesTakenWithTheDumpReplaceTheUploadsOnce() throws Exception {
        ok(sh("rm -rf /data/uploads && mkdir -p /data/uploads && echo old > /data/uploads/stale.jpg"));

        ok(sh("sh /restore.sh uploads"));
        assertThat(sh("ls -1A /data/uploads /data/uploads/items").getStdout())
                .contains("cake.jpg", ".e2e-restored").doesNotContain("stale.jpg");

        // An image a test uploaded survives another `up`.
        ok(sh("echo png > /data/uploads/items/uploaded.png"));
        assertThat(ok(sh("sh /restore.sh uploads")).getStdout()).contains("already hold");
        assertThat(sh("ls -1A /data/uploads/items").getStdout()).contains("cake.jpg", "uploaded.png");

        // A dump without its archive: no images rather than another dump's.
        ok(sh("E2E_DUMP=baker-20261101T023000Z.dump sh /restore.sh uploads"));
        assertThat(sh("ls -1A /data/uploads").getStdout().strip()).isEqualTo(".e2e-restored");
    }

    // ---- helpers ----

    private static String freshDatabase() throws Exception {
        String name = "e2e" + databases.incrementAndGet();
        ok(sh("createdb " + name));
        return name;
    }

    private static ExecResult ok(ExecResult result) {
        assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isZero();
        return result;
    }

    private static ExecResult sh(String command) throws IOException, InterruptedException {
        return restore.execInContainer("sh", "-c", command);
    }

    private static ExecResult psql(String database, String sql) throws IOException, InterruptedException {
        return restore.execInContainer("psql", "-d", database, "-tA", "-v", "ON_ERROR_STOP=1", "-c", sql);
    }

    private static String query(String database, String sql) throws IOException, InterruptedException {
        return ok(psql(database, sql)).getStdout().strip();
    }

    private static Path repoRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".env.example"))) {
                return dir;
            }
        }
        throw new IllegalStateException(".env.example not found above " + Path.of("").toAbsolutePath());
    }
}
