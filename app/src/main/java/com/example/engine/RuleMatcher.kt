package com.example.engine

import com.example.data.local.entity.MatchType
import com.example.data.local.entity.ReplyRuleEntity

object RuleMatcher {

    fun findBestMatch(
        incomingText: String,
        rules: List<ReplyRuleEntity>
    ): ReplyRuleEntity? {
        val normalizedIncoming = incomingText.trim()
        if (normalizedIncoming.isEmpty()) return null

        val enabledSorted = rules
            .filter { it.isEnabled }
            .sortedWith(compareByDescending<ReplyRuleEntity> { it.priority }.thenByDescending { it.createdAt })

        // Evaluate specific rules first, wildcard "*" last
        val specificRules = enabledSorted.filter { it.keyword.trim() != "*" }
        val wildcardRules = enabledSorted.filter { it.keyword.trim() == "*" }

        for (rule in specificRules) {
            if (matchesRule(normalizedIncoming, rule)) {
                return rule
            }
        }
        return wildcardRules.firstOrNull()
    }

    fun matchesRule(incomingText: String, rule: ReplyRuleEntity): Boolean {
        val keywords = rule.keyword
            .split(",", "،")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (keywords.isEmpty()) return false
        if (keywords.any { it == "*" }) return true

        val textLower = incomingText.lowercase()
        return keywords.any { kw ->
            val kwLower = kw.lowercase()
            when (rule.matchType) {
                MatchType.CONTAINS -> textLower.contains(kwLower)
                MatchType.EXACT -> textLower == kwLower
                MatchType.STARTS_WITH -> textLower.startsWith(kwLower)
                MatchType.REGEX -> runCatching {
                    Regex(kw, RegexOption.IGNORE_CASE).containsMatchIn(incomingText)
                }.getOrDefault(false)
            }
        }
    }
}
