import { useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import {
  strategyResearchApi,
  strategyResearchErrorMessage,
  type StrategyResearchAiDiscoveryItemDto,
  type StrategyResearchAiDiscoveryRequestDto,
  type StrategyResearchAiDiscoveryResponseDto,
  type StrategyResearchCandidateDto,
  type StrategyResearchReportDto,
  type StrategyResearchRequestDto,
  type StrategyResearchTradeDto,
} from '../../api/strategyResearch'
import type { InstrumentSearchResultDto } from '../../api/instrument'
import { InstrumentSearchInput } from '../../components/InstrumentSearchInput'
import { StrategyResearchChart } from '../../components/strategyResearch/StrategyResearchChart'
import { formatKst, formatMoney } from '../../components/scenario/scenarioReplayFormat'
import { kstInputToIso } from '../../components/scenario/scenarioReplayTime'
import {
  buildStrategyResearchDraft,
  storeStrategyResearchDraft,
} from '../../lib/strategyResearch/builderDraft'
import { useOnboardingStore } from '../../stores/onboarding'

const DEFAULT_FROM_KST = '2026-04-28T09:00'
const DEFAULT_TO_KST = '2026-06-02T15:30'
const INTERVALS = ['1m', '3m', '5m', '15m', '30m', '1h', '1d']
const AI_DISCOVERY_DEFAULT_QUERY = '요즘 반도체와 AI 인프라 쪽에서 전략 리서치로 검증해볼 국내주'
const AI_DISCOVERY_MARKETS = ['', 'KOSPI', 'KOSDAQ', 'KONEX'] as const

function formatPct(value: number): string {
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}%`
}

function pctClass(value: number): string {
  if (value > 0) return 'text-emerald-300'
  if (value < 0) return 'text-red-300'
  return 'text-gray-300'
}

function regimeLabel(type: string): string {
  if (type === 'DOWNTREND') return '하락장'
  if (type === 'UPTREND') return '상승장'
  if (type === 'SIDEWAYS') return '횡보'
  return type
}

function exitReasonLabel(reason: StrategyResearchTradeDto['exitReason']): string {
  if (reason === 'TAKE_PROFIT') return '익절'
  if (reason === 'STOP_LOSS') return '손절'
  if (reason === 'TIME_EXIT') return '시간 청산'
  return reason
}

function candidateTone(candidate: StrategyResearchCandidateDto): string {
  if (candidate.stats.totalReturnPct > 0) return 'border-emerald-800 bg-emerald-950/20'
  if (candidate.stats.trades === 0) return 'border-gray-800 bg-gray-900'
  return 'border-amber-800 bg-amber-950/20'
}

function instrumentDisplay(result: InstrumentSearchResultDto): string {
  return `${result.name} (${result.code})`
}

function extractInstrumentCode(value: string): string | null {
  const trimmed = value.trim()
  const labelMatch = trimmed.match(/\((\d{6})\)\s*$/)
  if (labelMatch) return labelMatch[1]
  return /^\d{6}$/.test(trimmed) ? trimmed : null
}

export function StrategyResearchPage() {
  const navigate = useNavigate()
  const registeredUserId = useOnboardingStore((s) => s.registeredUserId)
  const [code, setCode] = useState('038110')
  const [instrumentInput, setInstrumentInput] = useState('038110')
  const [selectedInstrumentName, setSelectedInstrumentName] = useState<string | null>(null)
  const [interval, setInterval] = useState('1m')
  const [fromKst, setFromKst] = useState(DEFAULT_FROM_KST)
  const [toKst, setToKst] = useState(DEFAULT_TO_KST)
  const [targetProfitPct, setTargetProfitPct] = useState(1.5)
  const [stopLossPct, setStopLossPct] = useState(0.8)
  const [maxHoldBars, setMaxHoldBars] = useState(12)
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [discoveryQuery, setDiscoveryQuery] = useState(AI_DISCOVERY_DEFAULT_QUERY)
  const [discoveryMarket, setDiscoveryMarket] = useState<(typeof AI_DISCOVERY_MARKETS)[number]>('')
  const [discoveryLimit, setDiscoveryLimit] = useState(6)
  const [discoveryError, setDiscoveryError] = useState<string | null>(null)

  const mutation = useMutation<StrategyResearchReportDto, unknown, StrategyResearchRequestDto>({
    mutationFn: strategyResearchApi.propose,
    onSuccess: (report) => {
      setSelectedKey(report.candidates[0]?.key ?? null)
      setFormError(null)
    },
    onError: (error) => setFormError(strategyResearchErrorMessage(error)),
  })

  const discoveryMutation = useMutation<
    StrategyResearchAiDiscoveryResponseDto,
    unknown,
    StrategyResearchAiDiscoveryRequestDto
  >({
    mutationFn: strategyResearchApi.discoverWithAi,
    onSuccess: () => setDiscoveryError(null),
    onError: (error) => setDiscoveryError(strategyResearchErrorMessage(error)),
  })

  const report = mutation.data
  const discoveryReport = discoveryMutation.data
  const selectedCandidate = useMemo(() => {
    if (!report) return null
    return report.candidates.find((candidate) => candidate.key === selectedKey) ?? report.candidates[0] ?? null
  }, [report, selectedKey])

  const handleInstrumentRawChange = (value: string) => {
    setInstrumentInput(value)
    setSelectedInstrumentName(null)
    setCode(extractInstrumentCode(value) ?? '')
  }

  const handleInstrumentSelect = (result: InstrumentSearchResultDto) => {
    setCode(result.code)
    setInstrumentInput(instrumentDisplay(result))
    setSelectedInstrumentName(result.name)
  }

  const submitDiscovery = () => {
    const query = discoveryQuery.trim()
    if (!query) {
      setDiscoveryError('찾고 싶은 테마나 조건을 입력해주세요')
      return
    }
    setDiscoveryError(null)
    discoveryMutation.mutate({
      userId: String(registeredUserId),
      query,
      market: discoveryMarket,
      limit: discoveryLimit,
    })
  }

  const selectDiscoveryItem = (item: StrategyResearchAiDiscoveryItemDto) => {
    if (!item.code) {
      setDiscoveryError('종목 마스터와 매칭된 후보만 선택할 수 있습니다')
      return
    }
    const result: InstrumentSearchResultDto = {
      code: item.code,
      name: item.name,
      market: item.market ?? 'UNKNOWN',
      sector: item.sector,
      score: 1,
    }
    handleInstrumentSelect(result)
    setFormError(null)
  }

  if (!registeredUserId) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center px-4">
        <div className="text-center">
          <p className="text-sm text-gray-400">사용자 정보가 없습니다</p>
          <p className="mt-1 text-xs text-gray-600">셋업 마법사를 완료한 후 이용해주세요</p>
          <Link
            to="/setup"
            className="mt-4 inline-block rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            셋업 시작
          </Link>
        </div>
      </div>
    )
  }

  const submit = () => {
    try {
      setFormError(null)
      const selectedCode = code || extractInstrumentCode(instrumentInput)
      if (!selectedCode) {
        setFormError('종목명을 검색해서 선택하거나 6자리 종목코드를 입력해주세요')
        return
      }
      mutation.mutate({
        userId: String(registeredUserId),
        code: selectedCode,
        interval,
        from: kstInputToIso(fromKst),
        to: kstInputToIso(toKst),
        targetProfitPct,
        stopLossPct,
        maxHoldBars,
      })
    } catch (error) {
      setFormError(error instanceof Error ? error.message : '입력값을 확인해주세요')
    }
  }

  const openBuilderDraft = (candidate: StrategyResearchCandidateDto) => {
    if (!report) return
    const result = buildStrategyResearchDraft(report, candidate)
    if (!result.draft) {
      setFormError(result.reason ?? '이 후보는 전략 빌더로 가져올 수 없습니다.')
      return
    }
    const draftId = storeStrategyResearchDraft(result.draft)
    navigate(`/strategy/builder?researchDraftId=${encodeURIComponent(draftId)}`)
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-6 sm:px-6">
      <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-lg font-bold text-gray-100">전략 리서치</h1>
          <p className="mt-0.5 text-xs text-gray-500">캔들 기반 전략 후보·가상 진입/청산 분석</p>
        </div>
        <div className="flex gap-2">
          <Link
            to="/strategy/scenario-replay"
            className="rounded border border-gray-700 px-3 py-1.5 text-xs text-gray-400 hover:bg-gray-800"
          >
            시나리오 리플레이
          </Link>
          <Link
            to="/strategy/builder"
            className="rounded border border-gray-700 px-3 py-1.5 text-xs text-gray-400 hover:bg-gray-800"
          >
            전략 빌더
          </Link>
        </div>
      </div>

      <div className="mb-4 grid gap-2 rounded-lg border border-sky-900/60 bg-sky-950/20 p-3 text-xs text-sky-200 sm:grid-cols-3">
        <div>
          <span className="font-semibold text-sky-100">리서치 전용</span>
          <span className="ml-2 text-sky-400">/strategy/research</span>
        </div>
        <div>
          <span className="font-semibold text-sky-100">가상 분석</span>
          <span className="ml-2 text-sky-400">KIS 주문 미발행</span>
        </div>
        <div>
          <span className="font-semibold text-sky-100">저장 없음</span>
          <span className="ml-2 text-sky-400">후보 초안만 표시</span>
        </div>
      </div>

      <section className="mb-4 rounded-lg border border-gray-800 bg-gray-900 p-4" aria-labelledby="ai-discovery-heading">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 id="ai-discovery-heading" className="text-sm font-semibold text-gray-200">
              AI 종목발굴
            </h2>
            <p className="mt-0.5 text-xs text-gray-500">Claude CLI 후보 · 종목 마스터 매핑</p>
          </div>
          {discoveryReport && (
            <span className="rounded border border-gray-700 px-2 py-1 text-xs text-gray-400">
              {discoveryReport.provider}
            </span>
          )}
        </div>

        <div className="mt-4 grid gap-3 lg:grid-cols-12">
          <label className="lg:col-span-7">
            <span className="text-xs text-gray-500">찾을 조건</span>
            <textarea
              value={discoveryQuery}
              onChange={(event) => setDiscoveryQuery(event.target.value)}
              disabled={discoveryMutation.isPending}
              rows={3}
              className="mt-1 w-full resize-none rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 placeholder-gray-600 focus:border-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
            />
          </label>
          <label className="lg:col-span-2">
            <span className="text-xs text-gray-500">시장</span>
            <select
              value={discoveryMarket}
              onChange={(event) => setDiscoveryMarket(event.target.value as (typeof AI_DISCOVERY_MARKETS)[number])}
              disabled={discoveryMutation.isPending}
              className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
            >
              {AI_DISCOVERY_MARKETS.map((item) => (
                <option key={item || 'ALL'} value={item}>{item || '전체'}</option>
              ))}
            </select>
          </label>
          <label className="lg:col-span-1">
            <span className="text-xs text-gray-500">개수</span>
            <input
              type="number"
              min="1"
              max="12"
              value={discoveryLimit}
              onChange={(event) => setDiscoveryLimit(Number(event.target.value))}
              disabled={discoveryMutation.isPending}
              className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
            />
          </label>
          <div className="flex items-end lg:col-span-2">
            <button
              type="button"
              onClick={submitDiscovery}
              disabled={discoveryMutation.isPending}
              className="h-10 w-full rounded bg-indigo-600 px-4 text-sm font-semibold text-white hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {discoveryMutation.isPending ? '발굴 중' : '발굴'}
            </button>
          </div>
        </div>

        {discoveryMutation.isPending && (
          <div
            className="mt-4 rounded border border-indigo-900 bg-indigo-950/20 px-3 py-3"
            role="status"
            aria-live="polite"
          >
            <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
              <span className="font-medium text-indigo-100">Claude CLI가 후보를 찾는 중입니다</span>
              <span className="text-indigo-300">최대 3분까지 기다립니다</span>
            </div>
            <div className="mt-2 h-2 overflow-hidden rounded-full bg-gray-800">
              <div className="ai-discovery-loading-bar h-full w-1/3 rounded-full bg-indigo-400" />
            </div>
          </div>
        )}

        {discoveryError && (
          <div className="mt-4 rounded border border-red-900 bg-red-950/30 px-3 py-2 text-sm text-red-200">
            {discoveryError}
          </div>
        )}

        {discoveryReport && (
          <div className="mt-4 space-y-3">
            {discoveryReport.warnings.length > 0 && (
              <div className="rounded border border-amber-900 bg-amber-950/20 px-3 py-2 text-xs text-amber-200">
                {discoveryReport.warnings.join(' · ')}
              </div>
            )}
            <div className="grid gap-3 lg:grid-cols-3">
              {discoveryReport.items.map((item, index) => (
                <div
                  key={`${item.rawCode ?? item.code ?? item.name}-${index}`}
                  className="rounded border border-gray-800 bg-gray-950 p-4"
                >
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <div className="text-sm font-semibold text-gray-100">{item.name}</div>
                      <div className="mt-1 flex flex-wrap gap-1 text-xs text-gray-500">
                        {item.code && <span className="font-mono text-blue-300">{item.code}</span>}
                        {item.market && <span>{item.market}</span>}
                        {item.sector && <span>{item.sector}</span>}
                      </div>
                    </div>
                    <span className={[
                      'rounded border px-2 py-0.5 text-xs',
                      item.matched ? 'border-emerald-800 text-emerald-300' : 'border-amber-800 text-amber-300',
                    ].join(' ')}
                    >
                      {item.matched ? '매칭' : '확인필요'}
                    </span>
                  </div>
                  <p className="mt-3 text-sm leading-6 text-gray-300">{item.aiReason}</p>
                  {item.themes.length > 0 && (
                    <div className="mt-3 flex flex-wrap gap-1">
                      {item.themes.map((theme) => (
                        <span key={theme} className="rounded border border-gray-800 px-2 py-0.5 text-xs text-gray-400">
                          {theme}
                        </span>
                      ))}
                    </div>
                  )}
                  <div className="mt-4 flex flex-wrap items-center justify-between gap-2">
                    <span className="text-xs text-gray-500">
                      신뢰 {Math.round(item.confidence * 100)}%
                      {(item.tradingHalt || item.managementStock) && ' · 가드 확인'}
                    </span>
                    <div className="flex gap-2">
                      {item.code && (
                        <Link
                          to={`/symbol/${item.code}`}
                          className="rounded border border-gray-700 px-2.5 py-1 text-xs text-gray-300 hover:bg-gray-800"
                        >
                          차트
                        </Link>
                      )}
                      <button
                        type="button"
                        onClick={() => selectDiscoveryItem(item)}
                        disabled={!item.code}
                        className="rounded bg-blue-600 px-2.5 py-1 text-xs font-semibold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        선택
                      </button>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </section>

      <section className="rounded-lg border border-gray-800 bg-gray-900 p-4" aria-labelledby="research-form-heading">
        <h2 id="research-form-heading" className="text-sm font-semibold text-gray-200">
          후보 생성
        </h2>
        <div className="mt-4 grid gap-4 lg:grid-cols-12">
          <label className="lg:col-span-2">
            <span className="text-xs text-gray-500">종목</span>
            <div className="mt-1">
              <InstrumentSearchInput
                value={instrumentInput}
                onRawChange={handleInstrumentRawChange}
                onSelect={handleInstrumentSelect}
                placeholder="종목명 또는 코드 검색"
              />
            </div>
            {(code || selectedInstrumentName) && (
              <p className="mt-1 text-xs text-gray-500">
                선택: {selectedInstrumentName && <span className="text-gray-300">{selectedInstrumentName}</span>}{' '}
                {code && <span className="font-mono text-blue-300">{code}</span>}
              </p>
            )}
          </label>
          <label className="lg:col-span-2">
            <span className="text-xs text-gray-500">캔들 interval</span>
            <select
              value={interval}
              onChange={(event) => setInterval(event.target.value)}
              className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none"
            >
              {INTERVALS.map((item) => (
                <option key={item} value={item}>{item}</option>
              ))}
            </select>
          </label>
          <label className="lg:col-span-2">
            <span className="text-xs text-gray-500">시작 시각 (KST)</span>
            <input
              type="datetime-local"
              value={fromKst}
              onChange={(event) => setFromKst(event.target.value)}
              className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none"
            />
          </label>
          <label className="lg:col-span-2">
            <span className="text-xs text-gray-500">종료 시각 (KST)</span>
            <input
              type="datetime-local"
              value={toKst}
              onChange={(event) => setToKst(event.target.value)}
              className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none"
            />
          </label>
          <label className="lg:col-span-1">
            <span className="text-xs text-gray-500">익절 %</span>
            <input
              type="number"
              min="0.1"
              max="10"
              step="0.1"
              value={targetProfitPct}
              onChange={(event) => setTargetProfitPct(Number(event.target.value))}
              className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none"
            />
          </label>
          <label className="lg:col-span-1">
            <span className="text-xs text-gray-500">손절 %</span>
            <input
              type="number"
              min="0.1"
              max="10"
              step="0.1"
              value={stopLossPct}
              onChange={(event) => setStopLossPct(Number(event.target.value))}
              className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none"
            />
          </label>
          <label className="lg:col-span-1">
            <span className="text-xs text-gray-500">보유</span>
            <input
              type="number"
              min="1"
              max="120"
              value={maxHoldBars}
              onChange={(event) => setMaxHoldBars(Number(event.target.value))}
              className="mt-1 w-full rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none"
            />
          </label>
          <div className="flex items-end lg:col-span-1">
            <button
              type="button"
              onClick={submit}
              disabled={mutation.isPending}
              className="h-10 w-full rounded bg-blue-600 px-4 text-sm font-semibold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {mutation.isPending ? '분석 중' : '리서치'}
            </button>
          </div>
        </div>
        {formError && (
          <div className="mt-4 rounded border border-red-900 bg-red-950/30 px-3 py-2 text-sm text-red-200">
            {formError}
          </div>
        )}
      </section>

      {report && (
        <div className="mt-5 space-y-5">
          <section className="grid gap-3 md:grid-cols-4">
            <div className="rounded border border-gray-800 bg-gray-900 px-4 py-3">
              <div className="text-xs text-gray-500">시장 상태</div>
              <div className="mt-1 text-lg font-semibold text-gray-100">{regimeLabel(report.marketRegime.type)}</div>
              <div className={['mt-1 text-sm tabular-nums', pctClass(report.marketRegime.trendPct)].join(' ')}>
                {formatPct(report.marketRegime.trendPct)}
              </div>
            </div>
            <div className="rounded border border-gray-800 bg-gray-900 px-4 py-3">
              <div className="text-xs text-gray-500">candle</div>
              <div className="mt-1 text-lg font-semibold text-gray-100">{report.candleCount.toLocaleString('ko-KR')}</div>
              <div className="mt-1 text-sm text-gray-500">{report.request.interval}</div>
            </div>
            <div className="rounded border border-gray-800 bg-gray-900 px-4 py-3">
              <div className="text-xs text-gray-500">분석 구간</div>
              <div className="mt-1 text-sm text-gray-200">{formatKst(report.request.from)}</div>
              <div className="mt-1 text-sm text-gray-500">{formatKst(report.request.to)}</div>
            </div>
            <div className="rounded border border-gray-800 bg-gray-900 px-4 py-3">
              <div className="text-xs text-gray-500">후보</div>
              <div className="mt-1 text-lg font-semibold text-gray-100">{report.candidates.length.toLocaleString('ko-KR')}</div>
              <div className="mt-1 text-sm text-gray-500">익절 {report.request.targetProfitPct}% · 손절 {report.request.stopLossPct}%</div>
            </div>
          </section>

          {report.warnings.length > 0 && (
            <section className="rounded border border-amber-900 bg-amber-950/20 px-4 py-3 text-sm text-amber-200">
              {report.warnings.join(' · ')}
            </section>
          )}

          <section className="rounded-lg border border-gray-800 bg-gray-900 p-4">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <div>
                <h2 className="text-sm font-semibold text-gray-200">후보 전략</h2>
                <p className="mt-0.5 text-xs text-gray-500">{report.marketRegime.explanation}</p>
              </div>
            </div>
            <div className="grid gap-3 lg:grid-cols-3">
              {report.candidates.map((candidate) => (
                <button
                  key={candidate.key}
                  type="button"
                  onClick={() => setSelectedKey(candidate.key)}
                  className={[
                    'rounded border p-4 text-left transition-colors hover:border-blue-600',
                    candidateTone(candidate),
                    selectedCandidate?.key === candidate.key ? 'ring-1 ring-blue-500' : '',
                  ].join(' ')}
                >
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <div className="text-sm font-semibold text-gray-100">{candidate.title}</div>
                      <div className="mt-1 text-xs text-gray-500">{candidate.marketFit}</div>
                    </div>
                    <span className="rounded border border-gray-700 px-2 py-0.5 text-xs text-gray-400">
                      {candidate.score}
                    </span>
                  </div>
                  <div className="mt-4 grid grid-cols-3 gap-2 text-xs">
                    <div>
                      <div className="text-gray-500">거래</div>
                      <div className="mt-1 text-gray-100">{candidate.stats.trades}</div>
                    </div>
                    <div>
                      <div className="text-gray-500">승률</div>
                      <div className="mt-1 text-gray-100">{candidate.stats.winRatePct}%</div>
                    </div>
                    <div>
                      <div className="text-gray-500">누적</div>
                      <div className={['mt-1', pctClass(candidate.stats.totalReturnPct)].join(' ')}>
                        {formatPct(candidate.stats.totalReturnPct)}
                      </div>
                    </div>
                  </div>
                </button>
              ))}
            </div>
          </section>

          {selectedCandidate && (
            <section className="rounded-lg border border-gray-800 bg-gray-900 p-4">
              <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
                <div>
                  <h2 className="text-base font-semibold text-gray-100">{selectedCandidate.title}</h2>
                  <p className="mt-1 text-sm text-gray-500">{selectedCandidate.summary}</p>
                </div>
                <div className="flex flex-wrap items-center gap-2 text-xs">
                  <span className="rounded border border-gray-700 px-2 py-1 text-gray-300">
                    MDD {selectedCandidate.stats.maxDrawdownPct}%
                  </span>
                  <span className="rounded border border-gray-700 px-2 py-1 text-gray-300">
                    평균 {formatPct(selectedCandidate.stats.avgTradeReturnPct)}
                  </span>
                  <span className="rounded border border-gray-700 px-2 py-1 text-gray-300">
                    연속손실 {selectedCandidate.stats.maxConsecutiveLosses}
                  </span>
                  <button
                    type="button"
                    onClick={() => openBuilderDraft(selectedCandidate)}
                    className="rounded bg-blue-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-blue-700"
                  >
                    전략 빌더로 가져오기
                  </button>
                </div>
              </div>

              <StrategyResearchChart
                code={report.request.code}
                interval={report.request.interval}
                candles={report.candles}
                candidate={selectedCandidate}
              />

              <div className="mt-5 grid gap-4 lg:grid-cols-3">
                <div>
                  <h3 className="text-xs font-semibold text-gray-400">진입 조건</h3>
                  <ul className="mt-2 space-y-1 text-sm text-gray-300">
                    {selectedCandidate.entryRules.map((rule) => (
                      <li key={rule}>· {rule}</li>
                    ))}
                  </ul>
                </div>
                <div>
                  <h3 className="text-xs font-semibold text-gray-400">청산 조건</h3>
                  <ul className="mt-2 space-y-1 text-sm text-gray-300">
                    {selectedCandidate.exitRules.map((rule) => (
                      <li key={rule}>· {rule}</li>
                    ))}
                  </ul>
                </div>
                <div>
                  <h3 className="text-xs font-semibold text-gray-400">검증 가드</h3>
                  <ul className="mt-2 space-y-1 text-sm text-gray-300">
                    {selectedCandidate.riskRules.map((rule) => (
                      <li key={rule}>· {rule}</li>
                    ))}
                  </ul>
                </div>
              </div>

              <div className="mt-5 grid gap-3 lg:grid-cols-2">
                <div className="rounded border border-gray-800 bg-gray-950 p-3">
                  <div className="text-xs text-gray-500">entry draft</div>
                  <pre className="mt-2 whitespace-pre-wrap break-words text-xs text-blue-200">{selectedCandidate.entryDslDraft}</pre>
                </div>
                <div className="rounded border border-gray-800 bg-gray-950 p-3">
                  <div className="text-xs text-gray-500">exit draft</div>
                  <pre className="mt-2 whitespace-pre-wrap break-words text-xs text-blue-200">{selectedCandidate.exitDslDraft}</pre>
                </div>
              </div>

              <div className="mt-5 overflow-x-auto">
                <table className="min-w-full divide-y divide-gray-800 text-sm">
                  <thead>
                    <tr className="text-left text-xs text-gray-500">
                      <th className="px-3 py-2 font-medium">진입</th>
                      <th className="px-3 py-2 font-medium">청산</th>
                      <th className="px-3 py-2 text-right font-medium">진입가</th>
                      <th className="px-3 py-2 text-right font-medium">청산가</th>
                      <th className="px-3 py-2 text-right font-medium">수익률</th>
                      <th className="px-3 py-2 font-medium">근거</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-800">
                    {selectedCandidate.trades.slice(0, 40).map((trade, index) => (
                      <tr key={`${trade.entryTime}-${index}`} className="text-gray-300">
                        <td className="whitespace-nowrap px-3 py-2">{formatKst(trade.entryTime)}</td>
                        <td className="whitespace-nowrap px-3 py-2">{formatKst(trade.exitTime)} · {exitReasonLabel(trade.exitReason)}</td>
                        <td className="px-3 py-2 text-right tabular-nums">{formatMoney(trade.entryPrice)}</td>
                        <td className="px-3 py-2 text-right tabular-nums">{formatMoney(trade.exitPrice)}</td>
                        <td className={['px-3 py-2 text-right tabular-nums', pctClass(trade.returnPct)].join(' ')}>
                          {formatPct(trade.returnPct)}
                        </td>
                        <td className="min-w-56 px-3 py-2 text-xs text-gray-500">{trade.reason}</td>
                      </tr>
                    ))}
                    {selectedCandidate.trades.length === 0 && (
                      <tr>
                        <td colSpan={6} className="px-3 py-8 text-center text-sm text-gray-500">
                          조건을 만족한 가상 거래가 없습니다
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </section>
          )}
        </div>
      )}
    </div>
  )
}
