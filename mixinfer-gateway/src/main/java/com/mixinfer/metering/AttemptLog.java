package com.mixinfer.metering;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects per-attempt diagnostic information during a request.
 *
 * <p>Not persisted as a structured column; the JSON representation goes
 * into {@code UsageRecord.attempts}, while {@link #size()} feeds
 * {@code UsageRecord.attemptCount}. The latter exists so aggregate
 * queries can sum attempts without parsing JSON.
 */
public final class AttemptLog {

    private final List<Map<String, Object>> entries = new ArrayList<>();

    public void recordSuccess(String provider, String endpointId) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("provider", provider);
        entry.put("endpointId", endpointId);
        entry.put("outcome", "success");
        entries.add(entry);
    }

    public void recordFailure(String provider,
                              String endpointId,
                              String failureType,
                              String message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("provider", provider);
        entry.put("endpointId", endpointId);
        entry.put("outcome", "failure");
        entry.put("failureType", failureType);
        if (message != null) {
            entry.put("message", message);
        }
        entries.add(entry);
    }

    public int size() {
        return entries.size();
    }

    public String toJson(ObjectMapper mapper) {
        if (entries.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(entries);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}