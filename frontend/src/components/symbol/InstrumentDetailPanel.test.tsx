/**
 * InstrumentDetailPanel 컴포넌트 테스트
 * 1. detail 데이터 정상 렌더링 (삼성전자 mock)
 * 2. isLoading=true 시 Skeleton 표시
 * 3. isError=true 시 키 미등록 안내 Alert 표시 + "지금 등록" 버튼
 * 4. null 필드 → "—" 표시
 * 5. onOpenSettings 콜백 — "지금 등록" 버튼 클릭 시 호출
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import InstrumentDetailPanel from './InstrumentDetailPanel'
import type { InstrumentDetail } from '@/api/types'

const MOCK_DETAIL: InstrumentDetail = {
  code: '005930',
  name: '삼성전자',
  market: 'KOSPI',
  sector: '전기·전자',
  industry: '반도체',
  representativeName: '이재용',
  marketCap: 450_000_000_000_000,
  sharesOutstanding: 7_780_000_000,
  foreignRatio: 0.537,
  institutionRatio: 0.221,
  revenue: 79_400_000_000_000,
  operatingIncome: 6_600_000_000_000,
  netIncome: 5_400_000_000_000,
  per: 22.4,
  pbr: 1.3,
  eps: 3_166,
  bps: 53_428,
  dividendYield: 0.024,
  majorShareholderRatio: 0.234,
}

describe('InstrumentDetailPanel', () => {
  // ── 시나리오 1: 정상 렌더링 ──────────────────────────────────────────────
  it('detail 데이터가 있을 때 종목명·시가총액·PER 등이 렌더링된다', () => {
    render(
      <InstrumentDetailPanel detail={MOCK_DETAIL} isLoading={false} isError={false} />,
    )

    expect(screen.getByText('삼성전자')).toBeInTheDocument()
    expect(screen.getByText('005930')).toBeInTheDocument()
    expect(screen.getByText('KOSPI')).toBeInTheDocument()
    // 시가총액: 450조
    expect(screen.getByText('450.0조')).toBeInTheDocument()
    // PER
    expect(screen.getByText('22.4배')).toBeInTheDocument()
    // 업종
    expect(screen.getByText('전기·전자 · 반도체')).toBeInTheDocument()
    // 대표자
    expect(screen.getByText('대표: 이재용')).toBeInTheDocument()
  })

  // ── 시나리오 2: 로딩 중 스켈레톤 ────────────────────────────────────────
  it('isLoading=true 시 animate-pulse 스켈레톤이 표시된다', () => {
    const { container } = render(
      <InstrumentDetailPanel detail={undefined} isLoading={true} isError={false} />,
    )
    const skeletons = container.querySelectorAll('.animate-pulse')
    expect(skeletons.length).toBeGreaterThan(0)
  })

  // ── 시나리오 3: 에러 시 키 미등록 안내 ───────────────────────────────────
  it('isError=true 시 키 미등록 안내 메시지가 표시된다', () => {
    render(
      <InstrumentDetailPanel detail={undefined} isLoading={false} isError={true} />,
    )
    expect(screen.getByText(/종목 메타·재무 데이터 미수신/)).toBeInTheDocument()
    expect(screen.getByText(/KIS 실전 키 또는 DART 키/)).toBeInTheDocument()
  })

  // ── 시나리오 4: null 필드 → "—" ─────────────────────────────────────────
  it('null 필드는 "—"로 표시된다', () => {
    const minimal: InstrumentDetail = {
      ...MOCK_DETAIL,
      per: null,
      pbr: null,
      eps: null,
      bps: null,
      dividendYield: null,
    }
    render(
      <InstrumentDetailPanel detail={minimal} isLoading={false} isError={false} />,
    )

    // "—" 표시가 여러 개 있어야 함
    const dashes = screen.getAllByText('—')
    expect(dashes.length).toBeGreaterThanOrEqual(4)
  })

  // ── 시나리오 5: onOpenSettings 콜백 호출 ────────────────────────────────
  it('onOpenSettings 전달 시 "지금 등록" 버튼이 표시되고 클릭하면 콜백이 호출된다', () => {
    const onOpenSettings = vi.fn()
    render(
      <InstrumentDetailPanel
        detail={undefined}
        isLoading={false}
        isError={true}
        onOpenSettings={onOpenSettings}
      />,
    )

    const btn = screen.getByTestId('open-settings-btn')
    expect(btn).toBeInTheDocument()
    fireEvent.click(btn)
    expect(onOpenSettings).toHaveBeenCalledTimes(1)
  })

  // ── 시나리오 6: warnings 배열 — KIS + DART 두 코드 표시 ─────────────────
  it('warnings 배열에 KIS_API_FAILED, DART_CORP_CODE_NOT_FOUND가 있으면 경고 배너가 표시된다', () => {
    const detailWithWarnings: InstrumentDetail = {
      ...MOCK_DETAIL,
      warnings: ['KIS_API_FAILED', 'DART_CORP_CODE_NOT_FOUND'],
    }
    render(
      <InstrumentDetailPanel detail={detailWithWarnings} isLoading={false} isError={false} />,
    )

    const banner = screen.getByTestId('warnings-banner')
    expect(banner).toBeInTheDocument()
    // 한글 메시지가 렌더링되어야 함
    expect(
      screen.getByText(/KIS OpenAPI 호출 실패 — 시총·PER·EPS 등 미수신/),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/DART 종목 매핑 실패 — 재무 데이터 미수신/),
    ).toBeInTheDocument()
  })

  // ── 시나리오 7: warnings 빈 배열 — 배너 미표시 ──────────────────────────
  it('warnings 배열이 비어 있으면 경고 배너가 표시되지 않는다', () => {
    const detailNoWarnings: InstrumentDetail = {
      ...MOCK_DETAIL,
      warnings: [],
    }
    render(
      <InstrumentDetailPanel detail={detailNoWarnings} isLoading={false} isError={false} />,
    )

    expect(screen.queryByTestId('warnings-banner')).not.toBeInTheDocument()
  })

  // ── 시나리오 8: warnings 필드 absent — 배너 미표시 ──────────────────────
  it('warnings 필드가 없으면 경고 배너가 표시되지 않는다', () => {
    const detailAbsent: InstrumentDetail = { ...MOCK_DETAIL }
    // warnings 키가 없는 경우 (optional)
    delete (detailAbsent as Partial<InstrumentDetail>).warnings
    render(
      <InstrumentDetailPanel detail={detailAbsent} isLoading={false} isError={false} />,
    )

    expect(screen.queryByTestId('warnings-banner')).not.toBeInTheDocument()
  })

  // ── 시나리오 9: unknown 경고 코드 — 코드 그대로 표시 ────────────────────
  it('알 수 없는 경고 코드는 코드 문자열 그대로 표시된다', () => {
    const detailUnknown: InstrumentDetail = {
      ...MOCK_DETAIL,
      warnings: ['UNKNOWN_ERROR_CODE'],
    }
    render(
      <InstrumentDetailPanel detail={detailUnknown} isLoading={false} isError={false} />,
    )

    expect(screen.getByTestId('warnings-banner')).toBeInTheDocument()
    expect(screen.getByText(/UNKNOWN_ERROR_CODE/)).toBeInTheDocument()
  })
})
