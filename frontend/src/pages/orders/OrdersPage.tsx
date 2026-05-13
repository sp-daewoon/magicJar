/**
 * OrdersPage — /orders 주문 목록 페이지 (T-O Phase 3 — ADR 0068 §16)
 *
 * 기능:
 * - 주문 시계열 목록 (GET /api/orders, 페이징)
 * - 상태 필터 (REQUESTED·ACK·FILLED·PARTIALLY_FILLED·REJECTED·CANCELLED)
 * - 빈 목록 graceful 안내 (feedback_no_mock_data 정합)
 * - useStompOrder 구독 (4 event 토스트 + 목록 자동 갱신)
 */
import { useState } from 'react'
import { useOrders } from '../../api/hooks/useOrders'
import { useStompOrder } from '../../api/hooks/useStompOrder'
import { useRealTradingConsent } from '../../api/hooks/useUpdateRealTradingConsent'
import { OrderCard } from '../../components/order/OrderCard'
import type { OrderStatus } from '../../api/order'

// TODO: 실제 사용자 ID는 auth context에서 가져와야 함. 현재 MVP 단계에서 '1' 사용.
const CURRENT_USER_ID = '1'

const STATUS_FILTER_OPTIONS: { value: OrderStatus | ''; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'REQUESTED', label: '접수' },
  { value: 'ACK', label: 'KIS 접수' },
  { value: 'PARTIALLY_FILLED', label: '부분 체결' },
  { value: 'FILLED', label: '체결 완료' },
  { value: 'REJECTED', label: '거부' },
  { value: 'CANCELLED', label: '취소' },
]

export function OrdersPage() {
  const [statusFilter, setStatusFilter] = useState<OrderStatus | ''>('')
  const [page, setPage] = useState(0)

  const { data, isLoading, isError, error } = useOrders({
    status: statusFilter || undefined,
    page,
    size: 20,
  })

  // STOMP 구독 — 새 주문 이벤트 발생 시 토스트 + 목록 자동 갱신
  useStompOrder({ userId: CURRENT_USER_ID, enabled: true })

  // 실거래 동의 상태 배지용
  const { data: consent } = useRealTradingConsent()
  const isRealMode = !!(consent?.realTradingConsentAt)

  return (
    <div className="mx-auto max-w-4xl px-4 py-6">
      {/* 헤더 */}
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white">주문 내역</h1>
          <p className="mt-1 text-sm text-gray-400">
            자동매매 주문 의도·체결·감사 이벤트 시계열 (ADR 0068 T-O)
          </p>
        </div>
        {/* 거래 모드 배지 */}
        {isRealMode ? (
          <span className="rounded-lg border border-red-800 bg-red-950/50 px-3 py-1.5 text-xs font-bold text-red-400">
            REAL 거래 모드
          </span>
        ) : (
          <span className="rounded-lg border border-gray-700 bg-gray-900 px-3 py-1.5 text-xs text-gray-500">
            MOCK 모드
          </span>
        )}
      </div>

      {/* 상태 필터 */}
      <div className="mb-4 flex flex-wrap gap-2">
        {STATUS_FILTER_OPTIONS.map((opt) => (
          <button
            key={opt.value}
            type="button"
            onClick={() => {
              setStatusFilter(opt.value)
              setPage(0)
            }}
            className={[
              'rounded-lg px-3 py-1.5 text-xs font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400',
              statusFilter === opt.value
                ? 'bg-blue-600 text-white'
                : 'border border-gray-700 bg-gray-900 text-gray-400 hover:bg-gray-800',
            ].join(' ')}
          >
            {opt.label}
            {opt.value !== '' && statusFilter === opt.value && (
              <span className="ml-1 text-blue-200">
                ({data?.totalElements ?? 0})
              </span>
            )}
          </button>
        ))}
      </div>

      {/* 목록 */}
      {isLoading && (
        <div className="flex items-center justify-center py-12">
          <div className="flex flex-col items-center gap-3">
            <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-800 border-t-blue-500" />
            <p className="text-sm text-gray-500">주문 내역 로딩 중...</p>
          </div>
        </div>
      )}

      {isError && (
        <div className="rounded-xl border border-red-900/50 bg-red-950/20 px-4 py-6 text-center">
          <p className="text-sm text-red-400">주문 내역 조회 실패</p>
          <p className="mt-1 text-xs text-red-600">{error?.message}</p>
        </div>
      )}

      {!isLoading && !isError && data && (
        <>
          {data.content.length === 0 ? (
            // 빈 목록 graceful (feedback_no_mock_data 정합)
            <div className="rounded-xl border border-gray-800 bg-gray-900/50 px-4 py-12 text-center">
              <p className="text-base text-gray-400">주문 내역이 없습니다.</p>
              <p className="mt-2 text-sm text-gray-600">
                {statusFilter
                  ? `'${STATUS_FILTER_OPTIONS.find((o) => o.value === statusFilter)?.label}' 상태의 주문이 없습니다.`
                  : '자동매매 시스템이 주문을 발행하면 이 목록에 표시됩니다.'}
              </p>
            </div>
          ) : (
            <div className="space-y-2">
              {data.content.map((order) => (
                <OrderCard key={order.id} order={order} />
              ))}
            </div>
          )}

          {/* 페이지네이션 */}
          {data.totalPages > 1 && (
            <div className="mt-6 flex items-center justify-between">
              <p className="text-xs text-gray-500">
                전체 {data.totalElements.toLocaleString('ko-KR')}건 · {page + 1}/{data.totalPages} 페이지
              </p>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  disabled={page === 0}
                  className="rounded-lg border border-gray-700 px-3 py-1.5 text-xs text-gray-400 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  이전
                </button>
                <button
                  type="button"
                  onClick={() => setPage((p) => Math.min(data.totalPages - 1, p + 1))}
                  disabled={data.last}
                  className="rounded-lg border border-gray-700 px-3 py-1.5 text-xs text-gray-400 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  다음
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  )
}
