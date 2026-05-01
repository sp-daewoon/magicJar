/**
 * NewsAnalysisCard — AI가 분석한 뉴스의 감성·요약 표시.
 * STOMP `/topic/news` → useNewsAnalyzed hook이 종목별 필터.
 *
 * 표시:
 *   - 평균 감성 점수 (배지: 긍정·중립·부정 + 신뢰도)
 *   - 최근 5건 뉴스 목록 (제목·요약·시각·source)
 *   - 비어있으면 "AI가 분석한 뉴스 없음" 안내
 */
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { cn } from '@/lib/cn'
import { useNewsAnalyzed } from '@/hooks/useNewsAnalyzed'
import type { NewsAnalyzedMessage, SentimentLabel } from '@/api/types'

interface Props {
  code: string
}

const LABEL_CONFIG: Record<SentimentLabel, { ko: string; bg: string; fg: string; emoji: string }> = {
  POSITIVE: { ko: '긍정', bg: 'bg-red-50', fg: 'text-red-700', emoji: '▲' },
  NEUTRAL: { ko: '중립', bg: 'bg-gray-100', fg: 'text-gray-700', emoji: '─' },
  NEGATIVE: { ko: '부정', bg: 'bg-blue-50', fg: 'text-blue-700', emoji: '▼' },
}

function formatTime(iso: string): string {
  const d = new Date(iso)
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(d)
}

/** 감성 점수 평균 — POSITIVE +1, NEUTRAL 0, NEGATIVE -1, sentimentScore(신뢰도)로 가중 */
function aggregate(items: NewsAnalyzedMessage[]): {
  net: number          // -1 ~ +1
  avgConfidence: number
  counts: Record<SentimentLabel, number>
} {
  if (items.length === 0) {
    return { net: 0, avgConfidence: 0, counts: { POSITIVE: 0, NEUTRAL: 0, NEGATIVE: 0 } }
  }
  const counts: Record<SentimentLabel, number> = { POSITIVE: 0, NEUTRAL: 0, NEGATIVE: 0 }
  let weightedSum = 0
  let confSum = 0
  for (const it of items) {
    counts[it.sentiment]++
    const v = it.sentiment === 'POSITIVE' ? 1 : it.sentiment === 'NEGATIVE' ? -1 : 0
    weightedSum += v * it.sentimentScore
    confSum += it.sentimentScore
  }
  return {
    net: confSum > 0 ? weightedSum / confSum : 0,
    avgConfidence: confSum / items.length,
    counts,
  }
}

export default function NewsAnalysisCard({ code }: Props) {
  const items = useNewsAnalyzed(code, 10)
  const { net, avgConfidence, counts } = aggregate(items)
  const dominant: SentimentLabel =
    net > 0.2 ? 'POSITIVE' : net < -0.2 ? 'NEGATIVE' : 'NEUTRAL'

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center justify-between text-base">
          <span>AI 뉴스 감성</span>
          {items.length > 0 && (
            <span className={cn('rounded-full px-2.5 py-0.5 text-xs font-medium', LABEL_CONFIG[dominant].bg, LABEL_CONFIG[dominant].fg)}>
              {LABEL_CONFIG[dominant].emoji} {LABEL_CONFIG[dominant].ko}
              <span className="ml-1 text-[10px] opacity-70">신뢰도 {(avgConfidence * 100).toFixed(0)}%</span>
            </span>
          )}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {items.length === 0 ? (
          <div className="rounded border border-dashed border-gray-300 px-3 py-6 text-center text-xs text-muted-foreground">
            AI가 분석한 뉴스가 아직 없습니다.
            <br />
            <span className="text-[10px]">백엔드 news.analyzed.v1 토픽 메시지 도착 시 자동 표시.</span>
          </div>
        ) : (
          <>
            {/* 분포 막대 */}
            <div className="flex items-center gap-1 text-[10px]">
              <span className="w-12 text-muted-foreground">분포</span>
              <div className="flex h-2 flex-1 overflow-hidden rounded-full bg-gray-100">
                {(['POSITIVE', 'NEUTRAL', 'NEGATIVE'] as const).map((label) => {
                  const ratio = items.length > 0 ? (counts[label] / items.length) * 100 : 0
                  return (
                    <div
                      key={label}
                      className={cn(
                        label === 'POSITIVE' && 'bg-red-400',
                        label === 'NEUTRAL' && 'bg-gray-300',
                        label === 'NEGATIVE' && 'bg-blue-400',
                      )}
                      style={{ width: `${ratio}%` }}
                    />
                  )
                })}
              </div>
              <span className="tabular-nums text-muted-foreground">
                +{counts.POSITIVE}/-{counts.NEGATIVE}/{counts.NEUTRAL}
              </span>
            </div>

            {/* 뉴스 목록 */}
            <ul className="space-y-2 text-xs">
              {items.slice(0, 5).map((it) => {
                const cfg = LABEL_CONFIG[it.sentiment]
                return (
                  <li
                    key={it.newsId}
                    className="rounded border border-gray-100 bg-gray-50/40 p-2"
                  >
                    <div className="flex items-start gap-2">
                      <span
                        className={cn(
                          'inline-flex shrink-0 items-center rounded px-1.5 py-0.5 text-[10px] font-medium',
                          cfg.bg,
                          cfg.fg,
                        )}
                      >
                        {cfg.emoji} {cfg.ko}
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="line-clamp-2 font-medium text-gray-900">{it.title}</div>
                        {it.summary && (
                          <div className="mt-0.5 line-clamp-2 text-[11px] text-gray-600">
                            {it.summary}
                          </div>
                        )}
                        <div className="mt-1 flex items-center gap-2 text-[10px] text-gray-400">
                          <span>{formatTime(it.publishedAt)}</span>
                          <span>·</span>
                          <span>{it.source}</span>
                          <span>·</span>
                          <span>신뢰도 {(it.sentimentScore * 100).toFixed(0)}%</span>
                        </div>
                      </div>
                    </div>
                  </li>
                )
              })}
            </ul>
            {items.length > 5 && (
              <div className="text-center text-[10px] text-muted-foreground">
                +{items.length - 5}건 더 (최근 {items.length}건 보유)
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}
