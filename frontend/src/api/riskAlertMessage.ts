import type { RiskEventDto } from './risk'

export function buildRiskToastMessage(event: RiskEventDto): string {
  const prefix = event.verdict === 'AUTO_PAUSED' ? '[자동 정지]' : event.verdict === 'BLOCKED' ? '[차단]' : `[${event.verdict}]`
  const title = event.alertTitle ?? fallbackTitle(event.rule)
  const message = event.alertMessage ?? fallbackMessage(event)
  const formula = event.alertFormula ?? fallbackFormula(event)

  return [title ? `${prefix} ${title}` : `${prefix} ${event.rule}`, message, formula ? `수식: ${formula}` : null]
    .filter(Boolean)
    .join('\n')
}

function fallbackTitle(rule: string): string {
  switch (rule) {
    case 'POSITION_LIMIT':
      return 'A. 포지션 한도 초과'
    case 'DAILY_LOSS_LIMIT':
      return 'B. 일일 손실 한도 초과'
    case 'MAX_CONCURRENT_POSITIONS':
      return 'C. 동시 보유 종목 한도 도달'
    case 'TRADE_FREQUENCY_MINUTE':
      return 'D. 거래 빈도 한도 도달 - 분당 최대 거래'
    case 'TRADE_FREQUENCY_DAY':
      return 'D. 거래 빈도 한도 도달 - 일별 최대 거래'
    case 'KILL_SWITCH_ON':
      return 'KillSwitch ON'
    default:
      return rule || '리스크 차단'
  }
}

function fallbackMessage(event: RiskEventDto): string {
  switch (event.rule) {
    case 'TRADE_FREQUENCY_DAY':
      return '오늘 이 전략에서 허용된 주문 횟수를 모두 사용해 추가 자동 주문을 차단했습니다.'
    case 'TRADE_FREQUENCY_MINUTE':
      return '최근 1분 안에 허용된 주문 횟수를 모두 사용해 추가 자동 주문을 차단했습니다.'
    case 'POSITION_LIMIT':
      return '종목당 최대 투자금액을 넘어서 자동 주문을 내보내지 않았습니다.'
    case 'MAX_CONCURRENT_POSITIONS':
      return '최대 보유 종목 수를 이미 채워서 추가 매수 주문을 내보내지 않았습니다.'
    case 'DAILY_LOSS_LIMIT':
      return '오늘 누적 손실이 설정한 DLL 한도에 도달해 신규 자동 주문을 내보내지 않았습니다.'
    default:
      return event.reason
  }
}

function fallbackFormula(event: RiskEventDto): string | null {
  const raw = event.rawReason ?? event.reason
  if (event.rule === 'TRADE_FREQUENCY_DAY') {
    const parsed = /Strategy daily trade count (\d+) >= limit (\d+)/.exec(raw)
    if (parsed) return `오늘 허용 주문 수 ${parsed[1]}회 >= 일별 최대 거래 ${parsed[2]}회/일`
  }
  if (event.rule === 'TRADE_FREQUENCY_MINUTE') {
    const parsed = /Strategy minute trade count (\d+) >= limit (\d+)/.exec(raw)
    if (parsed) return `최근 1분 허용 주문 수 ${parsed[1]}회 >= 분당 최대 거래 ${parsed[2]}회/분`
  }
  if (event.rule === 'MAX_CONCURRENT_POSITIONS') {
    const parsed = /Concurrent positions (\d+) >= limit (\d+)/.exec(raw)
    if (parsed) return `현재 보유 종목 수 ${parsed[1]}개 >= 최대 보유 종목 수 ${parsed[2]}개`
  }
  return null
}
