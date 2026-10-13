package com.mixinfer.metering;

import com.mixinfer.domain.UsageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes usage records to a {@link UsageRepository}.
 *
 * <p>Implements the "best-effort" persistence rule from ADR-004: a failed
 * write logs a warning and does not affect the client request. Admission
 * failures (rate limit state) have a different policy and are handled
 * separately by the rate limiter.
 */
@Component
public class RepositoryUsageRecorder implements UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(RepositoryUsageRecorder.class);

    private final UsageRepository repository;

    public RepositoryUsageRecorder(UsageRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(UsageRecord record) {
        try {
            repository.save(record);
        } catch (RuntimeException e) {
            log.warn("Failed to persist usage record requestId={}: {}",
                    record.getRequestId(), e.getMessage());
        }
    }
}