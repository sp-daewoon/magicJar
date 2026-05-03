import { useState, useMemo, useCallback } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { useDevlogList } from '@/hooks/useDevlogList'
import { devlogApi } from '@/api/client'
import type { DevlogEntry } from '@/api/types'

/* ------------------------------------------------------------------ */
/* 유틸                                                                 */
/* ------------------------------------------------------------------ */

/** 'YYYY-MM-DD' → '2026년 5월 3일 (일)' */
function formatDateLabel(entry: DevlogEntry): string {
  const [y, m, d] = entry.date.split('-')
  return `${y}년 ${Number(m)}월 ${Number(d)}일 (${entry.dayOfWeek})`
}

/** bytes → 가독 단위 */
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  return `${(bytes / 1024).toFixed(1)} KB`
}

/** 'YYYY-MM-DD' → 'YYYY-MM' (월 추출) */
function toMonth(date: string): string {
  return date.slice(0, 7)
}

/* ------------------------------------------------------------------ */
/* 일지 목록 아이템                                                     */
/* ------------------------------------------------------------------ */

interface EntryItemProps {
  entry: DevlogEntry
  selected: boolean
  onClick: () => void
}

function EntryItem({ entry, selected, onClick }: EntryItemProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={[
        'w-full text-left rounded-md px-3 py-2.5 transition-colors',
        selected
          ? 'bg-primary text-primary-foreground'
          : 'hover:bg-muted',
      ].join(' ')}
    >
      <div className="flex items-center justify-between gap-2">
        <span className={['text-sm font-medium', selected ? '' : 'text-foreground'].join(' ')}>
          {entry.date}
        </span>
        <span
          className={[
            'text-xs',
            selected ? 'text-primary-foreground/70' : 'text-muted-foreground',
          ].join(' ')}
        >
          {entry.dayOfWeek}
        </span>
      </div>
      <p
        className={[
          'mt-0.5 text-xs truncate',
          selected ? 'text-primary-foreground/80' : 'text-muted-foreground',
        ].join(' ')}
      >
        {entry.summary}
      </p>
      <p
        className={[
          'mt-0.5 text-xs',
          selected ? 'text-primary-foreground/60' : 'text-muted-foreground',
        ].join(' ')}
      >
        {formatSize(entry.sizeBytes)}
      </p>
    </button>
  )
}

/* ------------------------------------------------------------------ */
/* 일지 탭 (메인)                                                       */
/* ------------------------------------------------------------------ */

function JournalTab() {
  const { entries, isLoading, isError, isMock, refetch } = useDevlogList()
  const [selectedDate, setSelectedDate] = useState<string | null>(null)
  const [searchQuery, setSearchQuery] = useState('')
  const [selectedMonth, setSelectedMonth] = useState<string>('all')
  const [mobileView, setMobileView] = useState<'list' | 'content'>('list')

  /* 월 목록 */
  const months = useMemo(() => {
    const set = new Set(entries.map((e) => toMonth(e.date)))
    return ['all', ...Array.from(set).sort().reverse()]
  }, [entries])

  /* 필터링 */
  const filtered = useMemo(() => {
    const q = searchQuery.trim().toLowerCase()
    return entries.filter((e) => {
      if (selectedMonth !== 'all' && toMonth(e.date) !== selectedMonth) return false
      if (q && !e.summary.toLowerCase().includes(q) && !e.date.includes(q)) return false
      return true
    })
  }, [entries, searchQuery, selectedMonth])

  /* 선택 일지 자동 초기화 */
  const currentEntry = useMemo(
    () => entries.find((e) => e.date === selectedDate) ?? null,
    [entries, selectedDate],
  )

  const handleSelect = useCallback(
    (date: string) => {
      setSelectedDate(date)
      setMobileView('content')
    },
    [],
  )

  const iframeUrl = currentEntry ? devlogApi.htmlUrl(currentEntry.date) : null

  return (
    <div className="flex flex-col gap-4 lg:flex-row lg:h-[calc(100vh-220px)]">
      {/* ---- 좌측: 목록 패널 ---- */}
      <div
        className={[
          'lg:w-72 xl:w-80 flex-shrink-0 flex flex-col gap-3',
          mobileView === 'content' ? 'hidden lg:flex' : 'flex',
        ].join(' ')}
      >
        {/* API 미연결 경고 */}
        {isMock && !isLoading && (
          <div className="flex items-start gap-2 rounded-md border border-yellow-200 bg-yellow-50 px-3 py-2 text-xs text-yellow-800">
            {/* AlertCircle icon */}
            <svg
              xmlns="http://www.w3.org/2000/svg"
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="mt-0.5 shrink-0"
              aria-hidden="true"
            >
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="8" x2="12" y2="12" />
              <line x1="12" y1="16" x2="12.01" y2="16" />
            </svg>
            <span>
              API 미연결 — 백엔드 머지 후 재시도.{' '}
              <button
                type="button"
                onClick={() => refetch()}
                className="underline hover:no-underline"
              >
                다시 시도
              </button>
            </span>
          </div>
        )}

        {/* 검색 */}
        <Input
          placeholder="일지 검색..."
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          className="h-8 text-sm"
          aria-label="일지 검색"
        />

        {/* 월 필터 */}
        {months.length > 2 && (
          <div className="flex flex-wrap gap-1.5">
            {months.map((m) => (
              <button
                key={m}
                type="button"
                onClick={() => setSelectedMonth(m)}
                className={[
                  'rounded-full px-2.5 py-0.5 text-xs font-medium transition-colors',
                  selectedMonth === m
                    ? 'bg-primary text-primary-foreground'
                    : 'bg-muted text-muted-foreground hover:bg-muted/80',
                ].join(' ')}
              >
                {m === 'all' ? '전체' : m}
              </button>
            ))}
          </div>
        )}

        {/* 목록 */}
        <div className="flex-1 overflow-y-auto space-y-0.5 min-h-0">
          {isLoading && (
            <p className="py-4 text-center text-sm text-muted-foreground">불러오는 중...</p>
          )}
          {!isLoading && filtered.length === 0 && (
            <p className="py-4 text-center text-sm text-muted-foreground">
              {searchQuery ? '검색 결과 없음' : '일지 없음'}
            </p>
          )}
          {filtered.map((entry) => (
            <EntryItem
              key={entry.date}
              entry={entry}
              selected={entry.date === selectedDate}
              onClick={() => handleSelect(entry.date)}
            />
          ))}
        </div>
      </div>

      {/* ---- 우측: 콘텐츠 패널 ---- */}
      <div
        className={[
          'flex-1 min-h-0 flex flex-col',
          mobileView === 'list' ? 'hidden lg:flex' : 'flex',
        ].join(' ')}
      >
        {/* 모바일 뒤로 가기 */}
        <button
          type="button"
          onClick={() => setMobileView('list')}
          className="mb-2 text-sm text-primary hover:underline lg:hidden"
        >
          &larr; 목록으로
        </button>

        {!currentEntry ? (
          <div className="flex-1 flex items-center justify-center rounded-md border-2 border-dashed border-muted">
            <div className="text-center text-muted-foreground">
              {/* BookOpen icon */}
              <svg
                xmlns="http://www.w3.org/2000/svg"
                width="32"
                height="32"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                className="mx-auto mb-2 opacity-40"
                aria-hidden="true"
              >
                <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z" />
                <path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z" />
              </svg>
              <p className="text-sm">좌측에서 일자를 선택하세요</p>
            </div>
          </div>
        ) : (
          <Card className="flex-1 min-h-0 flex flex-col">
            <CardHeader className="pb-2 shrink-0">
              <CardTitle className="text-base flex items-center justify-between">
                <span>{formatDateLabel(currentEntry)}</span>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted-foreground font-normal">
                    {formatSize(currentEntry.sizeBytes)}
                  </span>
                  <a
                    href={devlogApi.htmlUrl(currentEntry.date)}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-xs text-primary hover:underline"
                  >
                    새 창
                  </a>
                </div>
              </CardTitle>
              <p className="text-xs text-muted-foreground">{currentEntry.summary}</p>
            </CardHeader>
            <CardContent className="flex-1 min-h-0 p-0 pb-0">
              {iframeUrl && (
                <iframe
                  key={iframeUrl}
                  src={iframeUrl}
                  title={`개발일지 ${currentEntry.date}`}
                  className="w-full h-full min-h-[500px] rounded-b-lg border-0"
                  sandbox="allow-same-origin allow-scripts"
                  loading="lazy"
                />
              )}
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* 하네스 탭 (1차 placeholder)                                          */
/* ------------------------------------------------------------------ */

function HarnessTab() {
  return (
    <div className="flex flex-col items-center justify-center gap-4 py-16 text-center text-muted-foreground">
      {/* Construction icon */}
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width="40"
        height="40"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className="opacity-40"
        aria-hidden="true"
      >
        <rect x="2" y="6" width="20" height="8" rx="1" />
        <path d="M17 14v7" />
        <path d="M7 14v7" />
        <path d="M17 3v3" />
        <path d="M7 3v3" />
        <path d="M10 14L2.3 6.3" />
        <path d="m14 6 7.7 7.7" />
        <path d="m8 6 8 8" />
      </svg>
      <div>
        <p className="text-sm font-medium">에이전트 활동 · worktree · task 파일 — 다음 사이클 통합</p>
        <p className="mt-1 text-xs">
          현재 운영 상황은{' '}
          <a href="/harness" className="text-primary hover:underline">
            하네스 운영 현황
          </a>{' '}
          페이지를 참고하세요.
        </p>
      </div>
      <div className="rounded-md border border-dashed border-muted p-4 text-left text-xs w-full max-w-sm">
        <p className="font-semibold mb-1">T22 R2 계획 (다음 사이클)</p>
        <ul className="space-y-0.5 list-disc list-inside text-muted-foreground">
          <li>_workspace/_harness-state.json 파싱 → 에이전트 카드</li>
          <li>_workspace/tasks/ 파일 목록 → 타임라인</li>
          <li>ADR 목록 실시간 조회</li>
          <li>QA 이슈 현황 카드</li>
        </ul>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* 빌드/CI 탭 (1차 placeholder)                                         */
/* ------------------------------------------------------------------ */

function CiTab() {
  return (
    <div className="flex flex-col items-center justify-center gap-4 py-16 text-center text-muted-foreground">
      {/* GitBranch icon */}
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width="40"
        height="40"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className="opacity-40"
        aria-hidden="true"
      >
        <line x1="6" y1="3" x2="6" y2="15" />
        <circle cx="18" cy="6" r="3" />
        <circle cx="6" cy="18" r="3" />
        <path d="M18 9a9 9 0 0 1-9 9" />
      </svg>
      <div>
        <p className="text-sm font-medium">GitHub Actions 결과 표시 — 다음 사이클</p>
        <p className="mt-1 text-xs">
          CI/CD 상태는{' '}
          <a
            href="https://github.com/sp-daewoon/magicJar/actions"
            target="_blank"
            rel="noopener noreferrer"
            className="text-primary hover:underline"
          >
            GitHub Actions
          </a>
          에서 확인하세요.
        </p>
      </div>
      <div className="rounded-md border border-dashed border-muted p-4 text-left text-xs w-full max-w-sm">
        <p className="font-semibold mb-1">T22 R2 계획 (다음 사이클)</p>
        <ul className="space-y-0.5 list-disc list-inside text-muted-foreground">
          <li>GitHub Actions API → 워크플로우 실행 목록</li>
          <li>PR 별 CI 뱃지</li>
          <li>빌드 시간 트렌드 차트</li>
        </ul>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* DevlogPage (메인 컴포넌트)                                           */
/* ------------------------------------------------------------------ */

export default function DevlogPage() {
  return (
    <div className="space-y-4">
      {/* 페이지 헤더 */}
      <div className="flex items-center gap-2">
        {/* BookOpen icon */}
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          className="text-primary"
          aria-hidden="true"
        >
          <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z" />
          <path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z" />
        </svg>
        <h1 className="text-2xl font-bold">개발일지</h1>
      </div>

      {/* 탭 */}
      <Tabs defaultValue="journal">
        <TabsList>
          <TabsTrigger value="journal">일지</TabsTrigger>
          <TabsTrigger value="harness">하네스</TabsTrigger>
          <TabsTrigger value="ci">빌드/CI</TabsTrigger>
        </TabsList>

        <TabsContent value="journal">
          <JournalTab />
        </TabsContent>

        <TabsContent value="harness">
          <HarnessTab />
        </TabsContent>

        <TabsContent value="ci">
          <CiTab />
        </TabsContent>
      </Tabs>
    </div>
  )
}
