/**
 * Order REST API 클라이언트 (T-O Phase 3 — ADR 0068 §16)
 *
 * GET    /api/orders                          → OrderListPage
 * GET    /api/orders/{id}                     → OrderDetailDto
 * POST   /api/orders/{id}/cancel              → CancelOrderResponseDto
 *
 * 룰 5: LLM provider import 0건
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// enum / literal
// ──────────────────────────────────────────────

/** 주문 상태 (ADR 0068 §5-1 OrderStatus 정합) */
export type OrderStatus =
  | 'REQUESTED'
  | 'ACK'
  | 'FILLED'
  | 'PARTIALLY_FILLED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'EXPIRED'

/** 매수/매도 구분 */
export type OrderSide = 'BUY' | 'SELL'

/** 주문 가격 유형 */
export type OrderPriceType = 'MARKET' | 'LIMIT'

/** 거래 모드 */
export type TradingMode = 'MOCK' | 'REAL'

/** audit event 유형 (ADR 0068 §5-3 OrderAuditEventType 정합) */
export type OrderAuditEventType =
  | 'REQUESTED'
  | 'REAL_GATE_DENIED'
  | 'IDEMPOTENCY_SKIPPED'
  | 'KILLSWITCH_DENIED'
  | 'KIS_REQUEST'
  | 'KIS_ACK'
  | 'KIS_REJECT'
  | 'KIS_EXCEPTION'
  | 'FILL_RECEIVED'
  | 'PARTIAL_FILL'
  | 'FULL_FILL'
  | 'USER_CANCEL_REQUEST'
  | 'KIS_CANCEL_ACK'
  | 'KIS_CANCEL_REJECT'
  | 'EXECUTION_TIMEOUT'
  | 'EXECUTION_OVERFLOW'

// ──────────────────────────────────────────────
// DTO
// ──────────────────────────────────────────────

/** 체결 단건 DTO (분할 체결 1:N) */
export interface OrderExecutionDto {
  id: number
  orderId: number
  kisOrderNo: string
  executedQuantity: number
  executedPrice: number
  executedAt: string
  fee: number | null
  tax: number | null
}

/** audit 이벤트 단건 DTO */
export interface OrderAuditEventDto {
  id: number
  orderId: number
  eventType: OrderAuditEventType
  payload: string | null
  createdAt: string
}

/** 목록 카드 DTO */
export interface OrderListItemDto {
  id: number
  code: string
  side: OrderSide
  priceType: OrderPriceType
  quantity: number
  limitPrice: number | null
  status: OrderStatus
  mode: TradingMode
  kisOrderNo: string | null
  rejectReason: string | null
  requestedAt: string
  completedAt: string | null
}

/** 단건 상세 DTO (request + executions + audit) */
export interface OrderDetailDto {
  id: number
  code: string
  side: OrderSide
  priceType: OrderPriceType
  quantity: number
  limitPrice: number | null
  status: OrderStatus
  mode: TradingMode
  kisOrderNo: string | null
  kisFwdgOrgno: string | null
  rejectReason: string | null
  requestedAt: string
  acknowledgedAt: string | null
  completedAt: string | null
  executions: OrderExecutionDto[]
  auditEvents: OrderAuditEventDto[]
}

/** 취소 응답 DTO */
export interface CancelOrderResponseDto {
  id: number
  status: OrderStatus
  cancelledAt: string
}

/** 목록 페이지 응답 */
export interface OrderListPage {
  content: OrderListItemDto[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

/** 목록 조회 파라미터 */
export interface OrderListParams {
  userId?: string
  status?: OrderStatus | OrderStatus[]
  side?: OrderSide
  code?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const orderApi = {
  /**
   * GET /api/orders
   * 주문 시계열 목록 (status·side·code·date range 필터 + 페이징)
   */
  list: async (params: OrderListParams = {}): Promise<OrderListPage> => {
    const searchParams = new URLSearchParams()
    if (params.userId) searchParams.set('userId', params.userId)
    if (params.status) {
      if (Array.isArray(params.status)) {
        params.status.forEach((s) => searchParams.append('status', s))
      } else {
        searchParams.set('status', params.status)
      }
    }
    if (params.side) searchParams.set('side', params.side)
    if (params.code) searchParams.set('code', params.code)
    if (params.from) searchParams.set('from', params.from)
    if (params.to) searchParams.set('to', params.to)
    if (params.page !== undefined) searchParams.set('page', String(params.page))
    if (params.size !== undefined) searchParams.set('size', String(params.size))

    const qs = searchParams.toString()
    const res = await apiClient.get<OrderListPage>(`/orders${qs ? `?${qs}` : ''}`)
    return res.data
  },

  /**
   * GET /api/orders/{id}
   * 주문 단건 (request + executions N건 + audit_events 시계열)
   */
  get: async (id: number, userId: string): Promise<OrderDetailDto> => {
    const res = await apiClient.get<OrderDetailDto>(
      `/orders/${id}?userId=${encodeURIComponent(userId)}`,
    )
    return res.data
  },

  /**
   * POST /api/orders/{id}/cancel
   * 사용자 수동 취소 (ADR 0068 의제 E-1)
   */
  cancel: async (id: number, userId: string, reason?: string): Promise<CancelOrderResponseDto> => {
    const res = await apiClient.post<CancelOrderResponseDto>(
      `/orders/${id}/cancel?userId=${encodeURIComponent(userId)}`,
      { reason: reason ?? null },
    )
    return res.data
  },
}
