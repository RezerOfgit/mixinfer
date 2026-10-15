package com.mixinfer.metering;

import com.mixinfer.domain.LlmUsage;
import com.mixinfer.router.Pricing;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CostCalculatorTest {

    private final CostCalculator calculator = new CostCalculator();

    @Test
    void should_compute_cost_from_usage_and_pricing() {
        // 1,000,000 input tokens at $0.14/1M  -> 140,000 microcents
        //   500,000 output tokens at $0.28/1M -> 140,000 microcents
        // Total: 280,000 microcents = $0.0028
        LlmUsage usage = LlmUsage.builder()
                .promptTokens(1_000_000)
                .completionTokens(500_000)
                .totalTokens(1_500_000)
                .build();
        Pricing pricing = new Pricing(140_000L, 280_000L, "v1");

        CostResult result = calculator.calculate(usage, pricing);

        assertThat(result).isNotNull();
        assertThat(result.microcents()).isEqualTo(280_000L);
        assertThat(result.pricingVersion()).isEqualTo("v1");
    }

    @Test
    void should_return_null_when_usage_is_missing() {
        Pricing pricing = new Pricing(140_000L, 280_000L, "v1");
        assertThat(calculator.calculate(null, pricing)).isNull();
    }

    @Test
    void should_return_null_when_pricing_is_missing() {
        LlmUsage usage = LlmUsage.builder()
                .promptTokens(100).completionTokens(50).totalTokens(150).build();
        assertThat(calculator.calculate(usage, null)).isNull();
    }

    @Test
    void should_round_to_nearest_microcent() {
        // 1 token at 1,000,000 microcents/1M -> 1 microcent exactly
        // 1 token at 1,500,000 microcents/1M -> 1.5 microcents -> rounds to 2
        LlmUsage usage = LlmUsage.builder()
                .promptTokens(1).completionTokens(1).totalTokens(2).build();
        Pricing pricing = new Pricing(1_000_000L, 1_500_000L, "v1");

        CostResult result = calculator.calculate(usage, pricing);

        assertThat(result.microcents()).isEqualTo(1L + 2L);
    }

    @Test
    void should_handle_zero_tokens() {
        LlmUsage usage = LlmUsage.builder()
                .promptTokens(0).completionTokens(0).totalTokens(0).build();
        Pricing pricing = new Pricing(140_000L, 280_000L, "v1");

        CostResult result = calculator.calculate(usage, pricing);

        assertThat(result.microcents()).isZero();
    }

    @Test
    void should_return_null_on_overflow() {
        LlmUsage usage = LlmUsage.builder()
                .promptTokens(Integer.MAX_VALUE)
                .completionTokens(0)
                .totalTokens(Integer.MAX_VALUE)
                .build();
        Pricing pricing = new Pricing(Long.MAX_VALUE, 0L, "v1");

        assertThat(calculator.calculate(usage, pricing)).isNull();
    }

    @Test
    void should_reject_blank_version() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new Pricing(1L, 1L, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }
}