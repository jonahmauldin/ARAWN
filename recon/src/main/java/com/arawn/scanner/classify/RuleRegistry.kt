package com.arawn.scanner.classify

import com.arawn.scanner.classify.rules.CoreRules

/**
 * The single place rules are assembled into the set the engine runs.
 *
 * [DEFAULT] is the shipped rule library. [with] returns a NEW registry with an
 * extra rule appended (immutably), which is how tests or future modules inject
 * rules without mutating global state or editing the engine.
 */
class RuleRegistry private constructor(val rules: List<ClassificationRule>) {

    fun with(extra: ClassificationRule): RuleRegistry = RuleRegistry(rules + extra)

    fun with(extra: List<ClassificationRule>): RuleRegistry = RuleRegistry(rules + extra)

    companion object {
        val DEFAULT: RuleRegistry = RuleRegistry(CoreRules.ALL)
    }
}
