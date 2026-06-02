package com.arawn.scanner.classify

/**
 * All tunable knobs for the scoring math in one auditable place. Defaults are
 * conservative — they favour UNKNOWN/AMBIGUOUS over a forced wrong answer.
 *
 * @property saturationScore the raw score at which evidence is treated as
 *   "fully sufficient" (sufficiency caps at 1.0 here). Lower = more confident
 *   from less evidence.
 * @property baseWeight floor multiplier so a clean win is never reported at 0%
 *   just because it shares some points with the field.
 * @property dominanceWeight how much the winner's share of total positive score
 *   lifts confidence above [baseWeight].
 * @property minEvidenceScore below this top score the verdict is UNKNOWN.
 * @property lowConfidencePercent below this confidence% the verdict is flagged
 *   LOW_CONFIDENCE.
 * @property ambiguousMarginScore if the top two classes are within this many
 *   points, the verdict is AMBIGUOUS and both are surfaced.
 */
data class ScoringConfig(
    val saturationScore: Int = 80,
    val baseWeight: Double = 0.60,
    val dominanceWeight: Double = 0.40,
    val minEvidenceScore: Int = 25,
    val lowConfidencePercent: Int = 50,
    val ambiguousMarginScore: Int = 12,
) {
    companion object {
        val DEFAULT = ScoringConfig()
    }
}
