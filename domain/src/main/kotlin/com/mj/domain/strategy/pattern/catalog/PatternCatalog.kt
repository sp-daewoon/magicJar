package com.mj.domain.strategy.pattern.catalog

import com.mj.domain.strategy.pattern.CandlePattern
import com.mj.domain.strategy.pattern.PatternCategory
import com.mj.domain.strategy.pattern.PatternDirection

/**
 * 캔들스틱 패턴 카탈로그 (57개, KIS Strategy Builder 정합).
 *
 * KIS strategy_builder/core/candlestick.py 의 66 패턴에서 Lean(QuantConnect) 미지원 9개를
 * 제외해 57개로 확정한다.
 *
 * 제외된 9개:
 * 1. Opening Marubozu        — Lean은 Marubozu/ClosingMarubozu만 보유
 * 2. Tweezer Top             — Lean 미보유
 * 3. Tweezer Bottom          — Lean 미보유
 * 4. Meeting Lines           — Lean 미보유 (Counterattack과 유사하나 별개 정의)
 * 5. Matching High           — Lean은 MatchingLow만 보유
 * 6. Deliberation            — Lean 미보유 (Stalled Pattern과 유사)
 * 7. Rising Three Methods    — Lean RiseFallThreeMethods로 통합 → mat_hold만 유지
 * 8. Falling Three Methods   — Lean RiseFallThreeMethods로 통합 → mat_hold만 유지
 * 9. Side by Side White Lines — Lean `GapSideBySideWhite`로 통합 → gap_side_by_side_white만 유지
 *
 * 각 패턴의 `code`는 안정 식별자다. 변경 시 DSL/DB/REST 호환성 깨질 수 있으므로 신규 패턴
 * 추가만 허용하고 기존 code rename은 ADR을 거친다.
 *
 * 정확한 출처:
 *  - KIS: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/candlestick.py
 *  - Lean: https://github.com/QuantConnect/Lean/tree/master/Indicators/CandlestickPatterns
 */
object PatternCatalog {
    // ---------- Single candle patterns (14개, KIS 15 - Opening Marubozu 1) ----------
    val DOJI =
        CandlePattern(
            code = "doji",
            nameEn = "Doji",
            nameKo = "도지",
            category = PatternCategory.INDECISION,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 1,
            description = "시가와 종가가 거의 같아 매수·매도 균형 상태. 추세 망설임 신호.",
        )

    val DRAGONFLY_DOJI =
        CandlePattern(
            code = "dragonfly_doji",
            nameEn = "Dragonfly Doji",
            nameKo = "잠자리형 도지",
            category = PatternCategory.BULLISH_REVERSAL,
            defaultDirection = PatternDirection.BULL,
            candleCount = 1,
            description = "긴 아랫꼬리 + 시가=고가=종가. 하락 추세 후 강세 반전 신호.",
        )

    val GRAVESTONE_DOJI =
        CandlePattern(
            code = "gravestone_doji",
            nameEn = "Gravestone Doji",
            nameKo = "비석형 도지",
            category = PatternCategory.BEARISH_REVERSAL,
            defaultDirection = PatternDirection.BEAR,
            candleCount = 1,
            description = "긴 윗꼬리 + 시가=저가=종가. 상승 추세 후 약세 반전 신호.",
        )

    val LONG_LEGGED_DOJI =
        CandlePattern(
            code = "long_legged_doji",
            nameEn = "Long Legged Doji",
            nameKo = "롱 레그 도지",
            category = PatternCategory.INDECISION,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 1,
            description = "윗꼬리·아랫꼬리 모두 길고 몸통은 작음. 강한 망설임.",
        )

    val HAMMER =
        CandlePattern(
            code = "hammer",
            nameEn = "Hammer",
            nameKo = "해머",
            category = PatternCategory.BULLISH_REVERSAL,
            defaultDirection = PatternDirection.BULL,
            candleCount = 1,
            description = "작은 몸통 + 긴 아랫꼬리. 하락 추세 끝에서 강세 반전.",
        )

    val HANGING_MAN =
        CandlePattern(
            code = "hanging_man",
            nameEn = "Hanging Man",
            nameKo = "매달린 사람",
            category = PatternCategory.BEARISH_REVERSAL,
            defaultDirection = PatternDirection.BEAR,
            candleCount = 1,
            description = "해머와 동일 형태이나 상승 추세 끝에서 출현. 약세 반전 경고.",
        )

    val INVERTED_HAMMER =
        CandlePattern(
            code = "inverted_hammer",
            nameEn = "Inverted Hammer",
            nameKo = "역해머",
            category = PatternCategory.BULLISH_REVERSAL,
            defaultDirection = PatternDirection.BULL,
            candleCount = 1,
            description = "작은 몸통 + 긴 윗꼬리. 하락 추세 끝에서 반전 가능성.",
        )

    val SHOOTING_STAR =
        CandlePattern(
            code = "shooting_star",
            nameEn = "Shooting Star",
            nameKo = "슈팅스타",
            category = PatternCategory.BEARISH_REVERSAL,
            defaultDirection = PatternDirection.BEAR,
            candleCount = 1,
            description = "역해머와 동일 형태이나 상승 추세 끝에서 출현. 약세 반전.",
        )

    val MARUBOZU =
        CandlePattern(
            code = "marubozu",
            nameEn = "Marubozu",
            nameKo = "마루보주",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 1,
            description = "꼬리가 거의 없는 강한 몸통. 양봉=강세, 음봉=약세.",
        )

    val CLOSING_MARUBOZU =
        CandlePattern(
            code = "closing_marubozu",
            nameEn = "Closing Marubozu",
            nameKo = "종가 마루보주",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 1,
            description = "종가 측 꼬리 없음. 추세의 종가 우위.",
        )

    val SPINNING_TOP =
        CandlePattern(
            code = "spinning_top",
            nameEn = "Spinning Top",
            nameKo = "팽이",
            category = PatternCategory.INDECISION,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 1,
            description = "작은 몸통 + 양쪽 꼬리. 망설임.",
        )

    val BELT_HOLD =
        CandlePattern(
            code = "belt_hold",
            nameEn = "Belt Hold",
            nameKo = "벨트홀드",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 1,
            description = "긴 양봉/음봉의 시가 측 꼬리가 거의 없음. 강한 추세 시작.",
        )

    val HIGH_WAVE =
        CandlePattern(
            code = "high_wave",
            nameEn = "High Wave",
            nameKo = "높은 파도",
            category = PatternCategory.INDECISION,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 1,
            description = "매우 긴 윗·아랫꼬리 + 작은 몸통. 극심한 망설임.",
        )

    val RICKSHAW_MAN =
        CandlePattern(
            code = "rickshaw_man",
            nameEn = "Rickshaw Man",
            nameKo = "인력거꾼",
            category = PatternCategory.INDECISION,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 1,
            description = "Long Legged Doji 변형 — 몸통이 캔들 중앙에 위치.",
        )

    // ---------- Double candle patterns (16개, KIS 20 - Tweezer Top/Bottom·Meeting Lines·Matching High 4) ----------
    val ENGULFING =
        CandlePattern(
            code = "engulfing",
            nameEn = "Engulfing",
            nameKo = "잉걸핑",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 2,
            description = "두 번째 캔들 몸통이 첫 번째를 완전히 감쌈. 양봉이면 강세, 음봉이면 약세 반전.",
        )

    val HARAMI =
        CandlePattern(
            code = "harami",
            nameEn = "Harami",
            nameKo = "잉태형",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 2,
            description = "첫 번째 큰 몸통 안에 두 번째 작은 몸통이 들어감. 추세 둔화.",
        )

    val HARAMI_CROSS =
        CandlePattern(
            code = "harami_cross",
            nameEn = "Harami Cross",
            nameKo = "잉태형 도지",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 2,
            description = "Harami 두 번째 캔들이 도지인 형태. 더 강한 반전 신호.",
        )

    val PIERCING =
        CandlePattern(
            code = "piercing",
            nameEn = "Piercing",
            nameKo = "관통형",
            category = PatternCategory.BULLISH_REVERSAL,
            defaultDirection = PatternDirection.BULL,
            candleCount = 2,
            description = "음봉 다음 양봉이 음봉 몸통 절반 이상을 회복. 강세 반전.",
        )

    val DARK_CLOUD_COVER =
        CandlePattern(
            code = "dark_cloud_cover",
            nameEn = "Dark Cloud Cover",
            nameKo = "먹구름형",
            category = PatternCategory.BEARISH_REVERSAL,
            defaultDirection = PatternDirection.BEAR,
            candleCount = 2,
            description = "양봉 다음 음봉이 양봉 몸통 절반 이상을 잠식. 약세 반전.",
        )

    val COUNTERATTACK =
        CandlePattern(
            code = "counterattack",
            nameEn = "Counterattack",
            nameKo = "반격형",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 2,
            description = "두 캔들의 종가가 거의 같음 — 추세 정지 후 반전 가능.",
        )

    val ON_NECK =
        CandlePattern(
            code = "on_neck",
            nameEn = "On Neck",
            nameKo = "온넥",
            category = PatternCategory.BEARISH_CONTINUATION,
            defaultDirection = PatternDirection.BEAR,
            candleCount = 2,
            description = "음봉 후 양봉 종가가 음봉 저가 부근에서 닫힘. 하락 지속.",
        )

    val IN_NECK =
        CandlePattern(
            code = "in_neck",
            nameEn = "In Neck",
            nameKo = "인넥",
            category = PatternCategory.BEARISH_CONTINUATION,
            defaultDirection = PatternDirection.BEAR,
            candleCount = 2,
            description = "음봉 후 양봉 종가가 음봉 종가 살짝 위에서 닫힘. 하락 지속.",
        )

    val THRUSTING =
        CandlePattern(
            code = "thrusting",
            nameEn = "Thrusting",
            nameKo = "관통(부분)형",
            category = PatternCategory.BEARISH_CONTINUATION,
            defaultDirection = PatternDirection.BEAR,
            candleCount = 2,
            description = "Piercing 미달 — 양봉이 음봉 몸통 절반 미만 회복. 하락 지속.",
        )

    val SEPARATING_LINES =
        CandlePattern(
            code = "separating_lines",
            nameEn = "Separating Lines",
            nameKo = "분리선",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 2,
            description = "두 캔들의 시가가 동일하고 방향 반대. 추세 지속 신호.",
        )

    val KICKING =
        CandlePattern(
            code = "kicking",
            nameEn = "Kicking",
            nameKo = "킥킹",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 2,
            description = "두 마루보주가 갭으로 분리, 방향 반대. 강한 반전.",
        )

    val KICKING_BY_LENGTH =
        CandlePattern(
            code = "kicking_by_length",
            nameEn = "Kicking by Length",
            nameKo = "킥킹 (장신)",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 2,
            description = "Kicking 변형 — 길이 큰 쪽 캔들 방향이 우세 신호.",
        )

    val MATCHING_LOW =
        CandlePattern(
            code = "matching_low",
            nameEn = "Matching Low",
            nameKo = "동일 저가",
            category = PatternCategory.BULLISH_CONTINUATION,
            defaultDirection = PatternDirection.BULL,
            candleCount = 2,
            description = "두 음봉의 종가(저가 부근)가 같음. 지지 확인.",
        )

    val GAP_SIDE_BY_SIDE_WHITE =
        CandlePattern(
            code = "gap_side_by_side_white",
            nameEn = "Gap Side by Side White",
            nameKo = "갭 나란히 양봉",
            category = PatternCategory.BULLISH_REVERSAL,
            defaultDirection = PatternDirection.BULL,
            candleCount = 2,
            description = "갭 상승 후 두 양봉이 거의 같은 시가/종가. 강세 지속.",
        )

    val HOMING_PIGEON =
        CandlePattern(
            code = "homing_pigeon",
            nameEn = "Homing Pigeon",
            nameKo = "귀가비둘기",
            category = PatternCategory.BULLISH_REVERSAL,
            defaultDirection = PatternDirection.BULL,
            candleCount = 2,
            description = "큰 음봉 + 그 안에 들어가는 작은 음봉. 하락세 둔화.",
        )

    val DOJI_STAR =
        CandlePattern(
            code = "doji_star",
            nameEn = "Doji Star",
            nameKo = "도지스타",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 2,
            description = "큰 몸통 + 갭 후 도지. 반전 임박.",
        )

    // ---------- Triple+ candle patterns (27개, KIS 30 - Deliberation·Rising/Falling Three Methods 3) ----------
    val MORNING_STAR =
        CandlePattern(
            code = "morning_star",
            nameEn = "Morning Star",
            nameKo = "샛별",
            category = PatternCategory.BULLISH_REVERSAL,
            defaultDirection = PatternDirection.BULL,
            candleCount = 3,
            description = "음봉 + 갭하락 작은 캔들 + 양봉. 하락 추세 끝 강세 반전 대표 패턴.",
        )

    val MORNING_DOJI_STAR =
        CandlePattern(
            code = "morning_doji_star",
            nameEn = "Morning Doji Star",
            nameKo = "샛별 도지",
            category = PatternCategory.BULLISH_REVERSAL,
            defaultDirection = PatternDirection.BULL,
            candleCount = 3,
            description = "Morning Star 가운데 캔들이 도지인 강화 형태.",
        )

    val EVENING_STAR =
        CandlePattern(
            code = "evening_star",
            nameEn = "Evening Star",
            nameKo = "저녁별",
            category = PatternCategory.BEARISH_REVERSAL,
            defaultDirection = PatternDirection.BEAR,
            candleCount = 3,
            description = "양봉 + 갭상승 작은 캔들 + 음봉. 상승 추세 끝 약세 반전 대표 패턴.",
        )

    val EVENING_DOJI_STAR =
        CandlePattern(
            code = "evening_doji_star",
            nameEn = "Evening Doji Star",
            nameKo = "저녁별 도지",
            category = PatternCategory.BEARISH_REVERSAL,
            defaultDirection = PatternDirection.BEAR,
            candleCount = 3,
            description = "Evening Star 가운데 캔들이 도지인 강화 형태.",
        )

    val THREE_WHITE_SOLDIERS =
        CandlePattern(
            code = "three_white_soldiers",
            nameEn = "Three White Soldiers",
            nameKo = "세 흰병사",
            category = PatternCategory.BULLISH_REVERSAL,
            defaultDirection = PatternDirection.BULL,
            candleCount = 3,
            description = "연속 3개 강한 양봉, 각 시가가 직전 몸통 안. 강한 강세 반전.",
        )

    val THREE_BLACK_CROWS =
        CandlePattern(
            code = "three_black_crows",
            nameEn = "Three Black Crows",
            nameKo = "세 까마귀",
            category = PatternCategory.BEARISH_REVERSAL,
            defaultDirection = PatternDirection.BEAR,
            candleCount = 3,
            description = "연속 3개 강한 음봉, 각 시가가 직전 몸통 안. 강한 약세 반전.",
        )

    val THREE_INSIDE =
        CandlePattern(
            code = "three_inside",
            nameEn = "Three Inside",
            nameKo = "삼중 인사이드",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 3,
            description = "Harami + 확인 캔들. 방향은 세 번째 캔들 종가로 결정.",
        )

    val THREE_OUTSIDE =
        CandlePattern(
            code = "three_outside",
            nameEn = "Three Outside",
            nameKo = "삼중 아웃사이드",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 3,
            description = "Engulfing + 확인 캔들. 방향은 세 번째 캔들 종가로 결정.",
        )

    val ABANDONED_BABY =
        CandlePattern(
            code = "abandoned_baby",
            nameEn = "Abandoned Baby",
            nameKo = "버려진 아기",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 3,
            description = "Morning/Evening Doji Star + 갭 양쪽 분리. 강한 반전.",
        )

    val THREE_STARS_IN_SOUTH =
        CandlePattern(
            code = "three_stars_in_south",
            nameEn = "Three Stars in South",
            nameKo = "남쪽의 세 별",
            category = PatternCategory.BULLISH_REVERSAL,
            defaultDirection = PatternDirection.BULL,
            candleCount = 3,
            description = "점차 작아지는 세 음봉. 하락 동력 소진 신호.",
        )

    val ADVANCE_BLOCK =
        CandlePattern(
            code = "advance_block",
            nameEn = "Advance Block",
            nameKo = "어드밴스 블록",
            category = PatternCategory.BEARISH_REVERSAL,
            defaultDirection = PatternDirection.BEAR,
            candleCount = 3,
            description = "세 양봉이지만 점점 짧아지고 윗꼬리 길어짐. 상승 동력 약화.",
        )

    val STALLED_PATTERN =
        CandlePattern(
            code = "stalled_pattern",
            nameEn = "Stalled Pattern",
            nameKo = "정체형",
            category = PatternCategory.BEARISH_REVERSAL,
            defaultDirection = PatternDirection.BEAR,
            candleCount = 3,
            description = "세 양봉 중 마지막이 매우 짧음. 상승 동력 정체.",
        )

    val TASUKI_GAP =
        CandlePattern(
            code = "tasuki_gap",
            nameEn = "Tasuki Gap",
            nameKo = "타스키 갭",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 3,
            description = "추세 갭 후 갭을 메우지 못하는 반대 색 캔들. 추세 지속.",
        )

    val UPSIDE_GAP_TWO_CROWS =
        CandlePattern(
            code = "upside_gap_two_crows",
            nameEn = "Upside Gap Two Crows",
            nameKo = "갭상승 두 까마귀",
            category = PatternCategory.BEARISH_REVERSAL,
            defaultDirection = PatternDirection.BEAR,
            candleCount = 3,
            description = "양봉 + 갭상승 음봉 + 더 큰 음봉. 약세 반전.",
        )

    val THREE_LINE_STRIKE =
        CandlePattern(
            code = "three_line_strike",
            nameEn = "Three Line Strike",
            nameKo = "삼선 스트라이크",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 4,
            description = "추세 3봉 + 반대 색 큰 캔들이 모두 되돌림. 추세 지속(역설적 신호).",
        )

    val UNIQUE_THREE_RIVER =
        CandlePattern(
            code = "unique_three_river",
            nameEn = "Unique Three River",
            nameKo = "유니크 세 강",
            category = PatternCategory.BULLISH_REVERSAL,
            defaultDirection = PatternDirection.BULL,
            candleCount = 3,
            description = "큰 음봉 + 해머형 음봉 + 작은 양봉. 희귀한 강세 반전.",
        )

    val BREAKAWAY =
        CandlePattern(
            code = "breakaway",
            nameEn = "Breakaway",
            nameKo = "브레이크어웨이",
            category = PatternCategory.BULLISH_REVERSAL,
            defaultDirection = PatternDirection.BULL,
            candleCount = 5,
            description = "5봉 형태 — 추세 끝의 갭 + 작은 봉들 후 반대 색 큰 봉.",
        )

    val MAT_HOLD =
        CandlePattern(
            code = "mat_hold",
            nameEn = "Mat Hold",
            nameKo = "매트홀드",
            category = PatternCategory.BULLISH_CONTINUATION,
            defaultDirection = PatternDirection.BULL,
            candleCount = 5,
            description = "양봉 + 갭상승 후 작은 음봉 3개 + 양봉 신고가. 강세 지속.",
        )

    val LADDER_BOTTOM =
        CandlePattern(
            code = "ladder_bottom",
            nameEn = "Ladder Bottom",
            nameKo = "사다리 바닥",
            category = PatternCategory.BULLISH_REVERSAL,
            defaultDirection = PatternDirection.BULL,
            candleCount = 5,
            description = "연속 4 음봉 후 갭상승 양봉. 강세 반전.",
        )

    val CONCEALING_BABY_SWALLOW =
        CandlePattern(
            code = "concealing_baby_swallow",
            nameEn = "Concealing Baby Swallow",
            nameKo = "은폐된 베이비 스왈로",
            category = PatternCategory.BULLISH_REVERSAL,
            defaultDirection = PatternDirection.BULL,
            candleCount = 4,
            description = "두 마루보주 음봉 + 갭하락 음봉 + 4번째 음봉이 3번째를 감쌈. 희귀.",
        )

    val STICK_SANDWICH =
        CandlePattern(
            code = "stick_sandwich",
            nameEn = "Stick Sandwich",
            nameKo = "스틱 샌드위치",
            category = PatternCategory.BULLISH_REVERSAL,
            defaultDirection = PatternDirection.BULL,
            candleCount = 3,
            description = "음봉 + 양봉 + 첫 음봉 종가와 동일한 종가의 음봉. 강세 반전.",
        )

    val TRISTAR =
        CandlePattern(
            code = "tristar",
            nameEn = "Tristar",
            nameKo = "트라이스타",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 3,
            description = "연속 3 도지. 강한 반전 신호 (희귀).",
        )

    val IDENTICAL_THREE_CROWS =
        CandlePattern(
            code = "identical_three_crows",
            nameEn = "Identical Three Crows",
            nameKo = "동일 세 까마귀",
            category = PatternCategory.BEARISH_REVERSAL,
            defaultDirection = PatternDirection.BEAR,
            candleCount = 3,
            description = "Three Black Crows 변형 — 두·세번째 시가가 직전 종가와 같음.",
        )

    val TWO_CROWS =
        CandlePattern(
            code = "two_crows",
            nameEn = "Two Crows",
            nameKo = "두 까마귀",
            category = PatternCategory.BEARISH_REVERSAL,
            defaultDirection = PatternDirection.BEAR,
            candleCount = 3,
            description = "양봉 + 갭상승 음봉 + 갭하락이지만 첫 양봉 안에 닫는 음봉.",
        )

    val UP_DOWN_GAP_THREE_METHODS =
        CandlePattern(
            code = "up_down_gap_three_methods",
            nameEn = "Up Down Gap Three Methods",
            nameKo = "갭 삼법",
            category = PatternCategory.REVERSAL_DIRECTIONAL,
            defaultDirection = PatternDirection.NEUTRAL,
            candleCount = 3,
            description = "추세 갭 후 반대 색 캔들이 갭 메움. 추세 지속.",
        )

    val DOWNSIDE_TASUKI_GAP =
        CandlePattern(
            code = "downside_tasuki_gap",
            nameEn = "Downside Tasuki Gap",
            nameKo = "하락 타스키 갭",
            category = PatternCategory.BEARISH_CONTINUATION,
            defaultDirection = PatternDirection.BEAR,
            candleCount = 3,
            description = "하락 갭 + 양봉이 갭을 메우지 못함. 하락 지속.",
        )

    val UPSIDE_TASUKI_GAP =
        CandlePattern(
            code = "upside_tasuki_gap",
            nameEn = "Upside Tasuki Gap",
            nameKo = "상승 타스키 갭",
            category = PatternCategory.BULLISH_CONTINUATION,
            defaultDirection = PatternDirection.BULL,
            candleCount = 3,
            description = "상승 갭 + 음봉이 갭을 메우지 못함. 상승 지속.",
        )

    /**
     * 57개 패턴 전체 목록. KIS Strategy Builder 정합 (Lean 미지원 9개 제외).
     * 카테고리 + 캔들수 정렬은 PatternRecognizer가 적용한다 (단순 sliding window 효율).
     */
    val ALL: List<CandlePattern> =
        listOf(
            // Single (14)
            DOJI,
            DRAGONFLY_DOJI,
            GRAVESTONE_DOJI,
            LONG_LEGGED_DOJI,
            HAMMER,
            HANGING_MAN,
            INVERTED_HAMMER,
            SHOOTING_STAR,
            MARUBOZU,
            CLOSING_MARUBOZU,
            SPINNING_TOP,
            BELT_HOLD,
            HIGH_WAVE,
            RICKSHAW_MAN,
            // Double (16)
            ENGULFING,
            HARAMI,
            HARAMI_CROSS,
            PIERCING,
            DARK_CLOUD_COVER,
            COUNTERATTACK,
            ON_NECK,
            IN_NECK,
            THRUSTING,
            SEPARATING_LINES,
            KICKING,
            KICKING_BY_LENGTH,
            MATCHING_LOW,
            GAP_SIDE_BY_SIDE_WHITE,
            HOMING_PIGEON,
            DOJI_STAR,
            // Triple+ (27)
            MORNING_STAR,
            MORNING_DOJI_STAR,
            EVENING_STAR,
            EVENING_DOJI_STAR,
            THREE_WHITE_SOLDIERS,
            THREE_BLACK_CROWS,
            THREE_INSIDE,
            THREE_OUTSIDE,
            ABANDONED_BABY,
            THREE_STARS_IN_SOUTH,
            ADVANCE_BLOCK,
            STALLED_PATTERN,
            TASUKI_GAP,
            UPSIDE_GAP_TWO_CROWS,
            THREE_LINE_STRIKE,
            UNIQUE_THREE_RIVER,
            BREAKAWAY,
            MAT_HOLD,
            LADDER_BOTTOM,
            CONCEALING_BABY_SWALLOW,
            STICK_SANDWICH,
            TRISTAR,
            IDENTICAL_THREE_CROWS,
            TWO_CROWS,
            UP_DOWN_GAP_THREE_METHODS,
            DOWNSIDE_TASUKI_GAP,
            UPSIDE_TASUKI_GAP,
        )

    init {
        check(ALL.size == 57) { "PatternCatalog must have exactly 57 patterns, got ${ALL.size}" }
        val codes = ALL.map { it.code }
        check(codes.distinct().size == codes.size) {
            "duplicate pattern codes: ${codes.groupingBy { it }.eachCount().filter { it.value > 1 }}"
        }
    }

    private val byCode: Map<String, CandlePattern> = ALL.associateBy { it.code }

    fun findByCode(code: String): CandlePattern? = byCode[code]

    fun byCategory(category: PatternCategory): List<CandlePattern> = ALL.filter { it.category == category }

    fun byCandleCount(count: Int): List<CandlePattern> = ALL.filter { it.candleCount == count }
}
