/**
 * KIS 인증 API 클라이언트
 * T18a 백엔드 endpoint 정합 — T18a 머지 전까지는 stub fallback
 */

import { apiClient } from './client'

/** KIS 인증 모드 */
export type KisMode = 'vps' | 'prod'

/**
 * GET /api/auth/status?userId=...
 */
export interface AuthStatusResponse {
  authenticated: boolean
  mode: KisMode | null
  accountNo: string | null   // 마스킹 표시용 (예: 1234-XX-XXXX)
  hasAppKey: boolean
  hasAppSecret: boolean
  lastLoginAt?: string | null // (legacy, 미사용)
  lastUpdatedAt?: string | null // T18a 백엔드 필드명
}

/**
 * POST /api/auth/login
 */
export interface LoginRequest {
  userId: string
  mode: KisMode
  appKey: string
  appSecret: string
  accountNo: string
}

export interface LoginResponse {
  authenticated: boolean
  mode: KisMode
  message: string
}

/**
 * POST /api/auth/mode
 */
export interface ChangeModeRequest {
  userId: string
  mode: KisMode
}

export interface ChangeModeResponse {
  mode: KisMode
  message: string
}

/**
 * POST /api/instruments/master/sync
 */
export interface MasterSyncRequest {
  markets: Array<'KOSPI' | 'KOSDAQ' | 'ETF' | 'ETN'>
}

export interface MasterSyncResponse {
  runId: string
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  message?: string
}

/**
 * GET /api/instruments/master/sync/{runId}
 */
export interface MasterSyncStatusResponse {
  runId: string
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  processedCount?: number
  totalCount?: number
  errorMessage?: string
  completedAt?: string
}

export const authApi = {
  getStatus: (userId: string) =>
    apiClient.get<AuthStatusResponse>(`/auth/status?userId=${encodeURIComponent(userId)}`),

  login: (req: LoginRequest) =>
    apiClient.post<LoginResponse>('/auth/login', req),

  changeMode: (req: ChangeModeRequest) =>
    apiClient.post<ChangeModeResponse>('/auth/mode', req),
}

export const masterApi = {
  sync: (req: MasterSyncRequest) =>
    apiClient.post<MasterSyncResponse>('/instruments/master/sync', req),

  getStatus: (runId: string) =>
    apiClient.get<MasterSyncStatusResponse>(`/instruments/master/sync/${runId}`),
}
