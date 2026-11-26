package com.example.multihostsftp.support;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.file.root.RootedFileSystem;
import org.apache.sshd.common.file.root.RootedFileSystemProvider;
import org.apache.sshd.common.file.root.RootedPath;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

/**
 * A real SFTP server (Apache MINA SSHD) on 127.0.0.1, serving a local folder as its file system root.
 * Each instance has its own freshly generated host key and accepts exactly one user.
 */
public final class EmbeddedSftpServer implements AutoCloseable {

    public static final String HOST = "127.0.0.1";

    private final SshServer sshd;
    private final KeyPair hostKey;
    private final Path root;
    private final String loginDir;
    private final List<String> fileEvents = new CopyOnWriteArrayList<>();

    private EmbeddedSftpServer(Path root, int port, String user, PasswordAuthenticator passwords,
            PublickeyAuthenticator keys) throws IOException {
        this.root = Files.createDirectories(root);
        // like on a real server, a session does not start in "/" but in the user's home folder
        this.loginDir = "/home/" + user;
        Files.createDirectories(path(loginDir));
        this.hostKey = TestKeys.ecKeyPair();
        this.sshd = SshServer.setUpDefaultServer();
        sshd.setHost(HOST);
        sshd.setPort(port);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        sshd.setPasswordAuthenticator(passwords);
        sshd.setPublickeyAuthenticator(keys);
        sshd.setKeyboardInteractiveAuthenticator(null);
        SftpSubsystemFactory sftp = new SftpSubsystemFactory();
        sftp.addSftpEventListener(new FileEventRecorder());
        sshd.setSubsystemFactories(List.of(sftp));
        sshd.setFileSystemFactory(new RootedFileSystemFactory());
        sshd.start();
    }

    /** Serves {@link #root} as "/" (nothing outside it is reachable) and starts every session in {@link #loginDir}. */
    private final class RootedFileSystemFactory implements FileSystemFactory {

        @Override
        public Path getUserHomeDir(SessionContext session) {
            return root;
        }

        @Override
        public FileSystem createFileSystem(SessionContext session) {
            return new RootedFileSystem(new RootedFileSystemProvider(), root, Collections.emptyMap()) {
                @Override
                public RootedPath getDefaultDir() {
                    return getPath(loginDir);
                }
            };
        }
    }

    /** Writes and renames as the server saw them, e.g. "write PAY_1.csv.part", "rename PAY_1.csv.part -> PAY_1.csv". */
    public List<String> fileEvents() {
        return fileEvents.stream().distinct().toList();
    }

    private final class FileEventRecorder implements SftpEventListener {

        @Override
        public void written(ServerSession session, String remoteHandle, FileHandle localHandle, long offset,
                byte[] data, int dataOffset, int dataLen, Throwable thrown) {
            fileEvents.add("write " + localHandle.getFile().getFileName());
        }

        @Override
        public void moved(ServerSession session, Path srcPath, Path dstPath, Collection<CopyOption> opts,
                Throwable thrown) {
            if (thrown == null) {
                fileEvents.add("rename " + srcPath.getFileName() + " -> " + dstPath.getFileName());
            }
        }
    }

    /** Accepts only {@code user} with {@code password}. Port 0 picks a free port. */
    public static EmbeddedSftpServer withPassword(Path root, int port, String user, String password) throws IOException {
        return new EmbeddedSftpServer(root, port, user, (u, p, session) -> user.equals(u) && password.equals(p), null);
    }

    /** Accepts only {@code user} signing in with the private key belonging to {@code publicKey}. Port 0 picks a free port. */
    public static EmbeddedSftpServer withPublicKey(Path root, int port, String user, PublicKey publicKey) throws IOException {
        return new EmbeddedSftpServer(root, port, user, null,
                (u, key, session) -> user.equals(u) && KeyUtils.compareKeys(publicKey, key));
    }

    /** The folder a session starts in, as the SFTP client sees it, e.g. {@code /home/userA}. */
    public String loginDir() {
        return loginDir;
    }

    public int port() {
        return sshd.getPort();
    }

    public Path root() {
        return root;
    }

    /** Local location of a path as the SFTP client sees it, e.g. {@code /inbound/payments/PAY_1.csv}. */
    public Path path(String remotePath) {
        return root.resolve(remotePath.replaceFirst("^/+", ""));
    }

    public PublicKey hostKey() {
        return hostKey.getPublic();
    }

    /** known_hosts line that trusts this server. */
    public String knownHostsLine() {
        return knownHostsLine(hostKey.getPublic());
    }

    /** known_hosts line that claims this server's address has the given key. */
    public String knownHostsLine(PublicKey key) {
        return "[" + HOST + "]:" + port() + " " + PublicKeyEntry.toString(key);
    }

    @Override
    public void close() throws IOException {
        sshd.stop(true);
    }
}
