package kr.spdaewoon.magicjar.domain.backtest

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 백테스트 실행 결과 (ADR 0056 §2-1 LOCK).
 *
 * sealed class — Success · Aborted 두 상태만 허용.
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지.
 */
sealed class BacktestResult {
    /**
     * 백테스트 정상 완료.
     *
     * @param request 원본 요청
     * @param trades 전체 매매 기록
     * @param positions 전체 포지션 기록 (종료 + 미종료)
     * @param finalCapital 최종 자본 (KRW)
     * @param performance 성과 지표
     * @param executedAt 실행 완료 시각
     * @param durationMs 소요 시간 (ms)
     */
    data class Success(
        val request: BacktestRequest,
        val trades: List<BacktestTrade>,
        val positions: List<BacktestPosition>,
        val finalCapital: BigDecimal,
        val performance: BacktestPerformance,
        val executedAt: Instant,
        val durationMs: Long,
    ) : BacktestResult()

    /**
     * 백테스트 중단 — timeout 누적 50회 초과 또는 평가 오류 (ADR 0047 §2-5 + ADR 0056 §2-5).
     *
     * 부분 매매 기록(partialTrades)은 보존 — 사용자에게 중단 시점까지 결과 제공.
     *
     * @param request 원본 요청
     * @param partialTrades 중단 시점까지의 매매 기록
     * @param reason 중단 사유 ("TIMEOUT_QUOTA_EXCEEDED" · "EVALUATION_ERROR" · "NO_CANDLE_DATA")
     * @param timeoutCount 누적 timeout 횟수
     * @param executedAt 중단 시각
     * @param durationMs 중단까지 소요 시간 (ms)
     */
    data class Aborted(
        val request: BacktestRequest,
        val partialTrades: List<BacktestTrade>,
        val reason: String,
        val timeoutCount: Int,
        val executedAt: Instant,
        val durationMs: Long,
    ) : BacktestResult()
}
