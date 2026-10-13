package com.mixinfer.storage;

import com.mixinfer.domain.LlmUsage;
import com.mixinfer.domain.UsageRecord;
import com.mixinfer.domain.UsageSettlementStatus;
import com.mixinfer.domain.UsageSource;
import com.mixinfer.metering.UsageRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SQLite-backed {@link UsageRepository}.
 *
 * <p>All columns are primitive or TEXT; enums are stored by name, timestamps
 * as UTC epoch milliseconds. The {@code attempts} column holds an opaque JSON
 * string produced by the caller.
 */
@Repository
public class SqliteUsageRepository implements UsageRepository {

    private final JdbcTemplate jdbc;

    public SqliteUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(UsageRecord r) {
        jdbc.update("""
                INSERT INTO usage_records (
                    request_id, virtual_key_id, model, provider, endpoint_id,
                    upstream_model, input_tokens, output_tokens, cost_microcents,
                    pricing_version, settlement_status, usage_source, reason,
                    success, attempt_count, attempts, started_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(request_id) DO UPDATE SET
                    virtual_key_id  = excluded.virtual_key_id,
                    model           = excluded.model,
                    provider        = excluded.provider,
                    endpoint_id     = excluded.endpoint_id,
                    upstream_model  = excluded.upstream_model,
                    input_tokens    = excluded.input_tokens,
                    output_tokens   = excluded.output_tokens,
                    cost_microcents = excluded.cost_microcents,
                    pricing_version = excluded.pricing_version,
                    settlement_status = excluded.settlement_status,
                    usage_source    = excluded.usage_source,
                    reason          = excluded.reason,
                    success         = excluded.success,
                    attempt_count   = excluded.attempt_count,
                    attempts        = excluded.attempts,
                    started_at      = excluded.started_at,
                    completed_at    = excluded.completed_at
                """,
                r.getRequestId(),
                r.getVirtualKeyId(),
                r.getModel(),
                r.getProvider(),
                r.getEndpointId(),
                r.getUpstreamModel(),
                r.getProviderReported() == null ? null : r.getProviderReported().getPromptTokens(),
                r.getProviderReported() == null ? null : r.getProviderReported().getCompletionTokens(),
                r.getCostMicrocents(),
                r.getPricingVersion(),
                r.getSettlementStatus() == null ? null : r.getSettlementStatus().name(),
                r.getUsageSource() == null ? null : r.getUsageSource().name(),
                r.getReason(),
                r.isSuccess() ? 1 : 0,
                r.getAttemptCount(),
                r.getAttempts(),
                r.getStartedAt() == null ? null : r.getStartedAt().toEpochMilli(),
                r.getCompletedAt() == null ? null : r.getCompletedAt().toEpochMilli());
    }

    @Override
    public Optional<UsageRecord> findByRequestId(String requestId) {
        List<UsageRecord> rows = jdbc.query(
                "SELECT * FROM usage_records WHERE request_id = ?",
                (rs, rowNum) -> mapRow(rs),
                requestId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM usage_records", Long.class);
        return n == null ? 0L : n;
    }

    private UsageRecord mapRow(ResultSet rs) throws SQLException {
        Integer inputTokens = rs.getObject("input_tokens") == null
                ? null : rs.getInt("input_tokens");
        Integer outputTokens = rs.getObject("output_tokens") == null
                ? null : rs.getInt("output_tokens");

        LlmUsage usage = null;
        if (inputTokens != null || outputTokens != null) {
            int in = inputTokens == null ? 0 : inputTokens;
            int out = outputTokens == null ? 0 : outputTokens;
            usage = LlmUsage.builder()
                    .promptTokens(in)
                    .completionTokens(out)
                    .totalTokens(in + out)
                    .build();
        }

        Long costMicrocents = rs.getObject("cost_microcents") == null
                ? null : rs.getLong("cost_microcents");
        Long startedAtMs = rs.getObject("started_at") == null
                ? null : rs.getLong("started_at");
        Long completedAtMs = rs.getObject("completed_at") == null
                ? null : rs.getLong("completed_at");

        String settlementStatus = rs.getString("settlement_status");
        String usageSource = rs.getString("usage_source");

        return UsageRecord.builder()
                .requestId(rs.getString("request_id"))
                .virtualKeyId(rs.getString("virtual_key_id"))
                .model(rs.getString("model"))
                .provider(rs.getString("provider"))
                .endpointId(rs.getString("endpoint_id"))
                .upstreamModel(rs.getString("upstream_model"))
                .providerReported(usage)
                .costMicrocents(costMicrocents)
                .pricingVersion(rs.getString("pricing_version"))
                .settlementStatus(settlementStatus == null ? null
                        : UsageSettlementStatus.valueOf(settlementStatus))
                .usageSource(usageSource == null ? null
                        : UsageSource.valueOf(usageSource))
                .reason(rs.getString("reason"))
                .success(rs.getInt("success") == 1)
                .attemptCount(rs.getInt("attempt_count"))
                .attempts(rs.getString("attempts"))
                .startedAt(startedAtMs == null ? null : Instant.ofEpochMilli(startedAtMs))
                .completedAt(completedAtMs == null ? null : Instant.ofEpochMilli(completedAtMs))
                .build();
    }
}