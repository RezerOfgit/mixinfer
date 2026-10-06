package com.mixinfer.metering;

import com.mixinfer.domain.UsageRecord;

/**
 * Records settlement information for a completed request.
 *
 * <p>Implementations must be safe to call from request threads and must
 * not throw for ordinary IO failures — a settlement failure should never
 * break a user request.
 */
public interface UsageRecorder {

    void record(UsageRecord record);
}