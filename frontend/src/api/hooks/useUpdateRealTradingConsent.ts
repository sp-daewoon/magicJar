/**
 * useUpdateRealTradingConsent — 실거래 동의 토글 훅 (T-O Phase 3 — ADR 0068 §16)
 *
 * PUT /api/user-account/real-trading-consent
 * 성공 시 consent 쿼리 자동 무효화
 *
 * 이중 confirm UX: UI 레이어(RealTradingConsentToggle)에서 수행 (§16-4)
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  accountApi,
  type ConsentToggleRequestDto,
  type ConsentToggleResponseDto,
} from '../account'

export function useRealTradingConsent() {
  return useQuery<ConsentToggleResponseDto, Error>({
    queryKey: ['realTradingConsent'],
    queryFn: () => accountApi.getRealTradingConsent(),
    staleTime: 60_000,
  })
}

export function useUpdateRealTradingConsent() {
  const queryClient = useQueryClient()

  return useMutation<ConsentToggleResponseDto, Error, ConsentToggleRequestDto>({
    mutationFn: (req) => accountApi.updateRealTradingConsent(req),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['realTradingConsent'] })
    },
  })
}
