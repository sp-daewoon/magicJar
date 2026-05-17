/**
 * LightweightChart — LWC v4 래퍼 컴포넌트
 *
 * ADR 0025 §2-1 LOCK: LWC v4 공식 API 한정.
 * TradingView Advanced 라이브러리 import 0건.
 * 사용 API: createChart · addCandlestickSeries · addHistogramSeries · setData · update
 *
 * spec §4-4 LWC v4 시그니처 정합:
 * - time: UTCTimestamp (Math.floor(new Date(c.ts).getTime() / 1000))
 * - 거래량 = addHistogramSeries (priceScaleId: '')
 * - STOMP 실시간 갱신: candleSeries.update()
 *
 * 결함 수정 (v3.1.0 dogfooding):
 *   결함 1: ts ISO→UTCTimestamp 변환 + OHLC string→number (기존 로직 유지 확인)
 *   결함 5: KST 시간 포맷 (tickMarkFormatter + Asia/Seoul)
 *   결함 6: hover crosshair OHLCV·등락률 툴팁 (floating div)
 *
 * hotfix: chart-realtime-tick-update
 *   STOMP tick(price·ts) → interval boundary 계산 → 마지막 bar close·high·low 실시간 update
 *   liveTick prop 추가 — SymbolDetailPage → SymbolChart → LightweightChart 전달
 *
 * T-IND PR-33 R3 확장 (W2):
 *   onChartReady?: (chart: IChartApi) => void  — IndicatorOverlay 연동용 chart ref 노출
 *   onChartDestroy?: () => void                — chart destroy 시 알림
 *
 * T-PAT PR-40 R3 확장 (W2):
 *   onCandleSeriesReady?: (series: ISeriesApi<'Candlestick'>) => void — PatternMarker 연동용 series ref 노출
 *   onCandleSeriesDestroy?: () => void                               — series destroy 시 알림
 *
 * props:
 *   candles: CandleDto[]           초기 데이터 (ASC 정렬 보장 — backend 정합)
 *   interval: CandleInterval       현재 인터벌 (시간 포맷 분기용)
 *   realtimeCandle?: CandleDto     STOMP push 실시간 candle (optional)
 *   liveTick?: { price: string; ts: string } | null  STOMP tick — 마지막 bar 실시간 update
 *   onScrollLeft?: () => void      좌측 스크롤 끝 콜백 (페이지네이션 트리거)
 *   onChartReady?: (chart) => void 차트 생성 완료 콜백 (IndicatorOverlay 연동)
 *   onChartDestroy?: () => void    차트 파괴 콜백 (IndicatorOverlay 정리)
 *   onCandleSeriesReady?: (series) => void  캔들 시리즈 노출 (PatternMarker 연동)
 *   onCandleSeriesDestroy?: () => void      캔들 시리즈 파괴 알림
 */
import {
  createChart,
  type CandlestickData,
  type HistogramData,
  type IChartApi,
  type ISeriesApi,
  type SeriesType,
  type UTCTimestamp,
  type MouseEventParams,
} from 'lightweight-charts'
import { useEffect, useRef, useState, useCallback } from 'react'
import type { CandleDto, CandleInterval } from '../../api/symbol'

/** STOMP tick에서 추출한 실시간 가격·시각 */
export interface LiveTick {
  price: string
  ts: string
}

interface LightweightChartProps {
  candles: CandleDto[]
  interval?: CandleInterval
  realtimeCandle?: CandleDto | null
  /**
   * STOMP tick — 마지막 bar close·high·low 실시간 update (자본 보호 critical sync hotfix)
   * SymbolDetailPage가 /topic/market.tick.{code} 구독 후 price·ts 전달
   */
  liveTick?: LiveTick | null
  onScrollLeft?: () => void
  /** 차트 API 인스턴스를 부모에게 노출 (IndicatorOverlay 연동) */
  onChartReady?: (chart: IChartApi) => void
  /** 차트 파괴 시 알림 */
  onChartDestroy?: () => void
  /** 캔들스틱 시리즈를 부모에게 노출 (PatternMarker 연동 — T-PAT PR-40) */
  onCandleSeriesReady?: (series: ISeriesApi<'Candlestick'>) => void
  /** 캔들스틱 시리즈 파괴 시 알림 */
  onCandleSeriesDestroy?: () => void
}

/**
 * 인터벌이 분봉(intraday)인지 여부
 * ADR 0062 채택: 5m·15m·30m·90m·1h 합산 5종 포함 — KST 시·분 표시 활성
 * ADR 0063 채택: NXT 시장 08:00~20:00 KST 범위 — LWC v4 ts ASC input 시 X축 자동 확장 정합
 *   LWC v4 timeScale은 전달된 UTCTimestamp 범위 기반으로 X축을 자동 결정하므로
 *   08:00~20:00 KST 범위 ts가 입력되면 X축이 자동 확장됨 (별도 range 설정 불필요).
 */
function isIntradayInterval(interval?: CandleInterval): boolean {
  return (
    interval === '1m' ||
    interval === '5m' ||
    interval === '15m' ||
    interval === '30m' ||
    interval === '90m' ||
    interval === '1h'
  )
}

/**
 * tick 도착 시각(isoString)을 기준으로 interval의 bar time boundary를 UTCTimestamp로 반환.
 * KST(Asia/Seoul, UTC+9)를 기준으로 boundary를 계산하고 UTC로 변환.
 *
 * LWC v4 time은 UTCTimestamp(초) 사용 — bar의 time과 동일한 boundary를 사용해야
 * series.update()가 새 bar 생성 없이 기존 마지막 bar를 덮어쓴다.
 */
function barBoundaryTimestamp(isoString: string, interval: CandleInterval | undefined): UTCTimestamp {
  const ms = new Date(isoString).getTime()
  // KST = UTC+9
  const KST_OFFSET_MS = 9 * 60 * 60 * 1000
  const kstMs = ms + KST_OFFSET_MS

  let boundaryKstMs: number

  switch (interval) {
    case '1m': {
      // 현재 분 floor (초·밀리초 제거)
      boundaryKstMs = Math.floor(kstMs / (60 * 1000)) * (60 * 1000)
      break
    }
    case '5m': {
      boundaryKstMs = Math.floor(kstMs / (5 * 60 * 1000)) * (5 * 60 * 1000)
      break
    }
    case '15m': {
      boundaryKstMs = Math.floor(kstMs / (15 * 60 * 1000)) * (15 * 60 * 1000)
      break
    }
    case '30m': {
      boundaryKstMs = Math.floor(kstMs / (30 * 60 * 1000)) * (30 * 60 * 1000)
      break
    }
    case '90m': {
      boundaryKstMs = Math.floor(kstMs / (90 * 60 * 1000)) * (90 * 60 * 1000)
      break
    }
    case '1h': {
      boundaryKstMs = Math.floor(kstMs / (60 * 60 * 1000)) * (60 * 60 * 1000)
      break
    }
    case '1wk': {
      // 이번 주 월요일 KST 00:00
      // KST Date 생성 후 요일 추출
      const kstDate = new Date(kstMs)
      // getUTCDay()로 요일 계산 (kstMs는 이미 KST shifted)
      const dayOfWeek = kstDate.getUTCDay() // 0=일, 1=월 ... 6=토
      const daysSinceMonday = dayOfWeek === 0 ? 6 : dayOfWeek - 1
      const kstDayStartMs = Math.floor(kstMs / (24 * 60 * 60 * 1000)) * (24 * 60 * 60 * 1000)
      boundaryKstMs = kstDayStartMs - daysSinceMonday * 24 * 60 * 60 * 1000
      break
    }
    case '1mo': {
      // 이번 달 1일 KST 00:00
      const kstDate = new Date(kstMs)
      const year = kstDate.getUTCFullYear()
      const month = kstDate.getUTCMonth()
      boundaryKstMs = Date.UTC(year, month, 1)
      break
    }
    case '1y': {
      // 올해 1월 1일 KST 00:00
      const kstDate = new Date(kstMs)
      const year = kstDate.getUTCFullYear()
      boundaryKstMs = Date.UTC(year, 0, 1)
      break
    }
    case '1d':
    default: {
      // 오늘 KST 00:00
      const kstDayStartMs = Math.floor(kstMs / (24 * 60 * 60 * 1000)) * (24 * 60 * 60 * 1000)
      boundaryKstMs = kstDayStartMs
      break
    }
  }

  // KST boundary → UTC seconds
  const boundaryUtcMs = boundaryKstMs - KST_OFFSET_MS
  return Math.floor(boundaryUtcMs / 1000) as UTCTimestamp
}

function toUTCTimestamp(isoString: string): UTCTimestamp {
  return Math.floor(new Date(isoString).getTime() / 1000) as UTCTimestamp
}

function toCandlestickData(c: CandleDto): CandlestickData {
  return {
    time: toUTCTimestamp(c.ts),
    open: parseFloat(c.open),
    high: parseFloat(c.high),
    low: parseFloat(c.low),
    close: parseFloat(c.close),
  }
}

function toHistogramData(c: CandleDto): HistogramData {
  const close = parseFloat(c.close)
  const open = parseFloat(c.open)
  return {
    time: toUTCTimestamp(c.ts),
    value: c.volume,
    color: close >= open ? '#26a69a44' : '#ef535044',
  }
}

/**
 * UTCTimestamp 또는 BusinessDay {year,month,day}를 밀리초로 변환
 * LWC v4 tickMarkFormatter의 time 인자 타입에 대응.
 */
function lwcTimeToMs(time: UTCTimestamp | { year: number; month: number; day: number }): number {
  if (typeof time === 'number') return time * 1000
  return Date.UTC(time.year, time.month - 1, time.day)
}

/**
 * KST 시간 포맷터 — 분봉이면 "2026-01-01 10:20", 일봉 이상은 "2026-01-01"
 */
function formatKstTime(
  time: UTCTimestamp | { year: number; month: number; day: number },
  isIntraday: boolean,
): string {
  const ms = lwcTimeToMs(time)
  const d = new Date(ms)

  if (isIntraday) {
    // sv-SE locale: "2026-01-01 10:20" 형태 — Asia/Seoul KST
    return new Intl.DateTimeFormat('sv-SE', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
      timeZone: 'Asia/Seoul',
    })
      .format(d)
      .replace('T', ' ')
  } else {
    return new Intl.DateTimeFormat('sv-SE', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      timeZone: 'Asia/Seoul',
    }).format(d)
  }
}

/** 가격 천 단위 콤마 포맷 */
function fmtPrice(v: number): string {
  return v.toLocaleString('ko-KR')
}

/** 거래량 포맷 */
function fmtVolume(v: number): string {
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(1)}백만`
  if (v >= 1_000) return `${(v / 1_000).toFixed(0)}천`
  return v.toLocaleString('ko-KR')
}

interface TooltipState {
  visible: boolean
  x: number
  y: number
  open: number
  high: number
  low: number
  close: number
  volume: number
  changeRate: string
  timeLabel: string
}

export function LightweightChart({
  candles,
  interval,
  realtimeCandle,
  liveTick,
  onScrollLeft,
  onChartReady,
  onChartDestroy,
  onCandleSeriesReady,
  onCandleSeriesDestroy,
}: LightweightChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const volumeSeriesRef = useRef<ISeriesApi<SeriesType> | null>(null)
  const onScrollLeftRef = useRef(onScrollLeft)
  onScrollLeftRef.current = onScrollLeft
  const onChartReadyRef = useRef(onChartReady)
  onChartReadyRef.current = onChartReady
  const onChartDestroyRef = useRef(onChartDestroy)
  onChartDestroyRef.current = onChartDestroy
  const onCandleSeriesReadyRef = useRef(onCandleSeriesReady)
  onCandleSeriesReadyRef.current = onCandleSeriesReady
  const onCandleSeriesDestroyRef = useRef(onCandleSeriesDestroy)
  onCandleSeriesDestroyRef.current = onCandleSeriesDestroy

  // interval ref — tickMarkFormatter 클로저에서 최신 값 참조
  const intervalRef = useRef(interval)
  intervalRef.current = interval

  // 툴팁 상태
  const [tooltip, setTooltip] = useState<TooltipState>({
    visible: false,
    x: 0,
    y: 0,
    open: 0,
    high: 0,
    low: 0,
    close: 0,
    volume: 0,
    changeRate: '0.00',
    timeLabel: '',
  })

  // volume lookup: ts(UTCTimestamp) → volume
  const volumeMapRef = useRef<Map<number, number>>(new Map())

  /**
   * 마지막 bar OHLC 추적 ref — liveTick update 시 open·low·high 유지용.
   * candles setData 또는 realtimeCandle update 시 갱신됨.
   */
  const lastBarRef = useRef<{ time: UTCTimestamp; open: number; high: number; low: number; close: number } | null>(null)

  /**
   * backlog §30 fix — 차트 좌측 스크롤 자동 페이지네이션 false trigger 가드
   *
   * 문제: LWC v4 setData + fitContent 직후 timeScale이 visibleLogicalRange 콜백을
   *      자동 발화. fitContent는 우측 패딩을 두기 위해 range.from 값을 음수로
   *      설정(예: -10 ~ -50)하므로 사용자가 좌측 스크롤하지 않았음에도 onScrollLeft
   *      자동 호출 → fetchNextPage(before=oldest_ts) → 옛 candle 200개 즉시 요청.
   *      사용자 dogfooding(2026-05-11): 차트 첫 진입 시 `before=2009-10-31T15:00:00Z`
   *      자동 호출 + 응답 `ts=2000-01-31T15:00:00Z` 확인 — 의도되지 않은 초기 페이지네이션.
   *
   * 가드 정책 (2단):
   *   1. **첫 setData 후 1초 cooldown** — 초기 setData·fitContent 직후 자동 콜백 무시.
   *   2. **range.from 단조감소(좌측 pan) 검증** — 직전 range.from보다 더 작아진 경우만
   *      onScrollLeft 호출(사용자가 실제 좌측 pan했음 신호). 우측 zoom·resize 같은
   *      false positive 차단.
   */
  const setDataReceivedAtRef = useRef<number>(0)
  const lastVisibleRangeFromRef = useRef<number | null>(null)

  const hideTooltip = useCallback(() => {
    setTooltip((prev) => ({ ...prev, visible: false }))
  }, [])

  // dispose 플래그 — cleanup 후 async callback에서 stale 참조 방지
  const isDisposedRef = useRef(false)

  // 차트 생성 (마운트 1회)
  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    isDisposedRef.current = false

    const chart = createChart(container, {
      width: container.clientWidth,
      height: container.clientHeight || 400,
      layout: {
        background: { color: '#0d1117' },
        textColor: '#9ca3af',
      },
      grid: {
        vertLines: { color: '#1f2937' },
        horzLines: { color: '#1f2937' },
      },
      crosshair: {
        mode: 1, // CrosshairMode.Magnet
      },
      rightPriceScale: {
        borderColor: '#374151',
      },
      timeScale: {
        borderColor: '#374151',
        timeVisible: true,
        secondsVisible: false,
        tickMarkFormatter: (
          time: UTCTimestamp | { year: number; month: number; day: number },
        ) => {
          return formatKstTime(time, isIntradayInterval(intervalRef.current))
        },
      },
      localization: {
        locale: 'ko-KR',
        timeFormatter: (time: UTCTimestamp | { year: number; month: number; day: number }) => {
          return formatKstTime(time, isIntradayInterval(intervalRef.current))
        },
        dateFormat: 'yyyy-MM-dd',
      },
    })
    chartRef.current = chart

    // IndicatorOverlay 연동 콜백
    onChartReadyRef.current?.(chart)

    // 캔들스틱 시리즈
    const candleSeries = chart.addCandlestickSeries({
      upColor: '#26a69a',
      downColor: '#ef5350',
      borderDownColor: '#ef5350',
      borderUpColor: '#26a69a',
      wickDownColor: '#ef5350',
      wickUpColor: '#26a69a',
    })
    candleSeriesRef.current = candleSeries
    // PatternMarker 연동 콜백 (T-PAT PR-40)
    onCandleSeriesReadyRef.current?.(candleSeries)

    // 거래량 히스토그램 시리즈 (optional — 가격 스케일과 독립)
    const volumeSeries = chart.addHistogramSeries({
      priceScaleId: '',
      priceFormat: { type: 'volume' },
    })
    volumeSeries.priceScale().applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 },
    })
    volumeSeriesRef.current = volumeSeries

    // 차트 좌측 스크롤 끝 도달 감지 (페이지네이션 트리거 — backlog §30 fix)
    //
    // 가드 1) setData 후 1초 cooldown — fitContent 자동 발화 무시.
    // 가드 2) range.from 단조감소 — 좌측 pan 신호일 때만 트리거.
    //         (이전 from > 현재 from = 좌측 이동, zoom·resize·우측 pan은 무시)
    chart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
      if (!range || !onScrollLeftRef.current) return

      // 가드 1: setData 직후 1초간은 자동 콜백(fitContent)이므로 무시
      const elapsedMs = Date.now() - setDataReceivedAtRef.current
      if (setDataReceivedAtRef.current === 0 || elapsedMs < 1000) {
        // 초기 from 값만 기록 — 가드 2 비교 기준 확립
        lastVisibleRangeFromRef.current = range.from
        return
      }

      // 가드 2: 이전 from 대비 단조감소(좌측 pan)일 때만 트리거
      const prevFrom = lastVisibleRangeFromRef.current
      lastVisibleRangeFromRef.current = range.from

      if (prevFrom === null || range.from >= prevFrom) {
        // 변화 없음·우측 pan·zoom — 무시
        return
      }

      // 좌측 끝 도달 (range.from < 1: 차트 좌측 경계 근접) + 단조감소(실제 좌측 pan)
      if (range.from < 1) {
        onScrollLeftRef.current()
      }
    })

    // crosshair 이동 → hover 툴팁 업데이트
    chart.subscribeCrosshairMove((param: MouseEventParams) => {
      // chart가 dispose된 후 실행되는 async callback 방지
      if (isDisposedRef.current) return

      if (
        !param.time ||
        !param.point ||
        param.point.x < 0 ||
        param.point.y < 0
      ) {
        hideTooltip()
        return
      }

      const cs = candleSeriesRef.current
      if (!cs) {
        hideTooltip()
        return
      }

      const candle = param.seriesData.get(cs) as CandlestickData | undefined
      if (!candle) {
        hideTooltip()
        return
      }

      const ts =
        typeof param.time === 'number'
          ? (param.time as UTCTimestamp)
          : (Math.floor(
              Date.UTC(
                (param.time as { year: number; month: number; day: number }).year,
                (param.time as { year: number; month: number; day: number }).month - 1,
                (param.time as { year: number; month: number; day: number }).day,
              ) / 1000,
            ) as UTCTimestamp)

      const volume = volumeMapRef.current.get(ts) ?? 0
      const changeRate = candle.open !== 0
        ? (((candle.close - candle.open) / candle.open) * 100).toFixed(2)
        : '0.00'

      const containerRect = container.getBoundingClientRect()
      // param.point는 차트 내부 좌표 — 컨테이너 기준 위치 사용
      const chartEl = container.querySelector('canvas')
      const offsetLeft = chartEl ? chartEl.getBoundingClientRect().left - containerRect.left : 0
      const offsetTop = chartEl ? chartEl.getBoundingClientRect().top - containerRect.top : 0

      setTooltip({
        visible: true,
        x: param.point.x + offsetLeft,
        y: param.point.y + offsetTop,
        open: candle.open,
        high: candle.high,
        low: candle.low,
        close: candle.close,
        volume,
        changeRate,
        timeLabel: formatKstTime(param.time as UTCTimestamp, isIntradayInterval(intervalRef.current)),
      })
    })

    // 반응형 리사이즈
    const resizeObserver = new ResizeObserver((entries) => {
      if (isDisposedRef.current) return
      for (const entry of entries) {
        try {
          chart.applyOptions({ width: entry.contentRect.width })
        } catch {
          // 이미 dispose된 경우 무시
        }
      }
    })
    resizeObserver.observe(container)

    return () => {
      // dispose 플래그를 먼저 세워 async callback(crosshair/scroll)이 stale 인스턴스에 접근 차단
      isDisposedRef.current = true
      resizeObserver.disconnect()
      // series ref를 먼저 null 처리 — IndicatorOverlay cleanup이 removeSeries 호출 전에
      // chartRef.current가 유효하더라도 이미 dispose 예정임을 알 수 있도록 순서 보장
      candleSeriesRef.current = null
      volumeSeriesRef.current = null
      // 콜백 알림 (IndicatorOverlay·PatternMarker가 ref 정리 가능하도록)
      onCandleSeriesDestroyRef.current?.()
      onChartDestroyRef.current?.()
      // chart.remove() — try/catch: 이미 dispose된 경우 graceful 무시
      try {
        chart.remove()
      } catch {
        // 이미 dispose된 경우 무시
      }
      chartRef.current = null
    }
  }, [hideTooltip])

  // interval 변경 시 timeScale 옵션 재적용 (tickMarkFormatter 갱신)
  useEffect(() => {
    if (!chartRef.current) return
    chartRef.current.applyOptions({
      timeScale: {
        timeVisible: isIntradayInterval(interval),
        secondsVisible: false,
      },
    })
  }, [interval])

  // 캔들 데이터 업데이트 (interval 변경 또는 초기 로드)
  useEffect(() => {
    const candleSeries = candleSeriesRef.current
    const volumeSeries = volumeSeriesRef.current
    if (!candleSeries || !volumeSeries) return

    if (candles.length === 0) {
      candleSeries.setData([])
      volumeSeries.setData([])
      volumeMapRef.current = new Map()
      lastBarRef.current = null
      return
    }

    // backend가 ASC 정렬 보장 (spec §3-6 §10-6 정합)
    const candleData = candles.map(toCandlestickData)
    const volumeData = candles.map(toHistogramData)

    // volume lookup 맵 갱신
    const vMap = new Map<number, number>()
    candles.forEach((c) => {
      const ts = toUTCTimestamp(c.ts)
      vMap.set(ts, c.volume)
    })
    volumeMapRef.current = vMap

    candleSeries.setData(candleData)
    volumeSeries.setData(volumeData)
    chartRef.current?.timeScale().fitContent()

    // backlog §30 fix — setData 시각 기록 (visibleLogicalRange cooldown 가드용)
    setDataReceivedAtRef.current = Date.now()
    // 추가 페이지 append 시 prevFrom 단조감소 비교 baseline 재설정
    lastVisibleRangeFromRef.current = null

    // 마지막 bar 추적 — liveTick update 시 open·low·high 유지용
    const last = candleData[candleData.length - 1]
    if (last) {
      lastBarRef.current = {
        time: last.time as UTCTimestamp,
        open: last.open,
        high: last.high,
        low: last.low,
        close: last.close,
      }
    }
  }, [candles])

  // STOMP 실시간 candle 갱신
  useEffect(() => {
    if (!realtimeCandle) return
    const candleSeries = candleSeriesRef.current
    const volumeSeries = volumeSeriesRef.current
    if (!candleSeries || !volumeSeries) return

    try {
      const cd = toCandlestickData(realtimeCandle)
      const vd = toHistogramData(realtimeCandle)
      candleSeries.update(cd)
      volumeSeries.update(vd)
      // volume 맵도 갱신
      volumeMapRef.current.set(cd.time as number, realtimeCandle.volume)
      // 마지막 bar 추적 갱신
      lastBarRef.current = {
        time: cd.time as UTCTimestamp,
        open: cd.open,
        high: cd.high,
        low: cd.low,
        close: cd.close,
      }
    } catch {
      // LWC update 오류 (잘못된 시간 순서 등) — graceful 무시
    }
  }, [realtimeCandle])

  /**
   * STOMP tick → 마지막 bar 실시간 update (자본 보호 critical sync hotfix)
   *
   * tick.price를 close로 설정, high/low는 기존 값과 비교해 확장.
   * bar time은 interval boundary로 계산 (새 bar 생성 방지).
   * isDisposedRef 가드 + try-catch — lifecycle 안전.
   */
  useEffect(() => {
    if (!liveTick) return
    if (isDisposedRef.current) return

    const candleSeries = candleSeriesRef.current
    if (!candleSeries) return

    const price = parseFloat(liveTick.price)
    if (isNaN(price) || price <= 0) return

    try {
      const barTime = barBoundaryTimestamp(liveTick.ts, intervalRef.current)
      const prev = lastBarRef.current

      // prev가 없으면 시리즈 데이터가 아직 없는 상태 — open=close=price로 새 bar 생성
      const open = prev && prev.time === barTime ? prev.open : price
      const high = prev && prev.time === barTime ? Math.max(prev.high, price) : price
      const low  = prev && prev.time === barTime ? Math.min(prev.low,  price) : price

      const updated = {
        time: barTime,
        open,
        high,
        low,
        close: price,
      }
      candleSeries.update(updated)

      // lastBarRef 갱신
      lastBarRef.current = updated
    } catch {
      // LWC update 오류 — graceful 무시 (dispose 후 stale ref 접근 등)
    }
  }, [liveTick])

  const changeRateNum = parseFloat(tooltip.changeRate)
  const changeRateColor =
    changeRateNum > 0 ? 'text-red-400' : changeRateNum < 0 ? 'text-blue-400' : 'text-gray-300'

  return (
    <div className="relative w-full h-96" aria-label="종목 차트" role="img">
      <div ref={containerRef} className="w-full h-full" />

      {/* hover 툴팁 — 결함 6 */}
      {tooltip.visible && (
        <div
          className="pointer-events-none absolute z-10 rounded-lg border border-gray-700 bg-gray-900/95 p-2 text-xs shadow-xl"
          style={{
            left: Math.min(tooltip.x + 12, (containerRef.current?.clientWidth ?? 300) - 160),
            top: Math.max(tooltip.y - 80, 4),
            minWidth: '148px',
          }}
          role="tooltip"
          aria-label="캔들 상세 정보"
        >
          <div className="mb-1 text-gray-400 text-[10px]">{tooltip.timeLabel}</div>
          <dl className="grid grid-cols-2 gap-x-3 gap-y-0.5">
            <dt className="text-gray-500">시가</dt>
            <dd className="text-right tabular-nums text-gray-200">{fmtPrice(tooltip.open)}</dd>
            <dt className="text-gray-500">고가</dt>
            <dd className="text-right tabular-nums text-red-300">{fmtPrice(tooltip.high)}</dd>
            <dt className="text-gray-500">저가</dt>
            <dd className="text-right tabular-nums text-blue-300">{fmtPrice(tooltip.low)}</dd>
            <dt className="text-gray-500">종가</dt>
            <dd className="text-right tabular-nums text-gray-200">{fmtPrice(tooltip.close)}</dd>
            <dt className="text-gray-500">거래량</dt>
            <dd className="text-right tabular-nums text-gray-300">{fmtVolume(tooltip.volume)}</dd>
            <dt className="text-gray-500">등락률</dt>
            <dd className={`text-right tabular-nums font-semibold ${changeRateColor}`}>
              {changeRateNum >= 0 ? '+' : ''}
              {tooltip.changeRate}%
            </dd>
          </dl>
        </div>
      )}
    </div>
  )
}
