/**
 * S-11 전략 빌더 시작 화면 — Visual + E2E (헌법 §9-2 frontend 핵심 화면 신규 의무 dispatch)
 *
 * 검증 대상: /strategy/builder 진입 시 빈 캔버스가 아니라 "시작 화면"이 뜨는가.
 *   StrategyStartScreen (frontend/src/components/strategy/StrategyStartScreen.tsx, PR #1044 R3)
 *   StrategyBuilderPage 분기 (showStartScreen — 빈 트리 + 미로드 + deeplink 없음 + 미시작)
 *
 * userId 주입: store(registeredUserId)는 localStorage 'magicjar-onboarding' persist에서 복원되며,
 *   useOnboardingStatus가 server status.userId(74683223)를 store에 auto-sync한다.
 *   결정적 재현을 위해 s8/s9/s10 관습대로 addInitScript로 localStorage를 선주입한다.
 *
 * 실데이터 (API 실측): GET /api/strategies/start?userId=74683223 → templates 10 + myStrategies 8.
 *   mock 합성 0 (feedback_no_mock_data) — 실제 백엔드 응답으로 진행.
 *
 * 시나리오:
 *   1. 시작 화면 렌더 — 헤더·📦검증 템플릿·⭐내 전략·🔍검색·💬말로 설명·"빈 캔버스로 직접"
 *   2. 템플릿 카드 클릭 → 빌더 캔버스 전환 (DSL 로드)
 *   3. 즐겨찾기 토글 → POST/DELETE /favorite 발사 + UI 반영
 *   4. 빈 캔버스 탈출구 → 기존 빌더
 *   5. 스크린샷 — 시작 화면 전체 + 카드 클릭 후 빌더
 */
import { expect, test, type Request } from '@playwright/test'

const USER_ID = 74683223

/** store(registeredUserId) 선주입 — useOnboardingStatus auto-sync 전 race 회피 */
async function seedUser(page: import('@playwright/test').Page) {
  await page.addInitScript((userId) => {
    window.localStorage.setItem(
      'magicjar-onboarding',
      JSON.stringify({ state: { registeredUserId: userId }, version: 2 }),
    )
  }, USER_ID)
}

/** 시작 화면 컨테이너 — aria-label="전략 시작 화면" (StrategyStartScreen 루트) */
function startScreen(page: import('@playwright/test').Page) {
  return page.locator('[aria-label="전략 시작 화면"]')
}

test.describe('S-11 전략 빌더 시작 화면', () => {
  test('1+5. 시작 화면 렌더 + 전체 스크린샷 baseline', async ({ page }) => {
    await seedUser(page)
    await page.goto('/strategy/builder')
    await page.waitForLoadState('networkidle')

    const screen = startScreen(page)
    await screen.waitFor({ state: 'visible', timeout: 10000 })

    // 헤더
    await expect(page.getByRole('heading', { name: '전략 만들기 시작' })).toBeVisible()

    // 💬 말로 설명 / 빈 캔버스 입구 버튼
    await expect(page.getByRole('button', { name: /말로 설명/ })).toBeVisible()
    await expect(page.getByRole('button', { name: '빈 캔버스로 직접' })).toBeVisible()

    // 🔍 검색
    await expect(page.getByRole('searchbox', { name: '전략 검색' })).toBeVisible()

    // 📦 검증 템플릿 섹션 — API templates 10건 (개수는 API가 주는 그대로, 하드코딩 0)
    await expect(page.getByRole('heading', { name: /검증 템플릿/ })).toBeVisible()
    // ⭐ 내 전략 섹션 — myStrategies 8건
    await expect(page.getByRole('heading', { name: /내 전략/ })).toBeVisible()

    // 카드는 "불러오기" aria-label 버튼 — 1건 이상 렌더 확인 (실데이터)
    const cards = page.getByRole('button', { name: /불러오기$/ })
    expect(await cards.count()).toBeGreaterThan(0)

    await expect(page).toHaveScreenshot('s11-start-screen-full.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })

  test('2. 템플릿 카드 클릭 → 빌더 캔버스 전환 (DSL 로드)', async ({ page }) => {
    await seedUser(page)
    await page.goto('/strategy/builder')
    await page.waitForLoadState('networkidle')
    await startScreen(page).waitFor({ state: 'visible', timeout: 10000 })

    // 검색으로 결정적 카드 1건(FVG 매수 후보) 추림 — confirm 다이얼로그 회피(빈 트리라 미발생)
    await page.getByRole('searchbox', { name: '전략 검색' }).fill('FVG')
    const card = page.getByRole('button', { name: /불러오기$/ }).first()
    await card.waitFor({ state: 'visible', timeout: 5000 })
    await card.click()

    // 시작 화면 사라지고 빌더 캔버스 진입 — 시작 화면 컨테이너 detach
    await expect(startScreen(page)).toBeHidden({ timeout: 5000 })

    // 빌더 우측 결과 영역 "전략 실행" 헤더 등장 (데스크톱 레이아웃)
    await expect(page.getByRole('heading', { name: '전략 실행' })).toBeVisible({ timeout: 5000 })

    await expect(page).toHaveScreenshot('s11-builder-after-pick.png', {
      fullPage: true,
      animations: 'disabled',
    })
  })

  test('3. 즐겨찾기 토글 → /favorite 네트워크 발사 + UI 반영', async ({ page }) => {
    await seedUser(page)
    await page.goto('/strategy/builder')
    await page.waitForLoadState('networkidle')
    await startScreen(page).waitFor({ state: 'visible', timeout: 10000 })

    // 첫 카드의 ★ 버튼 — aria-label "즐겨찾기 추가" 또는 "즐겨찾기 해제"
    const favBtn = page
      .getByRole('button', { name: /즐겨찾기 (추가|해제)$/ })
      .first()
    await favBtn.waitFor({ state: 'visible', timeout: 5000 })

    const wasPressed = (await favBtn.getAttribute('aria-pressed')) === 'true'
    // 현재 상태와 반대 메서드를 기대: 추가면 POST, 해제면 DELETE
    const expectedMethod = wasPressed ? 'DELETE' : 'POST'

    const favRequest = page.waitForRequest(
      (req: Request) =>
        /\/api\/strategies\/\d+\/favorite/.test(req.url()) && req.method() === expectedMethod,
      { timeout: 8000 },
    )

    await favBtn.click()
    const req = await favRequest
    expect(req.url()).toContain(`userId=${USER_ID}`)

    // 낙관적/무효화 반영 — query 무효화 후 aria-pressed 토글 (재조회 대기)
    await expect
      .poll(async () => (await favBtn.getAttribute('aria-pressed')), { timeout: 8000 })
      .toBe(String(!wasPressed))

    // 원복 — baseline 오염 회피 (add-only 정신 + 멱등)
    const restoreMethod = wasPressed ? 'POST' : 'DELETE'
    const restoreReq = page.waitForRequest(
      (req2: Request) =>
        /\/api\/strategies\/\d+\/favorite/.test(req2.url()) && req2.method() === restoreMethod,
      { timeout: 8000 },
    )
    await favBtn.click()
    await restoreReq
  })

  test('4. "빈 캔버스로 직접" → 기존 빌더 진입', async ({ page }) => {
    await seedUser(page)
    await page.goto('/strategy/builder')
    await page.waitForLoadState('networkidle')
    await startScreen(page).waitFor({ state: 'visible', timeout: 10000 })

    await page.getByRole('button', { name: '빈 캔버스로 직접' }).click()

    // 시작 화면 detach + 빌더 진입
    await expect(startScreen(page)).toBeHidden({ timeout: 5000 })
    await expect(page.getByRole('heading', { name: '전략 실행' })).toBeVisible({ timeout: 5000 })
  })
})
