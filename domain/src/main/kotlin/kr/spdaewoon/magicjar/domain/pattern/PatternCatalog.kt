package kr.spdaewoon.magicjar.domain.pattern

/**
 * 캔들스틱 패턴 카탈로그 57종 (ADR 0020 §3-1 + _workspace/strategy/pattern-catalog.md LOCK).
 *
 * KIS Strategy Builder candlestick.py 65 PATTERN_DETECTORS dict 중 Lean 미지원 9종 제외 = 57.
 *
 * - Single 14종: doji·dragonfly_doji·gravestone_doji·long_legged_doji·hammer·hanging_man·
 *                inverted_hammer·shooting_star·marubozu·closing_marubozu·spinning_top·
 *                belt_hold·high_wave·rickshaw_man
 * - Double 16종: engulfing·harami·harami_cross·piercing·dark_cloud_cover·counterattack·
 *                on_neck·in_neck·thrusting·separating_lines·kicking·kicking_by_length·
 *                matching_low·gap_side_by_side_white·homing_pigeon·doji_star
 * - Triple+ 27종: morning_star·morning_doji_star·evening_star·evening_doji_star·
 *                 three_white_soldiers·three_black_crows·three_inside·three_outside·
 *                 abandoned_baby·three_stars_in_south·advance_block·stalled_pattern·
 *                 tasuki_gap·upside_gap_two_crows·three_line_strike·unique_three_river·
 *                 breakaway·mat_hold·ladder_bottom·concealing_baby_swallow·stick_sandwich·
 *                 tristar·identical_three_crows·two_crows·up_down_gap_three_methods·
 *                 downside_tasuki_gap·upside_tasuki_gap
 *
 * Spring 의존 0.
 */
object PatternCatalog {
    data class Spec(
        val id: String,
        val category: String,
        val minCandles: Int = 5,
        val defaultDirection: Direction,
    )

    val ALL: List<Spec> =
        listOf(
            // ── Single Candle (14) ──────────────────────────────────────────────
            Spec("doji", "INDECISION", 5, Direction.NEUTRAL),
            Spec("dragonfly_doji", "BULLISH_REVERSAL", 5, Direction.BULL),
            Spec("gravestone_doji", "BEARISH_REVERSAL", 5, Direction.BEAR),
            Spec("long_legged_doji", "INDECISION", 5, Direction.NEUTRAL),
            Spec("hammer", "BULLISH_REVERSAL", 5, Direction.BULL),
            Spec("hanging_man", "BEARISH_REVERSAL", 5, Direction.BEAR),
            Spec("inverted_hammer", "BULLISH_REVERSAL", 5, Direction.BULL),
            Spec("shooting_star", "BEARISH_REVERSAL", 5, Direction.BEAR),
            Spec("marubozu", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("closing_marubozu", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("spinning_top", "INDECISION", 5, Direction.NEUTRAL),
            Spec("belt_hold", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("high_wave", "INDECISION", 5, Direction.NEUTRAL),
            Spec("rickshaw_man", "INDECISION", 5, Direction.NEUTRAL),
            // ── Double Candle (16) ──────────────────────────────────────────────
            Spec("engulfing", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("harami", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("harami_cross", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("piercing", "BULLISH_REVERSAL", 5, Direction.BULL),
            Spec("dark_cloud_cover", "BEARISH_REVERSAL", 5, Direction.BEAR),
            Spec("counterattack", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("on_neck", "BEARISH_CONTINUATION", 5, Direction.BEAR),
            Spec("in_neck", "BEARISH_CONTINUATION", 5, Direction.BEAR),
            Spec("thrusting", "BEARISH_CONTINUATION", 5, Direction.BEAR),
            Spec("separating_lines", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("kicking", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("kicking_by_length", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("matching_low", "BULLISH_CONTINUATION", 5, Direction.BULL),
            Spec("gap_side_by_side_white", "BULLISH_REVERSAL", 5, Direction.BULL),
            Spec("homing_pigeon", "BULLISH_REVERSAL", 5, Direction.BULL),
            Spec("doji_star", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            // ── Triple+ Candle (27) ─────────────────────────────────────────────
            Spec("morning_star", "BULLISH_REVERSAL", 5, Direction.BULL),
            Spec("morning_doji_star", "BULLISH_REVERSAL", 5, Direction.BULL),
            Spec("evening_star", "BEARISH_REVERSAL", 5, Direction.BEAR),
            Spec("evening_doji_star", "BEARISH_REVERSAL", 5, Direction.BEAR),
            Spec("three_white_soldiers", "BULLISH_REVERSAL", 5, Direction.BULL),
            Spec("three_black_crows", "BEARISH_REVERSAL", 5, Direction.BEAR),
            Spec("three_inside", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("three_outside", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("abandoned_baby", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("three_stars_in_south", "BULLISH_REVERSAL", 5, Direction.BULL),
            Spec("advance_block", "BEARISH_REVERSAL", 5, Direction.BEAR),
            Spec("stalled_pattern", "BEARISH_REVERSAL", 5, Direction.BEAR),
            Spec("tasuki_gap", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("upside_gap_two_crows", "BEARISH_REVERSAL", 5, Direction.BEAR),
            Spec("three_line_strike", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("unique_three_river", "BULLISH_REVERSAL", 5, Direction.BULL),
            Spec("breakaway", "BULLISH_REVERSAL", 5, Direction.BULL),
            Spec("mat_hold", "BULLISH_CONTINUATION", 5, Direction.BULL),
            Spec("ladder_bottom", "BULLISH_REVERSAL", 5, Direction.BULL),
            Spec("concealing_baby_swallow", "BULLISH_REVERSAL", 5, Direction.BULL),
            Spec("stick_sandwich", "BULLISH_REVERSAL", 5, Direction.BULL),
            Spec("tristar", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("identical_three_crows", "BEARISH_REVERSAL", 5, Direction.BEAR),
            Spec("two_crows", "BEARISH_REVERSAL", 5, Direction.BEAR),
            Spec("up_down_gap_three_methods", "REVERSAL_DIRECTIONAL", 5, Direction.NEUTRAL),
            Spec("downside_tasuki_gap", "BEARISH_CONTINUATION", 5, Direction.BEAR),
            Spec("upside_tasuki_gap", "BULLISH_CONTINUATION", 5, Direction.BULL),
        )

    init {
        check(ALL.size == 57) {
            "PatternCatalog ALL.size must be 57 (actual=${ALL.size})"
        }
    }

    fun byId(id: String): Spec? = ALL.find { it.id == id }
}
