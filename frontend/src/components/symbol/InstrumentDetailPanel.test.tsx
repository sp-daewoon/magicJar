/**
 * InstrumentDetailPanel 컴포넌트 테스트
 * 1. detail 데이터 정상 렌더링 (삼성전자 mock)
 * 2. isLoading=true 시 Skeleton 표시
 * 3. isError=true 시 에러 메시지 표시
 * 4. null 필드 → "—" 표시
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
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
    render(<InstrumentDetailPanel detail={MOCK_DETAIL} isLoading={false} isError={false} />)

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

  // ── 시나리오 3: 에러 상태 ────────────────────────────────────────────────
  it('isError=true 시 에러 메시지가 표시된다', () => {
    render(<InstrumentDetailPanel detail={undefined} isLoading={false} isError={true} />)
    expect(screen.getByText(/재무 데이터 없음/)).toBeInTheDocument()
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
    render(<InstrumentDetailPanel detail={minimal} isLoading={false} isError={false} />)

    // "—" 표시가 여러 개 있어야 함
    const dashes = screen.getAllByText('—')
    expect(dashes.length).toBeGreaterThanOrEqual(4)
  })
})
