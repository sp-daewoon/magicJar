import { apiClient } from './client'

export type Side = 'BUY' | 'SELL'
export type OrderType = 'MARKET' | 'LIMIT'
export type OrderStatus =
  | 'PENDING'
  | 'PARTIALLY_FILLED'
  | 'FILLED'
  | 'CANCELED'
  | 'REJECTED'

export interface OrderResponse {
  orderId: string
  kisOrderNo: string
  krxFwdgOrdOrgno: string
  accountNo: string
  kisMode: string
  code: string
  side: Side
  qty: number
  price: number | null
  orderType: OrderType
  status: OrderStatus
  filledQty: number
  avgFillPrice: number | null
  rejectReason: string | null
  createdAt: string
  updatedAt: string
}

export interface OrderRequest {
  code: string
  side: Side
  qty: number
  price: number | null
  orderType: OrderType
}

export interface OrderTriggerResponse {
  intentId: string
  publishedAt: string
}

export const ordersApi = {
  list: () => apiClient.get<OrderResponse[]>('/orders'),
  listOpen: () => apiClient.get<OrderResponse[]>('/orders/open'),
  trigger: (req: OrderRequest) =>
    apiClient.post<OrderTriggerResponse>('/orders', req),
}
