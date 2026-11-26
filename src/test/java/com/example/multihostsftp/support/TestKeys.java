package com.example.multihostsftp.support;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;

/** Key material for tests: nothing is checked in, everything is generated per run. */
public final class TestKeys {

    private TestKeys() {
    }

    /** ECDSA P-256 key pair (used as SFTP host key). */
    public static KeyPair ecKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** RSA 2048 key pair (used as client key, like an id_rsa). */
    public static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes the private key the way {@code ssh-keygen} does ("-----BEGIN OPENSSH PRIVATE KEY-----"),
     * encrypted with AES-256-CTR (bcrypt KDF) when a passphrase is given.
     */
    public static Path writeOpenSshPrivateKey(KeyPair keyPair, String passphrase, Path file)
            throws IOException, GeneralSecurityException {
        OpenSSHKeyEncryptionContext encryption = null;
        if (passphrase != null) {
            encryption = new OpenSSHKeyEncryptionContext();
            encryption.setPassword(passphrase);
            encryption.setCipherName("AES");
            encryption.setCipherType("256");
            encryption.setCipherMode("CTR");
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, "multihost-sftp test key", encryption, out);
        }
        return file;
    }
}
