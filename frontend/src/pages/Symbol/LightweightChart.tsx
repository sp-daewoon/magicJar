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
 * props:
 *   candles: CandleDto[]           초기 데이터 (ASC 정렬 보장 — backend 정합)
 *   realtimeCandle?: CandleDto     STOMP push 실시간 candle (optional)
 *   onScrollLeft?: () => void      좌측 스크롤 끝 콜백 (페이지네이션 트리거)
 */
import {
  createChart,
  type CandlestickData,
  type HistogramData,
  type IChartApi,
  type ISeriesApi,
  type SeriesType,
  type UTCTimestamp,
} from 'lightweight-charts'
import { useEffect, useRef } from 'react'
import type { CandleDto } from '../../api/symbol'

interface LightweightChartProps {
  candles: CandleDto[]
  realtimeCandle?: CandleDto | null
  onScrollLeft?: () => void
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

export function LightweightChart({ candles, realtimeCandle, onScrollLeft }: LightweightChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const volumeSeriesRef = useRef<ISeriesApi<SeriesType> | null>(null)
  const onScrollLeftRef = useRef(onScrollLeft)
  onScrollLeftRef.current = onScrollLeft

  // 차트 생성 (마운트 1회)
  useEffect(() => {
    const container = containerRef.current
    if (!container) return

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
      },
    })
    chartRef.current = chart

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

    // 거래량 히스토그램 시리즈 (optional — 가격 스케일과 독립)
    const volumeSeries = chart.addHistogramSeries({
      priceScaleId: '',
      priceFormat: { type: 'volume' },
    })
    volumeSeries.priceScale().applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 },
    })
    volumeSeriesRef.current = volumeSeries

    // 차트 좌측 스크롤 끝 도달 감지 (페이지네이션 트리거)
    chart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
      if (range && range.from < 1 && onScrollLeftRef.current) {
        onScrollLeftRef.current()
      }
    })

    // 반응형 리사이즈
    const resizeObserver = new ResizeObserver((entries) => {
      for (const entry of entries) {
        chart.applyOptions({ width: entry.contentRect.width })
      }
    })
    resizeObserver.observe(container)

    return () => {
      resizeObserver.disconnect()
      chart.remove()
      chartRef.current = null
      candleSeriesRef.current = null
      volumeSeriesRef.current = null
    }
  }, [])

  // 캔들 데이터 업데이트 (interval 변경 또는 초기 로드)
  useEffect(() => {
    const candleSeries = candleSeriesRef.current
    const volumeSeries = volumeSeriesRef.current
    if (!candleSeries || !volumeSeries) return

    if (candles.length === 0) {
      candleSeries.setData([])
      volumeSeries.setData([])
      return
    }

    // backend가 ASC 정렬 보장 (spec §3-6 §10-6 정합)
    candleSeries.setData(candles.map(toCandlestickData))
    volumeSeries.setData(candles.map(toHistogramData))
    chartRef.current?.timeScale().fitContent()
  }, [candles])

  // STOMP 실시간 candle 갱신
  useEffect(() => {
    if (!realtimeCandle) return
    const candleSeries = candleSeriesRef.current
    const volumeSeries = volumeSeriesRef.current
    if (!candleSeries || !volumeSeries) return

    try {
      candleSeries.update(toCandlestickData(realtimeCandle))
      volumeSeries.update(toHistogramData(realtimeCandle))
    } catch {
      // LWC update 오류 (잘못된 시간 순서 등) — graceful 무시
    }
  }, [realtimeCandle])

  return (
    <div
      ref={containerRef}
      className="w-full h-96"
      aria-label="종목 차트"
      role="img"
    />
  )
}
