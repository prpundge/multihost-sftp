package com.example.multihostsftp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.multihostsftp.SftpTransferProperties.Transfer;

import static org.springframework.util.StringUtils.hasText;

/**
 * Creates one Camel route per configured transfer from the XML route template {@value #TEMPLATE_ID}
 * ({@code src/main/resources/camel/sftp-transfer-template.xml}).
 * <p>
 * This class only translates the (already validated) configuration into template parameters; what a route does is
 * defined in the XML template. Camel adds templated routes after all XML templates have been loaded, so the order of
 * loading does not matter.
 */
@Component
public class TransferRoutesBootstrap extends RouteBuilder {

    /** Id of the route template in {@code camel/sftp-transfer-template.xml}. */
    public static final String TEMPLATE_ID = "sftp-transfer";

    private static final Logger LOG = LoggerFactory.getLogger(TransferRoutesBootstrap.class);

    private final SftpTransferProperties config;

    public TransferRoutesBootstrap(SftpTransferProperties config) {
        this.config = config;
    }

    @Override
    public void configure() {
        config.getTransfers().forEach(this::addTransferRoute);
        LOG.info("Created {} transfer route(s) from route template '{}'", config.getTransfers().size(), TEMPLATE_ID);
    }

    private void addTransferRoute(Transfer t) {
        boolean useKey = hasText(t.getPrivateKeyPath());
        boolean usePassword = hasText(t.getPassword());
        boolean strict = config.effectiveStrictHostKeyChecking(t);
        String fileNameRegex = SftpTransferProperties.toFileNameRegex(t.getFilePattern());
        Path sourceDir = Path.of(t.getSourceDir());
        Path doneDir = SftpTransferProperties.effectiveDoneDir(t);
        Path errorDir = SftpTransferProperties.effectiveErrorDir(t);

        // Create the local folders up front: transfers sharing a source folder share its done/error folders, and two
        // routes creating the same folder at the same moment can make Camel's move fail. This also reports missing
        // permissions at startup instead of after the first upload.
        createFolders(t, sourceDir, doneDir, errorDir, Path.of(config.getIdempotentDir()));

        templatedRoute(TEMPLATE_ID)
                .routeId(t.getName())
                // where files are picked up
                .parameter("sourceDir", uriPath(sourceDir))
                .parameter("fileNameRegex", fileNameRegex)
                .parameter("pollDelayMs", millis(config.effectivePollDelay(t)))
                .parameter("readLockCheckIntervalMs", millis(config.getReadLockCheckInterval()))
                .parameter("readLockTimeoutMs", millis(config.getReadLockTimeout()))
                .parameter("doneDir", uriPath(doneDir))
                .parameter("errorDir", uriPath(errorDir))
                .parameter("idempotentFile", uriPath(Path.of(config.getIdempotentDir(), t.getName() + "-uploaded.txt")))
                // where they are sent
                .parameter("host", t.getHost())
                .parameter("port", String.valueOf(t.getPort()))
                .parameter("remoteDir", remoteDirWithSlash(t.getRemoteDir()))
                .parameter("username", t.getUsername())
                .parameter("password", usePassword ? t.getPassword() : "")
                .parameter("privateKeyFile", useKey ? uriPath(Path.of(t.getPrivateKeyPath())) : "")
                .parameter("privateKeyPassphrase", useKey && hasText(t.getPrivateKeyPassphrase()) ? t.getPrivateKeyPassphrase() : "")
                .parameter("preferredAuthentications", preferredAuthentications(useKey, usePassword))
                // with strict checking off no known_hosts file is used at all (JSch would otherwise append unknown keys to it)
                .parameter("knownHostsFile", strict ? uriPath(Path.of(config.effectiveKnownHostsFile(t))) : "")
                .parameter("strictHostKeyChecking", strict ? "yes" : "no")
                .parameter("connectTimeoutMs", millis(config.getConnectTimeout()))
                // what happens when an upload fails
                .parameter("maxRetries", String.valueOf(config.getMaxRetries()))
                .parameter("retryDelayMs", millis(config.getRetryDelay()));

        // never log credentials: only which kind of authentication is used
        LOG.info("Transfer '{}': {} [{}] -> sftp://{}@{}:{} {} (auth={}, hostKeyCheck={}, poll every {})",
                t.getName(), t.getSourceDir(), fileNameRegex, t.getUsername(), t.getHost(), t.getPort(), t.getRemoteDir(),
                useKey ? (usePassword ? "privateKey+password" : "privateKey") : "password",
                strict ? "strict" : "OFF", readable(config.effectivePollDelay(t)));
        if (!strict) {
            LOG.warn("Transfer '{}': strict host key checking is OFF - the identity of {} is NOT verified",
                    t.getName(), t.getHost());
        }
    }

    private static void createFolders(Transfer t, Path... folders) {
        for (Path folder : folders) {
            try {
                Files.createDirectories(folder);
            } catch (IOException e) {
                throw new IllegalStateException("Transfer '" + t.getName() + "': cannot create folder " + folder
                        + " (" + e + ")", e);
            }
        }
    }

    private static String preferredAuthentications(boolean useKey, boolean usePassword) {
        if (useKey && usePassword) {
            return "publickey,password,keyboard-interactive";
        }
        return useKey ? "publickey" : "password,keyboard-interactive";
    }

    /**
     * The remote folder with exactly one trailing '/': "/inbound/payments/" (absolute) or "outbox/" (relative to the
     * login folder); the template appends the file name to it.
     */
    static String remoteDirWithSlash(String remoteDir) {
        String dir = remoteDir.trim().replace('\\', '/');
        while (dir.length() > 1 && dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        return dir.endsWith("/") ? dir : dir + "/";
    }

    /** Absolute path with forward slashes, which Camel endpoint URIs handle on every OS. */
    private static String uriPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static String millis(Duration duration) {
        return String.valueOf(duration.toMillis());
    }

    /** "1m", "10s", "200ms" - the way durations are written in application.yml. */
    private static String readable(Duration duration) {
        long ms = duration.toMillis();
        if (ms >= 60_000 && ms % 60_000 == 0) {
            return ms / 60_000 + "m";
        }
        return ms >= 1000 && ms % 1000 == 0 ? ms / 1000 + "s" : ms + "ms";
    }
}
