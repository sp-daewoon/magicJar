/**
 * RiskConfig TanStack Query hooks (T-RISK Phase 3 — ADR 0067)
 *
 * hooks:
 *   useRiskConfig()         — useQuery (단건)
 *   useUpdateRiskConfig()   — useMutation (PUT)
 *   useResetRiskConfig()    — useMutation (POST /reset)
 *
 * ADR 0009 TanStack Query 패턴 정합
 * 룰 5: LLM provider import 0건
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { riskApi, type RiskConfigDto, type RiskConfigUpdateRequest } from '../risk'

const RISK_CONFIG_KEY = ['risk-config'] as const

/**
 * 리스크 설정 조회 hook
 * staleTime=30s — default seed 포함
 */
export function useRiskConfig() {
  return useQuery<RiskConfigDto>({
    queryKey: RISK_CONFIG_KEY,
    queryFn: () => riskApi.getConfig(),
    staleTime: 30 * 1000,
  })
}

/**
 * 리스크 설정 갱신 mutation
 * 성공 시 쿼리 invalidate → 즉시 재조회
 */
export function useUpdateRiskConfig() {
  const queryClient = useQueryClient()
  return useMutation<RiskConfigDto, unknown, RiskConfigUpdateRequest>({
    mutationFn: (req) => riskApi.updateConfig(req),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: RISK_CONFIG_KEY })
    },
  })
}

/**
 * 리스크 설정 초기화 mutation (default seed)
 * 성공 시 쿼리 invalidate → 즉시 재조회
 */
export function useResetRiskConfig() {
  const queryClient = useQueryClient()
  return useMutation<RiskConfigDto, unknown, void>({
    mutationFn: () => riskApi.resetConfig(),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: RISK_CONFIG_KEY })
    },
  })
}
