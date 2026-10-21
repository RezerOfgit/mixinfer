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

    @Test
    void should_round_up_at_exact_half_microcent() {
        // 1 token at 500,000 microcents/1M -> 0.5 microcent
        // Round-half-up must produce 1, not 0.
        LlmUsage usage = LlmUsage.builder()
                .promptTokens(1).completionTokens(0).totalTokens(1).build();
        Pricing pricing = new Pricing(500_000L, 0L, "v1");

        CostResult result = calculator.calculate(usage, pricing);

        assertThat(result).isNotNull();
        assertThat(result.microcents()).isEqualTo(1L);
    }

    @Test
    void should_round_down_just_below_half_microcent() {
        // 1 token at 499,999 microcents/1M -> 0.499999 microcent
        // Must floor to 0; anything else is off-by-one at the boundary.
        LlmUsage usage = LlmUsage.builder()
                .promptTokens(1).completionTokens(0).totalTokens(1).build();
        Pricing pricing = new Pricing(499_999L, 0L, "v1");

        CostResult result = calculator.calculate(usage, pricing);

        assertThat(result).isNotNull();
        assertThat(result.microcents()).isZero();
    }

    @Test
    void should_round_up_just_above_half_microcent() {
        // 1 token at 500,001 microcents/1M -> 0.500001 microcent
        // Must round to 1.
        LlmUsage usage = LlmUsage.builder()
                .promptTokens(1).completionTokens(0).totalTokens(1).build();
        Pricing pricing = new Pricing(500_001L, 0L, "v1");

        CostResult result = calculator.calculate(usage, pricing);

        assertThat(result).isNotNull();
        assertThat(result.microcents()).isEqualTo(1L);
    }

    @Test
    void should_round_half_up_at_larger_values() {
        // 5 tokens at 500,000 microcents/1M -> 2.5 microcents
        // Round-half-up must produce 3, not 2 (this is round-half-up,
        // not banker's rounding / round-half-to-even).
        LlmUsage usage = LlmUsage.builder()
                .promptTokens(5).completionTokens(0).totalTokens(5).build();
        Pricing pricing = new Pricing(500_000L, 0L, "v1");

        CostResult result = calculator.calculate(usage, pricing);

        assertThat(result).isNotNull();
        assertThat(result.microcents()).isEqualTo(3L);
    }

    @Test
    void should_round_input_and_output_independently() {
        // input:  1 token x 400,000 / 1M = 0.4 -> 0
        // output: 1 token x 400,000 / 1M = 0.4 -> 0
        // Sum of independently-rounded components is 0.
        //
        // If the implementation summed tokens first and then priced
        // (2 tokens x 400,000 / 1M = 0.8 -> 1), the result would be 1.
        // This test locks in the "price each side separately" decision.
        LlmUsage usage = LlmUsage.builder()
                .promptTokens(1).completionTokens(1).totalTokens(2).build();
        Pricing pricing = new Pricing(400_000L, 400_000L, "v1");

        CostResult result = calculator.calculate(usage, pricing);

        assertThat(result).isNotNull();
        assertThat(result.microcents()).isZero();
    }

    @Test
    void should_return_null_when_rounding_bias_overflows() {
        // product = Long.MAX_VALUE - 499_999  (does not overflow on multiply)
        // biased  = product + 500_000         (overflows on add)
        //
        // The calculator must catch this and return null, not a wrapped value.
        long price = Long.MAX_VALUE - 499_999L;

        LlmUsage usage = LlmUsage.builder()
                .promptTokens(1).completionTokens(0).totalTokens(1).build();
        Pricing pricing = new Pricing(price, 0L, "v1");

        assertThat(calculator.calculate(usage, pricing)).isNull();
    }
}