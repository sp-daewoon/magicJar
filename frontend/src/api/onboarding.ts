/**
 * Onboarding REST API 클라이언트
 *
 * spec §4-1 REST 시그니처 LOCK 1:1 정합
 * 5 endpoint: status / kis/validate / kis/mock / system-config / complete
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import axios from 'axios'
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// Response / Request 타입 정의 (spec §4-1 LOCK)
// ──────────────────────────────────────────────

/** GET /api/onboarding/status */
export interface OnboardingStatus {
  hasUserAccount: boolean
  hasMockAccount: boolean
  hasDartKey: boolean
  hasSlackWebhook: boolean
}

/** POST /api/onboarding/kis/validate — 요청 */
export interface KisValidateRequest {
  accountNumber: string // 8자리 KIS 실전 계좌번호
  displayName?: string // UI 닉네임 (선택, 최대 100자)
  appKey: string // KIS 콘솔 발급
  secretKey: string
}

/** POST /api/onboarding/kis/validate — 응답 (200) */
export interface KisValidateResponse {
  userId: string // 8자리
  maskedAccount: string // "7468****"
  displayName?: string
  status: 'VALIDATED'
  validatedAt: string // ISO 8601
}

/** POST /api/onboarding/kis/mock — 요청 */
export interface KisMockRequest {
  userId: string // 실전 계좌번호 (이미 등록되어 있어야 함)
  mockAccountNumber: string // KIS 모의 발급 계좌번호 (8자리)
  appKey: string
  secretKey: string
}

/** POST /api/onboarding/kis/mock — 응답 (200) */
export interface KisMockResponse {
  userId: string
  mockAccountNumber: string
  status: 'VALIDATED'
}

/** POST /api/onboarding/system-config — 요청 */
export interface SystemConfigRequest {
  dartApiKey?: string // null = 변경 안 함, "" = 삭제, 비어있지 않음 = 등록·갱신
  slackWebhookUrl?: string // 동일 정책 + URL validation
}

/** POST /api/onboarding/system-config — 응답 (200) */
export interface SystemConfigResponse {
  hasDartKey: boolean
  hasSlackWebhook: boolean
  updatedAt: string // ISO 8601
}

/** POST /api/onboarding/complete — 요청 */
export interface OnboardingCompleteRequest {
  userId: string
}

/** POST /api/onboarding/complete — 응답 (200) */
export interface OnboardingCompleteResponse {
  userId: string
  maskedAccount: string
  redirectTo: string // "/"
}

/** 공통 에러 응답 포맷 (spec §4-2) */
export interface ErrorResponse {
  code: string
  message: string
}

/**
 * axios 에러에서 backend ErrorResponse 메시지를 추출하는 헬퍼
 * 실패 시 fallback 메시지 반환
 */
export function extractErrorMessage(err: unknown, fallback: string): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as Partial<ErrorResponse> | undefined
    return data?.message ?? fallback
  }
  return fallback
}

// ──────────────────────────────────────────────
// TanStack Query hooks
// ──────────────────────────────────────────────

/**
 * 온보딩 상태 조회
 * 라우팅 가드에서 사용 — staleTime=0 으로 마법사 완료 후 즉시 재조회
 */
export function useOnboardingStatus() {
  return useQuery<OnboardingStatus>({
    queryKey: ['onboarding', 'status'],
    queryFn: async () => {
      const res = await apiClient.get<OnboardingStatus>('/onboarding/status')
      return res.data
    },
    retry: 1,
    staleTime: 0,
  })
}

/**
 * KIS 실전 키 검증 + DB 영속 mutation
 * Step 2에서 사용 — 성공 시 KisValidateResponse 반환
 */
export function useValidateKisReal() {
  const queryClient = useQueryClient()
  return useMutation<KisValidateResponse, unknown, KisValidateRequest>({
    mutationFn: async (req) => {
      const res = await apiClient.post<KisValidateResponse>('/onboarding/kis/validate', req)
      return res.data
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['onboarding', 'status'] })
    },
  })
}

/**
 * KIS 모의계좌 추가 mutation
 * Step 3에서 사용 (선택)
 */
export function useAddMockAccount() {
  return useMutation<KisMockResponse, unknown, KisMockRequest>({
    mutationFn: async (req) => {
      const res = await apiClient.post<KisMockResponse>('/onboarding/kis/mock', req)
      return res.data
    },
  })
}

/**
 * 시스템 설정 갱신 mutation
 * Step 4에서 사용 (선택)
 */
export function useUpdateSystemConfig() {
  return useMutation<SystemConfigResponse, unknown, SystemConfigRequest>({
    mutationFn: async (req) => {
      const res = await apiClient.post<SystemConfigResponse>('/onboarding/system-config', req)
      return res.data
    },
  })
}

/**
 * 온보딩 완료 mutation
 * Step 5 "시작하기" 버튼에서 사용
 */
export function useCompleteOnboarding() {
  const queryClient = useQueryClient()
  return useMutation<OnboardingCompleteResponse, unknown, OnboardingCompleteRequest>({
    mutationFn: async (req) => {
      const res = await apiClient.post<OnboardingCompleteResponse>('/onboarding/complete', req)
      return res.data
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['onboarding', 'status'] })
    },
  })
}
