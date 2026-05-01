/**
 * KRX(한국거래소) 정규장 시간 판정.
 *
 * 정규장: 09:00 ~ 15:30 KST (월~금)
 * 동시호가: 08:00~09:00 (장전), 15:20~15:30 (장마감) — 본 모듈은 정규장만 표기
 * 시간외 단일가: 16:00~18:00 — 본 모듈은 OPEN으로 보지 않음 (별도 표기)
 *
 * 휴일 캘린더는 hardcoded 정해진 일자 + 요일 기반. 실 운영은 백엔드 endpoint
 * `/api/market/status`에서 한국거래소 공식 캘린더와 동기화 권장 (다음 PR).
 */

export type MarketStatus =
  | { state: 'OPEN'; closeAt: Date }                 // 정규장
  | { state: 'PRE_OPEN'; opensAt: Date }              // 09:00 전, 같은 영업일
  | { state: 'AFTER_HOURS'; nextOpenAt: Date }        // 평일 15:30 이후 (당일 마감)
  | { state: 'WEEKEND'; nextOpenAt: Date }            // 토·일
  | { state: 'HOLIDAY'; nextOpenAt: Date; reason?: string } // 공휴일

const KST_OFFSET_MIN = 9 * 60 // KST = UTC+9

/** 2026 한국 증시 휴일 (공식 KRX 캘린더 기준 — 운영 시 백엔드에서 동기화). */
const HOLIDAYS_2026: Record<string, string> = {
  '2026-01-01': '신정',
  '2026-02-16': '설날',
  '2026-02-17': '설날',
  '2026-02-18': '설날',
  '2026-03-01': '삼일절(주말 대체 안 함)', // 일요일
  '2026-03-02': '삼일절 대체',
  '2026-04-15': '국회의원선거',
  '2026-05-01': '근로자의 날',
  '2026-05-05': '어린이날',
  '2026-05-25': '석가탄신일',
  '2026-06-03': '대통령선거', // 가상
  '2026-06-06': '현충일',
  '2026-08-15': '광복절(토)',
  '2026-09-24': '추석',
  '2026-09-25': '추석',
  '2026-09-26': '추석(토)',
  '2026-10-03': '개천절(토)',
  '2026-10-09': '한글날',
  '2026-12-25': '성탄절',
  '2026-12-31': '연말 휴장',
}

/** Date를 KST 기준 'YYYY-MM-DD' 문자열로 변환. */
function toKstDateString(d: Date): string {
  const kst = new Date(d.getTime() + KST_OFFSET_MIN * 60_000)
  const y = kst.getUTCFullYear()
  const m = String(kst.getUTCMonth() + 1).padStart(2, '0')
  const day = String(kst.getUTCDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** Date의 KST 기준 요일 (0=일, 6=토). */
function kstDayOfWeek(d: Date): number {
  return new Date(d.getTime() + KST_OFFSET_MIN * 60_000).getUTCDay()
}

/** Date의 KST 기준 (시, 분). */
function kstHourMinute(d: Date): { hour: number; minute: number } {
  const kst = new Date(d.getTime() + KST_OFFSET_MIN * 60_000)
  return { hour: kst.getUTCHours(), minute: kst.getUTCMinutes() }
}

/** KST 'YYYY-MM-DD HH:mm:ss' → UTC Date 객체. */
function kstToDate(dateStr: string, hh: number, mm: number): Date {
  // dateStr은 KST 기준 날짜. UTC로 변환: KST - 9h.
  return new Date(`${dateStr}T${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}:00+09:00`)
}

/** 다음 영업일(KST) 'YYYY-MM-DD'. now 다음 날부터 시작해 평일+비휴일 첫날. */
function nextBusinessDay(now: Date): string {
  const d = new Date(now)
  for (let i = 1; i <= 14; i++) {
    d.setUTCDate(d.getUTCDate() + 1)
    const dow = kstDayOfWeek(d)
    const ds = toKstDateString(d)
    if (dow !== 0 && dow !== 6 && !HOLIDAYS_2026[ds]) return ds
  }
  // fallback — 14일 안에 못 찾으면 그냥 다음날
  d.setUTCDate(d.getUTCDate() + 1)
  return toKstDateString(d)
}

/** 현재 시각을 받아 KRX 시장 상태 판정. */
export function getMarketStatus(now: Date = new Date()): MarketStatus {
  const dow = kstDayOfWeek(now)
  const today = toKstDateString(now)
  const holiday = HOLIDAYS_2026[today]
  const { hour, minute } = kstHourMinute(now)
  const minutesOfDay = hour * 60 + minute

  // 주말
  if (dow === 0 || dow === 6) {
    return {
      state: 'WEEKEND',
      nextOpenAt: kstToDate(nextBusinessDay(now), 9, 0),
    }
  }

  // 휴일
  if (holiday) {
    return {
      state: 'HOLIDAY',
      nextOpenAt: kstToDate(nextBusinessDay(now), 9, 0),
      reason: holiday,
    }
  }

  // 평일
  const OPEN_MIN = 9 * 60      // 09:00
  const CLOSE_MIN = 15 * 60 + 30 // 15:30

  if (minutesOfDay < OPEN_MIN) {
    return {
      state: 'PRE_OPEN',
      opensAt: kstToDate(today, 9, 0),
    }
  }
  if (minutesOfDay >= CLOSE_MIN) {
    return {
      state: 'AFTER_HOURS',
      nextOpenAt: kstToDate(nextBusinessDay(now), 9, 0),
    }
  }
  return {
    state: 'OPEN',
    closeAt: kstToDate(today, 15, 30),
  }
}

/** 'OPEN' 여부만 빠르게 확인. */
export function isMarketOpen(now: Date = new Date()): boolean {
  return getMarketStatus(now).state === 'OPEN'
}

/** 사용자 표시용 짧은 라벨 (10자 이내). */
export function marketStatusLabel(s: MarketStatus): string {
  switch (s.state) {
    case 'OPEN':
      return '장중'
    case 'PRE_OPEN':
      return '장 시작 전'
    case 'AFTER_HOURS':
      return '장 마감'
    case 'WEEKEND':
      return '주말 휴장'
    case 'HOLIDAY':
      return s.reason ? `휴장 (${s.reason})` : '휴장'
  }
}

/** 사용자 표시용 보조 문구. */
export function marketStatusDetail(s: MarketStatus): string {
  const fmt = new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
  switch (s.state) {
    case 'OPEN':
      return `15:30까지 (${fmt.format(s.closeAt)} 마감)`
    case 'PRE_OPEN':
      return `${fmt.format(s.opensAt)} 개장`
    case 'AFTER_HOURS':
    case 'WEEKEND':
    case 'HOLIDAY':
      return `다음 개장 ${fmt.format(s.nextOpenAt)}`
  }
}
