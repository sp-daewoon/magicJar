/**
 * SymbolHeader — 종목명·코드·시장·산업 헤더 + [관심종목으로] 버튼
 *
 * spec §6-2 정합:
 * - 종목명 부재 시 code 그대로 표시 (graceful — spec §10-10)
 * - 산업 부재 시 표시 생략 (instrument 테이블 T-IM1 미완 graceful)
 * - [관심종목으로] 버튼 → useNavigate('/watchlist')
 */
import { useNavigate } from 'react-router-dom'
import type { SymbolDto } from '../../api/symbol'

interface SymbolHeaderProps {
  symbol: Pick<SymbolDto, 'code' | 'name' | 'market' | 'industry'>
}

export function SymbolHeader({ symbol }: SymbolHeaderProps) {
  const navigate = useNavigate()

  return (
    <div className="flex items-start justify-between gap-4">
      <div className="flex flex-col gap-1">
        {/* 종목명 + 코드 */}
        <div className="flex items-baseline gap-2">
          <h1 className="text-xl font-bold text-white">
            {symbol.name ?? symbol.code}
          </h1>
          {symbol.name && (
            <span className="font-mono text-sm text-gray-400 tracking-wider">
              ({symbol.code})
            </span>
          )}
        </div>

        {/* 시장 + 산업 배지 */}
        <div className="flex items-center gap-2">
          {symbol.market && (
            <span className="rounded-md bg-blue-900/40 px-2 py-0.5 text-xs font-medium text-blue-300">
              {symbol.market}
            </span>
          )}
          {symbol.industry && (
            <span className="rounded-md bg-gray-800 px-2 py-0.5 text-xs text-gray-400">
              {symbol.industry}
            </span>
          )}
        </div>
      </div>

      {/* 관심종목으로 복귀 버튼 */}
      <button
        type="button"
        onClick={() => navigate('/watchlist')}
        className="flex items-center gap-1.5 rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-gray-300 transition-colors hover:border-gray-600 hover:bg-gray-700 hover:text-white focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950"
        aria-label="관심종목 목록으로 돌아가기"
      >
        <svg
          className="h-4 w-4"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
          strokeWidth={2}
          aria-hidden="true"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M10.5 19.5 3 12m0 0 7.5-7.5M3 12h18"
          />
        </svg>
        관심종목으로
      </button>
    </div>
  )
}
