package com.mj.api.infrastructure.dsl

import com.mj.domain.strategy.dsl.StrategyDsl
import org.springframework.stereotype.Component

/**
 * KIS Strategy Builder 10 프리셋 전략 로더 (ADR 0040).
 *
 * 리소스: classpath:strategy/preset/NN-name.yaml
 *
 * 10개 카테고리 (KIS 정합):
 *  01. 골든크로스 (golden_cross)
 *  02. 모멘텀 (rsi_momentum)
 *  03. 추세 필터 (ema_trend_filter)
 *  04. 52주 신고가 (week52_high_breakout)
 *  05. 연속 양봉 패턴 (consecutive_three_white_soldiers)
 *  06. 이격도 (disparity_mean_reversion)
 *  07. 페이크 브레이크 (breakout_fail)
 *  08. 강세 마감 (strong_close_volume_breakout)
 *  09. 변동성 돌파 (atr_volatility_breakout)
 *  10. 평균 회귀 (bb_mean_reversion)
 *
 * 모두 KIS `.kis.yaml` 호환 superset — Parser가 KIS 표준 yaml(`indicators[]`/`entry`/`exit`/`conditions[]`)
 * 그대로 import 가능. Validator를 통과해야만 ALL 리스트에 등록 (부팅 시점 검증).
 */
@Component
class StrategyDslPresets(
    private val parser: StrategyDslParser,
) {
    /**
     * 프리셋 파일명 (resources/strategy/preset/ 하위, 정렬용 prefix 포함).
     */
    private val presetFiles =
        listOf(
            "01-golden-cross.yaml",
            "02-momentum.yaml",
            "03-trend-filter.yaml",
            "04-week52-high.yaml",
            "05-consecutive.yaml",
            "06-disparity.yaml",
            "07-breakout-fail.yaml",
            "08-strong-close.yaml",
            "09-volatility.yaml",
            "10-mean-reversion.yaml",
        )

    /**
     * 부팅 시점 1회 로드 — ClassLoader.getResourceAsStream.
     *
     * 파일 누락 / 파싱 실패 시 즉시 throw — 잘못된 프리셋이 운영에 흘러가지 않도록.
     */
    val all: List<PresetEntry> by lazy { loadAll() }

    fun byId(id: String): PresetEntry? = all.firstOrNull { it.dsl.strategy.id == id }

    private fun loadAll(): List<PresetEntry> {
        val cl = StrategyDslPresets::class.java.classLoader
        return presetFiles.map { name ->
            val path = "strategy/preset/$name"
            val yaml =
                cl.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalStateException("preset resource not found: $path")
            val dsl = parser.parse(yaml)
            PresetEntry(file = name, dsl = dsl, yaml = yaml)
        }
    }
}

/**
 * 프리셋 1건 — 파일·도메인·원본 yaml 묶음.
 */
data class PresetEntry(
    val file: String,
    val dsl: StrategyDsl,
    val yaml: String,
)
