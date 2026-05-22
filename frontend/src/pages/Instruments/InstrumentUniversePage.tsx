/**
 * InstrumentUniversePage — 전체 종목 검색 + 섹터별 그룹 보기.
 *
 * 첨부 heatmap처럼 섹터가 1차 구획이 되도록 sectorName 기준으로 묶고,
 * 종목 타일은 상세 화면(/symbol/:code)으로 연결한다.
 */
import { useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useInstrumentUniverse } from '../../api/hooks/useInstrumentUniverse'
import type { InstrumentUniverseItemDto } from '../../api/instrument'

const MARKET_OPTIONS = [
  { value: '', label: '전체' },
  { value: 'KOSPI', label: 'KOSPI' },
  { value: 'KOSDAQ', label: 'KOSDAQ' },
  { value: 'KONEX', label: 'KONEX' },
]

const PAGE_SIZE_OPTIONS = [100, 200, 300, 500]

const SECTOR_STYLES = [
  'border-cyan-700/60 bg-cyan-950/20',
  'border-emerald-700/60 bg-emerald-950/20',
  'border-amber-700/60 bg-amber-950/20',
  'border-rose-700/60 bg-rose-950/20',
  'border-sky-700/60 bg-sky-950/20',
  'border-lime-700/60 bg-lime-950/20',
  'border-fuchsia-700/60 bg-fuchsia-950/20',
  'border-orange-700/60 bg-orange-950/20',
]

function formatMarketCap(value: number | null): string {
  if (value === null || value === undefined) return '-'
  return `${value.toLocaleString('ko-KR')}억`
}

function groupBySector(items: InstrumentUniverseItemDto[]) {
  const map = new Map<string, { key: string; name: string; items: InstrumentUniverseItemDto[] }>()
  for (const item of items) {
    const key = item.sectorKey || 'unclassified'
    const existing = map.get(key)
    if (existing) {
      existing.items.push(item)
    } else {
      map.set(key, { key, name: item.sectorName || '미분류', items: [item] })
    }
  }
  return Array.from(map.values()).sort((a, b) => {
    if (a.key === 'unclassified') return 1
    if (b.key === 'unclassified') return -1
    return a.name.localeCompare(b.name, 'ko-KR')
  })
}

export function InstrumentUniversePage() {
  const [queryInput, setQueryInput] = useState('')
  const [query, setQuery] = useState('')
  const [market, setMarket] = useState('')
  const [activeOnly, setActiveOnly] = useState(true)
  const [limit, setLimit] = useState(200)
  const [offset, setOffset] = useState(0)

  const { data, isLoading, isError, error } = useInstrumentUniverse({
    q: query || undefined,
    market: market || undefined,
    active: activeOnly,
    limit,
    offset,
  })

  const sectorGroups = useMemo(() => groupBySector(data?.items ?? []), [data?.items])
  const total = data?.total ?? 0
  const totalPages = Math.ceil(total / limit)
  const currentPage = totalPages === 0 ? 0 : Math.floor(offset / limit) + 1

  function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    setQuery(queryInput.trim())
    setOffset(0)
  }

  function clearFilters() {
    setQueryInput('')
    setQuery('')
    setMarket('')
    setActiveOnly(true)
    setOffset(0)
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-6">
      <div className="mb-6 flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white">종목검색</h1>
          <p className="mt-1 text-sm text-gray-400">
            전체 {total.toLocaleString('ko-KR')}종목 · 섹터 {sectorGroups.length.toLocaleString('ko-KR')}개
          </p>
        </div>
        <form onSubmit={handleSubmit} className="grid gap-2 sm:grid-cols-[minmax(220px,1fr)_auto_auto]">
          <input
            type="search"
            value={queryInput}
            onChange={(e) => setQueryInput(e.target.value)}
            placeholder="종목명 또는 코드"
            className="h-10 rounded-lg border border-gray-700 bg-gray-900 px-3 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
          <button
            type="submit"
            className="h-10 rounded-lg bg-blue-600 px-4 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950"
          >
            검색
          </button>
          <button
            type="button"
            onClick={clearFilters}
            className="h-10 rounded-lg border border-gray-700 px-3 text-sm text-gray-400 transition-colors hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-400"
          >
            초기화
          </button>
        </form>
      </div>

      <div className="mb-5 flex flex-wrap items-center gap-2 border-y border-gray-800 py-3">
        <span className="text-xs text-gray-500">시장</span>
        {MARKET_OPTIONS.map((opt) => (
          <button
            key={opt.value}
            type="button"
            onClick={() => {
              setMarket(opt.value)
              setOffset(0)
            }}
            className={[
              'h-8 rounded-lg px-3 text-xs font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400',
              market === opt.value
                ? 'bg-blue-600 text-white'
                : 'border border-gray-700 text-gray-400 hover:bg-gray-800',
            ].join(' ')}
          >
            {opt.label}
          </button>
        ))}
        <label className="ml-2 flex h-8 items-center gap-2 rounded-lg border border-gray-700 px-3 text-xs text-gray-400">
          <input
            type="checkbox"
            checked={activeOnly}
            onChange={(e) => {
              setActiveOnly(e.target.checked)
              setOffset(0)
            }}
            className="h-3.5 w-3.5 accent-blue-500"
          />
          활성만
        </label>
        <label className="ml-auto flex items-center gap-2 text-xs text-gray-500">
          표시
          <select
            value={limit}
            onChange={(e) => {
              setLimit(Number(e.target.value))
              setOffset(0)
            }}
            className="h-8 rounded-lg border border-gray-700 bg-gray-950 px-2 text-xs text-gray-300 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          >
            {PAGE_SIZE_OPTIONS.map((size) => (
              <option key={size} value={size}>
                {size}
              </option>
            ))}
          </select>
        </label>
      </div>

      {isLoading && (
        <div className="flex items-center justify-center py-16">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-800 border-t-blue-500" />
        </div>
      )}

      {isError && (
        <div className="rounded-xl border border-red-900/50 bg-red-950/20 px-4 py-6 text-center">
          <p className="text-sm text-red-400">종목 조회 실패</p>
          <p className="mt-1 text-xs text-red-600">{error?.message}</p>
        </div>
      )}

      {!isLoading && !isError && sectorGroups.length === 0 && (
        <div className="rounded-xl border border-gray-800 bg-gray-900/50 px-4 py-12 text-center">
          <p className="text-base text-gray-400">조건에 맞는 종목이 없습니다.</p>
        </div>
      )}

      {!isLoading && !isError && sectorGroups.length > 0 && (
        <div className="space-y-4">
          {sectorGroups.map((group, groupIndex) => (
            <section
              key={group.key}
              className={`rounded-xl border p-4 ${SECTOR_STYLES[groupIndex % SECTOR_STYLES.length]}`}
            >
              <div className="mb-3 flex items-center justify-between gap-3">
                <h2 className="text-sm font-semibold text-white">{group.name}</h2>
                <span className="text-xs text-gray-400">{group.items.length.toLocaleString('ko-KR')}종목</span>
              </div>
              <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
                {group.items.map((item) => (
                  <Link
                    key={item.code}
                    to={`/symbol/${item.code}`}
                    className="min-h-24 rounded-lg border border-gray-800 bg-gray-950/80 p-3 transition-colors hover:border-gray-600 hover:bg-gray-900 focus:outline-none focus:ring-2 focus:ring-blue-400"
                  >
                    <div className="flex items-start justify-between gap-2">
                      <div>
                        <p className="text-sm font-semibold text-white">{item.name}</p>
                        <p className="mt-0.5 font-mono text-xs text-gray-500">{item.code}</p>
                      </div>
                      <span className="rounded bg-gray-800 px-1.5 py-0.5 text-[11px] text-gray-400">
                        {item.market}
                      </span>
                    </div>
                    <div className="mt-3 flex flex-wrap gap-1 text-[11px]">
                      <span className="rounded bg-gray-900 px-1.5 py-0.5 text-gray-500">
                        {formatMarketCap(item.marketCapPrev)}
                      </span>
                      {item.tradingHalt && (
                        <span className="rounded bg-red-950 px-1.5 py-0.5 text-red-300">거래정지</span>
                      )}
                      {item.managementStock && (
                        <span className="rounded bg-amber-950 px-1.5 py-0.5 text-amber-300">관리</span>
                      )}
                      {!item.active && (
                        <span className="rounded bg-gray-800 px-1.5 py-0.5 text-gray-500">비활성</span>
                      )}
                    </div>
                  </Link>
                ))}
              </div>
            </section>
          ))}
        </div>
      )}

      {!isLoading && !isError && (
        <div className="mt-6 flex flex-col gap-3 border-t border-gray-800 pt-4 sm:flex-row sm:items-center sm:justify-between">
          <p className="text-xs text-gray-500">
            {currentPage}/{totalPages} 페이지 · offset {offset.toLocaleString('ko-KR')}
          </p>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setOffset(0)}
              disabled={offset === 0}
              className="h-8 rounded-lg border border-gray-700 px-3 text-xs text-gray-400 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-40"
            >
              처음
            </button>
            <button
              type="button"
              onClick={() => setOffset((prev) => Math.max(0, prev - limit))}
              disabled={offset === 0}
              className="h-8 rounded-lg border border-gray-700 px-3 text-xs text-gray-400 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-40"
            >
              이전
            </button>
            <button
              type="button"
              onClick={() => setOffset((prev) => Math.min(Math.max(0, total - limit), prev + limit))}
              disabled={offset + limit >= total}
              className="h-8 rounded-lg border border-gray-700 px-3 text-xs text-gray-400 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-40"
            >
              다음
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
