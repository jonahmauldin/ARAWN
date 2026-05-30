package com.arawn.scanner.classify

/**
 * One isolated, explainable classification heuristic.
 *
 * A rule reads the [ClassifierInput] and emits zero or more weighted signals
 * through [sink]. Rules MUST be:
 *  - **stateless** — no mutable fields, safe to share as a singleton;
 *  - **independent** — they never read another rule's output;
 *  - **cheap** — a handful of string/set checks, no I/O or allocation storms.
 *
 * Adding a new rule never requires editing the engine, the aggregator, or any
 * existing rule — only registering the new object in `CoreRules.ALL`.
 *
 * @property id stable identifier carried into every emitted signal, so a score
 *  breakdown can be audited back to the exact rules that produced it.
 */
interface ClassificationRule {
    val id: String

    /**
     * @param sink invoked once per signal: (target class, points, human reason).
     *  Points may be negative to penalize a class. A no-op body is valid (the
     *  rule simply didn't fire for this input).
     */
    fun evaluate(input: ClassifierInput, sink: (DeviceClass, Int, String) -> Unit)
}
