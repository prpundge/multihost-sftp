package com.example.multihostsftp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.multihostsftp.support.BlackHoleServer;
import com.example.multihostsftp.support.EmbeddedSftpServer;
import com.example.multihostsftp.support.TestKeys;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real application - Spring Boot, the XML route template and one route per transfer in application-it.yml -
 * against two embedded SFTP servers (host A: key auth, two remote folders; host B: password auth) and a host that
 * hangs.
 */
@SpringBootTest
@ActiveProfiles("it")
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class SftpTransferIntegrationTest {

    // secrets are generated per run; the password contains characters that are special in URIs on purpose
    private static final String PASSWORD_SECRET_PART = UUID.randomUUID().toString();
    private static final String BANK_B_PASSWORD = "p@ss w+rd&%#!(x)" + PASSWORD_SECRET_PART;
    private static final String BANK_A_KEY_PASSPHRASE = UUID.randomUUID().toString();

    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    static Path work;

    static EmbeddedSftpServer hostA;
    static EmbeddedSftpServer hostB;
    static BlackHoleServer hangingHost;
    static Path knownHosts;
    static Path wrongKnownHosts;
    static Path clientKey;

    @BeforeAll
    static void startServers() throws Exception {
        KeyPair client = TestKeys.rsaKeyPair();
        clientKey = TestKeys.writeOpenSshPrivateKey(client, BANK_A_KEY_PASSPHRASE, work.resolve("client_id_rsa"));

        hostA = EmbeddedSftpServer.withPublicKey(work.resolve("sftp-hostA"), 0, "userA", client.getPublic());
        hostB = EmbeddedSftpServer.withPassword(work.resolve("sftp-hostB"), 0, "userB", BANK_B_PASSWORD);
        hangingHost = new BlackHoleServer();

        // the routes never create remote folders, so they exist up front (all but /does/not/exist)
        Files.createDirectories(hostA.path("/inbound/payments"));
        Files.createDirectories(hostA.path("/inbound/statements"));
        Files.createDirectories(hostB.path("/upload/reports"));
        Files.createDirectories(hostB.path(hostB.loginDir() + "/outbox"));

        knownHosts = Files.write(work.resolve("known_hosts"), List.of(hostA.knownHostsLine(), hostB.knownHostsLine()));
        // host A's address with host B's key: what a man-in-the-middle would look like
        wrongKnownHosts = Files.write(work.resolve("known_hosts_wrong"), List.of(hostA.knownHostsLine(hostB.hostKey())));
    }

    @DynamicPropertySource
    static void testEnvironment(DynamicPropertyRegistry registry) {
        registry.add("it.work", () -> slashes(work));
        registry.add("it.hostA.port", () -> hostA.port());
        registry.add("it.hostB.port", () -> hostB.port());
        registry.add("it.hangingHost.port", () -> hangingHost.port());
        registry.add("it.knownHosts", () -> slashes(knownHosts));
        registry.add("it.wrongKnownHosts", () -> slashes(wrongKnownHosts));
        registry.add("it.clientKey", () -> slashes(clientKey));
        // stand-ins for environment variables
        registry.add("IT_BANKA_KEY_PASSPHRASE", () -> BANK_A_KEY_PASSPHRASE);
        registry.add("IT_BANKB_PASSWORD", () -> BANK_B_PASSWORD);
    }

    @AfterAll
    static void stopServers() throws IOException {
        for (AutoCloseable server : new AutoCloseable[] {hostA, hostB, hangingHost}) {
            try {
                if (server != null) {
                    server.close();
                }
            } catch (Exception e) {
                // best effort
            }
        }
    }

    @Test
    void createsTheLocalFoldersAtStartup() {
        // created before the routes start: transfers sharing a source folder must not race to create done/ and error/
        for (String folder : List.of("bankA", "bankA/done", "bankA/error", "bankB/done", "bankB/error", "hanging/error")) {
            assertThat(local(folder)).isDirectory();
        }
        assertThat(work.resolve("idempotent")).isDirectory();
    }

    @Test
    void uploadsMatchingFilesToTheRightHostAndRemoteFolder(CapturedOutput output) throws IOException {
        drop("bankA", "PAY_20261126_001.csv", "id;amount\n1;100.00\n");
        drop("bankA", "STMT_20261126.xml", "<statement/>");
        drop("bankB", "RPT_20261126.txt", "daily report");

        eventually().untilAsserted(() -> {
            assertThat(hostA.path("/inbound/payments/PAY_20261126_001.csv")).hasContent("id;amount\n1;100.00\n");
            assertThat(hostA.path("/inbound/statements/STMT_20261126.xml")).hasContent("<statement/>");
            assertThat(hostB.path("/upload/reports/RPT_20261126.txt")).hasContent("daily report");
        });
        // ... and nowhere else (sessions start in /home/<user>: an absolute remoteDir must not end up below it)
        assertThat(hostA.path(hostA.loginDir() + "/inbound")).doesNotExist();
        assertThat(hostB.path(hostB.loginDir() + "/upload")).doesNotExist();
        assertThat(hostA.path("/inbound/statements/PAY_20261126_001.csv")).doesNotExist();
        assertThat(hostA.path("/inbound/payments/STMT_20261126.xml")).doesNotExist();
        assertThat(filesIn(hostA.root())).doesNotContain("RPT_20261126.txt");
        assertThat(filesIn(hostB.root())).doesNotContain("PAY_20261126_001.csv", "STMT_20261126.xml");

        // the local files are archived in done/
        eventually().untilAsserted(() -> {
            assertThat(local("bankA/done/PAY_20261126_001.csv")).exists();
            assertThat(local("bankA/done/STMT_20261126.xml")).exists();
            assertThat(local("bankB/done/RPT_20261126.txt")).exists();
        });
        assertThat(local("bankA/PAY_20261126_001.csv")).doesNotExist();
        assertThat(local("bankB/RPT_20261126.txt")).doesNotExist();

        // the server never saw a partial file: data went to <name>.part only, which was renamed when complete
        assertThat(hostA.fileEvents())
                .contains("write PAY_20261126_001.csv.part", "rename PAY_20261126_001.csv.part -> PAY_20261126_001.csv")
                .doesNotContain("write PAY_20261126_001.csv");
        assertThat(filesIn(hostA.root())).noneMatch(name -> name.endsWith(".part"));

        // one log line per transfer with route, file, host, remote folder and result
        assertThat(output).contains("route=bankA-payments file=PAY_20261126_001.csv host=127.0.0.1:" + hostA.port()
                + " remoteDir=/inbound/payments/ result=SUCCESS");
        assertThat(output).contains("route=bankB-reports file=RPT_20261126.txt host=127.0.0.1:" + hostB.port()
                + " remoteDir=/upload/reports/ result=SUCCESS");
    }

    @Test
    void remoteDirWithoutLeadingSlashIsRelativeToTheLoginFolder() throws IOException {
        drop("relative", "NOTE_001.txt", "relative");

        eventually().until(() -> Files.exists(hostB.path(hostB.loginDir() + "/outbox/NOTE_001.txt")));
        assertThat(hostB.path("/outbox/NOTE_001.txt")).doesNotExist();
    }

    @Test
    void ignoresFilesThatDoNotMatchThePattern() throws IOException {
        List<String> notMatching = List.of(
                "PAY_001.txt", "xPAY_001.csv", "pay_001.csv", "PAY_001.csv.tmp", "STMT_001.txt", "notes.md");
        for (String name : notMatching) {
            drop("bankA", name, "not for the bank");
        }
        // dropped last: once it is uploaded, the folder has been scanned while all the files above were there
        drop("bankA", "PAY_sentinel.csv", "sentinel");

        eventually().until(() -> Files.exists(local("bankA/done/PAY_sentinel.csv")));
        for (String name : notMatching) {
            assertThat(local("bankA/" + name)).as("%s is left alone", name).exists();
        }
        assertThat(filesIn(hostA.root())).doesNotContainAnyElementsOf(notMatching);
        assertThat(filesIn(local("bankA/done"))).doesNotContainAnyElementsOf(notMatching);
    }

    @Test
    void movesTheFileToTheErrorFolderWhenTheUploadFails(CapturedOutput output) throws IOException {
        drop("missing", "DATA_001.csv", "the remote folder does not exist");

        eventually().untilAsserted(() ->
                assertThat(local("missing/error/DATA_001.csv")).hasContent("the remote folder does not exist"));
        assertThat(local("missing/DATA_001.csv")).doesNotExist();
        assertThat(local("missing/done/DATA_001.csv")).doesNotExist();
        assertThat(hostB.path("/does")).as("remote folders are never created").doesNotExist();
        assertThat(output).contains("Transfer FAILED: route=bankB-missing-folder file=DATA_001.csv host=127.0.0.1:"
                + hostB.port() + " remoteDir=/does/not/exist/ result=FAILED after 3 retries");
    }

    @Test
    void refusesAServerWhoseHostKeyDoesNotMatchKnownHosts(CapturedOutput output) throws IOException {
        drop("mitm", "PAY_mitm.csv", "must not reach an impostor");

        eventually().untilAsserted(() -> assertThat(local("mitm/error/PAY_mitm.csv")).exists());
        assertThat(hostA.path("/inbound/payments/PAY_mitm.csv")).doesNotExist();
        assertThat(hostA.fileEvents()).noneMatch(event -> event.contains("PAY_mitm.csv"));
        assertThat(output).contains("Transfer FAILED: route=bankA-wrong-hostkey file=PAY_mitm.csv");
    }

    @Test
    void aHangingHostDoesNotHoldUpTheOtherTransfers() throws IOException {
        int attemptsBefore = hangingHost.connectionAttempts();
        drop("hanging", "HANG_001.csv", "stuck");
        eventually().until(() -> hangingHost.connectionAttempts() > attemptsBefore); // that route is now stuck

        drop("bankB", "RPT_while_hanging.txt", "still flowing");
        eventually().atMost(Duration.ofSeconds(10))
                .until(() -> Files.exists(hostB.path("/upload/reports/RPT_while_hanging.txt")));
        assertThat(local("hanging/error/HANG_001.csv")).as("the hanging transfer is still retrying").doesNotExist();

        // first attempt + 3 retries, each giving up after the 2 s connect timeout, then the file goes to error/
        eventually().atMost(Duration.ofSeconds(30)).until(() -> Files.exists(local("hanging/error/HANG_001.csv")));
        assertThat(hangingHost.connectionAttempts() - attemptsBefore).isEqualTo(4);
    }

    @Test
    void neverUploadsTheSameFileTwice() throws IOException {
        drop("bankB", "RPT_once.txt", "send me once");
        eventually().until(() -> Files.exists(local("bankB/done/RPT_once.txt")));
        Path remote = hostB.path("/upload/reports/RPT_once.txt");
        assertThat(remote).exists();
        Files.delete(remote); // a second upload would show up again

        // the very same file (name, size and timestamp) turns up again, e.g. restored by an operator
        Files.copy(local("bankB/done/RPT_once.txt"), local("bankB/RPT_once.txt"), StandardCopyOption.COPY_ATTRIBUTES);
        // a new file dropped after it tells us when the folder has been polled again
        drop("bankB", "RPT_after.txt", "a new file");
        eventually().until(() -> Files.exists(hostB.path("/upload/reports/RPT_after.txt")));

        assertThat(remote).as("not uploaded a second time").doesNotExist();
        assertThat(local("bankB/RPT_once.txt")).as("skipped, left where it is").exists();
    }

    @Test
    void waitsUntilAFileIsCompletelyWritten() throws Exception {
        Path file = Files.createDirectories(local("bankB")).resolve("RPT_slow.txt");
        StringBuilder expected = new StringBuilder();
        try (OutputStream out = Files.newOutputStream(file)) {
            for (int i = 1; i <= 20; i++) { // about 1 s of writing: several polls see a growing file
                String line = "line " + i + "\n";
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
                expected.append(line);
                Thread.sleep(50);
            }
        }
        eventually().untilAsserted(() ->
                assertThat(hostB.path("/upload/reports/RPT_slow.txt")).hasContent(expected.toString()));
    }

    @Test
    void neverLogsPasswordsOrPassphrases(CapturedOutput output) throws IOException {
        drop("bankA", "PAY_audit.csv", "audit");
        drop("bankB", "RPT_audit.txt", "audit");
        eventually().until(() -> Files.exists(hostA.path("/inbound/payments/PAY_audit.csv"))
                && Files.exists(hostB.path("/upload/reports/RPT_audit.txt")));

        assertThat(output.getAll())
                .contains("Transfer 'bankB-reports'") // the startup log is part of the captured output
                .doesNotContain(PASSWORD_SECRET_PART)
                .doesNotContain(BANK_A_KEY_PASSPHRASE);
    }

    // ------------------------------------------------------------------ helpers

    private static Path drop(String folder, String fileName, String content) throws IOException {
        Path dir = Files.createDirectories(work.resolve("out").resolve(folder));
        return Files.writeString(dir.resolve(fileName), content);
    }

    private static Path local(String relativePath) {
        return work.resolve("out").resolve(relativePath);
    }

    private static List<String> filesIn(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList();
        }
    }

    private static ConditionFactory eventually() {
        return Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100));
    }

    private static String slashes(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
