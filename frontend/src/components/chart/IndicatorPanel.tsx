/**
 * 보조지표 토글 패널.
 * BB는 3개 시리즈(상/중/하)를 한 그룹으로 묶어 단일 토글.
 */
import { cn } from '@/lib/cn'

interface ToggleOption {
  ids: string[]      // 활성화 시 추가될 indicator id (BB는 3개)
  label: string
  color: string
}

const GROUPS: { title: string; options: ToggleOption[] }[] = [
  {
    title: '이동평균선',
    options: [
      { ids: ['SMA-5'], label: 'MA 5', color: '#f59e0b' },
      { ids: ['SMA-10'], label: 'MA 10', color: '#10b981' },
      { ids: ['SMA-20'], label: 'MA 20', color: '#3b82f6' },
      { ids: ['SMA-60'], label: 'MA 60', color: '#8b5cf6' },
      { ids: ['SMA-120'], label: 'MA 120', color: '#6b7280' },
      { ids: ['EMA-12'], label: 'EMA 12', color: '#ec4899' },
      { ids: ['EMA-26'], label: 'EMA 26', color: '#06b6d4' },
    ],
  },
  {
    title: '밴드',
    options: [
      { ids: ['BB-20-UPPER', 'BB-20-MID', 'BB-20-LOWER'], label: '볼린저(20,2σ)', color: '#94a3b8' },
    ],
  },
  {
    title: '오실레이터',
    options: [{ ids: ['RSI-14'], label: 'RSI 14', color: '#dc2626' }],
  },
]

interface Props {
  active: Set<string>
  onChange: (next: Set<string>) => void
}

export default function IndicatorPanel({ active, onChange }: Props) {
  function toggleGroup(ids: string[]) {
    const next = new Set(active)
    const allOn = ids.every((id) => next.has(id))
    if (allOn) {
      ids.forEach((id) => next.delete(id))
    } else {
      ids.forEach((id) => next.add(id))
    }
    onChange(next)
  }

  return (
    <div className="flex flex-wrap items-start gap-3 rounded-md border bg-muted/30 px-3 py-2 text-xs">
      {GROUPS.map((group) => (
        <div key={group.title} className="flex items-center gap-2">
          <span className="text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
            {group.title}
          </span>
          <div className="flex flex-wrap gap-1">
            {group.options.map((opt) => {
              const isOn = opt.ids.every((id) => active.has(id))
              return (
                <button
                  key={opt.label}
                  type="button"
                  onClick={() => toggleGroup(opt.ids)}
                  className={cn(
                    'inline-flex items-center gap-1 rounded px-2 py-0.5 font-medium transition',
                    isOn
                      ? 'bg-white text-gray-900 shadow-sm ring-1 ring-gray-200'
                      : 'text-muted-foreground hover:bg-white/60',
                  )}
                >
                  <span
                    className="inline-block h-2 w-2 rounded-full"
                    style={{ backgroundColor: opt.color, opacity: isOn ? 1 : 0.4 }}
                  />
                  {opt.label}
                </button>
              )
            })}
          </div>
        </div>
      ))}
    </div>
  )
}
