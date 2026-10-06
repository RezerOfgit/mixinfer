package com.mixinfer.metering;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mixinfer.domain.LlmUsage;
import com.mixinfer.domain.UsageRecord;
import com.mixinfer.domain.UsageSettlementStatus;
import com.mixinfer.domain.UsageSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileUsageRecorderTest {

    @Test
    void should_append_json_lines(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("usage.log");
        ObjectMapper mapper = new ObjectMapper();
        // Serialize Instant as ISO-8601.
        mapper.findAndRegisterModules();

        FileUsageRecorder recorder = new FileUsageRecorder(mapper, logFile.toString());

        recorder.record(UsageRecord.builder()
                .requestId("req-1")
                .model("gpt-4o-mini")
                .provider("openai-compatible")
                .startedAt(Instant.parse("2026-10-06T00:00:00Z"))
                .completedAt(Instant.parse("2026-10-06T00:00:01Z"))
                .providerReported(LlmUsage.builder()
                        .promptTokens(5).completionTokens(3).totalTokens(8).build())
                .settlementStatus(UsageSettlementStatus.FINAL)
                .usageSource(UsageSource.PROVIDER)
                .build());

        recorder.record(UsageRecord.builder()
                .requestId("req-2")
                .model("deepseek-flash")
                .provider("openai-compatible")
                .startedAt(Instant.parse("2026-10-06T00:01:00Z"))
                .completedAt(Instant.parse("2026-10-06T00:01:02Z"))
                .settlementStatus(UsageSettlementStatus.UNKNOWN)
                .usageSource(UsageSource.NONE)
                .reason("client_disconnected")
                .build());

        recorder.shutdown();

        List<String> lines = Files.readAllLines(logFile);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("\"requestId\":\"req-1\"");
        assertThat(lines.get(1)).contains("\"settlementStatus\":\"UNKNOWN\"");
        assertThat(lines.get(1)).contains("\"reason\":\"client_disconnected\"");
    }
}