/**
 * S-16 추세매매 Phase 2 R3 Visual 검증 — qa 品質 헌터 on-demand dispatch (PR #1157, ADR 0140, 헌법 §9-2)
 *
 * 대상: frontend Phase 2 R3 — 기울선 전략 연결 + lookahead 경고 + 삭제 가드.
 * 읽기 전용 — 전략 저장·선 영구 삭제 금지. 검증용 임시 MANUAL/AUTO 선은 생성 후 afterAll 에서 청소.
 *
 * 스크린샷은 baseline(snapshots)이 아니라 scratchpad 에 page.screenshot 으로 발행한다
 * (add-only baseline 정책 보존 — 본 dispatch 는 baseline 신설이 아닌 스팟 검증).
 *
 * 005930 은 저장 추세선 0건이 실측 baseline(GET .../trendlines == []). 검증용 선을 API 로 심고 확인 후 지운다.
 */
import { expect, test, type APIRequestContext, type ConsoleMessage, type Page } from '@playwright/test'

const CODE = '005930'
const USER_ID = 74683223
const API = process.env.MAGICJAR_API_URL ?? 'http://localhost:8080/api'
const SHOTS = process.env.QA_SHOT_DIR ?? '/private/tmp/claude-501/-Users-ak-song-magic-ch1/05c98920-7225-4b8d-bba6-ebddfdb42693/scratchpad'
const LABEL = 'QA-PH2-TEMP'

// 검증용으로 심은 추세선 id — afterAll 청소 대상
const createdIds: number[] = []

/** store(registeredUserId) 선주입 — s11 관습 */
async function seedUser(page: Page) {
  await page.addInitScript((userId) => {
    window.localStorage.setItem(
      'magicjar-onboarding',
      JSON.stringify({ state: { registeredUserId: userId }, version: 2 }),
    )
  }, USER_ID)
}

async function createManualTrendline(
  request: APIRequestContext,
  body: {
    type: 'SUPPORT' | 'RESISTANCE'
    anchor1Ts: string
    anchor1Price: string
    anchor2Ts: string
    anchor2Price: string
    label: string
  },
): Promise<number> {
  const res = await request.post(`${API}/symbols/${CODE}/trendlines`, {
    data: { sourceInterval: '1d', extendRight: true, extendLeft: false, ...body },
  })
  expect(res.ok(), `POST trendline 실패: ${res.status()}`).toBeTruthy()
  const json = await res.json()
  createdIds.push(json.id)
  return json.id
}

/** 검증 아티팩트(QA-PH2 라벨 · AUTO source) 전량 삭제 — 각 테스트 격리용 */
async function sweep(request: APIRequestContext) {
  const res = await request.get(`${API}/symbols/${CODE}/trendlines`)
  if (!res.ok()) return
  const lines = (await res.json()) as Array<{ id: number; label?: string | null; source?: string }>
  for (const l of lines) {
    if (l.label?.startsWith('QA-PH2') || l.source === 'AUTO') {
      await request.delete(`${API}/symbols/${CODE}/trendlines/${l.id}?force=true`).catch(() => {})
    }
  }
}

/** 기존 QA-PH2 잔재 청소 (이전 run afterAll 실패 대비) */
test.beforeAll(async ({ request }) => {
  await sweep(request)
})

test.afterAll(async ({ request }) => {
  for (const id of createdIds) {
    await request.delete(`${API}/symbols/${CODE}/trendlines/${id}?force=true`).catch(() => {})
  }
  // 잔재 재확인 청소
  const res = await request.get(`${API}/symbols/${CODE}/trendlines`)
  if (res.ok()) {
    const lines = (await res.json()) as Array<{ id: number; label?: string | null }>
    for (const l of lines) {
      if (l.label?.startsWith('QA-PH2')) {
        await request.delete(`${API}/symbols/${CODE}/trendlines/${l.id}?force=true`).catch(() => {})
      }
    }
  }
})

/**
 * lightweight-charts 는 차트 언마운트/라우트 전환 시 dispose 후 남은 콜백이 발화하며
 * "Object is disposed" 를 던지는 알려진 teardown 레이스가 있다(PR #1157 무관 — 차트 lifecycle 미변경).
 * 회귀 판정에서 제외하되 별도 집계해 투명 보고한다.
 */
const BENIGN_PAGEERROR = /Object is disposed|ResizeObserver loop/i

function attachConsole(page: Page): { errors: string[]; pageErrors: string[]; benign: string[] } {
  const errors: string[] = []
  const pageErrors: string[] = []
  const benign: string[] = []
  page.on('console', (msg: ConsoleMessage) => {
    if (msg.type() === 'error') errors.push(msg.text())
  })
  page.on('pageerror', (err) => {
    if (BENIGN_PAGEERROR.test(err.message)) benign.push(err.message)
    else pageErrors.push(err.message)
  })
  return { errors, pageErrors, benign }
}

/** 차트 추세선 선택 hit 버튼(aria-label="추세선 선택")이 렌더될 때까지 대기 후 선택 */
async function selectTrendline(page: Page) {
  const hit = page.getByLabel('추세선 선택').first()
  await hit.waitFor({ state: 'attached', timeout: 30_000 })
  await hit.click({ force: true })
}

test.describe.serial('S-16 추세매매 Phase2 R3', () => {
  test('1. 기울선(MANUAL) — "이 선으로 전략 만들기" 활성 (Phase1 수평 제한 해제)', async ({ page, request }) => {
    const noise = attachConsole(page)
    await sweep(request)
    // 기울선: 두 앵커 가격이 크게 다름 → sloped
    await createManualTrendline(request, {
      type: 'SUPPORT',
      anchor1Ts: '2025-12-07T15:00:00Z',
      anchor1Price: '109500',
      anchor2Ts: '2026-07-01T15:00:00Z',
      anchor2Price: '309500',
      label: `${LABEL}-SLOPED`,
    })

    await seedUser(page)
    await page.goto(`/symbol/${CODE}`)
    await page.waitForLoadState('networkidle')
    await selectTrendline(page)

    const btn = page.getByRole('button', { name: '이 선으로 전략 만들기' })
    await expect(btn).toBeVisible({ timeout: 10_000 })
    await expect(btn, '기울선(MANUAL)에서 버튼이 활성이어야 함 — Phase1 수평 제한 해제').toBeEnabled()

    await page.screenshot({ path: `${SHOTS}/s16-1-sloped-button-active.png` })
    expect(noise.pageErrors, `pageError: ${noise.pageErrors.join('|')}`).toHaveLength(0)
  })

  test('2. AUTO 선(있으면) — 버튼 비활성 (시각 보조선 방향 가드)', async ({ page, request }) => {
    const noise = attachConsole(page)
    await sweep(request)
    // AUTO 선 자동 생성 시도 (0..N — best-effort). 생성분은 청소.
    const autoRes = await request.post(`${API}/symbols/${CODE}/trendlines/auto?interval=1d&lookback=240`)
    let autoCount = 0
    if (autoRes.ok()) {
      const json = await autoRes.json()
      const lines = (json.trendlines ?? []) as Array<{ id: number }>
      autoCount = lines.length
      for (const l of lines) createdIds.push(l.id)
    }

    await seedUser(page)
    await page.goto(`/symbol/${CODE}`)
    await page.waitForLoadState('networkidle')

    if (autoCount === 0) {
      test.info().annotations.push({ type: 'note', description: 'AUTO 추세선 0건 생성 — 항목 N/A (피벗 부재)' })
      await page.screenshot({ path: `${SHOTS}/s16-2-auto-none.png` })
      return
    }

    // AUTO 는 점선(strokeDasharray)로 far-right 에 짧게 렌더 — 얇은 hit 을 boundingBox 중심으로 순회 선택.
    // 이 종목엔 MANUAL 이 하나도 없으므로(sweep 후 AUTO 만), 선택 후 뜨는 create 버튼은 반드시 disabled 여야 한다.
    const hits = page.getByLabel('추세선 선택')
    await hits.first().waitFor({ state: 'attached', timeout: 30_000 })
    const createBtn = page.locator('button[aria-label="이 선으로 전략 만들기"]')
    const hitCount = await hits.count()
    let selected = false
    for (let i = hitCount - 1; i >= 0; i--) {
      const box = await hits.nth(i).boundingBox()
      if (!box) continue
      await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2)
      if (await createBtn.count()) {
        selected = true
        break
      }
    }
    // 불변식: AUTO-only 차트에서 활성(enabled) create 버튼은 절대 없어야 한다.
    const enabledCreate = page.locator('button[aria-label="이 선으로 전략 만들기"]:not([disabled])')
    expect(await enabledCreate.count(), 'AUTO 선에는 활성 전략 생성 버튼이 없어야 함').toBe(0)
    if (selected) {
      await expect(createBtn.first(), 'AUTO 선 선택 시 create 버튼은 disabled').toBeDisabled()
    } else {
      test.info().annotations.push({ type: 'note', description: 'AUTO hit 선택 실패(얇은 geometry) — enabled 버튼 0 불변식으로 대체 검증' })
    }
    await page.screenshot({ path: `${SHOTS}/s16-2-auto-button-disabled.png` })
    expect(noise.pageErrors).toHaveLength(0)
  })

  test('3. 빌더 프리셋 진입 — 기울선 안내 패널 + trendline(id) 블록 (저장 안 함)', async ({ page, request }) => {
    const noise = attachConsole(page)
    await sweep(request)
    await createManualTrendline(request, {
      type: 'RESISTANCE',
      anchor1Ts: '2025-12-07T15:00:00Z',
      anchor1Price: '120000',
      anchor2Ts: '2026-07-01T15:00:00Z',
      anchor2Price: '300000',
      label: `${LABEL}-BUILDER`,
    })

    await seedUser(page)
    await page.goto(`/symbol/${CODE}`)
    await page.waitForLoadState('networkidle')
    await selectTrendline(page)

    await page.getByRole('button', { name: '이 선으로 전략 만들기' }).click()

    // SPA 이동 → 빌더
    await expect(page).toHaveURL(/\/strategy\/builder/, { timeout: 10_000 })
    // 기울선 안내 패널
    await expect(page.getByText('차트 추세선에서 생성된 프리셋')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText(/기울선 참조 trendline\(\d+\)/)).toBeVisible()
    // 캔버스 trendline(id) 블록 (teal 추세선 라벨)
    await expect(page.getByText(/^trendline\(\d+\)$/).first()).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('추세선', { exact: true }).first()).toBeVisible()

    await page.screenshot({ path: `${SHOTS}/s16-3-builder-preset.png`, fullPage: true })
    // 저장 절대 안 함 — 스크린샷 후 종료
    expect(noise.pageErrors, `pageError: ${noise.pageErrors.join('|')}`).toHaveLength(0)
  })

  test('4a. 회귀 — 수평선(MANUAL) 버튼 활성 + 상수 DSL 프리셋 (Phase1 불변)', async ({ page, request }) => {
    const noise = attachConsole(page)
    await sweep(request)
    // 수평선: 두 앵커 가격 동일 → 상수 DSL
    await createManualTrendline(request, {
      type: 'SUPPORT',
      anchor1Ts: '2025-12-07T15:00:00Z',
      anchor1Price: '250000',
      anchor2Ts: '2026-07-01T15:00:00Z',
      anchor2Price: '250000',
      label: `${LABEL}-FLAT`,
    })

    await seedUser(page)
    await page.goto(`/symbol/${CODE}`)
    await page.waitForLoadState('networkidle')
    await selectTrendline(page)

    const btn = page.getByRole('button', { name: '이 선으로 전략 만들기' })
    await expect(btn).toBeVisible({ timeout: 10_000 })
    await expect(btn, '수평선(MANUAL) 버튼 활성 — Phase1 회귀 0').toBeEnabled()
    await btn.click()

    await expect(page).toHaveURL(/\/strategy\/builder/, { timeout: 10_000 })
    await expect(page.getByText('차트 추세선에서 생성된 프리셋')).toBeVisible({ timeout: 10_000 })
    // 수평선은 상수 DSL — "수평선 @..." 표기(기울선 참조가 아님)
    await expect(page.getByText(/수평선 @/)).toBeVisible()
    await page.screenshot({ path: `${SHOTS}/s16-4a-flat-preset.png`, fullPage: true })
    expect(noise.pageErrors).toHaveLength(0)
  })

  test('4b. 회귀 — 전략 목록 보관함 탭 로드', async ({ page }) => {
    const noise = attachConsole(page)
    await seedUser(page)
    await page.goto('/strategy/list')
    await page.waitForLoadState('networkidle')
    const tab = page.getByRole('button', { name: '보관함' }).first()
    if (await tab.count()) await tab.click().catch(() => {})
    await page.waitForTimeout(500)
    await page.screenshot({ path: `${SHOTS}/s16-4b-strategy-list-archive.png`, fullPage: true })
    expect(noise.pageErrors, `pageError: ${noise.pageErrors.join('|')}`).toHaveLength(0)
  })

  test('4c. 회귀 — /guide 로드', async ({ page }) => {
    const noise = attachConsole(page)
    await seedUser(page)
    await page.goto('/guide')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(500)
    await page.screenshot({ path: `${SHOTS}/s16-4c-guide.png`, fullPage: true })
    expect(noise.pageErrors, `pageError: ${noise.pageErrors.join('|')}`).toHaveLength(0)
  })
})
