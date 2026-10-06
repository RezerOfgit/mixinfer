package com.mixinfer.metering;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mixinfer.domain.UsageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Appends usage records to a JSON Lines file.
 *
 * <p>Each line is one JSON object. Failures are logged but never propagate:
 * settlement must not break the request path.
 */
@Component
public class FileUsageRecorder implements UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(FileUsageRecorder.class);

    private final ObjectMapper objectMapper;
    private final BufferedWriter writer;

    public FileUsageRecorder(ObjectMapper objectMapper,
                             @Value("${mixinfer.usage.file:logs/usage.log}") String filePath) {
        this.objectMapper = objectMapper;
        try {
            Path path = Paths.get(filePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open usage log file: " + filePath, e);
        }
    }

    @Override
    public synchronized void record(UsageRecord record) {
        try {
            String line = objectMapper.writeValueAsString(record);
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.warn("Failed to write usage record: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        try {
            writer.close();
        } catch (IOException ignored) {
        }
    }
}