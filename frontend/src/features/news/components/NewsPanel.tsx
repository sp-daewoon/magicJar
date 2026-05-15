/**
 * NewsPanel — 종목 상세 sidebar 뉴스 패널
 *
 * ADR 0075 §8-5 Phase 3 산출물 + spec §2-3 핵심 결정 LOCK:
 *   - useSymbolNews hook (refetchInterval 60s)
 *   - STOMP 0 LOCK (§6 사용자 결정 6-1)
 *   - 빈 상태: "최근 7일간 뉴스가 없습니다" (feedback_no_mock_data 정합)
 *   - Naver 키 미등록 + 빈 array → 안내 문구 표시
 *   - AI provider import 0 (§11-2): sentiment 필드 NULL render only
 *   - URL 새 탭 open (target="_blank" rel="noopener noreferrer")
 *
 * 페이지네이션: "더 보기" 버튼으로 page 증가 (totalPages 기준 제어)
 */
import { useState } from 'react'
import { useSymbolNews } from '../hooks/useSymbolNews'
import { NewsItemRow } from './NewsItem'

interface Props {
  code: string
}

export function NewsPanel({ code }: Props) {
  const [page, setPage] = useState(0)
  const { data, isLoading, isError } = useSymbolNews(code, { page, size: 20 })

  // ── 로딩 스켈레톤
  if (isLoading) {
    return (
      <section aria-label="뉴스" className="rounded-lg border border-gray-800 bg-gray-900 p-4">
        <h3 className="mb-3 text-sm font-semibold text-gray-300">뉴스</h3>
        <div className="space-y-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="space-y-1 rounded bg-gray-800 p-3 animate-pulse">
              <div className="h-3 w-3/4 rounded bg-gray-700" />
              <div className="h-2 w-1/2 rounded bg-gray-700" />
            </div>
          ))}
        </div>
      </section>
    )
  }

  // ── REST 실패
  if (isError) {
    return (
      <section aria-label="뉴스" className="rounded-lg border border-gray-800 bg-gray-900 p-4">
        <h3 className="mb-3 text-sm font-semibold text-gray-300">뉴스</h3>
        <p className="text-xs text-red-400">뉴스를 불러오지 못했습니다.</p>
      </section>
    )
  }

  const items = data?.items ?? []
  const totalPages = data?.totalPages ?? 0
  const hasMore = page < totalPages - 1

  return (
    <section aria-label="뉴스" className="rounded-lg border border-gray-800 bg-gray-900 p-4">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-gray-300">뉴스</h3>
        {data && data.totalElements > 0 && (
          <span className="text-xs text-gray-500">{data.totalElements}건</span>
        )}
      </div>

      {/* 빈 상태 (ADR 0075 §11-3 + feedback_no_mock_data 정합) */}
      {items.length === 0 ? (
        <div className="py-4 text-center">
          <p className="text-xs text-gray-500">최근 7일간 뉴스가 없습니다.</p>
          <p className="mt-1 text-xs text-gray-600">
            뉴스 수집을 시작하려면 서버 환경변수{' '}
            <code className="rounded bg-gray-800 px-1 text-gray-400">NAVER_OPEN_API_CLIENT_ID</code>
            를 설정하세요.
          </p>
        </div>
      ) : (
        <>
          <ul>
            {items.map((item) => (
              <NewsItemRow key={item.id} item={item} />
            ))}
          </ul>

          {/* 더 보기 */}
          {hasMore && (
            <button
              type="button"
              onClick={() => setPage((p) => p + 1)}
              className="mt-3 w-full rounded border border-gray-700 py-1.5 text-xs text-gray-400 hover:border-gray-500 hover:text-gray-200 transition-colors"
            >
              더 보기
            </button>
          )}

          {/* 첫 페이지 이상에서 처음으로 돌아가기 */}
          {page > 0 && (
            <button
              type="button"
              onClick={() => setPage(0)}
              className="mt-2 w-full text-xs text-gray-600 hover:text-gray-400 transition-colors"
            >
              처음으로
            </button>
          )}
        </>
      )}
    </section>
  )
}
