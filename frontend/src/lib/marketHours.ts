/**
 * 한국 주식시장 거래 시간 헬퍼
 *
 * KRX 장 시간: 평일 09:00~15:30 KST
 * 주말(토·일) 및 평일 장외 시간은 false.
 * 공휴일은 별도 API(T-MS1 MarketCalendar)와 연동 가능하나
 * 이 헬퍼는 클라이언트 독립 판정용 — 시간대 기반 fast path만 구현.
 */

/** 주어진 시각이 한국 주식시장 거래 시간(평일 09:00~15:30 KST)인지 판정 */
export function isKoreanMarketOpen(now: Date = new Date()): boolean {
  // KST = UTC+9
  const kstMs = now.getTime() + 9 * 60 * 60 * 1000
  const kstDate = new Date(kstMs)

  // 요일 (UTC 기준이지만 +9시간 보정된 날짜이므로 KST 요일과 동일)
  const dayOfWeek = kstDate.getUTCDay() // 0=일, 1=월 ... 6=토
  if (dayOfWeek === 0 || dayOfWeek === 6) return false // 주말

  const hours = kstDate.getUTCHours()
  const minutes = kstDate.getUTCMinutes()
  const totalMinutes = hours * 60 + minutes

  // 09:00 = 540분, 15:30 = 930분
  return totalMinutes >= 540 && totalMinutes < 930
}

/**
 * asOf ISO 문자열이 30초 이내(실시간 갱신 기준)인지 판정
 * @param asOf ISO 8601 문자열
 * @param now 현재 시각 (기본: Date.now())
 */
export function isWithin30Seconds(asOf: string, now: Date = new Date()): boolean {
  if (!asOf) return false
  const asOfMs = new Date(asOf).getTime()
  if (isNaN(asOfMs)) return false
  return now.getTime() - asOfMs <= 30_000
}

/**
 * 현재 가격 소스 + 시각을 바탕으로 표시 배지 종류 결정
 *
 * 반환값:
 *   'live'    — asOf가 30초 이내 (실시간, 녹색)
 *   'delayed' — polling + 장 시간 중 (지연, 노랑)
 *   'closed'  — polling + 장외 시간 or source='closed' (장 마감, 회색)
 */
export type PriceBadgeType = 'live' | 'delayed' | 'closed'

export function getPriceBadge(
  source: string,
  asOf: string,
  now: Date = new Date(),
): PriceBadgeType {
  if (isWithin30Seconds(asOf, now)) return 'live'
  if (source === 'closed') return 'closed'
  if (source === 'polling' && !isKoreanMarketOpen(now)) return 'closed'
  if (source === 'polling' && isKoreanMarketOpen(now)) return 'delayed'
  // 'live' source이지만 30초 초과 → 지연으로 처리
  return 'delayed'
}
