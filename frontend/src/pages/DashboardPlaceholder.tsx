/**
 * 메인 대시보드 placeholder.
 *
 * 실 구현: PR-7+ (T-W1 관심종목 + 차트 + 주문 + 포지션)
 * 진입점: ADR 0040 — 관심종목(/watchlist)이 UI default, 대시보드로 연결
 */
export function DashboardPlaceholder() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-gray-950 px-4">
      {/* 로고 영역 */}
      <div className="mb-8 text-center">
        <div className="mb-2 text-5xl font-bold tracking-tight text-white">
          magic<span className="text-blue-400">Jar</span>
        </div>
        <p className="text-sm text-gray-400">마법의항아리 — 한국 주식 자동매매 시스템</p>
      </div>

      {/* 안내 카드 */}
      <div className="w-full max-w-md rounded-2xl border border-gray-800 bg-gray-900 p-8 shadow-xl">
        <h1 className="mb-3 text-xl font-semibold text-white">메인 대시보드</h1>
        <p className="mb-6 text-sm leading-relaxed text-gray-400">
          이 화면은 <span className="font-medium text-blue-400">PR-7+</span>에서 구현됩니다.
          <br />
          관심종목(Watchlist) · 실시간 차트 · 주문 · 포지션 화면이 이곳에 배치됩니다.
        </p>

        {/* 예정 화면 목록 */}
        <ul className="mb-6 space-y-2 text-sm text-gray-500">
          <li className="flex items-center gap-2">
            <span className="h-1.5 w-1.5 rounded-full bg-gray-600" />
            관심종목 (T-W1) — 기본 진입 화면
          </li>
          <li className="flex items-center gap-2">
            <span className="h-1.5 w-1.5 rounded-full bg-gray-600" />
            TradingView LWC v4 차트 (T-LWC)
          </li>
          <li className="flex items-center gap-2">
            <span className="h-1.5 w-1.5 rounded-full bg-gray-600" />
            전략 빌더 GUI (T-SB)
          </li>
          <li className="flex items-center gap-2">
            <span className="h-1.5 w-1.5 rounded-full bg-gray-600" />
            AI 옵션 콘솔 (T-AI — 사용자 트리거 시에만)
          </li>
        </ul>

        {/* 관심종목 진입 (ADR 0040 — Watchlist-first UI) */}
        <a
          href="/watchlist"
          className="block w-full rounded-xl bg-blue-600 px-4 py-3 text-center text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-2 focus:ring-offset-gray-900"
        >
          관심종목 시작하기 →
        </a>
      </div>

      {/* 버전 정보 */}
      <p className="mt-6 text-xs text-gray-700">v3.0.1 · PR-5b 스캐폴드</p>
    </div>
  )
}
