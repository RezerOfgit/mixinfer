package com.mixinfer.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.sqlite.SQLiteConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Constructs the SQLite DataSource.
 *
 * <p>Two deliberate choices:
 * <ul>
 *   <li>Single connection ({@code maximumPoolSize = 1}): SQLite serializes
 *       all writes regardless of connection count, so extra connections add
 *       contention without throughput.</li>
 *   <li>WAL journal mode: reduces the probability of a read transaction
 *       blocking a write. With a single pooled connection its benefit is
 *       limited, but it prepares the schema for a future multi-connection
 *       deployment.</li>
 * </ul>
 */
@Configuration
public class SqliteDataSourceConfig {

    @Bean
    public DataSource dataSource(
            @Value("${mixinfer.storage.sqlite.path:logs/mixinfer.db}") String path)
            throws IOException {

        Path dbPath = Paths.get(path).toAbsolutePath();
        if (dbPath.getParent() != null) {
            Files.createDirectories(dbPath.getParent());
        }

        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setBusyTimeout(5000);

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:sqlite:" + dbPath);
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setMaximumPoolSize(1);
        hikari.setDataSourceProperties(sqlite.toProperties());

        return new HikariDataSource(hikari);
    }
}