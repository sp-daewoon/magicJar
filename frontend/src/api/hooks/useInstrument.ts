/**
 * useInstrument — 종목 마스터 detail TanStack Query hook
 *
 * 용도: SymbolDetailPage에서 instrument.active 등 마스터 메타 확인.
 * 가격(SymbolDto)은 별도 useSymbol — 둘은 데이터 소스가 다름:
 *   - SymbolDto: KIS inquire-price (실시간 가격) — 폐지 종목은 404/빈응답
 *   - InstrumentDetailDto: instrument 테이블 (마스터 메타) — 폐지 종목도 active=false로 조회 가능
 *
 * staleTime: 60s — 마스터 메타는 자주 안 바뀜 (sync 6시간 주기).
 */
import { useQuery } from '@tanstack/react-query'
import { instrumentApi } from '../instrument'

export function useInstrument(code: string) {
  return useQuery({
    queryKey: ['instrument', code] as const,
    queryFn: () => instrumentApi.getByCode(code),
    staleTime: 60_000,
    enabled: Boolean(code),
    retry: 1,
  })
}
