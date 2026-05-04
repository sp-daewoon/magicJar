/**
 * IntervalSelector 단위 테스트
 *
 * ADR 0050 §2-D — 자유 입력 정책 검증:
 *   1. 10m 버튼 클릭 → onChange('M10') 호출
 *   2. 자유 입력 정상 케이스: 45 입력 → onChange('45m') 호출
 *   3. 검증 실패 케이스 — onChange 호출 차단:
 *      - 0 (범위 미달)
 *      - 601 (범위 초과)
 *      - 소수 (3.5)
 *      - 문자 (abc)
 *      - 음수 (-1)
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import IntervalSelector from './IntervalSelector'

function setup(value = 'DAY' as Parameters<typeof IntervalSelector>[0]['value']) {
  const onChange = vi.fn()
  render(<IntervalSelector value={value} onChange={onChange} />)
  return { onChange }
}

// ── 버튼 동작 ──────────────────────────────────────────────────────────────

describe('버튼 동작', () => {
  it('10m 버튼 클릭 시 onChange("M10") 호출', () => {
    const { onChange } = setup()
    fireEvent.click(screen.getByRole('button', { name: '10분' }))
    expect(onChange).toHaveBeenCalledWith('M10')
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('1분 버튼 클릭 시 onChange("M1") 호출', () => {
    const { onChange } = setup()
    fireEvent.click(screen.getByRole('button', { name: '1분' }))
    expect(onChange).toHaveBeenCalledWith('M1')
  })

  it('5분 버튼 클릭 시 onChange("M5") 호출', () => {
    const { onChange } = setup()
    fireEvent.click(screen.getByRole('button', { name: '5분' }))
    expect(onChange).toHaveBeenCalledWith('M5')
  })

  it('일 버튼 클릭 시 onChange("DAY") 호출', () => {
    const { onChange } = setup('M1')
    fireEvent.click(screen.getByRole('button', { name: '일' }))
    expect(onChange).toHaveBeenCalledWith('DAY')
  })
})

// ── 자유 입력 정상 케이스 ───────────────────────────────────────────────────

describe('자유 입력 — 정상 케이스', () => {
  it('45 입력 후 적용 → onChange("45m") 호출', () => {
    const { onChange } = setup()
    const input = screen.getByRole('textbox', { name: /분 단위 직접 입력/ })
    fireEvent.change(input, { target: { value: '45' } })
    fireEvent.click(screen.getByRole('button', { name: '적용' }))
    expect(onChange).toHaveBeenCalledWith('45m')
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('12 입력 후 Enter → onChange("12m") 호출', () => {
    const { onChange } = setup()
    const input = screen.getByRole('textbox', { name: /분 단위 직접 입력/ })
    fireEvent.change(input, { target: { value: '12' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith('12m')
  })

  it('1 입력 → onChange("1m") 호출 (경계값 하한)', () => {
    const { onChange } = setup()
    const input = screen.getByRole('textbox', { name: /분 단위 직접 입력/ })
    fireEvent.change(input, { target: { value: '1' } })
    fireEvent.click(screen.getByRole('button', { name: '적용' }))
    expect(onChange).toHaveBeenCalledWith('1m')
  })

  it('600 입력 → onChange("600m") 호출 (경계값 상한)', () => {
    const { onChange } = setup()
    const input = screen.getByRole('textbox', { name: /분 단위 직접 입력/ })
    fireEvent.change(input, { target: { value: '600' } })
    fireEvent.click(screen.getByRole('button', { name: '적용' }))
    expect(onChange).toHaveBeenCalledWith('600m')
  })

  it('onBlur 적용 — 입력 후 포커스 해제 시 자동 적용', () => {
    const { onChange } = setup()
    const input = screen.getByRole('textbox', { name: /분 단위 직접 입력/ })
    fireEvent.change(input, { target: { value: '20' } })
    fireEvent.blur(input)
    expect(onChange).toHaveBeenCalledWith('20m')
  })
})

// ── 자유 입력 검증 실패 케이스 ─────────────────────────────────────────────

describe('자유 입력 — 검증 실패 케이스 (onChange 차단)', () => {
  it('0 입력 → 에러 표시, onChange 미호출', () => {
    const { onChange } = setup()
    const input = screen.getByRole('textbox', { name: /분 단위 직접 입력/ })
    fireEvent.change(input, { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: '적용' }))
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })

  it('601 입력 → 에러 표시, onChange 미호출', () => {
    const { onChange } = setup()
    const input = screen.getByRole('textbox', { name: /분 단위 직접 입력/ })
    fireEvent.change(input, { target: { value: '601' } })
    fireEvent.click(screen.getByRole('button', { name: '적용' }))
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })

  it('소수(3.5) 입력 → 에러 표시, onChange 미호출', () => {
    const { onChange } = setup()
    const input = screen.getByRole('textbox', { name: /분 단위 직접 입력/ })
    fireEvent.change(input, { target: { value: '3.5' } })
    fireEvent.click(screen.getByRole('button', { name: '적용' }))
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })

  it('문자(abc) 입력 → 에러 표시, onChange 미호출', () => {
    const { onChange } = setup()
    const input = screen.getByRole('textbox', { name: /분 단위 직접 입력/ })
    fireEvent.change(input, { target: { value: 'abc' } })
    fireEvent.click(screen.getByRole('button', { name: '적용' }))
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })

  it('음수(-1) 입력 → 에러 표시, onChange 미호출', () => {
    const { onChange } = setup()
    const input = screen.getByRole('textbox', { name: /분 단위 직접 입력/ })
    fireEvent.change(input, { target: { value: '-1' } })
    fireEvent.click(screen.getByRole('button', { name: '적용' }))
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })

  it('빈 입력 후 적용 → onChange 미호출, 에러 미노출', () => {
    const { onChange } = setup()
    const input = screen.getByRole('textbox', { name: /분 단위 직접 입력/ })
    fireEvent.change(input, { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: '적용' }))
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

// ── 자유 입력 활성 상태 표시 ─────────────────────────────────────────────

describe('자유 입력 활성 상태 표시', () => {
  it('value="45m" 전달 시 "현재: 45m" 표시', () => {
    setup('45m' as Parameters<typeof IntervalSelector>[0]['value'])
    expect(screen.getByText('현재: 45m')).toBeInTheDocument()
  })

  it('value="M10" 전달 시 "현재: ..." 미노출 (버튼 활성으로 처리)', () => {
    setup('M10')
    expect(screen.queryByText(/현재:/)).not.toBeInTheDocument()
  })
})
