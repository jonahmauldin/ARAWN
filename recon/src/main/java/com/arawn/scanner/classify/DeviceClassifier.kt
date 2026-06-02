package com.arawn.scanner.classify

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Phase 2 classification engine.
 *
 * Runs every registered [ClassificationRule] over one [ClassifierInput], sums
 * the emitted signals per [DeviceClass] (the score aggregator), normalizes the
 * leader into a confidence %, decides a [ClassificationStatus], and hands the
 * winning class's signals to [ExplanationBuilder].
 *
 * Stateless and thread-safe: construct one and reuse it across the scan
 * pipeline. Cost is O(rules) per observation — see the perf notes in the Phase 2
 * design. No Android dependencies.
 */
class DeviceClassifier(
    private val rules: List<ClassificationRule> = RuleRegistry.DEFAULT.rules,
    private val config: ScoringConfig = ScoringConfig.DEFAULT,
) {

    fun classify(input: ClassifierInput): ClassificationResult {
        // 1. Collect signals. ruleId is attached here so rules don't repeat it.
        val signals = ArrayList<ScoreSignal>(8)
        for (rule in rules) {
            rule.evaluate(input) { target, points, reason ->
                if (points != 0) signals.add(ScoreSignal(target, points, reason, rule.id))
            }
        }
        if (signals.isEmpty()) return ClassificationResult.UNKNOWN

        // 2. Aggregate per class.
        val totals = HashMap<DeviceClass, Int>()
        for (s in signals) totals[s.target] = (totals[s.target] ?: 0) + s.points
        val breakdown = totals.filterValues { it != 0 }
        if (breakdown.isEmpty()) return ClassificationResult.UNKNOWN

        val ranked = breakdown.entries.sortedByDescending { it.value }
        val top = ranked[0]
        val runnerUp = ranked.getOrNull(1)
        val topScore = top.value

        // 3. Not enough evidence → honest UNKNOWN (but keep the math for audit).
        if (topScore < config.minEvidenceScore) {
            return ClassificationResult.UNKNOWN.copy(breakdown = breakdown)
        }

        // 4. Confidence: absolute sufficiency × how cleanly it dominates the field.
        val totalPositive = breakdown.values.filter { it > 0 }.sum().coerceAtLeast(1)
        val sufficiency = min(1.0, topScore.toDouble() / config.saturationScore)
        val dominance = topScore.toDouble() / totalPositive
        val confidence = (100.0 * sufficiency *
            (config.baseWeight + config.dominanceWeight * dominance))
            .roundToInt().coerceIn(0, 100)

        // 5. Status gates — structurally prevent forcing a weak/contested verdict.
        val margin = topScore - (runnerUp?.value ?: 0)
        val status = when {
            runnerUp != null && margin <= config.ambiguousMarginScore ->
                ClassificationStatus.AMBIGUOUS
            confidence < config.lowConfidencePercent ->
                ClassificationStatus.LOW_CONFIDENCE
            else -> ClassificationStatus.CONFIDENT
        }

        // 6. Explain.
        val evidence = ExplanationBuilder.build(
            input = input,
            top = top.key,
            topSignals = signals.filter { it.target == top.key },
            status = status,
            runnerUp = runnerUp?.key,
        )

        return ClassificationResult(
            top = top.key,
            confidence = confidence,
            status = status,
            runnerUp = if (status == ClassificationStatus.AMBIGUOUS) runnerUp?.key else null,
            evidence = evidence,
            breakdown = breakdown,
        )
    }

    companion object {
        /** Shared default instance — stateless, safe to reuse process-wide. */
        val DEFAULT: DeviceClassifier by lazy { DeviceClassifier() }
    }
}
