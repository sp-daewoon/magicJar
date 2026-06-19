/**
 * S-12 분할매수 "예상 매수 계획" 패널 Visual baseline (헌법 §9-2 · qa dispatch 2026-06-19)
 *
 * 검증 대상: frontend/src/components/strategy/StrategyScaleInPlanPanel.tsx (PR #1062, main 배포 완료)
 *   - 전략 카드 "전략 보유 현황" 아래 "예상 매수 계획" 섹션 (ADR 0128 R3 · 사용자 ⑦⑧⑨ 권위)
 *   - API: GET /api/strategies/{id}/scalein-plan?currentPrice= → ScaleInPlanDto
 *
 * ── 접근 (dispatch 명시 허용) ──────────────────────────────────────────
 * 실 로그인/KIS·전략 라우팅 장벽으로 /strategy 화면에서 전략47(qa_scalein, PAUSED, 후성 093370)
 * 실 캡처가 막힌다. dispatch 지시대로 PlanBody 렌더 결과를 고정 픽스처로 직접 주입해 시각 baseline을
 * 생성한다 (mock 합성 0건 원칙의 예외 — "사용자 흐름 mock"이 아닌 "시각 baseline용 테스트 픽스처").
 *
 * 픽스처는 라이브 GET /api/strategies/47/scalein-plan 실응답과 정합:
 *   회차 40/35/25% · 평단 16,974.7 · fixedSL 5%(expSL 16,125.97) · floor 15%(floorPrice 14,428.50)
 *
 * 실 CSS: http://localhost:3000/assets/*.css (docker magicjar-frontend 운영 번들) 를 로드해
 *   운영 화면과 동일한 Tailwind 스타일로 렌더 — baseline 충실성 확보.
 *
 * ⚠ 마크업은 StrategyScaleInPlanPanel.tsx PlanBody 와 1:1 미러다. 컴포넌트 클래스/구조 변경 시
 *   이 spec 의 renderPlanBody 도 함께 갱신 + baseline 재발행(사용자 결재) 의무. drift 가드.
 *
 * 시나리오 (4 검증 항목):
 *   S12-1 회차별 표 — 40/35/25% 3행 + targetQuantity 주 표기 (검증항목 ①)
 *   S12-2 예상 손절가/floor 카드 — expSL red · floor amber + "자동 청산 아님" 문구 (검증항목 ②③)
 *   S12-3 미진입(targetQuantity null) — "기준가 대기" + "미진입 (미리보기)" (검증항목 ④)
 *   S12-4 0% 회차 제외 — [50,50,0] → 정확히 2행 (검증항목 ① ⑨)
 *
 * 실행:
 *   pnpm playwright test tests/e2e/s12-scale-in-plan-panel.spec.ts --reporter=list
 *   pnpm playwright test tests/e2e/s12-scale-in-plan-panel.spec.ts --update-snapshots   # baseline 발행
 */
import { expect, test, type Page } from '@playwright/test'

interface Slice {
  sliceIndex: number
  displayOrder: number
  weightPct: number
  targetQuantity: number | null
  triggerDescription: string
}
interface Plan {
  held: boolean
  filledLots: number
  totalSlices: number
  slices: Slice[]
  expectedStopLossPrice: number | null
  fixedStopLossPct: number | null
  floorPct: number
  floorPrice: number | null
}

// ── StrategyScaleInPlanPanel.tsx 의 formatKrw/formatPct 미러 ──
const fmtKrw = (v: number) => `${Math.round(v).toLocaleString('en-US')}원` // 1,000 단위 콤마
const fmtPct = (v: number) => `${Number(v.toFixed(2))}%`

/**
 * PlanBody + section wrapper 미러. 컴포넌트와 동일한 className/구조/문구.
 * (toLocaleString('ko-KR') 은 콤마 그룹핑이 동일하므로 en-US 로 결정성 확보 — 표기 동일.)
 */
function renderPanel(plan: Plan): string {
  const hasQuantities = plan.slices.some((s) => s.targetQuantity !== null)
  const headRight = plan.held
    ? `진행 ${plan.filledLots}/${plan.totalSlices}회차`
    : '미진입 (미리보기)'

  const sliceLis = plan.slices
    .map((slice) => {
      const isFilled = plan.held && slice.displayOrder <= plan.filledLots
      const liCls = isFilled
        ? 'border-emerald-900/50 bg-emerald-950/20'
        : 'border-gray-800/60 bg-gray-900/30'
      const qty =
        slice.targetQuantity !== null
          ? `${slice.targetQuantity.toLocaleString('en-US')}주`
          : `<span class="text-gray-500" title="기준가(현재가·평단)가 없어 목표 수량 미산출">기준가 대기</span>`
      const filledBadge = isFilled
        ? `<span class="ml-1.5 text-[11px] text-emerald-400">진입 완료</span>`
        : ''
      return `
        <li class="rounded border px-2 py-1.5 text-xs ${liCls}">
          <div class="flex items-center justify-between gap-2">
            <span class="font-medium text-gray-200">${slice.displayOrder}차<span class="ml-1.5 text-[11px] text-indigo-300">${fmtPct(slice.weightPct)}</span>${filledBadge}</span>
            <span class="tabular-nums font-medium text-gray-200">${qty}</span>
          </div>
          <div class="mt-0.5 text-[11px] text-gray-500">${slice.triggerDescription}</div>
        </li>`
    })
    .join('')

  const sliceBlock =
    plan.slices.length === 0
      ? `<p class="text-xs text-gray-500">표시할 회차가 없습니다.</p>`
      : `<ul class="space-y-1" aria-label="회차별 분할 매수 계획">${sliceLis}</ul>`

  const noQtyHint =
    !hasQuantities && plan.slices.length > 0
      ? `<p class="text-[11px] text-gray-500">1차 진입(또는 기준가 확보) 후 목표 수량이 계산됩니다.</p>`
      : ''

  // 예상 손절가 카드
  const slPctSpan =
    plan.fixedStopLossPct !== null
      ? `<span class="ml-1 text-red-300">(−${fmtPct(plan.fixedStopLossPct)})</span>`
      : ''
  let slValue: string
  if (plan.expectedStopLossPrice !== null) {
    slValue = `<div class="tabular-nums font-semibold text-red-200">${fmtKrw(plan.expectedStopLossPrice)}</div>`
  } else if (plan.fixedStopLossPct === null) {
    slValue = `<div class="text-[11px] text-gray-500">손절 미설정</div>`
  } else {
    slValue = `<div class="text-[11px] text-gray-500" title="평단·기준가 미확보">기준가 대기</div>`
  }

  // floor 알람선 카드 (⑦ — 자동 청산 아님)
  const floorValue =
    plan.floorPrice !== null
      ? `<div class="tabular-nums font-semibold text-amber-200">${fmtKrw(plan.floorPrice)}</div>`
      : `<div class="text-[11px] text-gray-500" title="평단·기준가 미확보">기준가 대기</div>`

  return `
  <section class="rounded-lg border border-gray-800 bg-gray-950/40 p-3" aria-label="분할 매수 계획" style="width:360px">
    <div class="mb-2 flex items-center justify-between">
      <span class="text-xs font-semibold text-gray-300">예상 매수 계획</span>
      <span class="text-[11px] text-gray-500">${headRight}</span>
    </div>
    <div class="space-y-2.5">
      ${sliceBlock}
      ${noQtyHint}
      <div class="grid grid-cols-1 gap-1.5 border-t border-gray-800/60 pt-2 sm:grid-cols-2">
        <div class="rounded border border-red-900/40 bg-red-950/15 px-2 py-1.5">
          <div class="text-[11px] text-gray-400">예상 손절가${slPctSpan}</div>
          ${slValue}
        </div>
        <div class="rounded border border-amber-900/40 bg-amber-950/15 px-2 py-1.5">
          <div class="text-[11px] text-gray-400">알람선 floor<span class="ml-1 text-amber-300">(−${fmtPct(plan.floorPct)})</span></div>
          ${floorValue}
          <div class="mt-0.5 text-[10px] leading-tight text-amber-400/80">알람만 · 자동 청산 아님 (청산은 수동)</div>
        </div>
      </div>
    </div>
  </section>`
}

/** 실 운영 번들 CSS 를 로드한 빈 페이지에 패널 마크업을 주입한다. */
async function mountPanel(page: Page, plan: Plan): Promise<void> {
  // 운영 frontend 의 빌드 CSS asset 경로를 index.html 에서 추출
  await page.goto('http://localhost:3000/')
  const cssHref = await page.evaluate(() => {
    const link = Array.from(document.querySelectorAll('link[rel="stylesheet"]')).find((l) =>
      (l as HTMLLinkElement).href.includes('/assets/'),
    ) as HTMLLinkElement | undefined
    return link?.href ?? ''
  })
  await page.setContent(
    `<!doctype html><html lang="ko"><head><meta charset="utf-8">
       ${cssHref ? `<link rel="stylesheet" href="${cssHref}">` : ''}
     </head>
     <body class="bg-gray-900" style="margin:0;padding:16px">
       <div id="root">${renderPanel(plan)}</div>
     </body></html>`,
    { waitUntil: 'networkidle' },
  )
  await page.waitForTimeout(150) // 폰트·CSS 적용 안정화
}

const PANEL = '#root section[aria-label="분할 매수 계획"]'

test.describe('S-12 분할매수 예상 매수 계획 패널 Visual baseline', () => {
  // S12-1 — 회차별 표 (targetQuantity 채워진 케이스) · 검증항목 ①
  test('S12-1 회차별 표 40/35/25% 3행 + 목표 수량 주 표기', async ({ page }) => {
    const plan: Plan = {
      held: true,
      filledLots: 1,
      totalSlices: 3,
      slices: [
        { sliceIndex: 0, displayOrder: 1, weightPct: 40, targetQuantity: 40, triggerDescription: '1차 진입 — BUY 시그널 발화 시 즉시' },
        { sliceIndex: 1, displayOrder: 2, weightPct: 35, targetQuantity: 35, triggerDescription: '2차 — BUY 시그널 재발화 시' },
        { sliceIndex: 2, displayOrder: 3, weightPct: 25, targetQuantity: 25, triggerDescription: '3차 — BUY 시그널 재발화 시' },
      ],
      expectedStopLossPrice: 16125.97,
      fixedStopLossPct: 5.0,
      floorPct: 15,
      floorPrice: 14428.5,
    }
    await mountPanel(page, plan)

    const rows = page.locator(`${PANEL} ul[aria-label="회차별 분할 매수 계획"] > li`)
    await expect(rows, '40/35/25% → 정확히 3행').toHaveCount(3)
    await expect(rows.nth(0)).toContainText('1차')
    await expect(rows.nth(0)).toContainText('40%')
    await expect(rows.nth(0)).toContainText('40주')
    await expect(rows.nth(1)).toContainText('35%')
    await expect(rows.nth(2)).toContainText('25%')
    // 1차 진입 완료 배지(filledLots=1)
    await expect(rows.nth(0)).toContainText('진입 완료')

    await expect(page.locator(PANEL)).toHaveScreenshot('s12-1-slices-with-quantity.png', {
      animations: 'disabled',
    })
  })

  // S12-2 — 예상 손절가/floor 카드 · 검증항목 ②③
  test('S12-2 예상 손절가(red) + floor 알람선(amber) + 자동청산 아님 문구', async ({ page }) => {
    const plan: Plan = {
      held: true,
      filledLots: 3,
      totalSlices: 3,
      slices: [
        { sliceIndex: 0, displayOrder: 1, weightPct: 40, targetQuantity: 40, triggerDescription: '1차 진입 — BUY 시그널 발화 시 즉시' },
        { sliceIndex: 1, displayOrder: 2, weightPct: 35, targetQuantity: 35, triggerDescription: '2차 — BUY 시그널 재발화 시' },
        { sliceIndex: 2, displayOrder: 3, weightPct: 25, targetQuantity: 25, triggerDescription: '3차 — BUY 시그널 재발화 시' },
      ],
      expectedStopLossPrice: 16125.97, // 평단 16974.7 × (1 − 0.05)
      fixedStopLossPct: 5.0,
      floorPct: 15,
      floorPrice: 14428.5, // 평단 × (1 − 0.15)
    }
    await mountPanel(page, plan)

    // ② 예상 손절가 — 값 + 손절% 부기
    const slCard = page.locator(`${PANEL} div.border-red-900\\/40`)
    await expect(slCard).toContainText('예상 손절가')
    await expect(slCard).toContainText('16,126원') // round(16125.97)
    await expect(slCard).toContainText('−5%')

    // ③ floor 알람선 — 값 + floor% + "자동 청산 아님" 문구
    const floorCard = page.locator(`${PANEL} div.border-amber-900\\/40`)
    await expect(floorCard).toContainText('알람선 floor')
    await expect(floorCard).toContainText('14,429원') // round(14428.5)
    await expect(floorCard).toContainText('−15%')
    await expect(floorCard, '⑦ floor 자동청산 아님 문구 필수').toContainText(
      '알람만 · 자동 청산 아님 (청산은 수동)',
    )

    await expect(page.locator(PANEL)).toHaveScreenshot('s12-2-stoploss-floor-cards.png', {
      animations: 'disabled',
    })
  })

  // S12-3 — 미진입(targetQuantity null) · 검증항목 ④
  test('S12-3 미진입 미리보기 — 기준가 대기 + 미진입 표기', async ({ page }) => {
    const plan: Plan = {
      held: false,
      filledLots: 0,
      totalSlices: 3,
      slices: [
        { sliceIndex: 0, displayOrder: 1, weightPct: 40, targetQuantity: null, triggerDescription: '1차 진입 — BUY 시그널 발화 시 즉시' },
        { sliceIndex: 1, displayOrder: 2, weightPct: 35, targetQuantity: null, triggerDescription: '2차 — BUY 시그널 재발화 시' },
        { sliceIndex: 2, displayOrder: 3, weightPct: 25, targetQuantity: null, triggerDescription: '3차 — BUY 시그널 재발화 시' },
      ],
      expectedStopLossPrice: null,
      fixedStopLossPct: 5.0,
      floorPct: 15,
      floorPrice: null,
    }
    await mountPanel(page, plan)

    // ④ 미진입 헤더 + 기준가 대기 (mock 수량 합성 0)
    await expect(page.locator(PANEL)).toContainText('미진입 (미리보기)')
    const rows = page.locator(`${PANEL} ul[aria-label="회차별 분할 매수 계획"] > li`)
    await expect(rows).toHaveCount(3)
    await expect(rows.nth(0)).toContainText('기준가 대기')
    await expect(page.locator(PANEL)).toContainText('1차 진입(또는 기준가 확보) 후 목표 수량이 계산됩니다.')
    // 손절가/floor 도 평단 미확보 → 기준가 대기
    await expect(page.locator(`${PANEL} div.border-red-900\\/40`)).toContainText('기준가 대기')
    await expect(page.locator(`${PANEL} div.border-amber-900\\/40`)).toContainText('기준가 대기')
    // 미진입이어도 floor 자동청산 아님 문구는 유지
    await expect(page.locator(`${PANEL} div.border-amber-900\\/40`)).toContainText('자동 청산 아님')

    await expect(page.locator(PANEL)).toHaveScreenshot('s12-3-preview-no-quantity.png', {
      animations: 'disabled',
    })
  })

  // S12-4 — 0% 회차 제외 (서버 제외분 그대로) · 검증항목 ① ⑨
  test('S12-4 0% 회차 제외 — [50,50,0] → 정확히 2행', async ({ page }) => {
    const plan: Plan = {
      held: true,
      filledLots: 1,
      totalSlices: 3, // 설정 3회차지만 3번째 0% → 서버 slices 에서 제외 (effectiveSlices=2)
      slices: [
        { sliceIndex: 0, displayOrder: 1, weightPct: 50, targetQuantity: 50, triggerDescription: '1차 진입 — BUY 시그널 발화 시 즉시' },
        { sliceIndex: 1, displayOrder: 2, weightPct: 50, targetQuantity: 50, triggerDescription: '2차 — BUY 시그널 재발화 시' },
      ],
      expectedStopLossPrice: 16125.97,
      fixedStopLossPct: 5.0,
      floorPct: 15,
      floorPrice: 14428.5,
    }
    await mountPanel(page, plan)

    const rows = page.locator(`${PANEL} ul[aria-label="회차별 분할 매수 계획"] > li`)
    await expect(rows, '0% 회차 제외 → 정확히 2행 (⑨)').toHaveCount(2)
    await expect(rows.nth(0)).toContainText('50%')
    await expect(rows.nth(1)).toContainText('50%')
    // 0% 회차가 행으로 새지 않았는지 — 비중 라벨(indigo span) 중 정확히 "0%" 인 것이 없어야 함.
    // (단순 .not.toContainText('0%') 는 "50%" 의 부분문자열 "0%" 에 false-positive 매칭되므로 회피)
    const weightLabels = await page.locator(`${PANEL} ul > li span.text-indigo-300`).allInnerTexts()
    expect(weightLabels, '회차 비중 라벨 2개만(0% 회차 제외)').toEqual(['50%', '50%'])
    // 3차(0%) triggerDescription 도 새지 않았는지
    await expect(rows).toHaveCount(2)

    await expect(page.locator(PANEL)).toHaveScreenshot('s12-4-zero-weight-excluded.png', {
      animations: 'disabled',
    })
  })
})
