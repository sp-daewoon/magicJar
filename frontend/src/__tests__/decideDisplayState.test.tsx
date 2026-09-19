/**
 * decideDisplayState 단위 테스트 (감사 2026-09-19 §5 — activeTradingMode 반영).
 *
 * 핵심 검증: 표시 기준은 env `mode`가 아니라 실제 활성 모드 `activeTradingMode`(DB).
 */
import { describe, expect, it } from 'vitest'
import { decideDisplayState } from '../components/layout/RuntimeFlagsIndicator'

const base = {
  mode: 'MOCK' as const,
  allowReal: false,
  killSwitchEnabled: false,
  killSwitchReason: null,
  userConsented: null as boolean | null,
  activeTradingMode: 'MOCK' as 'MOCK' | 'REAL',
}

describe('decideDisplayState', () => {
  it('KillSwitch ON이면 정지(회색) — 최우선', () => {
    const s = decideDisplayState({ ...base, killSwitchEnabled: true, killSwitchReason: 'test' })
    expect(s.color).toBe('gray')
    expect(s.label).toBe('정지')
  })

  it('activeTradingMode=MOCK이면 모의(녹색)', () => {
    const s = decideDisplayState({ ...base, activeTradingMode: 'MOCK' })
    expect(s.color).toBe('green')
    expect(s.label).toBe('모의')
  })

  it('핵심: env mode=REAL 상한이어도 activeTradingMode=MOCK이면 모의로 표시', () => {
    const s = decideDisplayState({
      ...base,
      mode: 'REAL',
      allowReal: true,
      userConsented: true,
      activeTradingMode: 'MOCK',
    })
    expect(s.color).toBe('green')
    expect(s.label).toBe('모의')
  })

  it('activeTradingMode=REAL + 3조건 충족이면 실전(빨강)', () => {
    const s = decideDisplayState({
      ...base,
      mode: 'REAL',
      allowReal: true,
      userConsented: true,
      activeTradingMode: 'REAL',
    })
    expect(s.color).toBe('red')
    expect(s.label).toBe('실전')
  })

  it('activeTradingMode=REAL인데 allowReal=false이면 실전·차단(노랑)', () => {
    const s = decideDisplayState({
      ...base,
      mode: 'REAL',
      allowReal: false,
      userConsented: true,
      activeTradingMode: 'REAL',
    })
    expect(s.color).toBe('yellow')
    expect(s.label).toBe('실전·차단')
  })

  it('activeTradingMode=REAL인데 동의 미체결이면 실전·동의대기(노랑)', () => {
    const s = decideDisplayState({
      ...base,
      mode: 'REAL',
      allowReal: true,
      userConsented: false,
      activeTradingMode: 'REAL',
    })
    expect(s.color).toBe('yellow')
    expect(s.label).toBe('실전·동의대기')
  })

  it('activeTradingMode 미제공(구버전 backend)이면 env mode로 fallback', () => {
    const s = decideDisplayState({
      mode: 'REAL',
      allowReal: true,
      killSwitchEnabled: false,
      killSwitchReason: null,
      userConsented: true,
    })
    expect(s.color).toBe('red')
    expect(s.label).toBe('실전')
  })
})
