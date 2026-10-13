package com.mixinfer.api;

import com.mixinfer.domain.UsageRecord;
import com.mixinfer.metering.AggregateQuery;
import com.mixinfer.metering.AggregateRow;
import com.mixinfer.metering.UsageQuery;
import com.mixinfer.metering.UsageRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Read-only admin endpoints for usage inspection.
 *
 * <p>Authentication is enforced upstream by {@code AdminKeyFilter}:
 * a valid admin bearer token is required for every request reaching
 * this controller. Client virtual keys cannot reach these endpoints.
 */
@RestController
@RequestMapping("/admin/usage")
public class AdminUsageController {

    private static final int MAX_LIMIT = 1000;

    private static final Set<String> VALID_GROUP_BY = Set.of(
            AggregateQuery.GROUP_BY_MODEL,
            AggregateQuery.GROUP_BY_PROVIDER,
            AggregateQuery.GROUP_BY_ENDPOINT,
            AggregateQuery.GROUP_BY_VIRTUAL_KEY);

    private static final Set<String> VALID_WINDOWS = Set.of(
            AggregateQuery.WINDOW_HOUR,
            AggregateQuery.WINDOW_DAY,
            AggregateQuery.WINDOW_MONTH);

    private final UsageRepository repository;

    public AdminUsageController(UsageRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/records")
    public List<UsageRecord> records(
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String endpointId,
            @RequestParam(required = false) String virtualKeyId,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) String settlementStatus,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        UsageQuery query = UsageQuery.builder()
                .requestId(requestId)
                .from(from)
                .to(to)
                .model(model)
                .provider(provider)
                .endpointId(endpointId)
                .virtualKeyId(virtualKeyId)
                .success(success)
                .settlementStatus(settlementStatus)
                .limit(Math.min(Math.max(limit, 1), MAX_LIMIT))
                .offset(Math.max(offset, 0))
                .build();

        return repository.findRecords(query);
    }

    @GetMapping("/aggregate")
    public List<AggregateRow> aggregate(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam String groupBy,
            @RequestParam(required = false) String window,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String virtualKeyId) {

        if (!VALID_GROUP_BY.contains(groupBy)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid groupBy: " + groupBy);
        }
        if (window != null && !VALID_WINDOWS.contains(window)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid window: " + window);
        }
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'from' must not be after 'to'");
        }

        AggregateQuery query = AggregateQuery.builder()
                .from(from)
                .to(to)
                .groupBy(groupBy)
                .window(window)
                .model(model)
                .provider(provider)
                .virtualKeyId(virtualKeyId)
                .build();

        return repository.aggregate(query);
    }
}