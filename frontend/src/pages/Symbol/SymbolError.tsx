/**
 * SymbolError — 종목 데이터 부재 시 에러 표시 컴포넌트
 *
 * spec §6-5 + DoD #12 정합:
 * - HTTP 503/404 → "종목 정보를 찾을 수 없습니다" 명시
 * - mock 합성 0 (사용자 메모리 feedback_no_mock_data.md 정합)
 * - [관심종목으로] 버튼 표시
 */
import { useNavigate } from 'react-router-dom'

interface SymbolErrorProps {
  code: string
  message?: string
}

export function SymbolError({ code, message }: SymbolErrorProps) {
  const navigate = useNavigate()

  return (
    <div className="flex min-h-96 items-center justify-center px-4">
      <div className="flex flex-col items-center gap-6 text-center max-w-sm">
        {/* 아이콘 */}
        <div className="flex h-16 w-16 items-center justify-center rounded-full bg-gray-800">
          <svg
            className="h-8 w-8 text-gray-500"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={1.5}
            aria-hidden="true"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M12 9v3.75m9-.75a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9 3.75h.008v.008H12v-.008Z"
            />
          </svg>
        </div>

        {/* 에러 메시지 */}
        <div className="flex flex-col gap-2">
          <h2 className="text-base font-semibold text-white">종목 정보를 찾을 수 없습니다</h2>
          {code && (
            <p className="text-sm text-gray-500">
              종목 코드: <span className="font-mono text-gray-400">{code}</span>
            </p>
          )}
          {message && (
            <p className="text-xs text-gray-600">{message}</p>
          )}
        </div>

        {/* 관심종목으로 복귀 버튼 */}
        <button
          type="button"
          onClick={() => navigate('/watchlist')}
          className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-2 focus:ring-offset-gray-950"
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
    </div>
  )
}
