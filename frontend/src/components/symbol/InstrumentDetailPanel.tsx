/**
 * InstrumentDetailPanel — 종목 상세 메타 패널.
 * T20-T7: 종목 상세 페이지 좌측 사이드바 (또는 상단 카드) 렌더링.
 *
 * 표시 항목 (null이면 "—"):
 *  - 시가총액·발행주식수·외국인 비율·기관 비율
 *  - 매출액·영업이익·당기순이익 (최근 분기, 단위 KRW B/M)
 *  - PER·PBR·EPS·BPS
 *  - 대주주 지분율·배당률
 *  - 업종·대표자
 *
 * 반응형: 기본 grid 2열, 모바일 1열 (sm:grid-cols-2)
 */
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Separator } from '@/components/ui/separator'
import type { InstrumentDetail } from '@/api/types'

interface InstrumentDetailPanelProps {
  detail: InstrumentDetail | undefined
  isLoading: boolean
  isError: boolean
}

// ── 포맷 유틸 ──────────────────────────────────────────────────────────────────

/** 원화 금액 → KRW 조(B) 또는 억(M) 표기 */
function formatKrwLarge(value: number | null | undefined): string {
  if (value == null) return '—'
  const trillion = 1_000_000_000_000
  const billion = 100_000_000
  if (Math.abs(value) >= trillion) {
    return `${(value / trillion).toFixed(1)}조`
  }
  if (Math.abs(value) >= billion) {
    return `${(value / billion).toFixed(0)}억`
  }
  return value.toLocaleString('ko-KR') + '원'
}

/** 비율 → % 문자열 */
function formatPercent(value: number | null | undefined, decimals = 1): string {
  if (value == null) return '—'
  return `${(value * 100).toFixed(decimals)}%`
}

/** 일반 숫자 포맷 */
function formatNumber(value: number | null | undefined, decimals = 1): string {
  if (value == null) return '—'
  return value.toFixed(decimals)
}

/** 천 단위 콤마 정수 */
function formatInt(value: number | null | undefined): string {
  if (value == null) return '—'
  return Math.round(value).toLocaleString('ko-KR')
}

// ── 메타 아이템 ────────────────────────────────────────────────────────────────

interface MetaItemProps {
  label: string
  value: string
  highlight?: 'up' | 'down' | 'neutral'
}

function MetaItem({ label, value, highlight }: MetaItemProps) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-[11px] text-muted-foreground">{label}</span>
      <span
        className={
          highlight === 'up'
            ? 'text-sm font-semibold text-red-600'
            : highlight === 'down'
              ? 'text-sm font-semibold text-blue-600'
              : 'text-sm font-semibold'
        }
      >
        {value}
      </span>
    </div>
  )
}

// ── 스켈레톤 ────────────────────────────────────────────────────────────────────

function DetailSkeleton() {
  return (
    <Card>
      <CardHeader className="pb-2">
        <Skeleton className="h-4 w-28" />
      </CardHeader>
      <CardContent className="space-y-3">
        {Array.from({ length: 8 }).map((_, i) => (
          <div key={i} className="flex flex-col gap-1">
            <Skeleton className="h-3 w-16" />
            <Skeleton className="h-4 w-24" />
          </div>
        ))}
      </CardContent>
    </Card>
  )
}

// ── 메인 컴포넌트 ───────────────────────────────────────────────────────────────

export default function InstrumentDetailPanel({
  detail,
  isLoading,
  isError,
}: InstrumentDetailPanelProps) {
  if (isLoading) return <DetailSkeleton />

  if (isError || !detail) {
    return (
      <Card>
        <CardContent className="flex items-center justify-center py-8 text-sm text-muted-foreground">
          상세 정보를 불러오지 못했습니다.
        </CardContent>
      </Card>
    )
  }

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-sm font-semibold">
          <span>{detail.name}</span>
          <span className="rounded bg-muted px-1.5 py-0.5 text-[11px] font-medium text-muted-foreground">
            {detail.code}
          </span>
          <span className="text-[11px] font-medium text-blue-600">{detail.market}</span>
        </CardTitle>
        {(detail.sector || detail.industry) && (
          <p className="text-[11px] text-muted-foreground">
            {[detail.sector, detail.industry].filter(Boolean).join(' · ')}
          </p>
        )}
        {detail.representativeName && (
          <p className="text-[11px] text-muted-foreground">
            대표: {detail.representativeName}
          </p>
        )}
      </CardHeader>

      <CardContent className="space-y-3 pb-4 pt-0">
        {/* 시장 규모 섹션 */}
        <Separator />
        <p className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          시장 규모
        </p>
        <div className="grid grid-cols-2 gap-x-4 gap-y-2">
          <MetaItem label="시가총액" value={formatKrwLarge(detail.marketCap)} />
          <MetaItem
            label="발행주식수"
            value={detail.sharesOutstanding ? `${formatInt(detail.sharesOutstanding)}주` : '—'}
          />
          <MetaItem label="외국인 비율" value={formatPercent(detail.foreignRatio)} />
          <MetaItem label="기관 비율" value={formatPercent(detail.institutionRatio)} />
        </div>

        {/* 재무 섹션 */}
        <Separator />
        <p className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          재무 (최근 분기)
        </p>
        <div className="grid grid-cols-2 gap-x-4 gap-y-2">
          <MetaItem label="매출액" value={formatKrwLarge(detail.revenue)} />
          <MetaItem label="영업이익" value={formatKrwLarge(detail.operatingIncome)} />
          <MetaItem label="당기순이익" value={formatKrwLarge(detail.netIncome)} />
        </div>

        {/* 밸류에이션 섹션 */}
        <Separator />
        <p className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          밸류에이션
        </p>
        <div className="grid grid-cols-2 gap-x-4 gap-y-2">
          <MetaItem label="PER" value={detail.per != null ? `${formatNumber(detail.per)}배` : '—'} />
          <MetaItem label="PBR" value={detail.pbr != null ? `${formatNumber(detail.pbr)}배` : '—'} />
          <MetaItem label="EPS" value={detail.eps != null ? `${formatInt(detail.eps)}원` : '—'} />
          <MetaItem label="BPS" value={detail.bps != null ? `${formatInt(detail.bps)}원` : '—'} />
        </div>

        {/* 주주 섹션 */}
        <Separator />
        <p className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          주주·배당
        </p>
        <div className="grid grid-cols-2 gap-x-4 gap-y-2">
          <MetaItem label="대주주 지분율" value={formatPercent(detail.majorShareholderRatio)} />
          <MetaItem label="배당률" value={formatPercent(detail.dividendYield)} />
        </div>
      </CardContent>
    </Card>
  )
}
