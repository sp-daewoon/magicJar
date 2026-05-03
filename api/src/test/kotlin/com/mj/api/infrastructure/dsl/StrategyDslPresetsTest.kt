package com.mj.api.infrastructure.dsl

import com.mj.domain.strategy.dsl.StrategyDslValidator
import com.mj.domain.strategy.dsl.ValidationResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * 10 KIS 프리셋 yaml round-trip + validator 통과 검증.
 *
 * 각 프리셋이:
 *  1. 파일이 존재
 *  2. parser.parse 성공
 *  3. validator.validate Ok
 *  4. serialize 후 다시 parse 성공
 */
class StrategyDslPresetsTest {
    private val parser = StrategyDslParser()
    private val presets = StrategyDslPresets(parser)

    @Test
    fun `all 10 presets load`() {
        presets.all.size shouldBe 10
    }

    @Test
    fun `each preset has unique id`() {
        val ids = presets.all.map { it.dsl.strategy.id }
        ids.toSet().size shouldBe ids.size
    }

    @Test
    fun `each preset passes validator`() {
        presets.all.forEach { entry ->
            val result = StrategyDslValidator.validate(entry.dsl)
            check(result is ValidationResult.Ok) {
                "preset '${entry.dsl.strategy.id}' (${entry.file}) failed: $result"
            }
        }
    }

    @Test
    fun `each preset round-trips through yaml`() {
        presets.all.forEach { entry ->
            val serialized = parser.serialize(entry.dsl)
            serialized shouldNotBe ""
            val reparsed = parser.parse(serialized)
            reparsed.strategy.id shouldBe entry.dsl.strategy.id
            reparsed.strategy.indicators.map { it.id } shouldBe
                entry.dsl.strategy.indicators
                    .map { it.id }
        }
    }

    @Test
    fun `golden_cross preset has expected shape`() {
        val gc = presets.byId("golden_cross_5_20")
        gc shouldNotBe null
        checkNotNull(gc)
        gc.dsl.strategy.indicators
            .map { it.alias } shouldBe listOf("ma5", "ma20")
        gc.dsl.metadata.tags shouldBe listOf("trend_following", "ma_cross")
    }

    @Test
    fun `consecutive_three_white_soldiers uses pattern condition`() {
        val p = presets.byId("consecutive_three_white_soldiers")
        p shouldNotBe null
        checkNotNull(p)
        p.dsl.strategy.patterns
            .map { it.code } shouldBe listOf("three_white_soldiers")
    }
}
