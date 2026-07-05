/**
 * TradingSetupGuidePage — 매매 세팅 가이드 (/guide)
 *
 * 목적: 최고 관리자(사용자)가 매일 스스로 자동매매를 세팅할 수 있도록,
 * 실제 화면 흐름을 STEP 0~6 단계별로 안내하는 허브 페이지.
 *
 * 설계 원칙:
 * - 존재하는 화면·기능만 언급(각 단계 실제 라우트 딥링크 버튼).
 * - 상단 라이브 체크리스트는 기존 API(useSigStrategies·useWatchlist)만 재사용.
 *   데이터 0건이면 "없음", 조회 실패면 "확인 불가" — mock 합성 0 (feedback_no_mock_data).
 * - 기존 Tailwind 다크 테마·카드 스타일 정합. 세로 스텝퍼 아코디언 + 모바일 대응.
 *
 * AI 호출 0 — 안내 콘텐츠 정적 렌더링.
 */
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useOnboardingStore } from '../../stores/onboarding'
import { useSigStrategies } from '../../api/hooks/useSigStrategy'
import { useWatchlist } from '../../api/hooks/useWatchlist'
import type { StrategyStatus } from '../../api/strategy'

// ──────────────────────────────────────────────
// 단계 콘텐츠 정의 (실제 화면·기능 기준)
// ──────────────────────────────────────────────

interface DeepLink {
  to: string
  label: string
  /** true면 새 탭이 아닌 라우터 이동 대신 안내만(모달 등) — 여기선 전부 라우터 이동 */
  hint?: string
}

interface GuideStep {
  id: string
  no: number
  title: string
  summary: string
  /** 본문 문단(문자열 배열 — 각 줄이 한 문단) */
  body: string[]
  /** 딥링크 버튼 */
  links: DeepLink[]
  /** 강조 주의 문구(amber 박스) */
  note?: string
}

const STEPS: GuideStep[] = [
  {
    id: 'step0',
    no: 0,
    title: '시스템 준비',
    summary: 'KIS 키 등록 · 실거래 동의 · Kill Switch 위치 확인',
    body: [
      'KIS(한국투자증권 OpenAPI) 키를 등록해야 시세·주문이 가능합니다. 헤더 우측 ⚙️ 설정에서 조회용(실전)·주문용 키를 입력하면 DB에 암호화 저장됩니다. 처음이라면 /setup 마법사가 단계별로 안내합니다.',
      '실거래 동의와 Kill Switch(긴급 정지 스위치)는 ⚙️ 설정 안에 있습니다. Kill Switch를 켜면 자동매매가 즉시 중단되고, 끄면 재개됩니다. 큰 사고가 의심될 때 가장 먼저 누르는 버튼입니다.',
      '실계좌로 실제 주문이 나가려면 서버 환경변수 MAGICJAR_MODE=REAL 과 MAGICJAR_ALLOW_REAL=true 두 게이트가 모두 켜져 있어야 합니다. 둘 중 하나라도 꺼져 있으면 항상 모의(MOCK)로 동작해 안전합니다. 이 값은 운영자가 서버에서 설정하며, 화면 좌상단 로고 옆 표시등(MOCK/REAL)으로 현재 모드를 확인할 수 있습니다.',
    ],
    links: [
      { to: '/setup', label: '셋업 마법사 열기 (/setup)' },
    ],
    note: '⚙️ 설정(KIS 키 입력·실거래 동의·Kill Switch)은 화면 우측 상단 톱니바퀴 아이콘으로 언제든 엽니다.',
  },
  {
    id: 'step1',
    no: 1,
    title: '후보 발굴',
    summary: '오늘 살 만한 종목을 찾습니다 — 단타 발굴 · 스코어카드 · 종목 검색',
    body: [
      '전략을 만들기 전에 "무엇을 볼지" 후보를 좁힙니다. 단타 발굴은 전략 없이도 빌트인 프리셋으로 오늘의 후보 TOP N을 보여줍니다. 발굴 스코어카드는 그 후보들의 forward-test(사후 추적) 점수를 읽기 전용으로 확인하는 화면입니다.',
      '개별 종목을 이름·코드로 찾거나 관심종목으로 담으려면 종목 검색을 사용합니다. 관심종목 시세는 실시간(WebSocket)이 아니라 5초마다 REST로 갱신되는 polling 시세입니다 — 초 단위 체결이 필요한 실매매는 전략(자동매매) 경로가 담당합니다.',
    ],
    links: [
      { to: '/strategy/daytrade-discover', label: '단타 발굴 (TOP N)' },
      { to: '/strategy/scorecard', label: '발굴 스코어카드' },
      { to: '/instruments', label: '종목 검색·관심종목' },
    ],
    note: '발굴 후보는 자동매매 결정이 아니라 검증·저장의 출발점입니다. 후보를 바로 사지 않고 STEP 2~3에서 전략으로 만들어 검증합니다.',
  },
  {
    id: 'step2',
    no: 2,
    title: '전략 만들기',
    summary: '전략 빌더에서 매수·매도 조건과 리스크를 조립합니다',
    body: [
      '전략 빌더에 들어가면 시작 화면에서 세 갈래로 출발할 수 있습니다 — ⭐ 내 전략·즐겨찾기(이미 만든 것), 📦 검증 템플릿(시스템 시드 전략), 💬 말로 설명해서 만들기(AI 초안 생성). 빈 캔버스로 직접 조립도 가능합니다.',
      '조건을 다 짠 뒤 저장할 때 RiskPlan을 함께 세팅합니다 — 손절%·익절%를 입력하면 손익비(리스크 대비 보상 비율) 프리뷰가 즉시 보입니다.',
      '분할매수를 쓰면 회수(몇 번에 나눠 살지)·회당 비중·catastrophic floor(분할이 다 끝나기 전 큰 손실 방어선)를 저장 시점에 설정합니다. floor는 기본적으로 알람만 울리고 자동 청산은 토글로 별도 선택합니다(스윙 큰 손실 회피 목적).',
    ],
    links: [
      { to: '/strategy/builder', label: '전략 빌더 열기' },
    ],
    note: 'AI 생성은 초안을 만들 뿐 자동으로 저장·매매하지 않습니다. 항상 사용자가 검토 후 저장합니다.',
  },
  {
    id: 'step3',
    no: 3,
    title: '검증',
    summary: '백테스트 → 모의(페이퍼) 순으로 확인합니다',
    body: [
      '먼저 백테스트로 과거 데이터에서 이 전략이 어떻게 작동했는지 봅니다. 그다음 모의 트레이딩(페이퍼)으로 실거래 없이 현재 시장에서 굴려봅니다. 순서는 백테스트 → 모의입니다.',
      '백테스트-라이브 정합(과거 결과와 실전 동작의 일치도)은 v3.15.0에서 개선됐지만, 과거에 잘 맞았다고 앞으로도 수익이 나는 것은 아닙니다.',
    ],
    links: [
      { to: '/strategy/backtest', label: '백테스트' },
      { to: '/strategy/paper', label: '모의 트레이딩(페이퍼)' },
    ],
    note: '백테스트 통과 ≠ 라이브 수익. 과거 데이터에 지나치게 맞춘 과최적화(overfitting)는 시스템이 걸러주지 못합니다 — 판단은 사용자 몫입니다.',
  },
  {
    id: 'step4',
    no: 4,
    title: '리스크 세팅',
    summary: '포지션 한도·일일 손실 한도를 계좌 단위로 설정합니다',
    body: [
      '리스크 설정에서 손실 한도·포지션 한도·거래 빈도를 정합니다. 포지션 한도는 종목별이 아니라 계좌 전체가 공유하는 1개의 풀입니다 — 새 매수 가능액은 (총 한도 − 계좌 전체 노출)과 예수금 중 작은 값으로 계산됩니다.',
      '일일 손실 한도에 도달하면 그날 신규 매수가 자동으로 차단됩니다(청산·매도는 계속 허용). 한도 근접도는 리스크 설정 화면 상단 게이지로 미리 볼 수 있습니다.',
    ],
    links: [
      { to: '/risk-config', label: '리스크 설정' },
      { to: '/risk-events', label: '위반 이력' },
    ],
    note: '포지션 한도는 계좌 전체 공유 풀 1개입니다. 종목마다 따로 한도가 붙지 않습니다.',
  },
  {
    id: 'step5',
    no: 5,
    title: '활성화 · 운영',
    summary: '전략 목록에서 ACTIVE로 전환하고 상태 의미를 이해합니다',
    body: [
      '검증이 끝난 전략은 전략 목록에서 ACTIVE로 전환하면 자동매매가 시작됩니다. 손절은 봉(캔들) 기반 평가와 tick(체결 단위) 기반 감시가 이중 안전망으로 자동 작동합니다.',
    ],
    links: [
      { to: '/strategy/list', label: '전략 목록 (ACTIVE 전환)' },
    ],
  },
  {
    id: 'step6',
    no: 6,
    title: '모니터링',
    summary: '언제 어느 화면을 보는지 — 아침·장중·장후 루틴',
    body: [
      '대시보드는 계좌 요약·보유·잔고를 한눈에 봅니다. 포지션은 현재 보유 종목의 평가손익, 주문 내역은 발행·체결된 주문을 확인합니다.',
      '매매일지는 주문·체결·시그널을 시계열로 복기할 때, 시스템 이벤트는 재연결·rate limit 같은 운영 이벤트를 확인할 때, 실시간 상태는 WebSocket 구독 슬롯이 정상인지 점검할 때 봅니다.',
    ],
    links: [
      { to: '/', label: '대시보드' },
      { to: '/positions', label: '포지션' },
      { to: '/orders', label: '주문 내역' },
      { to: '/journal', label: '매매일지' },
      { to: '/system', label: '시스템 이벤트' },
      { to: '/admin/realtime', label: '실시간 상태' },
    ],
    note: '아침 루틴 예시 — 장전: 전략 상태·KIS 키·잔고 확인 / 장중: 포지션·시그널 주시 / 장후: 매매일지로 복기.',
  },
]

// ──────────────────────────────────────────────
// 라이브 체크리스트 (기존 API 재사용)
// ──────────────────────────────────────────────

type LiveState =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'value'; total: number; text: string }

function LiveMetric({ label, state }: { label: string; state: LiveState }) {
  let valueNode: React.ReactNode
  let valueClass = 'text-white'
  if (state.kind === 'loading') {
    valueNode = '…'
    valueClass = 'text-gray-500'
  } else if (state.kind === 'error') {
    valueNode = '확인 불가'
    valueClass = 'text-amber-400'
  } else {
    valueNode = state.text
    if (state.total === 0) valueClass = 'text-gray-400'
  }
  return (
    <div className="rounded-lg border border-gray-800 bg-gray-900/60 px-4 py-3">
      <div className="text-xs text-gray-500">{label}</div>
      <div className={`mt-1 text-lg font-semibold ${valueClass}`}>{valueNode}</div>
    </div>
  )
}

function LiveChecklistCard({ userId }: { userId: number | null }) {
  // 전략 목록 — size 넉넉히 잡아 ACTIVE/BUY_BLOCKED 집계 (기존 API 재사용)
  const strategies = useSigStrategies(userId, 100)
  const watchlist = useWatchlist()

  const strategyMetric = useMemo<{ active: LiveState; blocked: LiveState; total: LiveState }>(() => {
    if (userId === null) {
      const none: LiveState = { kind: 'value', total: 0, text: '계좌 미등록' }
      return { active: none, blocked: none, total: none }
    }
    if (strategies.isLoading) {
      const l: LiveState = { kind: 'loading' }
      return { active: l, blocked: l, total: l }
    }
    if (strategies.isError || !strategies.data) {
      const e: LiveState = { kind: 'error' }
      return { active: e, blocked: e, total: e }
    }
    const all = strategies.data.pages.flatMap((p) => p.content)
    const totalElements = strategies.data.pages[0]?.totalElements ?? all.length
    const count = (status: StrategyStatus) => all.filter((s) => s.status === status).length
    const active = count('ACTIVE')
    const blocked = count('BUY_BLOCKED')
    return {
      active: { kind: 'value', total: active, text: active === 0 ? '없음' : `${active}개` },
      blocked: { kind: 'value', total: blocked, text: blocked === 0 ? '없음' : `${blocked}개` },
      total: {
        kind: 'value',
        total: totalElements,
        text: totalElements === 0 ? '없음' : `${totalElements}개`,
      },
    }
  }, [userId, strategies.isLoading, strategies.isError, strategies.data])

  const watchlistMetric = useMemo<LiveState>(() => {
    if (watchlist.isLoading) return { kind: 'loading' }
    if (watchlist.isError || !watchlist.data) return { kind: 'error' }
    const n = watchlist.data.length
    return { kind: 'value', total: n, text: n === 0 ? '없음' : `${n}개` }
  }, [watchlist.isLoading, watchlist.isError, watchlist.data])

  return (
    <section className="rounded-xl border border-gray-800 bg-gray-900/40 p-5">
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-gray-200">현재 상태</h2>
        <span className="text-xs text-gray-500">기존 API 실시간 조회 · 없으면 &quot;없음&quot;</span>
      </div>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <LiveMetric label="ACTIVE 전략" state={strategyMetric.active} />
        <LiveMetric label="매수차단(BUY_BLOCKED)" state={strategyMetric.blocked} />
        <LiveMetric label="전체 전략" state={strategyMetric.total} />
        <LiveMetric label="관심종목" state={watchlistMetric} />
      </div>
    </section>
  )
}

// ──────────────────────────────────────────────
// 상태 의미표 (STEP 5 보조)
// ──────────────────────────────────────────────

const STATUS_TABLE: Array<{ status: string; badge: string; meaning: string }> = [
  { status: 'ACTIVE', badge: 'bg-emerald-950 text-emerald-300 border-emerald-800', meaning: '평가 + 매수 모두 수행 (정상 가동)' },
  { status: 'BUY_BLOCKED', badge: 'bg-amber-950 text-amber-300 border-amber-800', meaning: '신규 매수 차단 · 청산(매도)만 허용' },
  { status: 'PAUSED', badge: 'bg-gray-800 text-gray-300 border-gray-700', meaning: '평가 대상에서 제외 (사용자 일시정지)' },
  { status: 'DISABLED', badge: 'bg-red-950 text-red-300 border-red-800', meaning: '시스템 보호 중지 (자동 비활성)' },
  { status: '보관함(DELETED)', badge: 'bg-gray-900 text-gray-500 border-gray-800', meaning: 'soft 삭제 · 목록에서 숨김' },
]

function StatusTable() {
  return (
    <div className="mt-3 overflow-x-auto rounded-lg border border-gray-800">
      <table className="w-full min-w-[420px] text-left text-sm">
        <thead className="bg-gray-900/60 text-xs text-gray-500">
          <tr>
            <th className="px-3 py-2 font-medium">상태</th>
            <th className="px-3 py-2 font-medium">의미</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-800">
          {STATUS_TABLE.map((row) => (
            <tr key={row.status}>
              <td className="px-3 py-2">
                <span className={`inline-block rounded-md border px-2 py-0.5 text-xs font-semibold ${row.badge}`}>
                  {row.status}
                </span>
              </td>
              <td className="px-3 py-2 text-gray-300">{row.meaning}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ──────────────────────────────────────────────
// 단계 아코디언
// ──────────────────────────────────────────────

function StepCard({
  step,
  open,
  onToggle,
}: {
  step: GuideStep
  open: boolean
  onToggle: () => void
}) {
  const panelId = `guide-panel-${step.id}`
  const btnId = `guide-btn-${step.id}`
  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900/40">
      <button
        id={btnId}
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        aria-controls={panelId}
        className="flex w-full items-center gap-4 px-5 py-4 text-left focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-inset"
      >
        <span
          className={[
            'flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-sm font-bold',
            open ? 'bg-blue-600 text-white' : 'bg-gray-800 text-gray-300',
          ].join(' ')}
          aria-hidden
        >
          {step.no}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-base font-semibold text-white">
            STEP {step.no} · {step.title}
          </span>
          <span className="mt-0.5 block truncate text-sm text-gray-400">{step.summary}</span>
        </span>
        <span className="shrink-0 text-gray-500" aria-hidden>
          {open ? '▲' : '▼'}
        </span>
      </button>

      {open && (
        <div id={panelId} role="region" aria-labelledby={btnId} className="border-t border-gray-800 px-5 py-4">
          <div className="space-y-3 text-sm leading-relaxed text-gray-300">
            {step.body.map((para, i) => (
              <p key={i}>{para}</p>
            ))}
          </div>

          {/* STEP 5 상태 의미표 */}
          {step.id === 'step5' && (
            <div className="mt-4">
              <div className="text-xs font-semibold text-gray-400">전략 상태 의미</div>
              <StatusTable />
            </div>
          )}

          {step.note && (
            <div className="mt-4 rounded-lg border border-amber-900/60 bg-amber-950/30 px-4 py-3 text-sm text-amber-200">
              {step.note}
            </div>
          )}

          {step.links.length > 0 && (
            <div className="mt-4 flex flex-wrap gap-2">
              {step.links.map((link) => (
                <Link
                  key={link.to}
                  to={link.to}
                  className="inline-flex items-center gap-1 rounded-lg border border-gray-700 bg-gray-800 px-3.5 py-2 text-sm font-medium text-gray-100 transition-colors hover:border-blue-500 hover:bg-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-400"
                >
                  {link.label}
                  <span aria-hidden className="text-gray-500">→</span>
                </Link>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ──────────────────────────────────────────────
// 페이지
// ──────────────────────────────────────────────

export function TradingSetupGuidePage() {
  const userId = useOnboardingStore((s) => s.registeredUserId)
  // 첫 단계(STEP 0)만 기본 펼침 — 나머지는 접힘
  const [openIds, setOpenIds] = useState<Set<string>>(() => new Set(['step0']))

  const toggle = (id: string) => {
    setOpenIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const allOpen = openIds.size === STEPS.length
  const toggleAll = () => {
    setOpenIds(allOpen ? new Set() : new Set(STEPS.map((s) => s.id)))
  }

  return (
    <div className="mx-auto max-w-4xl px-4 py-6 sm:px-6 lg:px-8">
      {/* 페이지 헤더 */}
      <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-white">매매 세팅 가이드</h1>
          <p className="mt-1 text-sm text-gray-400">
            매일 스스로 자동매매를 세팅하는 순서를 단계별로 안내합니다. 각 단계의 버튼으로 실제 화면으로 바로 이동합니다.
          </p>
        </div>
        <button
          type="button"
          onClick={toggleAll}
          className="rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-gray-300 transition-colors hover:bg-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-400"
        >
          {allOpen ? '모두 접기' : '모두 펼치기'}
        </button>
      </div>

      {/* 라이브 체크리스트 */}
      <div className="mb-6">
        <LiveChecklistCard userId={userId} />
      </div>

      {/* 단계 스텝퍼 */}
      <div className="space-y-3">
        {STEPS.map((step) => (
          <StepCard
            key={step.id}
            step={step}
            open={openIds.has(step.id)}
            onToggle={() => toggle(step.id)}
          />
        ))}
      </div>

      <p className="mt-8 text-center text-xs text-gray-600">
        이 화면은 안내 전용입니다 — 매매 결정·주문은 각 단계 화면에서 사용자가 직접 수행합니다.
      </p>
    </div>
  )
}
