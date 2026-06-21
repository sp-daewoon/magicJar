/**
 * S-15 전략 목록 UX — 검색·정렬·배지·빠른 액션·상태 요약 칩 (헌법 §9-2 · qa dispatch 2026-06-21)
 *
 * 검증 대상 (Wave2 #1069 #1071, main 라이브 배포):
 *   - StrategyFilterBar — 검색(debounce 300ms) + 정렬 드롭다운(최근·이름·상태) + 상태 탭
 *   - strategyCardBadges — 카드 배지: 분할매수(N회) · floor(자동청산 red / 알람 amber)
 *   - StrategyCard 빠른 액션 — 시작/중지·즐겨찾기·복제
 *   - StrategyList 다중선택 — 일괄 시작/중지
 *   - StrategyRunStatusSummary — 상태 요약 칩(자동매매중/매수차단/중지 + floor 알람·분할 진행),
 *       클릭 시 필터 점프(ACTIVE/BUY_BLOCKED/PAUSED)
 *
 * ── 라이브 데이터 정합 (실측 GET /api/strategies?userId=74683223) ──────────
 *   전략 8건. #47 qa_scalein_signalrepeat_s9 = scaleIn totalSlices=3(분할 3회 배지),
 *   floorPct=None(→ floor 배지·floor 알람 칩 없음 = 정상). PAUSED 8 / ACTIVE 0 / BUY_BLOCKED 0.
 *   ⇒ "분할 진행 1" 정보 칩은 노출, "floor 알람" 칩은 미노출(설정 전략 0건).
 *   floor 카드 배지/칩은 라이브 전략에 floor 설정분이 없어 렌더 미발생 — strategyCardBadges.ts
 *   단위테스트(floorBadge red/amber)가 파생 로직을 커버한다. 본 spec은 라이브 렌더 가능분만 검증.
 *
 * ★ 라이브 보호: 시작/중지·즐겨찾기·복제 같은 상태 변경 액션은 발사하지 않는다(실 canary DB 보호 —
 *   전략47 PAUSED·후성 오버나잇 보존). 렌더·검색·정렬·필터점프·다중선택 진입까지만 검증.
 *
 * 시나리오:
 *   S15-1 목록 전체 렌더 + 필터 바·정렬·칩 + 스크린샷
 *   S15-2 검색(debounce) — "canary" 입력 → 카드 수 축소(8→실측)
 *   S15-3 정렬 드롭다운 — 이름순 전환 → 카드 순서 갱신(렌더 안정)
 *   S15-4 카드 배지 — 분할 3회 배지(#47) 가시 + 빠른 액션 버튼(즐겨찾기·복제·시작/중지) 존재
 *   S15-5 상태 요약 칩 — 중지 칩 클릭 → 필터 탭 "중지" 활성(점프)
 *   S15-6 다중선택 — "여러 개 선택" → 체크박스·전체선택·일괄 시작/중지 컨트롤 노출
 *
 * 실행:
 *   pnpm playwright test tests/e2e/s15-strategy-list-ux.spec.ts --reporter=list
 *   pnpm playwright test tests/e2e/s15-strategy-list-ux.spec.ts --update-snapshots
 */
import { expect, test, type Page } from '@playwright/test'

const USER_ID = 74683223
const CARD = '[aria-label="전략별 보유 현황"]'

async function seedUser(page: Page) {
  await page.addInitScript((uid) => {
    window.localStorage.setItem(
      'magicjar-onboarding',
      JSON.stringify({ state: { registeredUserId: uid }, version: 2 }),
    )
  }, USER_ID)
}

async function gotoList(page: Page) {
  await seedUser(page)
  await page.goto('/strategy/list')
  await page.waitForLoadState('networkidle')
  await page.getByRole('heading', { name: '전략 목록' }).waitFor({ state: 'visible', timeout: 10000 })
  await page.waitForTimeout(800) // 카드 폴링·렌더 안정화
}

test.describe('S-15 전략 목록 UX', () => {
  // S15-1 — 전체 렌더 + 필터 바·정렬·칩 baseline
  test('S15-1 목록 전체 렌더 + 필터·정렬·칩 baseline', async ({ page }) => {
    await gotoList(page)

    // 필터 바
    await expect(page.getByRole('searchbox', { name: '전략 검색' })).toBeVisible()
    await expect(page.getByRole('combobox', { name: '전략 정렬' })).toBeVisible()
    await expect(page.getByRole('tablist', { name: '전략 상태 필터' })).toBeVisible()

    // 상태 요약 칩(클릭형 3종)
    await expect(page.getByRole('button', { name: /^자동매매중 \d+$/ })).toBeVisible()
    await expect(page.getByRole('button', { name: /^매수차단 \d+$/ })).toBeVisible()
    await expect(page.getByRole('button', { name: /^중지 \d+$/ })).toBeVisible()
    // 정보 칩 — 분할 진행(라이브 #47 scaleIn → 노출)
    await expect(page.getByText(/^분할 진행 \d+$/)).toBeVisible()

    // 카드 1건 이상 (실데이터)
    expect(await page.locator(CARD).count()).toBeGreaterThan(0)

    await expect(page).toHaveScreenshot('s15-1-list-full.png', {
      fullPage: true,
      animations: 'disabled',
      // 폴링되는 보유/평가손익·시그널 시각 라인은 mask로 noise 차단(자본 path 직결 화면 안정성)
      mask: [page.locator('[aria-label="전략별 보유 현황"]')],
    })
  })

  // S15-2 — 검색 debounce: "canary" → 카드 수 축소
  test('S15-2 검색(debounce) — canary 필터로 카드 수 축소', async ({ page }) => {
    await gotoList(page)
    const before = await page.locator(CARD).count()
    expect(before).toBeGreaterThan(0)

    await page.getByRole('searchbox', { name: '전략 검색' }).fill('canary')
    // debounce 300ms + 서버 재조회 — poll
    await expect
      .poll(async () => page.locator(CARD).count(), { timeout: 6000 })
      .toBeLessThan(before)

    // 남은 카드는 모두 canary 관련(실데이터 — 이름·코드 부분일치)
    const after = await page.locator(CARD).count()
    expect(after).toBeGreaterThan(0)
  })

  // S15-3 — 정렬 드롭다운: 이름순 전환 (렌더 안정 + 옵션 정합)
  test('S15-3 정렬 드롭다운 — 최근·이름·상태 3옵션 + 이름순 전환', async ({ page }) => {
    await gotoList(page)
    const sort = page.getByRole('combobox', { name: '전략 정렬' })
    await expect(sort.locator('option')).toHaveText(['최근순', '이름순', '상태순'])

    await sort.selectOption('name')
    // 정렬 전환 후에도 카드가 유지(서버 재조회) — 렌더 안정
    await expect
      .poll(async () => page.locator(CARD).count(), { timeout: 6000 })
      .toBeGreaterThan(0)
    await expect(sort).toHaveValue('name')
  })

  // S15-4 — 카드 배지 + 빠른 액션 버튼 존재 (액션 발사 X)
  test('S15-4 카드 배지(분할 N회) + 빠른 액션 버튼 존재', async ({ page }) => {
    await gotoList(page)

    // #47 분할 3회 배지 — 라이브 scaleIn totalSlices=3
    await expect(page.getByText('분할 3회').first()).toBeVisible()

    // 빠른 액션 — 즐겨찾기 / 복제 버튼이 카드마다 존재 (클릭하지 않음)
    expect(await page.getByRole('button', { name: /즐겨찾기 (추가|해제)/ }).count()).toBeGreaterThan(0)
    expect(await page.getByRole('button', { name: /복제/ }).count()).toBeGreaterThan(0)
    // 시작/중지 빠른 액션 — PAUSED 전략에 "시작" 계열 버튼 존재
    expect(
      await page.getByRole('button', { name: /시작|중지|재개|활성/ }).count(),
    ).toBeGreaterThan(0)
  })

  // S15-5 — 상태 요약 칩 클릭 → 필터 점프 (중지 → PAUSED 탭 활성)
  test('S15-5 상태 칩 클릭 → 필터 탭 점프', async ({ page }) => {
    await gotoList(page)

    // 중지 칩 클릭 → 필터 옵션 PAUSED 로 점프 → "중지" 탭 aria-selected
    // (exact:true — "중지"가 "보호중지" 탭에 부분매칭되는 strict-mode 충돌 회피)
    await page.getByRole('button', { name: /^중지 \d+$/ }).click()
    const stopTab = page.getByRole('tab', { name: '중지', exact: true })
    await expect
      .poll(async () => stopTab.getAttribute('aria-selected'), { timeout: 4000 })
      .toBe('true')

    // 자동매매중 칩 클릭 → ACTIVE 탭 활성 점프
    await page.getByRole('button', { name: /^자동매매중 \d+$/ }).click()
    const activeTab = page.getByRole('tab', { name: '자동매매중' })
    await expect
      .poll(async () => activeTab.getAttribute('aria-selected'), { timeout: 4000 })
      .toBe('true')
  })

  // S15-6 — 다중선택 모드 진입 (일괄 시작/중지 컨트롤 노출, 발사 X)
  test('S15-6 다중선택 — 체크박스·전체선택·일괄 시작/중지 노출', async ({ page }) => {
    await gotoList(page)

    await page.getByRole('button', { name: /여러 개 선택/ }).click()
    await page.waitForTimeout(400)

    // 카드 체크박스 노출
    expect(await page.getByRole('checkbox').count()).toBeGreaterThan(0)
    // 전체 선택 + 일괄 시작 컨트롤
    await expect(page.getByText('전체 선택')).toBeVisible()
    await expect(page.getByRole('button', { name: /일괄 시작/ })).toBeVisible()
    await expect(page.getByRole('button', { name: /선택 종료/ })).toBeVisible()

    // 선택 종료로 원복(상태 변경 미발사)
    await page.getByRole('button', { name: /선택 종료/ }).click()
    await expect(page.getByText('전체 선택')).toBeHidden()
  })
})
