package com.sugarcrumbs;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production backup (#24), run for real: the image from {@code infra/backup} runs
 * {@code backup.sh} against a Postgres container, and the dump it writes is restored into a fresh
 * database. Also: rotation keeps exactly the retention window, the off-box copy is made, and a
 * failed run neither leaves half-written files nor records a success.
 *
 * <p>The off-box remote here is a plain directory (rclone treats a path as a local remote);
 * production uses the encrypted S3 remote that {@code entrypoint.sh} builds.
 */
class BackupScriptTests {

    private static final Network network = Network.newNetwork();

    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withNetwork(network)
            .withNetworkAliases("db");

    private static final GenericContainer<?> backup = new GenericContainer<>(
            new ImageFromDockerfile("sugarcrumbs/backup-test", true)
                    .withFileFromPath(".", repoRoot().resolve("infra/backup")))
            .withNetwork(network)
            // Idle instead of the scheduler, so each test runs backup.sh itself.
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sleep", "infinity"));

    @BeforeAll
    static void start() throws Exception {
        postgres.start();
        backup.withEnv("PGHOST", "db")
                .withEnv("PGUSER", postgres.getUsername())
                .withEnv("PGPASSWORD", postgres.getPassword())
                .withEnv("PGDATABASE", postgres.getDatabaseName())
                .start();
        psql(postgres.getDatabaseName(), "CREATE TABLE guests (id int PRIMARY KEY, name text);"
                + " INSERT INTO guests VALUES (1, 'Ada');");
        sh("mkdir -p /data/uploads && echo jpeg > /data/uploads/cake.jpg");
    }

    @AfterAll
    static void stop() {
        backup.stop();
        postgres.stop();
        network.close();
    }

    @Test
    void aBackupRestoresIntoAFreshDatabaseAndKeepsTwoWeeks() throws Exception {
        sh("rm -rf /backups /offsite && mkdir -p /backups"
                + " && touch -t " + daysAgo(20) + " /backups/baker-20000101T000000Z.dump"
                + " && touch -t " + daysAgo(20) + " /backups/uploads-20000101T000000Z.tar.gz"
                + " && touch -t " + daysAgo(3) + " /backups/baker-20000102T000000Z.dump"
                + " && touch -t " + daysAgo(20) + " /backups/notes.txt");

        ExecResult run = sh("BACKUP_REMOTE=/offsite /usr/local/bin/backup.sh");

        assertThat(run.getExitCode()).as(run.getStdout() + run.getStderr()).isZero();
        List<String> kept = list("/backups");
        assertThat(kept).as("older than 14 days removed, newer and unrelated files kept")
                .doesNotContain("baker-20000101T000000Z.dump", "uploads-20000101T000000Z.tar.gz")
                .contains("baker-20000102T000000Z.dump", "notes.txt", "last-success")
                .noneMatch(name -> name.endsWith(".partial"));
        String dump = kept.stream().filter(n -> n.matches("baker-\\d{8}T\\d{6}Z\\.dump")
                && !n.startsWith("baker-2000")).findFirst().orElseThrow();
        String uploads = dump.replace("baker-", "uploads-").replace(".dump", ".tar.gz");
        assertThat(kept).contains(uploads);
        assertThat(list("/offsite")).contains(dump, uploads);
        assertThat(sh("tar -tzf /backups/" + uploads).getStdout()).contains("cake.jpg");

        assertThat(sh("createdb restored && pg_restore --no-owner -d restored /backups/" + dump).getExitCode())
                .isZero();
        assertThat(psql("restored", "SELECT name FROM guests WHERE id = 1").getStdout().strip()).isEqualTo("Ada");

        // The README's runbook command: restoring over the live database puts it back as it was.
        psql(postgres.getDatabaseName(), "UPDATE guests SET name = 'Eve'; INSERT INTO guests VALUES (2, 'Bob');");
        ExecResult restore = sh("pg_restore --clean --if-exists --no-owner --single-transaction -d \"$PGDATABASE\""
                + " /backups/" + dump);
        assertThat(restore.getExitCode()).as(restore.getStderr()).isZero();
        assertThat(psql(postgres.getDatabaseName(), "SELECT string_agg(name, ',' ORDER BY id) FROM guests")
                .getStdout().strip()).isEqualTo("Ada");
    }

    @Test
    void aFailedRunLeavesNoPartialFilesAndRecordsNoSuccess() throws Exception {
        sh("rm -rf /backups && mkdir -p /backups && echo 1 > /backups/last-success");

        ExecResult run = sh("PGPASSWORD=wrong /usr/local/bin/backup.sh");

        assertThat(run.getExitCode()).isNotZero();
        assertThat(list("/backups")).containsExactly("last-success");
        assertThat(sh("cat /backups/last-success").getStdout().strip()).isEqualTo("1");
    }

    /** Backups hold guests' contact details: they never leave the server unencrypted. */
    @Test
    void theSchedulerRefusesABucketWithoutAnEncryptionPassword() throws Exception {
        ExecResult start = sh("BACKUP_S3_BUCKET=bakery /usr/local/bin/entrypoint.sh");

        assertThat(start.getExitCode()).isEqualTo(1);
        assertThat(start.getStdout()).contains("refusing to send unencrypted backups");
    }

    // ---- helpers ----

    private static ExecResult sh(String command) throws IOException, InterruptedException {
        return backup.execInContainer("sh", "-c", command);
    }

    private static ExecResult psql(String database, String sql) throws IOException, InterruptedException {
        return backup.execInContainer("psql", "-d", database, "-tA", "-v", "ON_ERROR_STOP=1", "-c", sql);
    }

    private static List<String> list(String dir) throws IOException, InterruptedException {
        return Arrays.stream(sh("ls -1A " + dir).getStdout().split("\n")).filter(s -> !s.isBlank()).toList();
    }

    /** A {@code touch -t} timestamp {@code days} days ago; the container's clock is UTC. */
    private static String daysAgo(int days) {
        return LocalDateTime.now(ZoneOffset.UTC).minusDays(days).format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
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
