/**
 * TradingJournalPage — /journal 라우트
 *
 * ADR 0072 §2-6 F-1 + spec §3-3 Phase 3 정합:
 * - filter bar + JournalEntryList + 페이지네이션
 * - 행 클릭 → JournalEntryDetailModal
 * - userId: useOnboardingStore.registeredUserId (null 시 setup 안내)
 * - mock 합성 0 — 빈 결과 "매매일지가 비어있습니다" 명시
 */
import { useState } from 'react'
import { useOnboardingStore } from '../stores/onboarding'
import { JournalEntryFilterBar } from '../features/journal/components/JournalEntryFilterBar'
import { JournalEntryList } from '../features/journal/components/JournalEntryList'
import { JournalEntryDetailModal } from '../features/journal/components/JournalEntryDetailModal'
import { useJournalEntries } from '../features/journal/hooks/useJournalEntries'
import type { TradingJournalEntryDto } from '../features/journal/api/journalApi'

interface FilterState {
  from: string
  to: string
  instrumentCode: string
}

const DEFAULT_FILTER: FilterState = {
  from: '',
  to: '',
  instrumentCode: '',
}

export function TradingJournalPage() {
  // ADR 0089 Phase 2 — registeredUserId now number, no conversion needed
  const userId = useOnboardingStore((s) => s.registeredUserId)

  const [filter, setFilter] = useState<FilterState>(DEFAULT_FILTER)
  const [page, setPage] = useState(0)
  const [selectedEntry, setSelectedEntry] = useState<TradingJournalEntryDto | null>(null)

  // 필터 변경 시 page 리셋
  function handleFilterChange(next: FilterState) {
    setFilter(next)
    setPage(0)
  }

  function handleFilterReset() {
    setFilter(DEFAULT_FILTER)
    setPage(0)
  }

  const { data, isLoading, isError } = useJournalEntries({
    userId,
    from: filter.from || undefined,
    to: filter.to || undefined,
    instrumentCode: filter.instrumentCode || undefined,
    page,
    size: 20,
  })

  // userId 미등록 → setup 안내
  if (!userId) {
    return (
      <div className="mx-auto max-w-7xl px-4 py-10">
        <div className="rounded-lg border border-yellow-800 bg-yellow-900/20 p-6 text-center">
          <p className="text-sm text-yellow-300">사용자 설정이 완료되지 않았습니다.</p>
          <p className="mt-1 text-xs text-yellow-400">
            <a href="/setup" className="underline hover:text-yellow-200">
              설정 마법사
            </a>
            에서 계좌를 등록해 주세요.
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-6">
      {/* 페이지 헤더 */}
      <div className="mb-5">
        <h1 className="text-xl font-bold text-gray-100">매매일지</h1>
        <p className="mt-0.5 text-sm text-gray-500">
          실제 매수·매도 체결과 전략 근거를 남기는 복기장
        </p>
      </div>

      {/* 필터 바 */}
      <div className="mb-4">
        <JournalEntryFilterBar
          filter={filter}
          onChange={handleFilterChange}
          onReset={handleFilterReset}
        />
      </div>

      {/* 총 건수 */}
      {data && (
        <p className="mb-2 text-xs text-gray-500">
          총 {data.totalElements.toLocaleString('ko-KR')}건
        </p>
      )}

      {/* 목록 */}
      <JournalEntryList
        entries={data?.content ?? []}
        isLoading={isLoading}
        isError={isError}
        page={page}
        totalPages={data?.totalPages ?? 0}
        onPageChange={setPage}
        onEntryClick={setSelectedEntry}
      />

      {/* 상세 모달 */}
      {selectedEntry && (
        <JournalEntryDetailModal
          entryId={selectedEntry.id}
          userId={userId}
          onClose={() => setSelectedEntry(null)}
        />
      )}
    </div>
  )
}
