package com.mixinfer.storage;

import com.mixinfer.domain.LlmUsage;
import com.mixinfer.domain.UsageRecord;
import com.mixinfer.domain.UsageSettlementStatus;
import com.mixinfer.domain.UsageSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteUsageRepositoryTest {

    @TempDir
    Path tempDir;

    DataSource ds;
    SqliteUsageRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        String dbPath = tempDir.resolve("test.db").toString();
        ds = new SqliteDataSourceConfig().dataSource(dbPath);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new DatabaseInitializer(jdbc).initialize();
        repo = new SqliteUsageRepository(jdbc);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Close the pool so SQLite releases the file; @TempDir can then delete it.
        if (ds instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void should_save_and_find_by_request_id() {
        UsageRecord record = UsageRecord.builder()
                .requestId("req-1")
                .virtualKeyId("vk_team_a")
                .model("deepseek-flash")
                .provider("openai-compatible")
                .endpointId("deepseek-official")
                .upstreamModel("deepseek-chat")
                .startedAt(Instant.parse("2026-10-13T00:00:00Z"))
                .completedAt(Instant.parse("2026-10-13T00:00:01Z"))
                .providerReported(LlmUsage.builder()
                        .promptTokens(10).completionTokens(20).totalTokens(30).build())
                .costMicrocents(420L)
                .pricingVersion("2026-10-13-1")
                .settlementStatus(UsageSettlementStatus.FINAL)
                .usageSource(UsageSource.PROVIDER)
                .success(true)
                .attemptCount(1)
                .attempts("[{\"provider\":\"openai-compatible\",\"ok\":true}]")
                .build();

        repo.save(record);

        Optional<UsageRecord> loaded = repo.findByRequestId("req-1");
        assertThat(loaded).isPresent();

        UsageRecord r = loaded.get();
        assertThat(r.getRequestId()).isEqualTo("req-1");
        assertThat(r.getVirtualKeyId()).isEqualTo("vk_team_a");
        assertThat(r.getModel()).isEqualTo("deepseek-flash");
        assertThat(r.getProvider()).isEqualTo("openai-compatible");
        assertThat(r.getEndpointId()).isEqualTo("deepseek-official");
        assertThat(r.getUpstreamModel()).isEqualTo("deepseek-chat");
        assertThat(r.getProviderReported().getPromptTokens()).isEqualTo(10);
        assertThat(r.getProviderReported().getCompletionTokens()).isEqualTo(20);
        assertThat(r.getProviderReported().getTotalTokens()).isEqualTo(30);
        assertThat(r.getCostMicrocents()).isEqualTo(420L);
        assertThat(r.getPricingVersion()).isEqualTo("2026-10-13-1");
        assertThat(r.getSettlementStatus()).isEqualTo(UsageSettlementStatus.FINAL);
        assertThat(r.getUsageSource()).isEqualTo(UsageSource.PROVIDER);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getAttemptCount()).isEqualTo(1);
        assertThat(r.getStartedAt()).isEqualTo(Instant.parse("2026-10-13T00:00:00Z"));
        assertThat(r.getCompletedAt()).isEqualTo(Instant.parse("2026-10-13T00:00:01Z"));
    }

    @Test
    void should_return_empty_for_unknown_request_id() {
        assertThat(repo.findByRequestId("nope")).isEmpty();
    }

    @Test
    void should_overwrite_on_duplicate_request_id() {
        UsageRecord first = minimal("req-1", "v1");
        UsageRecord second = minimal("req-1", "v2");

        repo.save(first);
        repo.save(second);

        UsageRecord loaded = repo.findByRequestId("req-1").orElseThrow();
        assertThat(loaded.getModel()).isEqualTo("v2");
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void should_persist_null_usage_and_null_cost() {
        UsageRecord record = UsageRecord.builder()
                .requestId("req-null")
                .model("deepseek-flash")
                .startedAt(Instant.parse("2026-10-13T00:00:00Z"))
                .completedAt(Instant.parse("2026-10-13T00:00:01Z"))
                .settlementStatus(UsageSettlementStatus.UNKNOWN)
                .usageSource(UsageSource.NONE)
                .reason("client_disconnected")
                .success(false)
                .attemptCount(1)
                .build();

        repo.save(record);

        UsageRecord r = repo.findByRequestId("req-null").orElseThrow();
        assertThat(r.getProviderReported()).isNull();
        assertThat(r.getCostMicrocents()).isNull();
        assertThat(r.getReason()).isEqualTo("client_disconnected");
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getUsageSource()).isEqualTo(UsageSource.NONE);
    }

    private UsageRecord minimal(String requestId, String model) {
        return UsageRecord.builder()
                .requestId(requestId)
                .model(model)
                .startedAt(Instant.parse("2026-10-13T00:00:00Z"))
                .completedAt(Instant.parse("2026-10-13T00:00:01Z"))
                .settlementStatus(UsageSettlementStatus.FINAL)
                .usageSource(UsageSource.PROVIDER)
                .success(true)
                .build();
    }
}