/**
 * API 클라이언트 — P3는 mock stub, P4에서 orval로 자동 생성
 * baseURL: /api (vite proxy → http://localhost:8080)
 */

const BASE_URL = '/api'

export interface ApiError {
  status: number
  message: string
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...init?.headers,
    },
    ...init,
  })

  if (!res.ok) {
    const err: ApiError = {
      status: res.status,
      message: await res.text().catch(() => res.statusText),
    }
    throw err
  }

  // 204 No Content
  if (res.status === 204) {
    return undefined as T
  }

  return res.json() as Promise<T>
}

export const apiClient = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body: unknown) =>
    request<T>(path, { method: 'POST', body: JSON.stringify(body) }),
  put: <T>(path: string, body: unknown) =>
    request<T>(path, { method: 'PUT', body: JSON.stringify(body) }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}

import type {
  BacktestStatusResponse,
  BacktestTriggerRequest,
  BacktestTriggerResponse,
} from './types'

// Mock stubs — 추후 orval generated/로 교체
export const symbolsApi = {
  search: (q: string) =>
    apiClient.get<Array<{ code: string; name: string }>>(`/symbols/search?q=${q}`),
}

export const backtestApi = {
  /** POST /api/backtest — 즉시 runId 반환, 실 실행은 batch-app이 비동기. */
  trigger: (params: BacktestTriggerRequest) =>
    apiClient.post<BacktestTriggerResponse>('/backtest', params),
  /** GET /api/backtest/{runId} — 폴링용. summaryJson은 string, 클라이언트가 JSON.parse. */
  status: (runId: string) => apiClient.get<BacktestStatusResponse>(`/backtest/${runId}`),
}

export const journalApi = {
  list: (page = 0) => apiClient.get<unknown>(`/journal?page=${page}`),
  get: (id: string) => apiClient.get<unknown>(`/journal/${id}`),
}
