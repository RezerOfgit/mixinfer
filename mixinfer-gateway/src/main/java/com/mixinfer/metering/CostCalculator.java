package com.mixinfer.metering;

import com.mixinfer.domain.LlmUsage;
import com.mixinfer.router.Pricing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Computes an estimated cost from usage and a pricing table.
 *
 * <p>The result is a configuration-based estimate, not a provider invoice.
 * Unknown usage yields null rather than zero, so aggregation does not
 * silently underreport cost.
 *
 * <p>All arithmetic is integer-based (long); no double intermediate values
 * are used. Prices are in microcents per one million tokens.
 */
@Component
public class CostCalculator {

    private static final Logger log = LoggerFactory.getLogger(CostCalculator.class);

    private static final long MICROCENTS_PER_CENT = 1_000_000L;
    private static final long ROUND_BIAS = MICROCENTS_PER_CENT / 2;

    /**
     * @return the computed cost, or null when usage or pricing is missing,
     *         or when the arithmetic would overflow
     */
    public CostResult calculate(LlmUsage usage, Pricing pricing) {
        if (usage == null || pricing == null) {
            return null;
        }
        try {
            long inputCost = component(usage.getPromptTokens(),
                    pricing.inputPricePerMillionMicrocents());
            long outputCost = component(usage.getCompletionTokens(),
                    pricing.outputPricePerMillionMicrocents());
            return new CostResult(
                    Math.addExact(inputCost, outputCost),
                    pricing.version());
        } catch (ArithmeticException e) {
            log.warn("Cost computation overflowed: {}", e.getMessage());
            return null;
        }
    }

    private long component(long tokens, long pricePerMillion) {
        long product = Math.multiplyExact(tokens, pricePerMillion);
        long biased = Math.addExact(product, ROUND_BIAS);
        return biased / MICROCENTS_PER_CENT;
    }
}