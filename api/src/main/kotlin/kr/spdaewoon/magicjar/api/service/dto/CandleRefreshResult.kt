package kr.spdaewoon.magicjar.api.service.dto

/**
 * 캔들 수동 갱신 endpoint 응답 DTO.
 *
 * POST /api/symbols/{code}/candles/refresh 응답.
 * refreshedIntervals: 각 interval 별 fetch/insert 결과 목록.
 * elapsedMs: 전체 처리 시간 (ms).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
data class CandleRefreshResponse(
    val refreshedIntervals: List<IntervalRefreshResult>,
    val elapsedMs: Long,
)

/**
 * interval 별 갱신 결과.
 *
 * @param interval 인터벌 코드 (예: "1d", "1wk", "1mo", "1y", "1m")
 * @param fetchedCount KIS 응답 건수
 * @param insertedCount ON CONFLICT DO NOTHING 후 실제 INSERT 건수
 * @param latestTs DB 최신 ts ISO-8601 문자열 (null = 데이터 없음)
 * @param status "OK" | "SKIPPED" | "FAILED"
 */
data class IntervalRefreshResult(
    val interval: String,
    val fetchedCount: Int,
    val insertedCount: Int,
    val latestTs: String?,
    val status: String,
)
