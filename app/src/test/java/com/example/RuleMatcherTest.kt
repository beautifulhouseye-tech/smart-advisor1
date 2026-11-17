package com.example

import com.example.data.local.entity.MatchType
import com.example.data.local.entity.ReplyRuleEntity
import com.example.engine.RuleMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleMatcherTest {

    @Test
    fun `matches comma separated Arabic keywords by priority`() {
        val rules = listOf(
            ReplyRuleEntity(
                id = 1,
                keyword = "*",
                replyText = "رد افتراضي",
                priority = 1,
                isEnabled = true
            ),
            ReplyRuleEntity(
                id = 2,
                keyword = "السلام عليكم, مرحبا",
                replyText = "وعليكم السلام",
                matchType = MatchType.CONTAINS,
                priority = 10,
                isEnabled = true
            )
        )

        val matched = RuleMatcher.findBestMatch("أهلاً، السلام عليكم أخي", rules)
        assertEquals(2L, matched?.id)
        assertEquals("وعليكم السلام", matched?.replyText)
    }

    @Test
    fun `falls back to wildcard when no specific rule matches`() {
        val rules = listOf(
            ReplyRuleEntity(
                id = 1,
                keyword = "*",
                replyText = "شكراً لتواصلك",
                priority = 1,
                isEnabled = true
            ),
            ReplyRuleEntity(
                id = 2,
                keyword = "السعر",
                replyText = "100 ريال",
                matchType = MatchType.EXACT,
                priority = 10,
                isEnabled = true
            )
        )

        val matched = RuleMatcher.findBestMatch("كيف حالك اليوم؟", rules)
        assertEquals(1L, matched?.id)
    }

    @Test
    fun `ignores disabled rules and invalid regex safely`() {
        val rules = listOf(
            ReplyRuleEntity(
                id = 1,
                keyword = "مرحبا",
                replyText = "معطل",
                isEnabled = false
            ),
            ReplyRuleEntity(
                id = 2,
                keyword = "[unclosed-regex",
                replyText = "regex",
                matchType = MatchType.REGEX,
                isEnabled = true
            )
        )

        val matched = RuleMatcher.findBestMatch("مرحبا", rules)
        assertNull(matched)
    }
}
