package com.example.multihostsftp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;

import com.example.multihostsftp.support.EmbeddedSftpServer;
import com.example.multihostsftp.support.TestKeys;

/**
 * Try the application on your machine without any real SFTP server:
 *
 * <pre>
 *   mvn spring-boot:test-run
 * </pre>
 *
 * Starts two embedded SFTP servers - host A on 127.0.0.1:2222 (key auth) and host B on 127.0.0.1:2223 (password auth)
 * - and then the real application with the {@code local} profile ({@code src/test/resources/application-local.yml}).
 * Then it drops a few mock files, some matching a transfer and some not, into {@code target/local-demo/out/bankA} and
 * {@code .../bankB}; drop more there yourself. Uploads arrive below {@code target/local-demo/sftp-hostA} and
 * {@code .../sftp-hostB}, and the originals are moved to the backup folders {@code target/local-demo/backup/bankA} and
 * {@code .../bankB}. Options: {@code -Ddemo.portA=..., -Ddemo.portB=...}, {@code -Ddemo.mockFiles=false}.
 */
public final class LocalDemoApplication {

    private static final Logger LOG = LoggerFactory.getLogger(LocalDemoApplication.class);

    /** A mock file to drop below {@code out/}, and what should happen to it. */
    private record MockFile(String path, String expectedResult, String content) {
    }

    private LocalDemoApplication() {
    }

    public static void main(String[] args) throws Exception {
        Path work = Path.of("target", "local-demo").toAbsolutePath();
        Files.createDirectories(work);

        // generated per run and handed to the application the way environment variables would be
        String keyPassphrase = UUID.randomUUID().toString();
        String password = UUID.randomUUID().toString();
        KeyPair clientKey = TestKeys.rsaKeyPair();
        TestKeys.writeOpenSshPrivateKey(clientKey, keyPassphrase, work.resolve("client_id_rsa"));

        EmbeddedSftpServer hostA = EmbeddedSftpServer.withPublicKey(
                work.resolve("sftp-hostA"), Integer.getInteger("demo.portA", 2222), "userA", clientKey.getPublic());
        EmbeddedSftpServer hostB = EmbeddedSftpServer.withPassword(
                work.resolve("sftp-hostB"), Integer.getInteger("demo.portB", 2223), "userB", password);
        Files.createDirectories(hostA.path("/inbound/payments"));
        Files.createDirectories(hostA.path("/inbound/statements"));
        Files.createDirectories(hostB.path("/upload/reports"));
        Files.write(work.resolve("known_hosts"), List.of(hostA.knownHostsLine(), hostB.knownHostsLine()));

        System.setProperty("local.work", work.toString().replace('\\', '/'));
        System.setProperty("local.portA", String.valueOf(hostA.port()));
        System.setProperty("local.portB", String.valueOf(hostB.port()));
        System.setProperty("LOCAL_KEY_PASSPHRASE", keyPassphrase);
        System.setProperty("LOCAL_SFTP_PASSWORD", password);

        new SpringApplicationBuilder(SftpTransferApplication.class).profiles("local").run(args);

        Path out = work.resolve("out");
        LOG.info("""

                ==== Local demo running (Ctrl+C to stop) ====
                  SFTP host A  127.0.0.1:{}  (key auth)       files appear in {}
                  SFTP host B  127.0.0.1:{}  (password auth)  files appear in {}
                  Drop files into {}:
                    bankA/PAY_<anything>.csv   -> host A /inbound/payments
                    bankA/STMT_<anything>.xml  -> host A /inbound/statements
                    bankB/RPT_<anything>.txt   -> host B /upload/reports
                  After the upload the originals are moved to the backup folders in {} (bankA, bankB);
                  failed files go to the 'error' folder inside their out folder.
                """,
                hostA.port(), hostA.root(), hostB.port(), hostB.root(), out, work.resolve("backup"));

        if (Boolean.parseBoolean(System.getProperty("demo.mockFiles", "true"))) {
            dropMockFiles(out);
        }
    }

    /** Writes a few matching and non-matching mock files, so the demo shows transfers without any typing. */
    private static void dropMockFiles(Path out) throws IOException {
        LocalDate today = LocalDate.now();
        String day = today.format(DateTimeFormatter.BASIC_ISO_DATE);
        String ignored = "Mock file that matches no transfer pattern: it is not sent and stays here.\n";
        List<MockFile> files = List.of(
                new MockFile("bankA/PAY_" + day + "_001.csv", "host A /inbound/payments", """
                        paymentId;executionDate;debtorAccount;creditorAccount;amount;currency
                        PAY-%1$s-0001;%2$s;MOCK-ACC-1001;MOCK-ACC-2001;1250.00;EUR
                        PAY-%1$s-0002;%2$s;MOCK-ACC-1001;MOCK-ACC-2002;89.90;EUR
                        """.formatted(day, today)),
                new MockFile("bankA/PAY_" + day + "_002.csv", "host A /inbound/payments", """
                        paymentId;executionDate;debtorAccount;creditorAccount;amount;currency
                        PAY-%1$s-0003;%2$s;MOCK-ACC-1002;MOCK-ACC-2003;15000.00;EUR
                        """.formatted(day, today)),
                new MockFile("bankA/STMT_" + day + ".xml", "host A /inbound/statements", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <statement account="MOCK-ACC-1001" date="%2$s">
                          <entry type="DEBIT" amount="1250.00" currency="EUR" ref="PAY-%1$s-0001"/>
                          <entry type="DEBIT" amount="89.90" currency="EUR" ref="PAY-%1$s-0002"/>
                        </statement>
                        """.formatted(day, today)),
                new MockFile("bankB/RPT_" + day + ".txt", "host B /upload/reports", """
                        Mock daily report %s
                        payments sent: 3
                        statements sent: 1
                        """.formatted(today)),
                new MockFile("bankA/notes.txt", "stays: matches no pattern", ignored),
                new MockFile("bankA/PAY_" + day + "_003.txt", "stays: payments must be .csv", ignored),
                new MockFile("bankA/pay_" + day + "_004.csv", "stays: patterns are case-sensitive", ignored),
                new MockFile("bankB/RPT_" + day + ".csv", "stays: reports must be .txt", ignored));

        for (MockFile file : files) {
            Path target = out.resolve(file.path());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content());
        }
        LOG.info("\n\n==== Dropped {} mock files into {} ====\n{}\n", files.size(), out, files.stream()
                .map(f -> String.format("  %-28s -> %s", f.path(), f.expectedResult()))
                .collect(Collectors.joining("\n")));
    }
}
