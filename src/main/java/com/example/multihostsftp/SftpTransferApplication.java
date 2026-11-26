package com.example.multihostsftp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Picks up files from local folders and uploads them to SFTP servers.
 * <p>
 * Which files go where is configured in {@code application.yml} ({@code sftp-transfer.transfers}); every entry
 * becomes one Camel route built from the XML route template in {@code classpath:camel/}.
 */
@SpringBootApplication
@EnableConfigurationProperties(SftpTransferProperties.class)
public class SftpTransferApplication {

    public static void main(String[] args) {
        SpringApplication.run(SftpTransferApplication.class, args);
    }
}
