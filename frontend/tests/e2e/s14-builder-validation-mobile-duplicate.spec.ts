/**
 * S-14 빌더 고도화 — 인라인 검증 + 모바일 레이아웃 + 복제 흐름 (헌법 §9-2 · qa dispatch 2026-06-21)
 *
 * 검증 대상 (Wave2 #1070 + fix #1072, main 라이브 배포):
 *   - StrategyBuilderCanvas — 빈 슬롯 경고 + 검증/저장 disable (인라인 피드백)
 *   - 모바일 레이아웃(375px) — 블록·조건·결과 탭 세로 스택
 *   - 복제 흐름 — 목록 "복제" → 빌더가 신규 저장 모드(strategyId 없음 · 이름 변경 안내 토스트)
 *
 * ── 접근 ──────────────────────────────────────────────────────────────
 *   - 빈 캔버스: 시작 화면 "빈 캔버스로 직접" → 트리 비어있음 → 검증/저장 disabled.
 *   - 복제: /strategy/list "복제" 버튼 → /strategy/builder?strategyId=47&duplicate=1 →
 *           토스트 + 저장 모달 "전략 저장"(수정 아님).
 *   userId 주입은 s11~s13 관습(addInitScript localStorage 'magicjar-onboarding').
 *
 * ★ 라이브 보호: 저장 버튼 누르지 않음(실 DB 미오염). 복제는 deeplink/버튼 → 모달 렌더까지만.
 *   실데이터(전략 8건 · 템플릿 10건) mock 합성 0건(feedback_no_mock_data).
 *
 * 시나리오:
 *   S14-1 빈 캔버스 — 안내 + 검증/저장 disabled + 스크린샷
 *   S14-2 모바일(375px) 빈 캔버스 — 블록/조건/결과 탭 세로 스택 + 전체 스크린샷
 *   S14-3 복제 흐름 — 목록 복제 → deeplink + 토스트 + 저장 모달 "전략 저장"(신규)
 *
 * 실행:
 *   pnpm playwright test tests/e2e/s14-builder-validation-mobile-duplicate.spec.ts --reporter=list
 *   pnpm playwright test tests/e2e/s14-builder-validation-mobile-duplicate.spec.ts --update-snapshots
 */
import { expect, test, type Page } from '@playwright/test'

const USER_ID = 74683223
const DUP_STRATEGY_ID = 47 // qa_scalein_signalrepeat_s9 (PAUSED, 복제 안전)

async function seedUser(page: Page) {
  await page.addInitScript((uid) => {
    window.localStorage.setItem(
      'magicjar-onboarding',
      JSON.stringify({ state: { registeredUserId: uid }, version: 2 }),
    )
  }, USER_ID)
}

test.describe('S-14 빌더 고도화 — 검증·모바일·복제', () => {
  // S14-1 — 빈 캔버스 인라인 검증 (검증/저장 disabled)
  test('S14-1 빈 캔버스 — 안내 + 검증/저장 disabled', async ({ page }) => {
    await seedUser(page)
    await page.goto('/strategy/builder')
    await page.waitForLoadState('networkidle')
    await page.locator('[aria-label="전략 시작 화면"]').waitFor({ state: 'visible', timeout: 10000 })

    await page.getByRole('button', { name: '빈 캔버스로 직접' }).click()
    await expect(page.getByRole('heading', { name: '전략 실행' })).toBeVisible({ timeout: 5000 })

    // 빈 트리 안내 (팔레트에서 블록 선택 유도)
    await expect(page.getByText('팔레트에서 블록을 선택하세요')).toBeVisible()

    // 인라인 피드백 — 검증·저장 둘 다 disabled (조건 미완)
    await expect(page.getByRole('button', { name: /^검증$/ })).toBeDisabled()
    await expect(page.getByRole('button', { name: /^저장$/ })).toBeDisabled()

    await expect(page).toHaveScreenshot('s14-1-empty-canvas-disabled.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })

  // S14-2 — 모바일(375px) 빈 캔버스: 블록/조건/결과 탭 세로 스택
  test('S14-2 모바일 375px — 블록/조건/결과 탭 세로 스택', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 })
    await seedUser(page)
    await page.goto('/strategy/builder')
    await page.waitForLoadState('networkidle')
    await page.locator('[aria-label="전략 시작 화면"]').waitFor({ state: 'visible', timeout: 10000 })

    await page.getByRole('button', { name: '빈 캔버스로 직접' }).click()
    await page.waitForTimeout(600)

    // 모바일 탭 — 블록·조건·결과 (세로 스택 레이아웃에서 전환 탭으로 노출)
    await expect(page.getByRole('button', { name: '블록' }).first()).toBeVisible()
    await expect(page.getByRole('button', { name: '조건' }).first()).toBeVisible()
    await expect(page.getByRole('button', { name: '결과' }).first()).toBeVisible()

    await expect(page).toHaveScreenshot('s14-2-mobile-375-builder.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })

  // S14-3 — 복제 흐름: 목록 복제 → deeplink + 토스트 + 저장 모달 "전략 저장"(신규)
  test('S14-3 복제 흐름 — deeplink + 토스트 + 신규 저장 모드', async ({ page }) => {
    await seedUser(page)
    await page.goto('/strategy/list')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(1000)

    const dupBtn = page.getByRole('button', { name: /복제/ }).first()
    await dupBtn.waitFor({ state: 'visible', timeout: 8000 })
    await dupBtn.click()

    // 빌더로 라우팅 — duplicate deeplink
    await expect(page).toHaveURL(/\/strategy\/builder\?.*duplicate=1/, { timeout: 8000 })
    await expect(page).toHaveURL(new RegExp(`strategyId=${DUP_STRATEGY_ID}`))

    // 복제 안내 토스트 — "이름을 바꿔 새 전략으로 저장하세요"
    await expect(page.getByText(/복제 — 이름을 바꿔 새 전략으로 저장하세요/)).toBeVisible({
      timeout: 5000,
    })

    // 저장 모달 — 신규 모드(전략 저장, 수정 아님)
    const saveBtn = page.getByRole('button', { name: /^저장$/ })
    await expect(saveBtn).toBeEnabled({ timeout: 5000 })
    await saveBtn.click()
    const dialog = page.getByRole('dialog', { name: '전략 저장' })
    await dialog.waitFor({ state: 'visible', timeout: 5000 })

    // 신규 모드 — 헤더 "전략 저장"(수정 X) + 제출 버튼 "저장"(수정 X)
    await expect(dialog.getByRole('heading', { name: '전략 저장' })).toBeVisible()
    await expect(dialog.getByRole('heading', { name: '전략 수정' })).toHaveCount(0)
    await expect(dialog.getByRole('button', { name: '저장' })).toBeVisible()
    await expect(dialog.getByRole('button', { name: '수정' })).toHaveCount(0)
  })
})
