package com.example.multihostsftp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

import com.example.multihostsftp.SftpTransferProperties.Transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The application must refuse to start, with a clear message, when the transfer configuration is not usable. */
@ExtendWith(OutputCaptureExtension.class)
class ConfigValidationTest {

    @TempDir
    static Path tmp;
    static Path knownHosts;

    @BeforeAll
    static void createFiles() throws IOException {
        knownHosts = Files.writeString(tmp.resolve("known_hosts"), "");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SftpTransferProperties.class)
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class)
            .withPropertyValues("sftp-transfer.knownHostsFile=" + slashes(knownHosts));

    @Test
    void acceptsAValidConfiguration() {
        runner.withPropertyValues(transfer(0, "bankA-payments")).withPropertyValues(transfer(1, "bankB-reports"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SftpTransferProperties.class).getTransfers())
                            .extracting(Transfer::getName).containsExactly("bankA-payments", "bankB-reports");
                });
    }

    @Test
    void reportsAllMissingFieldsAtOnce() {
        runner.withPropertyValues("sftp-transfer.transfers[0].name=bankA-payments",
                        "sftp-transfer.transfers[0].password=x")
                .run(context -> assertThat(startupFailure(context))
                        .contains("sftp-transfer.transfers[0] (bankA-payments): 'sourceDir' is required")
                        .contains("sftp-transfer.transfers[0] (bankA-payments): 'filePattern' is required")
                        .contains("sftp-transfer.transfers[0] (bankA-payments): 'host' is required")
                        .contains("sftp-transfer.transfers[0] (bankA-payments): 'username' is required")
                        .contains("sftp-transfer.transfers[0] (bankA-payments): 'remoteDir' is required"));
    }

    @Test
    void rejectsDuplicateNames() {
        runner.withPropertyValues(transfer(0, "bankA-payments")).withPropertyValues(transfer(1, "BANKA-payments"))
                .run(context -> assertThat(startupFailure(context)).contains(
                        "sftp-transfer.transfers[1] (BANKA-payments): name 'BANKA-payments' is already used by"
                                + " sftp-transfer.transfers[0] (bankA-payments)"));
    }

    @Test
    void requiresPasswordOrPrivateKey() {
        runner.withPropertyValues(transfer(0, "bankA-payments"))
                .withPropertyValues("sftp-transfer.transfers[0].password=")
                .run(context -> assertThat(startupFailure(context))
                        .contains("sftp-transfer.transfers[0] (bankA-payments): set 'password' or 'privateKeyPath'"));
    }

    @Test
    void reportsAnUnsetEnvironmentVariable() {
        runner.withPropertyValues(transfer(0, "bankB-reports"))
                .withPropertyValues("sftp-transfer.transfers[0].password=${SURELY_UNSET_SFTP_PASSWORD}")
                .run(context -> assertThat(startupFailure(context)).contains("sftp-transfer.transfers[0] (bankB-reports):"
                        + " 'password' refers to SURELY_UNSET_SFTP_PASSWORD, which is not set"));
    }

    @Test
    void neverShowsSecretsInTheErrorMessage() {
        runner.withPropertyValues(transfer(0, "bankB-reports"))
                .withPropertyValues("sftp-transfer.transfers[0].password=TopSecret)&42",
                        "sftp-transfer.transfers[0].host=")
                .run(context -> assertThat(startupFailure(context))
                        .contains("'host' is required")
                        .contains("'password' must not contain the character sequence ')&'")
                        .doesNotContain("TopSecret"));
    }

    @Test
    void rejectsAnInvalidRegularExpression() {
        runner.withPropertyValues(transfer(0, "bankA-payments"))
                .withPropertyValues("sftp-transfer.transfers[0].filePattern=PAY_[0-9")
                .run(context -> assertThat(startupFailure(context))
                        .contains("sftp-transfer.transfers[0] (bankA-payments): 'filePattern' is not a valid regular expression"));
    }

    @Test
    void rejectsATypoInAKey() {
        runner.withPropertyValues(transfer(0, "bankA-payments"))
                .withPropertyValues("sftp-transfer.transfers[0].remoteDirectory=/inbound")
                .run(context -> assertThat(startupFailure(context))
                        .containsIgnoringCase("sftp-transfer.transfers[0].remotedirectory")
                        .contains("were left unbound"));
    }

    @Test
    void requiresTheKnownHostsFileWhenStrictHostKeyCheckingIsOn() {
        runner.withPropertyValues(transfer(0, "bankA-payments"))
                .withPropertyValues("sftp-transfer.knownHostsFile=" + slashes(tmp.resolve("missing_known_hosts")))
                .run(context -> assertThat(startupFailure(context)).contains(
                        "sftp-transfer.transfers[0] (bankA-payments): strictHostKeyChecking is on but the known_hosts file is missing"));
    }

    @Test
    void rejectsTwoTransfersWatchingTheSameFiles() {
        runner.withPropertyValues(transfer(0, "bankA-payments")).withPropertyValues(transfer(1, "bankA-copy"))
                .withPropertyValues("sftp-transfer.transfers[1].sourceDir=" + slashes(tmp.resolve("out/bankA-payments")))
                .run(context -> assertThat(startupFailure(context)).contains(
                        "sftp-transfer.transfers[1] (bankA-copy): watches exactly the same files as"
                                + " sftp-transfer.transfers[0] (bankA-payments)"));
    }

    @Test
    void requiresAtLeastOneTransfer() {
        runner.run(context -> assertThat(startupFailure(context))
                .contains("sftp-transfer.transfers is empty - configure at least one transfer"));
    }

    /** What an operator actually sees when the configuration is wrong. */
    @Test
    void theApplicationRefusesToStartAndExplainsWhy(CapturedOutput output) {
        assertThatThrownBy(() -> SpringApplication.run(SftpTransferApplication.class,
                "--spring.main.banner-mode=off",
                "--sftp-transfer.knownHostsFile=" + slashes(knownHosts),
                "--sftp-transfer.transfers[0].name=bankB-reports",
                "--sftp-transfer.transfers[0].sourceDir=" + slashes(tmp.resolve("out/bankB")),
                "--sftp-transfer.transfers[0].filePattern=RPT_.*\\.txt",
                "--sftp-transfer.transfers[0].username=user2",
                "--sftp-transfer.transfers[0].password=${SURELY_UNSET_SFTP_PASSWORD}",
                "--sftp-transfer.transfers[0].remoteDir=/upload/reports"))
                .isNotNull();

        assertThat(output)
                .contains("APPLICATION FAILED TO START")
                .contains("Reason: sftp-transfer.transfers[0] (bankB-reports): 'host' is required")
                .contains("Reason: sftp-transfer.transfers[0] (bankB-reports): 'password' refers to"
                        + " SURELY_UNSET_SFTP_PASSWORD, which is not set");
    }

    // ------------------------------------------------------------------ pattern / path helpers

    @Test
    void translatesGlobPatternsToRegularExpressions() {
        assertThat(SftpTransferProperties.toFileNameRegex("glob:PAY_*.csv")).isEqualTo("PAY_.*\\.csv");
        assertThat(matches("glob:PAY_*.csv", "PAY_20261126.csv")).isTrue();
        assertThat(matches("glob:PAY_*.csv", "PAY_20261126.csv.tmp")).isFalse();
        assertThat(matches("glob:RPT_??.txt", "RPT_01.txt")).isTrue();
        assertThat(matches("glob:RPT_??.txt", "RPT_1.txt")).isFalse();
        assertThat(matches("glob:a+b(1).txt", "a+b(1).txt")).isTrue();
        // unprefixed and "regex:" patterns are regular expressions
        assertThat(SftpTransferProperties.toFileNameRegex("PAY_.*\\.csv")).isEqualTo("PAY_.*\\.csv");
        assertThat(SftpTransferProperties.toFileNameRegex("regex:PAY_\\d{8}\\.csv")).isEqualTo("PAY_\\d{8}\\.csv");
    }

    @Test
    void remoteDirAlwaysEndsWithExactlyOneSlash() {
        assertThat(TransferRoutesBootstrap.remoteDirWithSlash("/inbound/payments")).isEqualTo("/inbound/payments/");
        assertThat(TransferRoutesBootstrap.remoteDirWithSlash("/inbound/payments//")).isEqualTo("/inbound/payments/");
        assertThat(TransferRoutesBootstrap.remoteDirWithSlash("outbox")).isEqualTo("outbox/");
        assertThat(TransferRoutesBootstrap.remoteDirWithSlash("/")).isEqualTo("/");
    }

    // ------------------------------------------------------------------ helpers

    /** A complete, valid transfer (password auth) whose source folder is named after the transfer. */
    private static String[] transfer(int index, String name) {
        String p = "sftp-transfer.transfers[" + index + "].";
        return new String[] {
                p + "name=" + name,
                p + "sourceDir=" + slashes(tmp.resolve("out").resolve(name)),
                p + "filePattern=.*\\.csv",
                p + "host=sftp.example.com",
                p + "username=user",
                p + "password=not-a-real-password",
                p + "remoteDir=/inbound"};
    }

    private static String startupFailure(AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        StringBuilder messages = new StringBuilder();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private static boolean matches(String filePattern, String fileName) {
        return Pattern.matches(SftpTransferProperties.toFileNameRegex(filePattern), fileName);
    }

    private static String slashes(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
