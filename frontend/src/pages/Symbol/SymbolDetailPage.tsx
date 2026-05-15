/**
 * SymbolDetailPage — /symbol/:code 종목 상세 페이지
 *
 * spec §6-1 라우트 LOCK: `/symbol/:code`
 * ADR 0040 §2-4: 현재가·메타·차트·호가 4 영역
 *
 * 구현 사항:
 * 1. useParams → code 추출
 * 2. useSymbol(code) → 메타·현재가 (5s polling fallback)
 * 3. STOMP /topic/market.tick.{code} → 현재가 실시간 갱신
 * 4. STOMP /topic/market.candle.minute.{code} → 차트 1m 실시간 append
 * 5. STOMP /topic/market.orderbook.{code} → 호가 실시간 갱신
 * 6. useEffect mount → POST /api/symbols/{code}/orderbook/subscribe
 * 7. useEffect cleanup → POST /api/symbols/{code}/orderbook/unsubscribe + sendBeacon fallback
 *    (spec §5 호가 LRU 10 slot 라이프사이클 LOCK)
 *
 * graceful 처리 (spec §10·§12):
 * - HTTP 503/404 → SymbolError
 * - cold-start 미완 (candle 0건) → SymbolChart "데이터 수집 중" skeleton
 * - 장 마감 → stale 배지 (SymbolPriceCard)
 * - STOMP 끊김 → "실시간 연결 재시도 중..." 배지 + 5s polling 자동 fallback
 * - instrument 테이블 부재 → code 그대로 표시 (SymbolHeader graceful)
 * - 호가 slot 가득(queued) → "polling fallback 중" 배지 (SymbolOrderbook)
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { useInstrument } from '../../api/hooks/useInstrument'
import { useSymbol } from '../../api/hooks/useSymbol'
import {
  symbolApi,
  type CandleMinutePayload,
  type OrderbookPayload,
  type TickPayload,
} from '../../api/symbol'
import { isStompConnected, useStompTopic } from '../../lib/stomp'
import { SymbolChart } from './SymbolChart'
import { SymbolError } from './SymbolError'
import { SymbolHeader } from './SymbolHeader'
import { SymbolMetaCard } from './SymbolMetaCard'
import { SymbolOrderbook } from './SymbolOrderbook'
import { SymbolPriceCard } from './SymbolPriceCard'

// ──────────────────────────────────────────────
// 유틸
// ──────────────────────────────────────────────

function isBEError(status: number | undefined): boolean {
  return status === 503 || status === 404 || status === 400
}

// ──────────────────────────────────────────────
// SymbolDetailPage
// ──────────────────────────────────────────────

export function SymbolDetailPage() {
  const { code = '' } = useParams<{ code: string }>()
  const { data: instrument, isLoading: isInstrumentLoading } = useInstrument(code)
  const isDelisted = instrument?.active === false
  // 상장폐지 종목은 가격 조회 의미 없음 — useSymbol 비활성으로 polling 차단
  const { data: symbol, isLoading: isSymbolLoading, isError, error } = useSymbol(isDelisted ? '' : code)
  const isLoading = isInstrumentLoading || (!isDelisted && isSymbolLoading)

  // STOMP 실시간 상태
  const [realtimeTick, setRealtimeTick] = useState<TickPayload | null>(null)
  const [realtimeCandle, setRealtimeCandle] = useState<CandleMinutePayload | null>(null)
  const [orderbook, setOrderbook] = useState<OrderbookPayload | null>(null)
  const [orderbookSlot, setOrderbookSlot] = useState<'live' | 'queued' | null>(null)
  const [stompConnected, setStompConnected] = useState(false)

  // STOMP 연결 상태 주기적 확인 (5s)
  useEffect(() => {
    const id = setInterval(() => {
      setStompConnected(isStompConnected())
    }, 5000)
    setStompConnected(isStompConnected())
    return () => clearInterval(id)
  }, [])

  // STOMP topic 구독 (3개 — tick·candle.minute·orderbook).
  // 상장폐지 종목은 실시간 데이터가 없으므로 구독 자체 차단.
  const stompEnabled = Boolean(code) && !isDelisted

  useStompTopic<TickPayload>(
    `/topic/market.tick.${code}`,
    useCallback((payload) => setRealtimeTick(payload), []),
    stompEnabled,
  )

  useStompTopic<CandleMinutePayload>(
    `/topic/market.candle.minute.${code}`,
    useCallback((payload) => setRealtimeCandle(payload), []),
    stompEnabled,
  )

  useStompTopic<OrderbookPayload>(
    `/topic/market.orderbook.${code}`,
    useCallback((payload) => setOrderbook(payload), []),
    stompEnabled,
  )

  // ──────────────────────────────────────────────
  // 호가 slot 라이프사이클 (spec §5 LOCK)
  // ──────────────────────────────────────────────
  const subscribeCalledRef = useRef(false)

  useEffect(() => {
    // 상장폐지 종목은 호가 LRU slot 점유 안 함 (실시간 데이터 없음).
    if (!code || isDelisted || subscribeCalledRef.current) return
    subscribeCalledRef.current = true

    // mount → POST subscribe (200ms 딜레이 — spec §5-2 throttle 정합)
    const timer = setTimeout(() => {
      symbolApi
        .subscribeOrderbook(code)
        .then((res) => {
          setOrderbookSlot(res.slot)
        })
        .catch(() => {
          // 구독 실패 — graceful (backend 미완 시 무시)
        })
    }, 200)

    return () => {
      clearTimeout(timer)
      // cleanup: POST unsubscribe
      // sendBeacon fallback (브라우저 탭 닫기 시에도 발동)
      // Content-Type: application/json blob 전달 — 일부 서버가 text/plain을 reject하는 경우 대비
      const unsubUrl = `/api/symbols/${encodeURIComponent(code)}/orderbook/unsubscribe`
      const beaconData = new Blob([JSON.stringify({ code })], { type: 'application/json' })
      try {
        navigator.sendBeacon(unsubUrl, beaconData)
      } catch {
        // sendBeacon 미지원 환경 — REST fallback
        symbolApi.unsubscribeOrderbook(code).catch(() => {
          // cleanup 실패 — backend 5분 idle timeout으로 자동 회수
        })
      }
      subscribeCalledRef.current = false
    }
  }, [code, isDelisted])

  // ──────────────────────────────────────────────
  // HTTP 에러 분류
  // ──────────────────────────────────────────────
  const httpStatus =
    isError && error && 'response' in (error as { response?: { status?: number } })
      ? (error as { response?: { status?: number } }).response?.status
      : undefined

  // ──────────────────────────────────────────────
  // 렌더링
  // ──────────────────────────────────────────────

  // 로딩 중
  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gray-950">
        <div className="flex flex-col items-center gap-4">
          <div className="h-10 w-10 animate-spin rounded-full border-4 border-gray-800 border-t-blue-500" />
          <p className="text-sm text-gray-500">종목 정보 로딩 중...</p>
        </div>
      </div>
    )
  }

  // 상장폐지 종목 (instrument.active=false) — 가격·차트·호가 진입 차단 + 안내
  if (isDelisted) {
    return (
      <div className="min-h-screen bg-gray-950 px-4 py-8">
        <SymbolError
          code={code}
          variant="delisted"
          delistedAt={instrument?.delistedAt ?? null}
        />
      </div>
    )
  }

  // 데이터 부재 에러 (404·503 등)
  if (isError && isBEError(httpStatus)) {
    return (
      <div className="min-h-screen bg-gray-950 px-4 py-8">
        <SymbolError code={code} message="종목 데이터를 가져올 수 없습니다." />
      </div>
    )
  }

  // symbol 없음 (예상치 못한 경우)
  if (!symbol) {
    return (
      <div className="min-h-screen bg-gray-950 px-4 py-8">
        <SymbolError code={code} />
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-gray-950 px-4 py-6">
      <div className="mx-auto max-w-7xl space-y-4">
        {/* ── 헤더: 종목명·코드·시장·산업 + 복귀 버튼 ── */}
        <SymbolHeader symbol={symbol} />

        {/* ── STOMP 연결 상태 배지 ── */}
        {!stompConnected && (
          <div
            role="status"
            aria-live="polite"
            className="flex items-center gap-2 rounded-lg border border-yellow-800/50 bg-yellow-900/20 px-3 py-2 text-xs text-yellow-400"
          >
            <svg className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z" />
            </svg>
            실시간 연결 재시도 중... (5초 polling fallback 활성)
          </div>
        )}

        {/* ── 본문 레이아웃 (desktop: 3열, mobile: stacked) ── */}
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
          {/* 좌측: 현재가 + 메타 카드 */}
          <div className="flex flex-col gap-4 lg:col-span-1">
            <SymbolPriceCard symbol={symbol} realtimeTick={realtimeTick} />
            <SymbolMetaCard symbol={symbol} />
          </div>

          {/* 우측: 차트 (2열) */}
          <div className="lg:col-span-2">
            <SymbolChart
              code={code}
              realtimeCandle={realtimeCandle}
              liveTick={realtimeTick ? { price: realtimeTick.price, ts: realtimeTick.ts } : null}
            />
          </div>
        </div>

        {/* ── 호가창 (전폭) ── */}
        <SymbolOrderbook
          code={code}
          orderbook={orderbook}
          orderbookSlot={orderbookSlot}
          isStompConnected={stompConnected}
        />
      </div>
    </div>
  )
}
