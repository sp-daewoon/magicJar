/**
 * useScaleInDefaults — 분할 매수 floor default 조회 hook (ADR 0128 amend R3 갭①)
 *
 * GET /api/strategies/scale-in-defaults → { floorDefaultPct }
 *   - 빌더 floor 필드(stopLossFloorPct)의 prefill 힌트 값을 가져온다.
 *   - 거의 변하지 않는 yml 상수이므로 staleTime 길게(5분) + 재시도 1회.
 *   - 실패 시 graceful fallback: SCALE_IN_FLOOR_DEFAULT_PCT_FALLBACK(15).
 *     ⚠ 이건 입력 폼 default prefill 힌트지 데이터 합성이 아니다(feedback_no_mock_data 위배 아님).
 *     사용자가 그대로 저장해도 백엔드가 null→yml default를 재주입하므로 권위는 항상 백엔드.
 *
 * ADR 0009 TanStack Query 패턴 정합. 룰 5: LLM provider import 0건.
 */
import { useQuery } from '@tanstack/react-query'
import { sigStrategyApi, SCALE_IN_FLOOR_DEFAULT_PCT_FALLBACK, type ScaleInDefaultsDto } from '../strategy'

const SCALE_IN_DEFAULTS_KEY = ['scale-in-defaults'] as const

/** 5분 — yml 상수라 자주 바뀌지 않음 */
const SCALE_IN_DEFAULTS_STALE_MS = 5 * 60 * 1000

/**
 * 분할 매수 floor default 조회. 로딩·에러 중에도 floorDefaultPct fallback(15)을 즉시 노출해
 * 빌더가 floor 필드를 항상 prefill 할 수 있게 한다.
 */
export function useScaleInDefaults() {
  const query = useQuery<ScaleInDefaultsDto>({
    queryKey: SCALE_IN_DEFAULTS_KEY,
    queryFn: () => sigStrategyApi.scaleInDefaults(),
    staleTime: SCALE_IN_DEFAULTS_STALE_MS,
    retry: 1,
  })

  // 성공 시 서버값, 그 외(로딩·에러)는 fallback 상수로 prefill 힌트를 항상 보장
  const floorDefaultPct =
    typeof query.data?.floorDefaultPct === 'number' && Number.isFinite(query.data.floorDefaultPct)
      ? query.data.floorDefaultPct
      : SCALE_IN_FLOOR_DEFAULT_PCT_FALLBACK

  return { ...query, floorDefaultPct }
}
