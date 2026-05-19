/**
 * Order REST API 클라이언트 (T-O Phase 3 — ADR 0068 §16 · T-MAN Phase 2 — ADR 0082)
 *
 * GET    /api/orders                          → OrderListPage
 * GET    /api/orders/{id}                     → OrderDetailDto
 * POST   /api/orders/{id}/cancel              → CancelOrderResponseDto
 * POST   /api/orders/manual                   → ManualOrderResponseDto    (T-MAN Phase 2)
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

/**
 * 주문 출처 (T-MAN B-2-extended LOCK — ADR 0082)
 *
 * 사용자 결정 정합: "데이터 상에선 사용자 주문이랑 자동매매주문의 타입이 구분되어야하는거 필수"
 *
 *  - AUTO   : 자동매매 path — signal.generated.v1 → T-RISK ALLOWED → order.intent.v1 (ADR 0068 §14-2)
 *  - MANUAL : 사용자 수동 주문 — POST /api/orders/manual → order.intent.v1 (ADR 0082 T-MAN)
 */
export type OrderSource = 'AUTO' | 'MANUAL'

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

/**
 * 체결 단건 DTO (분할 체결 1:N)
 *
 * backend 권위 (`OrderQueryController.kt` OrderExecutionDto) 정합:
 *  - executedQty: Int (executedQuantity 아님)
 *  - executedPrice/fee/tax: String (소수점 정밀도 보존 — BigDecimal toString)
 *  - kisOrderNo: backend 응답에 없음
 */
export interface OrderExecutionDto {
  id: number
  orderId: number
  executedQty: number
  executedPrice: string
  executedAt: string
  fee: string
  tax: string
}

/**
 * audit 이벤트 단건 DTO
 *
 * backend 권위 (`OrderQueryController.kt` OrderAuditEventDto) 정합:
 *  - payloadJson: String (non-null — payload 아님)
 *  - ts: String (createdAt 아님)
 *  - orderId: nullable (IDEMPOTENCY_SKIPPED 시 null)
 */
export interface OrderAuditEventDto {
  id: number
  orderId: number | null
  eventType: OrderAuditEventType
  payloadJson: string
  ts: string
}

/**
 * 목록 카드 DTO
 *
 * backend 권위 (`OrderQueryController.kt` OrderListItemDto) 정합:
 *  - qty: Int (quantity 아님)
 *  - limitPrice: String? (소수점 정밀도 보존)
 *  - rejectReason: String? (PR #489 신규 — REAL_GATE_DENIED 등 노출)
 *  - userId: number (ADR 0089 Phase 2 — backend Long → JS number 정합)
 *  - source: OrderSource? (T-MAN B-2-extended 신규 — backend 필드 add 흡수 전까지 optional)
 *
 * T-MAN B-2-extended LOCK (ADR 0082):
 *   `source` 필드는 backend `OrderListItemDto.source` 컬럼 add 후 항상 'AUTO'|'MANUAL' 도래.
 *   현재는 backend Phase 1b 흡수 의존 (optional 안전망).
 */
export interface OrderListItemDto {
  id: number
  userId: number
  code: string
  side: OrderSide
  priceType: OrderPriceType
  qty: number
  limitPrice: string | null
  status: OrderStatus
  mode: TradingMode
  kisOrderNo: string | null
  rejectReason: string | null
  requestedAt: string
  completedAt: string | null
  /** T-MAN B-2-extended — backend 필드 합류 전까지 optional. 미수신 시 'AUTO' default 가정. */
  source?: OrderSource
}

/**
 * 단건 상세 DTO (request + executions + audit)
 *
 * backend 권위 (`OrderQueryController.kt` OrderDetailResponse) 정합:
 *  - request: OrderListItemDto (nest 구조 — 기존 평면 구조 폐기)
 *  - executions: 분할 체결 1:N
 *  - auditEvents: 감사 이벤트 시계열
 */
export interface OrderDetailDto {
  request: OrderListItemDto
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

/** 목록 조회 파라미터 (ADR 0089 — userId number) */
export interface OrderListParams {
  userId?: number
  status?: OrderStatus | OrderStatus[]
  side?: OrderSide
  code?: string
  /** T-MAN B-2-extended — source filter (AUTO·MANUAL). 미지정 시 전체. */
  source?: OrderSource
  from?: string
  to?: string
  page?: number
  size?: number
}

// ──────────────────────────────────────────────
// T-MAN — 수동 주문 DTO (ADR 0082 T-MAN B-2-extended)
// ──────────────────────────────────────────────

/**
 * 수동 주문 요청 DTO (POST /api/orders/manual).
 *
 * backend 권위 (`api/.../dto/ManualOrderRequestDto.kt`) 정합:
 *  - userId: KIS credential identity (path 아닌 body field) — number (ADR 0089 Phase 2)
 *  - code: 6자리 숫자 LOCK (`^\d{6}$`)
 *  - side: 'BUY' | 'SELL'
 *  - priceType: 'MARKET' | 'LIMIT'
 *  - quantity: 양수 LOCK (Min(1))
 *  - limitPrice: LIMIT 시 not null (string — BigDecimal 정밀도)
 */
export interface ManualOrderRequest {
  userId: number
  code: string
  side: OrderSide
  priceType: OrderPriceType
  quantity: number
  limitPrice?: string | null
}

/**
 * 수동 주문 응답 DTO (202 Accepted).
 *
 * backend 권위 (`api/.../dto/ManualOrderResponseDto.kt`) 정합:
 *  - orderId: null (consumer step 5 INSERT 시점 채번)
 *  - status: 'ACCEPTED' (endpoint 응답 마커 — OrderRequest.status 아님)
 *  - idempotencyKey: "manual:{userId}:{epochSeconds}"
 *  - message: 한국어 안내문
 */
export interface ManualOrderResponse {
  orderId: number | null
  status: string
  idempotencyKey: string
  message: string
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
    if (params.userId !== undefined) searchParams.set('userId', String(params.userId))
    if (params.status) {
      if (Array.isArray(params.status)) {
        params.status.forEach((s) => searchParams.append('status', s))
      } else {
        searchParams.set('status', params.status)
      }
    }
    if (params.side) searchParams.set('side', params.side)
    if (params.code) searchParams.set('code', params.code)
    if (params.source) searchParams.set('source', params.source)
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
  get: async (id: number, userId: number): Promise<OrderDetailDto> => {
    const res = await apiClient.get<OrderDetailDto>(
      `/orders/${id}?userId=${userId}`,
    )
    return res.data
  },

  /**
   * POST /api/orders/{id}/cancel
   * 사용자 수동 취소 (ADR 0068 의제 E-1)
   */
  cancel: async (id: number, userId: number, reason?: string): Promise<CancelOrderResponseDto> => {
    const res = await apiClient.post<CancelOrderResponseDto>(
      `/orders/${id}/cancel?userId=${userId}`,
      { reason: reason ?? null },
    )
    return res.data
  },

  /**
   * POST /api/orders/manual
   * 사용자 수동 주문 발행 (T-MAN B-2-extended — ADR 0082).
   *
   * 202 Accepted + ManualOrderResponse (orderId=null — consumer step 5 INSERT 시점 채번).
   * 3중 게이트(MAGICJAR_MODE·MAGICJAR_ALLOW_REAL·real_trading_consent_at) precheck + KillSwitch precheck는
   * backend fast precheck + consumer step 3·4 권위 재검증.
   */
  manualIssue: async (request: ManualOrderRequest): Promise<ManualOrderResponse> => {
    const res = await apiClient.post<ManualOrderResponse>('/orders/manual', request)
    return res.data
  },
}
