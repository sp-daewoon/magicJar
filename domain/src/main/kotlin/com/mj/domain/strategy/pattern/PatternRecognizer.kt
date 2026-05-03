package com.mj.domain.strategy.pattern

import com.mj.domain.common.Candle
import com.mj.domain.strategy.pattern.catalog.PatternCatalog
import java.math.BigDecimal

/**
 * 캔들스틱 패턴 인식기 (순수 도메인, 상태 없음).
 *
 * 입력: 시간 오름차순 정렬된 Candle 리스트 (같은 종목·인터벌)
 * 출력: 발견된 PatternMatch 리스트. 같은 endIndex에서 여러 패턴이 매칭될 수 있다 (예: 도지 + 잉걸핑).
 *
 * R1 범위 — 대표 10개 패턴 정확 매칭. 나머지 47개는 카탈로그에는 있되 매칭 함수가 빈 리스트 반환.
 * R2에서 단계적 보강.
 *
 * 알고리즘:
 *  - 각 패턴별로 `pattern.candleCount` 크기 sliding window를 끝에서 lookback 만큼 훑음
 *  - 매칭 시 PatternMatch 누적
 *  - 강도(strength)는 0~1 범위, 명확한 형태 1.0, 경계 케이스 ~0.5
 *
 * BigDecimal 사용 (코드 컨벤션 §4.1). Double 사용 금지.
 */
object PatternRecognizer {
    /**
     * 캔들 리스트에서 직전 [lookback] 봉 구간의 모든 패턴 매칭 반환.
     *
     * @param candles  시간 오름차순 캔들. 같은 code+interval 가정
     * @param lookback 끝에서 거꾸로 검사할 캔들 수. 기본 50봉
     * @return 매칭 리스트 (endIndex 오름차순, 같은 endIndex에서는 카탈로그 순서)
     */
    fun recognize(
        candles: List<Candle>,
        lookback: Int = 50,
    ): List<PatternMatch> {
        require(lookback > 0) { "lookback must be positive: $lookback" }
        if (candles.isEmpty()) return emptyList()
        val startIdx = maxOf(0, candles.size - lookback)
        val out = mutableListOf<PatternMatch>()
        for (endIdx in startIdx until candles.size) {
            for (pattern in PatternCatalog.ALL) {
                if (endIdx - pattern.candleCount + 1 < 0) continue
                val match = match(pattern, candles, endIdx)
                if (match != null) out += match
            }
        }
        return out
    }

    /**
     * 특정 endIndex에서 특정 패턴이 매칭되는지 검사.
     *
     * 외부 노출 — REST 엔드포인트가 전체 카탈로그 대신 1개 패턴만 검사할 수 있도록.
     */
    fun match(
        pattern: CandlePattern,
        candles: List<Candle>,
        endIndex: Int,
    ): PatternMatch? {
        if (endIndex < 0 || endIndex >= candles.size) return null
        if (endIndex - pattern.candleCount + 1 < 0) return null
        return when (pattern.code) {
            "doji" -> matchDoji(candles, endIndex)
            "hammer" -> matchHammer(candles, endIndex)
            "hanging_man" -> matchHangingMan(candles, endIndex)
            "inverted_hammer" -> matchInvertedHammer(candles, endIndex)
            "shooting_star" -> matchShootingStar(candles, endIndex)
            "engulfing" -> matchEngulfing(candles, endIndex)
            "morning_star" -> matchMorningStar(candles, endIndex)
            "evening_star" -> matchEveningStar(candles, endIndex)
            "three_white_soldiers" -> matchThreeWhiteSoldiers(candles, endIndex)
            "three_black_crows" -> matchThreeBlackCrows(candles, endIndex)
            else -> null // R2 보강 대상 (47개 stub)
        }
    }

    // ----------------------------------------------------------------------
    // 1. Doji (1캔들·INDECISION)
    //   |close - open| <= 10% * (high - low)
    // ----------------------------------------------------------------------
    private fun matchDoji(
        candles: List<Candle>,
        endIndex: Int,
    ): PatternMatch? {
        val c = candles[endIndex]
        val range = CandleShape.range(c)
        if (range.signum() <= 0) return null // 평탄 봉은 도지로 보지 않음 (volume 0 캔들 등)
        if (!CandleShape.isDoji(c, epsilon = 0.10)) return null
        val ratio = CandleShape.bodyRatio(c).toDouble()
        // 몸통이 작을수록 강도↑. ratio 0=완벽 도지, ratio 0.10=경계
        val strength = (1.0 - (ratio / 0.10)).coerceIn(0.0, 1.0)
        return PatternMatch.of(
            pattern = PatternCatalog.DOJI,
            direction = PatternDirection.NEUTRAL,
            candles = candles,
            endIndex = endIndex,
            strength = strength,
        )
    }

    // ----------------------------------------------------------------------
    // 2. Hammer (1캔들·BULLISH_REVERSAL)
    //   - 작은 몸통 (range의 30% 이하)
    //   - 아랫꼬리 >= 2 * 몸통
    //   - 윗꼬리 <= 0.3 * 몸통 (또는 윗꼬리 < range의 10%)
    //   - 직전 추세 검증은 Hanging Man과 분리해서 단독 형태만으로도 매칭 (KIS 정합)
    // ----------------------------------------------------------------------
    private fun matchHammer(
        candles: List<Candle>,
        endIndex: Int,
    ): PatternMatch? {
        val c = candles[endIndex]
        if (!hasHammerShape(c)) return null
        // 직전 추세가 하락이면 강도↑, 아니면 0.6 (형태 정합만)
        val downTrend = isDowntrend(candles, endIndex, lookback = 5)
        val strength = if (downTrend) 1.0 else 0.6
        return PatternMatch.of(
            pattern = PatternCatalog.HAMMER,
            direction = PatternDirection.BULL,
            candles = candles,
            endIndex = endIndex,
            strength = strength,
        )
    }

    // ----------------------------------------------------------------------
    // 3. Hanging Man (1캔들·BEARISH_REVERSAL)
    //   - Hammer와 동일 형태
    //   - 직전 추세가 상승이어야 함 (필수 조건)
    // ----------------------------------------------------------------------
    private fun matchHangingMan(
        candles: List<Candle>,
        endIndex: Int,
    ): PatternMatch? {
        val c = candles[endIndex]
        if (!hasHammerShape(c)) return null
        if (!isUptrend(candles, endIndex, lookback = 5)) return null
        return PatternMatch.of(
            pattern = PatternCatalog.HANGING_MAN,
            direction = PatternDirection.BEAR,
            candles = candles,
            endIndex = endIndex,
            strength = 0.85,
        )
    }

    // ----------------------------------------------------------------------
    // 4. Inverted Hammer (1캔들·BULLISH_REVERSAL)
    //   - 윗꼬리 >= 2 * 몸통, 아랫꼬리 <= 0.3 * 몸통
    //   - 단독 형태로도 매칭, 하락 추세시 강도↑
    // ----------------------------------------------------------------------
    private fun matchInvertedHammer(
        candles: List<Candle>,
        endIndex: Int,
    ): PatternMatch? {
        val c = candles[endIndex]
        if (!hasInvertedHammerShape(c)) return null
        val downTrend = isDowntrend(candles, endIndex, lookback = 5)
        val strength = if (downTrend) 1.0 else 0.6
        return PatternMatch.of(
            pattern = PatternCatalog.INVERTED_HAMMER,
            direction = PatternDirection.BULL,
            candles = candles,
            endIndex = endIndex,
            strength = strength,
        )
    }

    // ----------------------------------------------------------------------
    // 5. Shooting Star (1캔들·BEARISH_REVERSAL)
    //   - Inverted Hammer 동일 형태 + 직전 상승 추세 필수
    // ----------------------------------------------------------------------
    private fun matchShootingStar(
        candles: List<Candle>,
        endIndex: Int,
    ): PatternMatch? {
        val c = candles[endIndex]
        if (!hasInvertedHammerShape(c)) return null
        if (!isUptrend(candles, endIndex, lookback = 5)) return null
        return PatternMatch.of(
            pattern = PatternCatalog.SHOOTING_STAR,
            direction = PatternDirection.BEAR,
            candles = candles,
            endIndex = endIndex,
            strength = 0.85,
        )
    }

    // ----------------------------------------------------------------------
    // 6. Engulfing (2캔들·REVERSAL_DIRECTIONAL)
    //   - 두 캔들 몸통 색이 반대
    //   - 두번째 몸통이 첫번째 몸통을 완전히 포함
    //   - 양봉 잉걸핑 = BULL, 음봉 잉걸핑 = BEAR
    // ----------------------------------------------------------------------
    private fun matchEngulfing(
        candles: List<Candle>,
        endIndex: Int,
    ): PatternMatch? {
        val prev = candles[endIndex - 1]
        val curr = candles[endIndex]
        val prevBull = CandleShape.isBullBody(prev)
        val prevBear = CandleShape.isBearBody(prev)
        val currBull = CandleShape.isBullBody(curr)
        val currBear = CandleShape.isBearBody(curr)
        if (!(prevBull || prevBear) || !(currBull || currBear)) return null
        if (prevBull == currBull) return null // 같은 색이면 X
        if (!CandleShape.bodyEngulfs(curr, prev)) return null

        val direction = if (currBull) PatternDirection.BULL else PatternDirection.BEAR
        // 강도: 두번째 몸통이 첫번째 대비 얼마나 큰지
        val ratio =
            if (CandleShape.bodyAbs(prev).signum() == 0) {
                BigDecimal.ONE
            } else {
                CandleShape.bodyAbs(curr).divide(CandleShape.bodyAbs(prev), CandleShape.MC)
            }
        val strength = (ratio.toDouble() / 2.0).coerceIn(0.5, 1.0)
        return PatternMatch.of(
            pattern = PatternCatalog.ENGULFING,
            direction = direction,
            candles = candles,
            endIndex = endIndex,
            strength = strength,
        )
    }

    // ----------------------------------------------------------------------
    // 7. Morning Star (3캔들·BULLISH_REVERSAL)
    //   - 1봉: 큰 음봉 (long body, bear)
    //   - 2봉: 갭다운 작은 봉 (몸통이 1봉의 30% 이하)
    //   - 3봉: 양봉, 1봉 몸통 절반 이상 회복 (close > 1봉 mid)
    // ----------------------------------------------------------------------
    private fun matchMorningStar(
        candles: List<Candle>,
        endIndex: Int,
    ): PatternMatch? {
        val c1 = candles[endIndex - 2]
        val c2 = candles[endIndex - 1]
        val c3 = candles[endIndex]
        if (!CandleShape.isBearBody(c1) || !CandleShape.isLongBody(c1)) return null
        // 2봉: 작은 몸통 + 1봉보다 아래에 위치 (갭다운)
        if (CandleShape.bodyAbs(c2) > CandleShape.bodyAbs(c1).multiply(BigDecimal("0.3"), CandleShape.MC)) return null
        val c2BodyHigh = maxOf(c2.open, c2.close)
        if (c2BodyHigh >= c1.close) return null // 갭다운 (2봉 몸통 위가 1봉 종가 아래)
        // 3봉: 양봉, 1봉 종가 → 시가 절반(=1봉 몸통 mid) 이상
        if (!CandleShape.isBullBody(c3)) return null
        if (c3.close <= CandleShape.bodyMid(c1)) return null
        return PatternMatch.of(
            pattern = PatternCatalog.MORNING_STAR,
            direction = PatternDirection.BULL,
            candles = candles,
            endIndex = endIndex,
            strength = 0.95,
        )
    }

    // ----------------------------------------------------------------------
    // 8. Evening Star (3캔들·BEARISH_REVERSAL)
    //   - 1봉: 큰 양봉
    //   - 2봉: 갭업 작은 봉
    //   - 3봉: 음봉, 1봉 몸통 절반 이상 잠식
    // ----------------------------------------------------------------------
    private fun matchEveningStar(
        candles: List<Candle>,
        endIndex: Int,
    ): PatternMatch? {
        val c1 = candles[endIndex - 2]
        val c2 = candles[endIndex - 1]
        val c3 = candles[endIndex]
        if (!CandleShape.isBullBody(c1) || !CandleShape.isLongBody(c1)) return null
        if (CandleShape.bodyAbs(c2) > CandleShape.bodyAbs(c1).multiply(BigDecimal("0.3"), CandleShape.MC)) return null
        val c2BodyLow = minOf(c2.open, c2.close)
        if (c2BodyLow <= c1.close) return null // 갭업
        if (!CandleShape.isBearBody(c3)) return null
        if (c3.close >= CandleShape.bodyMid(c1)) return null
        return PatternMatch.of(
            pattern = PatternCatalog.EVENING_STAR,
            direction = PatternDirection.BEAR,
            candles = candles,
            endIndex = endIndex,
            strength = 0.95,
        )
    }

    // ----------------------------------------------------------------------
    // 9. Three White Soldiers (3캔들·BULLISH_REVERSAL)
    //   - 연속 3 양봉
    //   - 각 봉 시가가 직전 봉 몸통 안 (직전 open ≤ open ≤ 직전 close)
    //   - 종가가 직전 종가보다 높음
    // ----------------------------------------------------------------------
    private fun matchThreeWhiteSoldiers(
        candles: List<Candle>,
        endIndex: Int,
    ): PatternMatch? {
        val c1 = candles[endIndex - 2]
        val c2 = candles[endIndex - 1]
        val c3 = candles[endIndex]
        if (!CandleShape.isBullBody(c1) || !CandleShape.isBullBody(c2) || !CandleShape.isBullBody(c3)) return null
        if (c2.open < c1.open || c2.open > c1.close) return null
        if (c3.open < c2.open || c3.open > c2.close) return null
        if (c2.close <= c1.close || c3.close <= c2.close) return null
        return PatternMatch.of(
            pattern = PatternCatalog.THREE_WHITE_SOLDIERS,
            direction = PatternDirection.BULL,
            candles = candles,
            endIndex = endIndex,
            strength = 1.0,
        )
    }

    // ----------------------------------------------------------------------
    // 10. Three Black Crows (3캔들·BEARISH_REVERSAL)
    //   - 연속 3 음봉
    //   - 각 봉 시가가 직전 봉 몸통 안
    //   - 종가가 직전 종가보다 낮음
    // ----------------------------------------------------------------------
    private fun matchThreeBlackCrows(
        candles: List<Candle>,
        endIndex: Int,
    ): PatternMatch? {
        val c1 = candles[endIndex - 2]
        val c2 = candles[endIndex - 1]
        val c3 = candles[endIndex]
        if (!CandleShape.isBearBody(c1) || !CandleShape.isBearBody(c2) || !CandleShape.isBearBody(c3)) return null
        // c2 open: c1 close ≤ open ≤ c1 open (음봉이라 close < open)
        if (c2.open > c1.open || c2.open < c1.close) return null
        if (c3.open > c2.open || c3.open < c2.close) return null
        if (c2.close >= c1.close || c3.close >= c2.close) return null
        return PatternMatch.of(
            pattern = PatternCatalog.THREE_BLACK_CROWS,
            direction = PatternDirection.BEAR,
            candles = candles,
            endIndex = endIndex,
            strength = 1.0,
        )
    }

    // ======================================================================
    // 헬퍼 — 형태/추세 판정
    // ======================================================================

    private fun hasHammerShape(c: Candle): Boolean {
        val body = CandleShape.bodyAbs(c)
        if (body.signum() <= 0) return false
        val range = CandleShape.range(c)
        if (range.signum() <= 0) return false
        // 작은 몸통 (range의 30% 이하)
        if (body.compareTo(range.multiply(BigDecimal("0.3"), CandleShape.MC)) > 0) return false
        val lower = CandleShape.lowerShadow(c)
        val upper = CandleShape.upperShadow(c)
        // 아랫꼬리 ≥ 2 * 몸통
        if (lower.compareTo(body.multiply(BigDecimal("2"), CandleShape.MC)) < 0) return false
        // 윗꼬리는 작아야 함 (몸통의 30% 이하 OR range의 10% 이하)
        val upperOk =
            upper.compareTo(body.multiply(BigDecimal("0.3"), CandleShape.MC)) <= 0 ||
                upper.compareTo(range.multiply(BigDecimal("0.10"), CandleShape.MC)) <= 0
        return upperOk
    }

    private fun hasInvertedHammerShape(c: Candle): Boolean {
        val body = CandleShape.bodyAbs(c)
        if (body.signum() <= 0) return false
        val range = CandleShape.range(c)
        if (range.signum() <= 0) return false
        if (body.compareTo(range.multiply(BigDecimal("0.3"), CandleShape.MC)) > 0) return false
        val lower = CandleShape.lowerShadow(c)
        val upper = CandleShape.upperShadow(c)
        if (upper.compareTo(body.multiply(BigDecimal("2"), CandleShape.MC)) < 0) return false
        val lowerOk =
            lower.compareTo(body.multiply(BigDecimal("0.3"), CandleShape.MC)) <= 0 ||
                lower.compareTo(range.multiply(BigDecimal("0.10"), CandleShape.MC)) <= 0
        return lowerOk
    }

    /**
     * 직전 lookback 봉 종가 추세가 하락인지 (단순 종가 비교).
     * endIndex 캔들 자체는 제외, [endIndex - lookback, endIndex - 1] 구간에서 첫 종가 > 끝 종가면 하락.
     * 데이터 부족 시 false (단독 형태로 매칭하되 강도만 낮춤).
     */
    private fun isDowntrend(
        candles: List<Candle>,
        endIndex: Int,
        lookback: Int,
    ): Boolean {
        if (endIndex < lookback) return false
        val first = candles[endIndex - lookback].close
        val last = candles[endIndex - 1].close
        return first > last
    }

    private fun isUptrend(
        candles: List<Candle>,
        endIndex: Int,
        lookback: Int,
    ): Boolean {
        if (endIndex < lookback) return false
        val first = candles[endIndex - lookback].close
        val last = candles[endIndex - 1].close
        return first < last
    }
}
