package com.mixinfer.storage;

import com.mixinfer.domain.LlmUsage;
import com.mixinfer.domain.UsageRecord;
import com.mixinfer.domain.UsageSettlementStatus;
import com.mixinfer.domain.UsageSource;
import com.mixinfer.metering.AggregateQuery;
import com.mixinfer.metering.AggregateRow;
import com.mixinfer.metering.UsageQuery;
import com.mixinfer.metering.UsageRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
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

    @Override
    public List<UsageRecord> findRecords(UsageQuery q) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM usage_records WHERE 1=1");
        List<Object> args = new ArrayList<>();

        if (q.getRequestId() != null) {
            sql.append(" AND request_id = ?");
            args.add(q.getRequestId());
        }
        if (q.getFrom() != null) {
            sql.append(" AND completed_at >= ?");
            args.add(q.getFrom().toEpochMilli());
        }
        if (q.getTo() != null) {
            sql.append(" AND completed_at < ?");
            args.add(q.getTo().toEpochMilli());
        }
        if (q.getModel() != null) {
            sql.append(" AND model = ?");
            args.add(q.getModel());
        }
        if (q.getProvider() != null) {
            sql.append(" AND provider = ?");
            args.add(q.getProvider());
        }
        if (q.getEndpointId() != null) {
            sql.append(" AND endpoint_id = ?");
            args.add(q.getEndpointId());
        }
        if (q.getVirtualKeyId() != null) {
            sql.append(" AND virtual_key_id = ?");
            args.add(q.getVirtualKeyId());
        }
        if (q.getSuccess() != null) {
            sql.append(" AND success = ?");
            args.add(q.getSuccess() ? 1 : 0);
        }
        if (q.getSettlementStatus() != null) {
            sql.append(" AND settlement_status = ?");
            args.add(q.getSettlementStatus());
        }

        sql.append(" ORDER BY completed_at DESC LIMIT ? OFFSET ?");
        args.add(q.getLimit());
        args.add(q.getOffset());

        return jdbc.query(sql.toString(), (rs, i) -> mapRow(rs), args.toArray());
    }

    @Override
    public List<AggregateRow> aggregate(AggregateQuery q) {
        String groupExpr = switch (q.getGroupBy()) {
            case AggregateQuery.GROUP_BY_MODEL -> "model";
            case AggregateQuery.GROUP_BY_PROVIDER -> "provider";
            case AggregateQuery.GROUP_BY_ENDPOINT -> "endpoint_id";
            case AggregateQuery.GROUP_BY_VIRTUAL_KEY -> "virtual_key_id";
            default -> throw new IllegalArgumentException(
                    "Invalid groupBy: " + q.getGroupBy());
        };

        String windowExpr = null;
        if (q.getWindow() != null) {
            windowExpr = switch (q.getWindow()) {
                case AggregateQuery.WINDOW_HOUR ->
                        "strftime('%Y-%m-%dT%H:00', completed_at/1000, 'unixepoch')";
                case AggregateQuery.WINDOW_DAY ->
                        "strftime('%Y-%m-%d', completed_at/1000, 'unixepoch')";
                case AggregateQuery.WINDOW_MONTH ->
                        "strftime('%Y-%m', completed_at/1000, 'unixepoch')";
                default -> throw new IllegalArgumentException(
                        "Invalid window: " + q.getWindow());
            };
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(groupExpr).append(" AS grp");
        sql.append(", ").append(windowExpr == null ? "NULL" : windowExpr)
                .append(" AS window_key");
        sql.append(", COUNT(*) AS request_count");
        sql.append(", SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS success_count");
        sql.append(", COALESCE(SUM(attempt_count), 0) AS attempt_count");
        sql.append(", COALESCE(SUM(COALESCE(input_tokens, 0)), 0) AS input_tokens");
        sql.append(", COALESCE(SUM(COALESCE(output_tokens, 0)), 0) AS output_tokens");
        sql.append(", COALESCE(SUM(COALESCE(cost_microcents, 0)), 0) AS cost_microcents");
        sql.append(" FROM usage_records WHERE 1=1");

        List<Object> args = new ArrayList<>();
        if (q.getFrom() != null) {
            sql.append(" AND completed_at >= ?");
            args.add(q.getFrom().toEpochMilli());
        }
        if (q.getTo() != null) {
            sql.append(" AND completed_at < ?");
            args.add(q.getTo().toEpochMilli());
        }
        if (q.getModel() != null) {
            sql.append(" AND model = ?");
            args.add(q.getModel());
        }
        if (q.getProvider() != null) {
            sql.append(" AND provider = ?");
            args.add(q.getProvider());
        }
        if (q.getVirtualKeyId() != null) {
            sql.append(" AND virtual_key_id = ?");
            args.add(q.getVirtualKeyId());
        }

        sql.append(" GROUP BY grp");
        if (windowExpr != null) {
            sql.append(", window_key");
        }
        sql.append(" ORDER BY ");
        if (windowExpr != null) {
            sql.append("window_key, grp");
        } else {
            sql.append("grp");
        }

        return jdbc.query(sql.toString(),
                (rs, i) -> AggregateRow.builder()
                        .key(rs.getString("grp"))
                        .windowKey(rs.getString("window_key"))
                        .requestCount(rs.getLong("request_count"))
                        .successCount(rs.getLong("success_count"))
                        .attemptCount(rs.getLong("attempt_count"))
                        .inputTokens(rs.getLong("input_tokens"))
                        .outputTokens(rs.getLong("output_tokens"))
                        .costMicrocents(rs.getLong("cost_microcents"))
                        .build(),
                args.toArray());
    }
}