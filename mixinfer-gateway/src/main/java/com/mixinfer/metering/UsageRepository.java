package com.mixinfer.metering;

import com.mixinfer.domain.UsageRecord;

import java.util.Optional;

/**
 * Persistence for usage records.
 *
 * <p>Implementations must not fail the client request on write errors;
 * settlement is best-effort and its loss is logged, not surfaced.
 */
public interface UsageRepository {

    /** Persists a record. Overwrites an existing record with the same requestId. */
    void save(UsageRecord record);

    /** Returns the record for a given requestId, if present. */
    Optional<UsageRecord> findByRequestId(String requestId);

    /** Total number of records. Intended for tests and diagnostics. */
    long count();
}