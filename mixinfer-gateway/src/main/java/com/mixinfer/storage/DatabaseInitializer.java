package com.mixinfer.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Applies versioned SQL migrations at startup.
 *
 * <p>Migration history is tracked in {@code schema_version}. Each entry in
 * {@link #MIGRATIONS} runs exactly once, in order. Migrations are
 * append-only: never edit an existing entry, always add a new one.
 */
@Component
public class DatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final JdbcTemplate jdbc;

    public DatabaseInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version    INTEGER PRIMARY KEY,
                    applied_at INTEGER NOT NULL
                )
                """);

        int current = currentVersion();
        for (int i = current; i < MIGRATIONS.size(); i++) {
            int version = i + 1;
            log.info("Applying migration V{}", version);
            jdbc.execute(MIGRATIONS.get(i));
            jdbc.update(
                    "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)",
                    version, System.currentTimeMillis());
        }
    }

    private int currentVersion() {
        Integer v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM schema_version",
                Integer.class);
        return v == null ? 0 : v;
    }

    private static final List<String> MIGRATIONS = List.of(
            // ---- V1: initial schema ----
            """
            CREATE TABLE usage_records (
                request_id       TEXT PRIMARY KEY,
                virtual_key_id   TEXT,
                model            TEXT NOT NULL,
                provider         TEXT,
                endpoint_id      TEXT,
                upstream_model   TEXT,
                input_tokens     INTEGER,
                output_tokens    INTEGER,
                cost_microcents  INTEGER,
                pricing_version  TEXT,
                settlement_status TEXT NOT NULL,
                usage_source     TEXT,
                reason           TEXT,
                success          INTEGER NOT NULL,
                attempt_count    INTEGER NOT NULL DEFAULT 0,
                attempts         TEXT,
                started_at       INTEGER NOT NULL,
                completed_at     INTEGER NOT NULL
            )
            """,
            // ---- V2: query indexes ----
            """
            CREATE INDEX idx_usage_completed_at
                ON usage_records(completed_at)
            """,
            """
            CREATE INDEX idx_usage_model_completed
                ON usage_records(model, completed_at)
            """,
            """
            CREATE INDEX idx_usage_key_completed
                ON usage_records(virtual_key_id, completed_at)
            """
    );
}