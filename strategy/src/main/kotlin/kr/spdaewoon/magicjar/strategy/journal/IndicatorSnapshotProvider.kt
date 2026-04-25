package kr.spdaewoon.magicjar.strategy.journal

import kr.spdaewoon.magicjar.core.domain.Candle
import kr.spdaewoon.magicjar.strategy.indicator.Macd
import kr.spdaewoon.magicjar.strategy.indicator.Rsi
import kr.spdaewoon.magicjar.strategy.indicator.Sma
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 매매일지 스냅샷용 지표 계산기. 주문 시점의 캔들을 받아 SMA20/SMA60/RSI14/MACD를 채워 반환한다.
 *
 * 캔들 부족 등으로 미정의된 항목은 결과 맵에서 제외(또는 0).
 */
@Component
class IndicatorSnapshotProvider {
    fun snapshot(candles: List<Candle>): Map<String, BigDecimal> {
        if (candles.isEmpty()) return emptyMap()
        val out = HashMap<String, BigDecimal>()
        val lastIdx = candles.size - 1
        out["close"] = candles[lastIdx].close
        out["volume"] = BigDecimal(candles[lastIdx].volume)
        Sma.ofClose(candles, 20).getOrNull(lastIdx)?.let { out["sma20"] = it }
        Sma.ofClose(candles, 60).getOrNull(lastIdx)?.let { out["sma60"] = it }
        Rsi.ofClose(candles, 14).getOrNull(lastIdx)?.let { out["rsi14"] = it }
        val macd = Macd.of(candles.map { it.close })
        macd.macd.getOrNull(lastIdx)?.let { out["macd"] = it }
        macd.signal.getOrNull(lastIdx)?.let { out["macd_signal"] = it }
        macd.histogram.getOrNull(lastIdx)?.let { out["macd_hist"] = it }
        return out
    }
}
