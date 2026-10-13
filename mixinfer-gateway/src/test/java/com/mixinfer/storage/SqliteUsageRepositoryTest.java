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

    @Test
    void should_find_records_by_model_and_status() {
        repo.save(record("req-a", "m1", "openai-compatible", true, 10, 20, 100L));
        repo.save(record("req-b", "m2", "openai-compatible", true, 5, 5, 50L));
        repo.save(record("req-c", "m1", "openai-compatible", false, null, null, null));

        var query = com.mixinfer.metering.UsageQuery.builder()
                .model("m1")
                .limit(10)
                .build();

        var results = repo.findRecords(query);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(r -> "m1".equals(r.getModel()));
    }

    @Test
    void should_aggregate_by_model() {
        repo.save(record("req-1", "m1", "p1", true, 10, 20, 100L));
        repo.save(record("req-2", "m1", "p1", true, 5, 5, 50L));
        repo.save(record("req-3", "m2", "p1", false, null, null, null));

        var query = com.mixinfer.metering.AggregateQuery.builder()
                .from(Instant.parse("2020-01-01T00:00:00Z"))
                .to(Instant.parse("2030-01-01T00:00:00Z"))
                .groupBy(com.mixinfer.metering.AggregateQuery.GROUP_BY_MODEL)
                .build();

        var rows = repo.aggregate(query);

        assertThat(rows).hasSize(2);
        var m1 = rows.stream().filter(r -> "m1".equals(r.getKey())).findFirst().orElseThrow();
        assertThat(m1.getRequestCount()).isEqualTo(2);
        assertThat(m1.getSuccessCount()).isEqualTo(2);
        assertThat(m1.getInputTokens()).isEqualTo(15);
        assertThat(m1.getOutputTokens()).isEqualTo(25);
        assertThat(m1.getCostMicrocents()).isEqualTo(150L);
    }

    @Test
    void should_aggregate_with_day_window() {
        repo.save(recordAt("req-1", "m1",
                Instant.parse("2026-10-13T10:00:00Z")));
        repo.save(recordAt("req-2", "m1",
                Instant.parse("2026-10-13T15:00:00Z")));
        repo.save(recordAt("req-3", "m1",
                Instant.parse("2026-10-14T09:00:00Z")));

        var query = com.mixinfer.metering.AggregateQuery.builder()
                .from(Instant.parse("2026-10-13T00:00:00Z"))
                .to(Instant.parse("2026-10-15T00:00:00Z"))
                .groupBy(com.mixinfer.metering.AggregateQuery.GROUP_BY_MODEL)
                .window(com.mixinfer.metering.AggregateQuery.WINDOW_DAY)
                .build();

        var rows = repo.aggregate(query);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getWindowKey()).isEqualTo("2026-10-13");
        assertThat(rows.get(0).getRequestCount()).isEqualTo(2);
        assertThat(rows.get(1).getWindowKey()).isEqualTo("2026-10-14");
        assertThat(rows.get(1).getRequestCount()).isEqualTo(1);
    }

    private UsageRecord record(String requestId, String model, String provider,
                               boolean success, Integer input, Integer output, Long cost) {
        var usage = (input == null && output == null) ? null
                : com.mixinfer.domain.LlmUsage.builder()
                  .promptTokens(input == null ? 0 : input)
                  .completionTokens(output == null ? 0 : output)
                  .totalTokens((input == null ? 0 : input) + (output == null ? 0 : output))
                  .build();
        return UsageRecord.builder()
                .requestId(requestId)
                .model(model)
                .provider(provider)
                .providerReported(usage)
                .costMicrocents(cost)
                .startedAt(Instant.parse("2026-10-13T00:00:00Z"))
                .completedAt(Instant.parse("2026-10-13T00:00:01Z"))
                .settlementStatus(UsageSettlementStatus.FINAL)
                .usageSource(UsageSource.PROVIDER)
                .success(success)
                .attemptCount(1)
                .build();
    }

    private UsageRecord recordAt(String requestId, String model, Instant completedAt) {
        return UsageRecord.builder()
                .requestId(requestId)
                .model(model)
                .provider("p1")
                .startedAt(completedAt)
                .completedAt(completedAt)
                .settlementStatus(UsageSettlementStatus.FINAL)
                .usageSource(UsageSource.PROVIDER)
                .success(true)
                .attemptCount(1)
                .build();
    }

    @Test
    void should_sum_cost_in_aggregate() {
        repo.save(costRecord("r1", 10, 20, 100L));
        repo.save(costRecord("r2", 5, 5, 50L));
        repo.save(costRecord("r3", 1, 1, 10L));

        var query = com.mixinfer.metering.AggregateQuery.builder()
                .from(Instant.parse("2020-01-01T00:00:00Z"))
                .to(Instant.parse("2030-01-01T00:00:00Z"))
                .groupBy(com.mixinfer.metering.AggregateQuery.GROUP_BY_MODEL)
                .build();

        var rows = repo.aggregate(query);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getCostMicrocents()).isEqualTo(160L);
    }

    @Test
    void should_exclude_null_cost_from_sum() {
        repo.save(costRecord("r1", 10, 20, 100L));
        repo.save(noCostRecord("r2"));

        var query = com.mixinfer.metering.AggregateQuery.builder()
                .from(Instant.parse("2020-01-01T00:00:00Z"))
                .to(Instant.parse("2030-01-01T00:00:00Z"))
                .groupBy(com.mixinfer.metering.AggregateQuery.GROUP_BY_MODEL)
                .build();

        var rows = repo.aggregate(query);

        // COALESCE(cost, 0) -> null contributes 0 to sum.
        assertThat(rows.get(0).getCostMicrocents()).isEqualTo(100L);
        // But request count includes both records.
        assertThat(rows.get(0).getRequestCount()).isEqualTo(2);
    }

    private UsageRecord costRecord(String id, int in, int out, long cost) {
        return UsageRecord.builder()
                .requestId(id)
                .model("gpt-4o-mini")
                .provider("p")
                .providerReported(com.mixinfer.domain.LlmUsage.builder()
                        .promptTokens(in).completionTokens(out).totalTokens(in + out).build())
                .costMicrocents(cost)
                .startedAt(Instant.parse("2026-10-13T00:00:00Z"))
                .completedAt(Instant.parse("2026-10-13T00:00:01Z"))
                .settlementStatus(UsageSettlementStatus.FINAL)
                .usageSource(UsageSource.PROVIDER)
                .success(true)
                .attemptCount(1)
                .build();
    }

    private UsageRecord noCostRecord(String id) {
        return UsageRecord.builder()
                .requestId(id)
                .model("gpt-4o-mini")
                .provider("p")
                .startedAt(Instant.parse("2026-10-13T00:00:00Z"))
                .completedAt(Instant.parse("2026-10-13T00:00:01Z"))
                .settlementStatus(UsageSettlementStatus.UNKNOWN)
                .usageSource(UsageSource.NONE)
                .success(false)
                .attemptCount(1)
                .build();
    }

    @Test
    void should_count_unpriced_requests_in_aggregate() {
        repo.save(costRecord("r1", 10, 20, 100L));
        repo.save(costRecord("r2", 5, 5, 50L));
        repo.save(noCostRecord("r3"));
        repo.save(noCostRecord("r4"));

        var query = com.mixinfer.metering.AggregateQuery.builder()
                .from(Instant.parse("2020-01-01T00:00:00Z"))
                .to(Instant.parse("2030-01-01T00:00:00Z"))
                .groupBy(com.mixinfer.metering.AggregateQuery.GROUP_BY_MODEL)
                .build();

        var rows = repo.aggregate(query);

        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.getRequestCount()).isEqualTo(4);
        assertThat(row.getCostMicrocents()).isEqualTo(150L);   // only priced records
        assertThat(row.getUnpricedCount()).isEqualTo(2);
    }
}