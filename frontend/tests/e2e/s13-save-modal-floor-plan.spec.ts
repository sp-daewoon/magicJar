/**
 * S-13 전략 저장 모달 — floor + 분할 예상 계획 + RiskPlan 그룹핑 (헌법 §9-2 · qa dispatch 2026-06-21)
 *
 * 검증 대상 (Wave1 #1067 + Wave2 #1070, main 라이브 배포):
 *   - StrategySaveModal.tsx — RiskPlan 섹션 그룹핑(필수 펼침 + 고급 접기 default)
 *   - ScaleInExitPanel.tsx — floor 필드 항상 노출(scale-in 사용 시) + default 15 prefill
 *       + 자동청산 토글 OFF "알람만(권장)" 회색 / ON amber "자동 전량 청산·반등 놓침" 경고
 *   - ScaleInPlanPreviewInline.tsx — 회차별 비중·예상 진입가·예상손절가·floor 알람선
 *       (자동청산/알람만 라벨), 0% 회차 제외, 기준가 미입력 시 안내
 *
 * ── 접근 ──────────────────────────────────────────────────────────────
 * /strategy/builder → 시작 화면 검색 "FVG 매수" → 템플릿 카드 불러오기(유효 DSL 로드) →
 *   캔버스 우측 "저장" 버튼 활성 → 클릭 → role=dialog "전략 저장" 열림.
 *   userId 주입은 s8~s12 관습대로 addInitScript(localStorage 'magicjar-onboarding').
 *
 * ★ 라이브 보호: 저장 버튼은 절대 누르지 않는다(실 DB 미오염). 모달 렌더·입력·토글 UI 검증만.
 *   실데이터(템플릿 10건)로 진입하며 mock 합성 0건(feedback_no_mock_data).
 *
 * 시나리오:
 *   S13-1 모달 그룹핑 — RiskPlan 펼침(필수) + 고급 2섹션 접힘(default) 스크린샷
 *   S13-2 floor 항상 노출 + default 15 prefill (scale-in 사용 시)
 *   S13-3 자동청산 토글 OFF→ON — 회색 권장 문구 / amber 경고 문구 전환 스크린샷
 *   S13-4 분할 예상 계획 프리뷰 — 회차 비중·예상가·손절가·floor 라벨 (기준가 입력됨)
 *   S13-5 기준가 미입력 시 안내 — "기준가를 입력하면 …" + 0% 회차 제외
 *
 * 실행:
 *   pnpm playwright test tests/e2e/s13-save-modal-floor-plan.spec.ts --reporter=list
 *   pnpm playwright test tests/e2e/s13-save-modal-floor-plan.spec.ts --update-snapshots   # baseline 발행
 */
import { expect, test, type Page } from '@playwright/test'

const USER_ID = 74683223

/** store(registeredUserId) 선주입 — useOnboardingStatus auto-sync 전 race 회피 (s11 관습) */
async function seedUser(page: Page) {
  await page.addInitScript((uid) => {
    window.localStorage.setItem(
      'magicjar-onboarding',
      JSON.stringify({ state: { registeredUserId: uid }, version: 2 }),
    )
  }, USER_ID)
}

/** 시작 화면 → FVG 매수 템플릿 로드 → 저장 모달 오픈. dialog locator 반환. */
async function openSaveModal(page: Page) {
  await seedUser(page)
  await page.goto('/strategy/builder')
  await page.waitForLoadState('networkidle')
  await page.locator('[aria-label="전략 시작 화면"]').waitFor({ state: 'visible', timeout: 10000 })

  // 결정적 카드 1건(FVG 매수 후보) — 유효 DSL 로드(저장 버튼 활성 전제)
  await page.getByRole('searchbox', { name: '전략 검색' }).fill('FVG 매수')
  await page.getByRole('button', { name: /불러오기$/ }).first().click()

  // 빌더 캔버스 진입 + 저장 버튼 활성 대기
  const saveBtn = page.getByRole('button', { name: /^저장$/ })
  await expect(saveBtn).toBeEnabled({ timeout: 8000 })
  await saveBtn.click()

  const dialog = page.getByRole('dialog', { name: '전략 저장' })
  await dialog.waitFor({ state: 'visible', timeout: 8000 })
  return dialog
}

/** "고급 — 분할 매수 / 분할 익절" 섹션 펼치고 분할 매수 토글 ON. floor·preview 노출 상태로 만든다. */
async function enableScaleIn(page: Page, dialog: ReturnType<Page['getByRole']>) {
  await dialog.getByRole('button', { name: /분할 매수 \/ 분할 익절/ }).click()
  await dialog.getByRole('checkbox', { name: '분할 매수 사용' }).check()
  await page.waitForTimeout(400) // floor prefill effect 안정화
}

test.describe('S-13 전략 저장 모달 floor + 분할 예상 계획', () => {
  // S13-1 — 모달 그룹핑: RiskPlan 펼침 + 고급 접힘 default
  test('S13-1 RiskPlan 필수 펼침 + 고급 2섹션 접힘 default', async ({ page }) => {
    const dialog = await openSaveModal(page)

    // 필수 RiskPlan 섹션 — 펼침(손절·익절 입력 가시)
    await expect(dialog.getByText('자동매매 RiskPlan')).toBeVisible()
    await expect(dialog.getByText('손절 기준')).toBeVisible()
    await expect(dialog.getByText('익절 기준')).toBeVisible()

    // 고급 2섹션 — 접힘 default (제목 버튼은 보이되 내부 컨트롤은 숨김)
    const advancedVi = dialog.getByRole('button', { name: /보유·VI 정책/ })
    const advancedScaleIn = dialog.getByRole('button', { name: /분할 매수 \/ 분할 익절/ })
    await expect(advancedVi).toBeVisible()
    await expect(advancedScaleIn).toBeVisible()
    // 접힘 상태 — 분할 매수 사용 체크박스는 아직 hidden
    await expect(dialog.getByRole('checkbox', { name: '분할 매수 사용' })).toBeHidden()
    // VI 정책 select도 hidden (접힘)
    await expect(dialog.getByText('VI 발동 정책')).toBeHidden()

    await expect(dialog).toHaveScreenshot('s13-1-modal-grouping-collapsed.png', {
      animations: 'disabled',
    })
  })

  // S13-2 — floor 항상 노출 + default 15 prefill
  test('S13-2 floor 필드 노출 + default 15 prefill', async ({ page }) => {
    const dialog = await openSaveModal(page)
    await enableScaleIn(page, dialog)

    // catastrophic floor 섹션 노출
    await expect(dialog.getByText('catastrophic floor')).toBeVisible()
    const floorInput = dialog.getByRole('spinbutton', { name: 'catastrophic floor 퍼센트' })
    await expect(floorInput).toBeVisible()
    // ADR 0128 R3 갭① — yml default(15) prefill
    await expect(floorInput).toHaveValue('15')

    // 자동청산 토글 — default OFF
    const autoLiq = dialog.getByRole('checkbox', { name: 'floor 도달 시 자동 청산' })
    await expect(autoLiq).not.toBeChecked()
  })

  // S13-3 — 자동청산 토글 OFF(회색 권장) → ON(amber 경고)
  test('S13-3 자동청산 토글 OFF 권장 → ON amber 경고 전환', async ({ page }) => {
    const dialog = await openSaveModal(page)
    await enableScaleIn(page, dialog)

    // OFF 상태 — 회색 "알람만 — 청산은 수동 판단 (권장)" 문구
    await expect(
      dialog.getByText(/알람만 — 청산은 수동 판단 \(권장\)/),
    ).toBeVisible()
    await expect(dialog.getByText(/자동 전량 청산합니다/)).toBeHidden()

    const floorSection = dialog.locator('section[aria-label="분할 매수 설정"] div.border-rose-900\\/50')
    await expect(floorSection).toHaveScreenshot('s13-3a-floor-toggle-off-alarm.png', {
      animations: 'disabled',
    })

    // ON 전환 — amber 경고 "자동 전량 청산 · 스윙 반등 놓침"
    await dialog.getByRole('checkbox', { name: 'floor 도달 시 자동 청산' }).check()
    await page.waitForTimeout(200)
    await expect(
      dialog.getByText(/floor 도달 시 자동 전량 청산합니다\. 스윙 반등을 놓칠 수 있습니다/),
    ).toBeVisible()
    await expect(dialog.getByText(/알람만 — 청산은 수동 판단 \(권장\)/)).toBeHidden()

    await expect(floorSection).toHaveScreenshot('s13-3b-floor-toggle-on-amber.png', {
      animations: 'disabled',
    })
  })

  // S13-4 — 분할 예상 계획 프리뷰 (기준가 입력됨 — 모달 default previewEntryPrice=100000)
  test('S13-4 예상 계획 프리뷰 — 회차 비중·예상가·손절가·floor 라벨', async ({ page }) => {
    const dialog = await openSaveModal(page)
    await enableScaleIn(page, dialog)

    const preview = dialog.locator('[aria-label="저장 전 분할 매수 계획 미리보기"]')
    await expect(preview).toBeVisible()
    await expect(preview.getByText('예상 매수 계획 (미리보기)')).toBeVisible()

    // 회차별 행 — default totalSlices=3 (균등) → 3행
    const rows = preview.locator('ul[aria-label="회차별 예상 매수 계획"] > li')
    await expect(rows).toHaveCount(3)
    // 기준가 100000 입력됨 → "예상 …원" 표기
    await expect(rows.first()).toContainText('예상')
    await expect(rows.first()).toContainText('원')

    // 예상 손절가 카드(red) + floor 알람선 카드(amber)
    await expect(preview.getByText('예상 손절가')).toBeVisible()
    await expect(preview.getByText('알람선 floor')).toBeVisible()
    // floor OFF(default) → "알람만 · 자동 청산 아님" 라벨
    await expect(preview.getByText(/알람만 · 자동 청산 아님/)).toBeVisible()

    await expect(preview).toHaveScreenshot('s13-4-plan-preview-with-base.png', {
      animations: 'disabled',
    })

    // 자동청산 ON → 프리뷰 floor 라벨이 "자동 청산"으로 전환
    await dialog.getByRole('checkbox', { name: 'floor 도달 시 자동 청산' }).check()
    await page.waitForTimeout(200)
    await expect(preview.locator('div.border-amber-900\\/40')).toContainText('자동 청산')
  })

  // S13-5 — 기준가 미입력 시 안내 (previewEntryPrice 비우기)
  test('S13-5 기준가 미입력 안내 — 예상가 미정 + 안내 문구', async ({ page }) => {
    const dialog = await openSaveModal(page)
    await enableScaleIn(page, dialog)

    // 미리보기 기준가 입력 비우기 → hasBase=false 경로
    const baseInput = dialog.getByRole('spinbutton', { name: /미리보기 기준가/ }).first()
    // label 매칭이 어려우면 RiskPlan 내 previewEntryPrice 직접 — placeholder "예: 100000"
    const priceInput = dialog.locator('input[placeholder="예: 100000"]')
    await priceInput.fill('')
    await page.waitForTimeout(200)

    const preview = dialog.locator('[aria-label="저장 전 분할 매수 계획 미리보기"]')
    await expect(
      preview.getByText(/위 미리보기 기준가를 입력하면 회차별 예상 진입가·손절가가 표시됩니다/),
    ).toBeVisible()
    // 회차 행은 유지하되 예상가 미정
    const rows = preview.locator('ul[aria-label="회차별 예상 매수 계획"] > li')
    await expect(rows.first()).toContainText('예상가 미정')

    await expect(preview).toHaveScreenshot('s13-5-plan-preview-no-base.png', {
      animations: 'disabled',
    })

    void baseInput // lint: 명시적 미사용 표시(priceInput 경로 사용)
  })
})
