package com.example.partnerfilereader.service;

import com.example.partnerfilereader.config.SftpProperties;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@Slf4j
@Service
@RequiredArgsConstructor
public class SftpDownloadService {

    private final SftpProperties properties;

    public Path downloadConfiguredFile() {
        validateConfig();

        Session session = null;
        Channel channel = null;

        try {
            Path localDir = Path.of(properties.getLocalDownloadDir());
            Files.createDirectories(localDir);

            Path localFile = localDir.resolve(extractFileName(properties.getRemoteFilePath()));
            log.info("Downloading partner file from SFTP {}:{}{} to {}",
                    properties.getHost(),
                    properties.getPort(),
                    properties.getRemoteFilePath(),
                    localFile);

            JSch jSch = new JSch();
            session = jSch.getSession(
                    properties.getUsername(),
                    properties.getHost(),
                    properties.getPort()
            );
            session.setPassword(properties.getPassword());

            Properties sessionConfig = new Properties();
            sessionConfig.put("StrictHostKeyChecking", "no");
            sessionConfig.put("PreferredAuthentications", "password");
            session.setConfig(sessionConfig);
            session.connect(10_000);

            channel = session.openChannel("sftp");
            channel.connect(10_000);

            ChannelSftp sftp = (ChannelSftp) channel;
            sftp.get(properties.getRemoteFilePath(), localFile.toString());

            log.info("Downloaded partner file from SFTP successfully: {}", localFile);
            return localFile;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to download partner file from SFTP", e);
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    private void validateConfig() {
        requireText(properties.getHost(), "sftp.host");
        requireText(properties.getUsername(), "sftp.username");
        requireText(properties.getPassword(), "sftp.password");
        requireText(properties.getRemoteFilePath(), "sftp.remote-file-path");
        requireText(properties.getLocalDownloadDir(), "sftp.local-download-dir");

        if (properties.getPort() <= 0) {
            throw new IllegalArgumentException("sftp.port must be greater than 0");
        }
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing " + fieldName);
        }
    }

    private String extractFileName(String remoteFilePath) {
        String normalizedPath = remoteFilePath.replace('\\', '/');
        int lastSlash = normalizedPath.lastIndexOf('/');
        if (lastSlash < 0) {
            return normalizedPath;
        }

        return normalizedPath.substring(lastSlash + 1);
    }
}
