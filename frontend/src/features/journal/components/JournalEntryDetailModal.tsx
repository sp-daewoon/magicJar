/**
 * JournalEntryDetailModal — 매매일지 entry 단건 상세 모달
 *
 * ADR 0072 §6-2·§6-3 정합:
 * - entry 단건 + payload + notes 표시
 * - 메모 작성 form 포함
 * - ESC 키 닫기 + 배경 클릭 닫기
 */
import { useEffect } from 'react'
import { useJournalEntry } from '../hooks/useJournalEntry'
import { useWriteJournalNote } from '../hooks/useWriteJournalNote'
import { JournalNoteForm } from './JournalNoteForm'
import { EVENT_TYPE_LABELS } from './JournalEntryFilterBar'

interface JournalEntryDetailModalProps {
  entryId: number
  userId: number
  onClose: () => void
}

function formatKst(isoString: string): string {
  const d = new Date(isoString)
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

export function JournalEntryDetailModal({ entryId, userId, onClose }: JournalEntryDetailModalProps) {
  const { data: entry, isLoading, isError } = useJournalEntry(entryId, userId)
  const writeNote = useWriteJournalNote()

  // ESC 키 닫기
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [onClose])

  function handleNoteSubmit(noteText: string) {
    writeNote.mutate({ userId, journalEntryId: entryId, noteText })
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      aria-label="매매일지 상세"
    >
      <div
        className="relative w-full max-w-2xl max-h-[85vh] overflow-y-auto rounded-xl border border-gray-700 bg-gray-950 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* 헤더 */}
        <div className="sticky top-0 flex items-center justify-between border-b border-gray-800 bg-gray-950 px-5 py-4">
          <h2 className="text-base font-semibold text-gray-200">매매일지 상세</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="닫기"
            className="rounded-md p-1 text-gray-500 hover:bg-gray-800 hover:text-gray-300 focus:outline-none focus:ring-2 focus:ring-blue-400"
          >
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div className="px-5 py-4 space-y-4">
          {isLoading && (
            <div className="space-y-3">
              {Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="h-5 animate-pulse rounded bg-gray-800" />
              ))}
            </div>
          )}

          {isError && (
            <p className="text-sm text-red-400">상세 정보를 불러오는 중 오류가 발생했습니다.</p>
          )}

          {entry && (
            <>
              {/* 기본 정보 */}
              <div className="grid grid-cols-2 gap-3 text-sm">
                <div>
                  <span className="text-xs text-gray-500">이벤트</span>
                  <p className="mt-0.5 font-medium text-gray-200">
                    {EVENT_TYPE_LABELS[entry.eventType]}
                  </p>
                </div>
                <div>
                  <span className="text-xs text-gray-500">발생 시각</span>
                  <p className="mt-0.5 text-gray-200">{formatKst(entry.occurredAt)}</p>
                </div>
                {entry.instrumentCode && (
                  <div>
                    <span className="text-xs text-gray-500">종목</span>
                    <p className="mt-0.5 text-gray-200">
                      {entry.instrumentName ?? ''} ({entry.instrumentCode})
                    </p>
                  </div>
                )}
                {entry.realizedPnl !== null && (
                  <div>
                    <span className="text-xs text-gray-500">실현 손익</span>
                    <p
                      className={`mt-0.5 font-mono font-semibold ${
                        Number(entry.realizedPnl) > 0 ? 'text-emerald-400' : 'text-red-400'
                      }`}
                    >
                      {Number(entry.realizedPnl) > 0 ? '+' : ''}
                      {Number(entry.realizedPnl).toLocaleString('ko-KR')}원
                    </p>
                  </div>
                )}
              </div>

              {/* 요약 */}
              <div>
                <span className="text-xs text-gray-500">내용</span>
                <p className="mt-1 text-sm text-gray-200">{entry.summary}</p>
              </div>

              {/* payload */}
              <div>
                <span className="text-xs text-gray-500">원본 페이로드</span>
                <pre className="mt-1 max-h-36 overflow-y-auto rounded-md border border-gray-800 bg-gray-900 p-3 text-xs text-gray-400">
                  {JSON.stringify(entry.payload, null, 2)}
                </pre>
              </div>

              {/* 메모 목록 */}
              {entry.notes.length > 0 && (
                <div>
                  <span className="text-xs text-gray-500">메모 ({entry.notes.length}건)</span>
                  <div className="mt-2 space-y-2">
                    {entry.notes.map((note) => (
                      <div key={note.id} className="rounded-md border border-gray-800 bg-gray-900 p-3">
                        <p className="text-sm text-gray-300">{note.noteText}</p>
                        <p className="mt-1 text-xs text-gray-600">{formatKst(note.createdAt)}</p>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* 메모 작성 */}
              <div>
                <span className="text-xs text-gray-500">메모 작성</span>
                <div className="mt-2">
                  <JournalNoteForm
                    onSubmit={handleNoteSubmit}
                    isSubmitting={writeNote.isPending}
                  />
                </div>
                {writeNote.isError && (
                  <p className="mt-1 text-xs text-red-400">메모 저장에 실패했습니다.</p>
                )}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
