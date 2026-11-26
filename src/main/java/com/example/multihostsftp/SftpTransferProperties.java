package com.example.multihostsftp;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import static org.springframework.util.StringUtils.hasText;

/**
 * The {@code sftp-transfer} section of application.yml.
 * <p>
 * Global settings sit directly under {@code sftp-transfer}; every entry of {@code transfers} becomes one Camel route.
 * {@code pollDelay}, {@code knownHostsFile} and {@code strictHostKeyChecking} can also be set on a single transfer.
 * <p>
 * The class validates itself: Spring Boot calls {@link #validate} right after binding, so the application refuses to
 * start - listing every problem at once - when the configuration is incomplete or ambiguous. Unknown keys are
 * rejected as well ({@code ignoreUnknownFields = false}), so a typo in an optional key cannot go unnoticed.
 */
@ConfigurationProperties(prefix = "sftp-transfer", ignoreUnknownFields = false)
public class SftpTransferProperties implements Validator {

    /** {@code filePattern} prefix for an ant-style pattern, e.g. {@code glob:PAY_*.csv}. */
    public static final String GLOB_PREFIX = "glob:";

    /** Optional {@code filePattern} prefix for a regular expression (a pattern without prefix is a regex too). */
    public static final String REGEX_PREFIX = "regex:";

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("\\$\\{([^}:]*)");

    /** How often a source folder is polled. Can be overridden per transfer. */
    private Duration pollDelay = Duration.ofSeconds(10);

    /** known_hosts file used to verify the SFTP servers' host keys. Can be overridden per transfer. */
    private String knownHostsFile = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts").toString();

    /** Refuse to talk to a server whose host key is not in knownHostsFile. Can be overridden per transfer. */
    private boolean strictHostKeyChecking = true;

    /** How many times a failed upload is retried before the file is moved to the error folder. */
    private int maxRetries = 3;

    /** Pause between two upload attempts. */
    private Duration retryDelay = Duration.ofSeconds(5);

    /** Timeout for opening the SSH connection to a server. */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** A file is only picked up once its size and timestamp have stopped changing for this long. */
    private Duration readLockCheckInterval = Duration.ofSeconds(1);

    /** A file that keeps changing for this long is skipped and tried again at the next poll. */
    private Duration readLockTimeout = Duration.ofSeconds(30);

    /** Folder for the per-transfer lists of already uploaded files, so that no file is sent twice. */
    private String idempotentDir = "data/idempotent";

    /** The transfers. One Camel route is created for every entry. */
    private List<Transfer> transfers = new ArrayList<>();

    // ------------------------------------------------------------------ effective values (transfer value or global)

    Duration effectivePollDelay(Transfer t) {
        return t.getPollDelay() != null ? t.getPollDelay() : pollDelay;
    }

    String effectiveKnownHostsFile(Transfer t) {
        return hasText(t.getKnownHostsFile()) ? t.getKnownHostsFile() : knownHostsFile;
    }

    boolean effectiveStrictHostKeyChecking(Transfer t) {
        return t.getStrictHostKeyChecking() != null ? t.getStrictHostKeyChecking() : strictHostKeyChecking;
    }

    static Path effectiveDoneDir(Transfer t) {
        return hasText(t.getDoneDir()) ? Path.of(t.getDoneDir()) : Path.of(t.getSourceDir(), "done");
    }

    static Path effectiveErrorDir(Transfer t) {
        return hasText(t.getErrorDir()) ? Path.of(t.getErrorDir()) : Path.of(t.getSourceDir(), "error");
    }

    /**
     * The Java regular expression a file name has to match (completely and case-sensitively) to be picked up:
     * {@code glob:} patterns are translated, {@code regex:} and unprefixed patterns are used as they are.
     */
    static String toFileNameRegex(String filePattern) {
        if (filePattern.startsWith(GLOB_PREFIX)) {
            return globToRegex(filePattern.substring(GLOB_PREFIX.length()));
        }
        if (filePattern.startsWith(REGEX_PREFIX)) {
            return filePattern.substring(REGEX_PREFIX.length());
        }
        return filePattern;
    }

    /** Ant-style file name pattern to regex: {@code *} is any number of characters, {@code ?} exactly one. */
    static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() + 8);
        for (char c : glob.toCharArray()) {
            if (c == '*') {
                if (regex.length() < 2 || !regex.substring(regex.length() - 2).equals(".*")) {
                    regex.append(".*"); // "**" is the same as "*" for a file name
                }
            } else if (c == '?') {
                regex.append('.');
            } else {
                if ("\\.[]{}()+^$|".indexOf(c) >= 0) {
                    regex.append('\\');
                }
                regex.append(c);
            }
        }
        return regex.toString();
    }

    // ------------------------------------------------------------------ validation

    @Override
    public boolean supports(Class<?> type) {
        return SftpTransferProperties.class.isAssignableFrom(type);
    }

    @Override
    public void validate(Object target, Errors errors) {
        for (String problem : ((SftpTransferProperties) target).findProblems()) {
            errors.reject("sftp-transfer.invalid", problem);
        }
    }

    /** Every configuration problem as a readable message; empty when the configuration is valid. Never shows secrets. */
    List<String> findProblems() {
        List<String> problems = new ArrayList<>();
        String root = "sftp-transfer";
        requirePositive(pollDelay, root + ".pollDelay", problems);
        requirePositive(connectTimeout, root + ".connectTimeout", problems);
        requirePositive(readLockCheckInterval, root + ".readLockCheckInterval", problems);
        if (maxRetries < 0) {
            problems.add(root + ".maxRetries must be 0 or more");
        }
        if (retryDelay == null || retryDelay.isNegative()) {
            problems.add(root + ".retryDelay must be 0 or more");
        }
        if (readLockTimeout == null || readLockCheckInterval == null || readLockTimeout.compareTo(readLockCheckInterval) <= 0) {
            problems.add(root + ".readLockTimeout must be longer than readLockCheckInterval");
        }
        if (!hasText(idempotentDir)) {
            problems.add(root + ".idempotentDir is required");
        }
        if (transfers.isEmpty()) {
            problems.add(root + ".transfers is empty - configure at least one transfer");
        }

        Map<String, String> byName = new HashMap<>();
        Map<String, String> bySourceAndPattern = new HashMap<>();
        for (int i = 0; i < transfers.size(); i++) {
            Transfer t = transfers.get(i);
            String at = root + ".transfers[" + i + "]" + (hasText(t.getName()) ? " (" + t.getName() + ")" : "");
            findProblems(t, at, problems);

            if (hasText(t.getName())) {
                String previous = byName.putIfAbsent(t.getName().toLowerCase(Locale.ROOT), at);
                if (previous != null) {
                    problems.add(at + ": name '" + t.getName() + "' is already used by " + previous
                            + " - transfer names must be unique (case-insensitive)");
                }
            }
            Path source = toPath(t.getSourceDir());
            if (source != null && hasText(t.getFilePattern())) {
                String previous = bySourceAndPattern.putIfAbsent(source + "|" + t.getFilePattern(), at);
                if (previous != null) {
                    problems.add(at + ": watches exactly the same files as " + previous
                            + " (same sourceDir and filePattern) - two routes would compete for every file");
                }
            }
        }
        return problems;
    }

    private void findProblems(Transfer t, String at, List<String> problems) {
        require(t.getName(), "name", at, problems);
        require(t.getSourceDir(), "sourceDir", at, problems);
        require(t.getFilePattern(), "filePattern", at, problems);
        require(t.getHost(), "host", at, problems);
        require(t.getUsername(), "username", at, problems);
        require(t.getRemoteDir(), "remoteDir", at, problems);

        if (hasText(t.getName()) && !VALID_NAME.matcher(t.getName()).matches()) {
            problems.add(at + ": 'name' may only contain letters, digits, '.', '_' and '-'"
                    + " (it is used as the route id and as a file name)");
        }
        if (t.getPort() < 1 || t.getPort() > 65535) {
            problems.add(at + ": 'port' must be between 1 and 65535");
        }
        if (hasText(t.getFilePattern())) {
            try {
                Pattern.compile(toFileNameRegex(t.getFilePattern()));
            } catch (PatternSyntaxException e) {
                problems.add(at + ": 'filePattern' is not a valid regular expression (" + e.getDescription()
                        + "); use the prefix 'glob:' for ant-style patterns such as glob:PAY_*.csv");
            }
        }

        // credentials - the messages below never contain their values
        boolean hasPassword = hasText(t.getPassword());
        boolean hasKey = hasText(t.getPrivateKeyPath());
        if (!hasPassword && !hasKey) {
            problems.add(at + ": set 'password' or 'privateKeyPath'");
        }
        if (hasText(t.getPrivateKeyPassphrase()) && !hasKey) {
            problems.add(at + ": 'privateKeyPassphrase' is set but 'privateKeyPath' is not");
        }
        boolean keyPathResolved = requireResolved(t.getPrivateKeyPath(), "privateKeyPath", at, problems);
        requireResolved(t.getPassword(), "password", at, problems);
        requireResolved(t.getPrivateKeyPassphrase(), "privateKeyPassphrase", at, problems);
        if (hasKey && keyPathResolved && !isReadableFile(t.getPrivateKeyPath())) {
            problems.add(at + ": 'privateKeyPath' file not found or not readable: " + t.getPrivateKeyPath());
        }

        // host key verification
        if (effectiveStrictHostKeyChecking(t) && !isReadableFile(effectiveKnownHostsFile(t))) {
            problems.add(at + ": strictHostKeyChecking is on but the known_hosts file is missing: "
                    + effectiveKnownHostsFile(t) + " (add the server's host key to it, see README)");
        }

        // folders
        requirePositive(effectivePollDelay(t), at + ": 'pollDelay'", problems);
        Path source = toPath(t.getSourceDir());
        if (hasText(t.getSourceDir()) && source == null) {
            problems.add(at + ": 'sourceDir' is not a valid path: " + t.getSourceDir());
        }
        if (source != null) {
            if (source.equals(toPath(effectiveDoneDir(t).toString()))) {
                problems.add(at + ": 'doneDir' must not be the same folder as 'sourceDir'");
            }
            if (source.equals(toPath(effectiveErrorDir(t).toString()))) {
                problems.add(at + ": 'errorDir' must not be the same folder as 'sourceDir'");
            }
        }

        // values that are passed to Camel inside RAW(...): the only thing RAW cannot carry is ")&"
        String[][] rawValues = {
                {"username", t.getUsername()}, {"password", t.getPassword()},
                {"privateKeyPassphrase", t.getPrivateKeyPassphrase()}, {"filePattern", t.getFilePattern()}};
        for (String[] raw : rawValues) {
            if (raw[1] != null && raw[1].contains(")&")) {
                problems.add(at + ": '" + raw[0] + "' must not contain the character sequence ')&'");
            }
        }
    }

    private static void require(String value, String key, String at, List<String> problems) {
        if (!hasText(value)) {
            problems.add(at + ": '" + key + "' is required");
        }
    }

    private static void requirePositive(Duration value, String key, List<String> problems) {
        if (value == null || value.isZero() || value.isNegative()) {
            problems.add(key + " must be greater than 0");
        }
    }

    /** Spring leaves a placeholder such as ${BANKB_SFTP_PASSWORD} as-is when the variable is not set. */
    private static boolean requireResolved(String value, String key, String at, List<String> problems) {
        if (value == null || !value.contains("${")) {
            return true;
        }
        Matcher m = UNRESOLVED_PLACEHOLDER.matcher(value);
        String variable = m.find() ? m.group(1) : "?";
        problems.add(at + ": '" + key + "' refers to " + variable
                + ", which is not set - define that environment variable (or system property)");
        return false;
    }

    private static boolean isReadableFile(String file) {
        Path path = toPath(file);
        return path != null && Files.isRegularFile(path) && Files.isReadable(path);
    }

    private static Path toPath(String path) {
        if (!hasText(path)) {
            return null;
        }
        try {
            return Path.of(path).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ getters / setters

    public Duration getPollDelay() {
        return pollDelay;
    }

    public void setPollDelay(Duration pollDelay) {
        this.pollDelay = pollDelay;
    }

    public String getKnownHostsFile() {
        return knownHostsFile;
    }

    public void setKnownHostsFile(String knownHostsFile) {
        this.knownHostsFile = knownHostsFile;
    }

    public boolean isStrictHostKeyChecking() {
        return strictHostKeyChecking;
    }

    public void setStrictHostKeyChecking(boolean strictHostKeyChecking) {
        this.strictHostKeyChecking = strictHostKeyChecking;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadLockCheckInterval() {
        return readLockCheckInterval;
    }

    public void setReadLockCheckInterval(Duration readLockCheckInterval) {
        this.readLockCheckInterval = readLockCheckInterval;
    }

    public Duration getReadLockTimeout() {
        return readLockTimeout;
    }

    public void setReadLockTimeout(Duration readLockTimeout) {
        this.readLockTimeout = readLockTimeout;
    }

    public String getIdempotentDir() {
        return idempotentDir;
    }

    public void setIdempotentDir(String idempotentDir) {
        this.idempotentDir = idempotentDir;
    }

    public List<Transfer> getTransfers() {
        return transfers;
    }

    public void setTransfers(List<Transfer> transfers) {
        this.transfers = transfers;
    }

    /** One local folder + file pattern to one SFTP host + remote folder. */
    public static class Transfer {

        /** Unique name of the transfer; also the Camel route id. */
        private String name;

        /** Local folder to pick files up from. */
        private String sourceDir;

        /**
         * Which files to pick up: a regular expression matched against the whole file name (case-sensitive),
         * or an ant-style pattern with the prefix {@code glob:}, e.g. {@code glob:PAY_*.csv}.
         */
        private String filePattern;

        /** SFTP server host name. */
        private String host;

        /** SFTP server port. */
        private int port = 22;

        /** SFTP user name. */
        private String username;

        /** SFTP password. Reference an environment variable, e.g. {@code ${BANKB_SFTP_PASSWORD}}. */
        private String password;

        /** Private key file (OpenSSH or PEM format) for public key authentication. */
        private String privateKeyPath;

        /** Passphrase of the private key, if it is encrypted. Reference an environment variable. */
        private String privateKeyPassphrase;

        /** Folder on the SFTP server to upload to; a leading '/' means absolute, otherwise relative to the login folder. */
        private String remoteDir;

        /** How often sourceDir is polled. Default: sftp-transfer.pollDelay. */
        private Duration pollDelay;

        /** Where a file goes after a successful upload. Default: sourceDir/done. */
        private String doneDir;

        /** Where a file goes when the upload failed after all retries. Default: sourceDir/error. */
        private String errorDir;

        /** known_hosts file for this server. Default: sftp-transfer.knownHostsFile. */
        private String knownHostsFile;

        /** Verify the server's host key against knownHostsFile. Default: sftp-transfer.strictHostKeyChecking. */
        private Boolean strictHostKeyChecking;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSourceDir() {
            return sourceDir;
        }

        public void setSourceDir(String sourceDir) {
            this.sourceDir = sourceDir;
        }

        public String getFilePattern() {
            return filePattern;
        }

        public void setFilePattern(String filePattern) {
            this.filePattern = filePattern;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        public void setPrivateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }

        public String getPrivateKeyPassphrase() {
            return privateKeyPassphrase;
        }

        public void setPrivateKeyPassphrase(String privateKeyPassphrase) {
            this.privateKeyPassphrase = privateKeyPassphrase;
        }

        public String getRemoteDir() {
            return remoteDir;
        }

        public void setRemoteDir(String remoteDir) {
            this.remoteDir = remoteDir;
        }

        public Duration getPollDelay() {
            return pollDelay;
        }

        public void setPollDelay(Duration pollDelay) {
            this.pollDelay = pollDelay;
        }

        public String getDoneDir() {
            return doneDir;
        }

        public void setDoneDir(String doneDir) {
            this.doneDir = doneDir;
        }

        public String getErrorDir() {
            return errorDir;
        }

        public void setErrorDir(String errorDir) {
            this.errorDir = errorDir;
        }

        public String getKnownHostsFile() {
            return knownHostsFile;
        }

        public void setKnownHostsFile(String knownHostsFile) {
            this.knownHostsFile = knownHostsFile;
        }

        public Boolean getStrictHostKeyChecking() {
            return strictHostKeyChecking;
        }

        public void setStrictHostKeyChecking(Boolean strictHostKeyChecking) {
            this.strictHostKeyChecking = strictHostKeyChecking;
        }

        /** Deliberately without password and passphrase. */
        @Override
        public String toString() {
            return "Transfer[" + name + ": " + sourceDir + " [" + filePattern + "] -> sftp://" + username + "@" + host
                    + ":" + port + " " + remoteDir + "]";
        }
    }
}
