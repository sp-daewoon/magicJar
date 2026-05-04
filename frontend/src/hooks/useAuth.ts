/**
 * KIS 인증 상태 관리 hook
 * TanStack Query 기반 — 5초 캐시, 모드 전환 시 invalidate
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { authApi } from '@/api/auth'
import type { LoginRequest, ChangeModeRequest, KisMode } from '@/api/auth'

/** 기본 userId — MVP 단계 단일 사용자. V21에서 UUID→VARCHAR(64) + KIS 모의 계좌번호 8자리로 통일. */
export const DEFAULT_USER_ID = '74683223'

const AUTH_QUERY_KEY = (userId: string) => ['auth', 'status', userId]

/**
 * 인증 상태 조회 — 5초 staleTime
 * T18a 미머지 상태에서는 404/500 → error 상태로 graceful fallback
 */
export function useAuthStatus(userId: string = DEFAULT_USER_ID) {
  return useQuery({
    queryKey: AUTH_QUERY_KEY(userId),
    queryFn: () => authApi.getStatus(userId),
    staleTime: 5_000,
    retry: 1,
    // 백엔드 미연결 시 에러 상태 그대로 노출 — UI가 fallback 메시지 표시
  })
}

/**
 * 로그인 mutation — 성공 시 auth status 캐시 invalidate
 */
export function useLoginMutation(userId: string = DEFAULT_USER_ID) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (req: LoginRequest) => authApi.login(req),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: AUTH_QUERY_KEY(userId) })
    },
  })
}

/**
 * 모드 전환 mutation — 성공 시 auth status 캐시 invalidate
 */
export function useChangeMode(userId: string = DEFAULT_USER_ID) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (mode: KisMode) =>
      authApi.changeMode({ userId, mode } satisfies ChangeModeRequest),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: AUTH_QUERY_KEY(userId) })
    },
  })
}
