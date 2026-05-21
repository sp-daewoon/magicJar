# qa Visual 격상 design — "품질 헌터" 페르소나 + Playwright 도구 매트릭스 + 시나리오 트리거

> **위치**: `_workspace/specs/2026-05-21-qa-visual-uplift.md`
> **연관 ADR**: `_workspace/arch/adr/0094-ops-stage-transition.md` Accepted §2-4 (본 spec의 design 본문)
> **작성**: architect (worktree `magic_ch1_architect_ops_transition`, branch `agent/architect/ops-transition-plan`)
> **결재**: 사용자(최고 관리자) 2026-05-21 KST 명시 위임 + orchestrator 자율 결정 매트릭스 반영
> **사용자 명시 evidence**:
> > "큐에이도 지금 필요한데 그냥 단순 에이전트가 아닌 확실한 큐에이가 필요하거든 스크린샷 등 하면서 테스트 시킬라했어. 오케스트라 믿고 맡길께. 최고 훌륭한 팀원으로 다시 구성해줘"

본 spec은 ADR 0094 §2-4 "qa Visual 격상 design" 본문을 구현 수준으로 확장한다. ADR은 결정·매트릭스만, 본 spec은 도구·페르소나·시나리오·셋업 가이드 보유.

---

## §0. 격상 trigger 사용자 evidence

| evidence | 인용 |
|----------|------|
| 1단계 사용자 결재 | "전영역 코드베이스로 검증 절차에 돌입해야해... 시스템 운영 단계 및 부분 고도화로 갈 준비를 해야해" |
| 2단계 사용자 위임 결재 (qa 격상 명시) | "큐에이도 지금 필요한데 그냥 단순 에이전트가 아닌 확실한 큐에이가 필요하거든 스크린샷 등 하면서 테스트 시킬라했어. 오케스트라 믿고 맡길께. 최고 훌륭한 팀원으로 다시 구성해줘" |
| past 7w qa dispatch | 1건 (frontend 145·market-data 129 대비 0.7%) — 단순 게이트 룰 부재 + 도구 매트릭스 부재 root cause |
| 사용자 명시 도구 | "스크린샷 등 하면서 테스트" → Playwright·시각 회귀·E2E 의무 |

**format**: 1단계 ADR 0094 "qa 해고" 제안 → 2단계 사용자 명시 "격상"으로 정정.

---

## §1. qa Visual 격상 — "품질 헌터" 페르소나 LOCK

### 1-1. 페르소나 본문 (`.claude/agents/qa.md` amend 본문 LOCK)

| 항목 | 값 |
|------|----|
| 역할 | Quality Hunter — Visual QA Engineer |
| 경력 | 10년차 (시각 회귀·E2E·사용자 시나리오 자동 재현 전문) |
| 성향 | 방어적, 사용자 시점 1순위, 회귀 0 LOCK lens |
| 별명 | "품질 헌터" (quality hunter) — 결함을 사용자보다 먼저 발견하는 사냥꾼 |
| 좌우명 | "사용자가 보기 전에 내가 본다." |
| 핵심 가치 5건 | (1) 사용자 시점 시나리오 자동 재현 (2) Visual 회귀 0 LOCK (3) evidence 수집(스크린샷·비디오·DOM snapshot) (4) 결함 1차 차단 + 사용자 보고 회피 (5) E2E 시나리오 자동화 — 핵심 화면·자본 path 직결 흐름 |
| 코드 리뷰 lens 6건 | (1) 사용자 시나리오 자동 재현 가능성 (2) Visual 회귀 evidence (스크린샷 diff) (3) DOM accessibility 검증 (4) E2E flow 자본 path 정합성 (5) 응답 시간 사용자 인지 임계치 (6) 결함 재현 단계 명확성 |
| 출력 형식 | `## QA (품질 헌터) 의견` 헤더 + Visual 회귀 매트릭스(스크린샷 before/after) + 시나리오 결과(✅ Pass / ❌ Fail + evidence path) + E2E flow 검증 + 점검 사항(📸 스크린샷 / 🎬 비디오 / 🌐 DOM / ⏱️ 응답시간 / 🚨 회귀) |
| 트리거 | frontend 핵심 화면 변경 PR (Dashboard·Orders·Positions·Settings) · dogfooding 결함 시나리오 재현 · 정기 주간 SLA Visual 검증 · 신규 트랙 진입 E2E plan · incident response 자동 재현 |
| 모델 | opus 4.7 (ADR 0080 정합) |

### 1-2. 페르소나 cross-review 매트릭스 (운영 단계 7 actor)

| 영역 | 페르소나 | qa Visual 검토 의무 |
|------|---------|-------------------|
| backend-core | 진보 "리팩토링맨" | OPS 변경 시 frontend 영향 → qa Visual 회귀 의무 |
| frontend | 보수 "안정빌드" (chart-curator 흡수) | 핵심 화면 변경 시 qa Visual 자동 dispatch 의무 |
| market-data | 보수 "안정빌드" (chart-curator 캔들 SLA 흡수) | 캔들 데이터 결함 시 qa Visual 시나리오 자동 재현 |
| strategy-engine | 보수 "안정빌드" | 시그널 → 주문 path E2E 시나리오 의무 |
| ai-engineer | 균형 (W4 가동 시) | AI 분석 UI 변경 시 qa Visual 회귀 의무 |
| **qa (격상)** | **방어 "품질 헌터"** | — (본인) |
| architect | 균형 결정자 | cross-review |

---

## §2. 도구 매트릭스 — Playwright + 시각 회귀

### 2-1. 채택 후보 매트릭스

| 도구 | 강점 | 약점 | **채택 평가** |
|------|------|------|-------------|
| **Playwright** (@playwright/test) | 멀티 브라우저(Chromium·Firefox·WebKit) + 자동 대기 + 트레이스 viewer + 스크린샷·비디오·DOM snapshot 내장 + TS first-class + 빠른 실행 | 학습 cost (Cypress 대비) | **✅ 채택 (1순위 LOCK)** |
| Cypress | DX 우수 + community 풍부 | 멀티 브라우저 제한적 + iframe·cross-origin 약점 + 트레이스 viewer 부재 | ❌ 거부 (Playwright 우위) |
| Percy (Browserstack) | 강력한 Visual diff + cloud 자동 | 외부 SaaS 의존 + 비용 + 사용자 메모리 `feedback_no_github_actions` 정합 위반 risk | ❌ 거부 (외부 SaaS 회피) |
| Chromatic | Storybook 통합 강력 | SaaS 의존 + magicJar Storybook 0건 | ❌ 거부 |
| **@playwright/test toMatchSnapshot** | Playwright 내장 + 로컬 PNG diff + git tracked | cloud cross-browser 시각 회귀 manual | **✅ 채택 (Playwright 내장 사용)** |
| Storybook + Chromatic | 컴포넌트 단위 | Storybook 0건 + SaaS | ❌ 거부 |

**최종 채택**: **Playwright + @playwright/test 내장 toMatchSnapshot** 단독 — 외부 SaaS 0 + 사용자 메모리 `feedback_no_github_actions` 정합 + local 실행 + git tracked PNG baseline.

### 2-2. 의존 매트릭스 (frontend 추가 — backend-core dispatch 동반)

```jsonc
// frontend/package.json devDependencies (qa 격상 P0 dispatch 시 추가)
{
  "@playwright/test": "^1.49.0",
  "playwright": "^1.49.0"
}
```

설치:
```bash
cd frontend && pnpm add -D @playwright/test playwright
npx playwright install --with-deps chromium  // 1순위 단일 브라우저 (운영 부담 ↓)
```

### 2-3. 디렉토리 구조 (frontend 신규)

```
frontend/
├─ tests/
│  ├─ visual/                  # @playwright/test toMatchSnapshot
│  │  ├─ dashboard.spec.ts     # Dashboard 시각 회귀
│  │  ├─ orders.spec.ts        # Orders 시각 회귀
│  │  ├─ positions.spec.ts     # Positions 시각 회귀
│  │  ├─ settings.spec.ts      # Settings 시각 회귀
│  │  └─ snapshots/            # baseline PNG (git tracked)
│  │     └─ <test-name>-<viewport>.png
│  ├─ e2e/                     # 사용자 시나리오 E2E
│  │  ├─ order-flow.spec.ts    # 주문 → 체결 → 잔고 갱신 flow
│  │  ├─ signal-flow.spec.ts   # 시그널 → 주문 발사 flow
│  │  ├─ backtest-flow.spec.ts # 백테스트 실행 → 결과 표시 flow
│  │  ├─ kill-switch.spec.ts   # KillSwitch 토글 + UI 반응
│  │  └─ symbol-search.spec.ts # 종목 검색 + Watchlist 추가
│  ├─ fixtures/
│  │  ├─ auth.ts               # KIS 키 입력 + 로그인 fixture
│  │  └─ mock-kis.ts           # 실제 KIS 호출 회피 mock layer (테스트 전용)
│  └─ playwright.config.ts     # Playwright 설정 (viewport·timeout·retry·reporter)
├─ src/
└─ package.json
```

### 2-4. playwright.config.ts 기본 설정

```typescript
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  retries: process.env.CI ? 2 : 0,  // 로컬 retry 0 (사용자 메모리 feedback_no_github_actions)
  workers: process.env.CI ? 1 : undefined,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
  ],
  use: {
    baseURL: process.env.MAGICJAR_FRONTEND_URL ?? 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
  },
  projects: [
    {
      name: 'chromium-desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    {
      name: 'chromium-mobile',
      use: { ...devices['Pixel 5'] },
    },
  ],
  expect: {
    toHaveScreenshot: {
      maxDiffPixelRatio: 0.01,  // 1% threshold (운영 단계 진입 후 0.005로 강화)
    },
  },
});
```

### 2-5. 기본 시나리오 매트릭스 (5건 P0 dispatch)

| # | 시나리오 | 파일 | 검증 |
|---|---------|------|------|
| **S-1** | Dashboard Visual 회귀 | `tests/visual/dashboard.spec.ts` | KIS 키 로그인 후 Dashboard 로드 → 스크린샷 baseline 비교 (위젯·차트·SystemEvent) |
| **S-2** | Orders 시각 회귀 + E2E | `tests/visual/orders.spec.ts` + `tests/e2e/order-flow.spec.ts` | Orders 페이지 Visual + 모의 주문 발사 → 잔고 갱신 STOMP 수신 → UI 반영 evidence |
| **S-3** | Positions 시각 회귀 | `tests/visual/positions.spec.ts` | Positions 페이지 Visual (잔고·수익률·평균단가 표시) — 사용자 dogfooding 결함 hotfix #643 evidence 정합 |
| **S-4** | Settings 시각 회귀 + KIS 키 흐름 | `tests/visual/settings.spec.ts` | Settings 페이지 Visual + KIS 키 입력 → DB 암호화 저장 → 토큰 발급 evidence |
| **S-5** | KillSwitch 토글 E2E | `tests/e2e/kill-switch.spec.ts` | KillSwitch 토글 → backend SystemEvent 발사 → UI alert 반영 evidence |

---

## §3. 트리거 매트릭스 — 자동 dispatch 룰

### 3-1. on-demand 트리거 (사용자 명시)

사용자 명시 호출 시 dispatch:
- "qa 검증" · "Visual 회귀 검사" · "시나리오 재현"
- `/qa-visual` slash command (별도 발행 — backend-core dispatch P1)

### 3-2. 자동 dispatch 트리거 (orchestrator 책임)

orchestrator가 PR diff 분석 후 자동 dispatch:

| trigger 조건 | qa 시나리오 | 사유 |
|--------------|------------|------|
| `frontend/src/pages/Dashboard*` 변경 | S-1 | 메인 화면 회귀 가드 |
| `frontend/src/pages/Orders*` 변경 | S-2 (E2E 포함) | 자본 path 직결 화면 |
| `frontend/src/pages/Positions*` 변경 | S-3 | 잔고 표시 회귀 가드 (#643 evidence) |
| `frontend/src/pages/Settings*` 변경 | S-4 | KIS 키 입력 흐름 회귀 가드 |
| `frontend/src/components/chart/*` 변경 | S-1 + LWC v4 차트 시각 회귀 | chart-curator 해고 후 frontend 단일 owner — 회귀 가드 의무 |
| `backend` OPS 변경 (logback·Actuator) | S-1 ~ S-4 전체 | 운영 인프라 변경 frontend 영향 회귀 가드 |
| `consumer/.../ws/*` 변경 | S-2 (E2E STOMP flow) | WS 변경 사용자 UX 영향 |
| `api/.../OrderController*` 변경 | S-2 (E2E) + S-5 | 주문 path 자본 흐름 |

### 3-3. 정기 트리거 (주간 SLA 검증)

매주 월요일 09:00 KST (장 시작 전) qa 자동 dispatch:
- S-1 ~ S-5 전체 시나리오 실행 + evidence 수집
- 결과: `_workspace/qa-reports/YYYY-Www.html` (주간 보고서)
- 회귀 발견 시 SystemEvent P1 자동 발사 + Slack alert (ADR 0044 reuse)

### 3-4. incident response 트리거 (dogfooding 결함 자동 재현)

사용자 dogfooding 결함 보고 시:
- qa가 결함 시나리오를 Playwright spec으로 자동 변환 (시도 단계 + 스크린샷 + DOM evidence)
- `tests/e2e/incidents/INC-<id>-<slug>.spec.ts` 발행
- root cause 영역 sub-agent (backend-core·frontend·market-data·strategy-engine)에게 시나리오 + evidence 전달

---

## §4. 셋업 가이드 (qa 격상 P0 dispatch 1차 작업)

### 4-1. 셋업 절차 (backend-core + qa 합작 — 격상 P0)

| 순서 | 작업 | actor | 산출물 |
|------|------|-------|--------|
| 1 | `frontend/package.json` 의존 추가 | backend-core | @playwright/test + playwright |
| 2 | `frontend/playwright.config.ts` 발행 | qa | 본 spec §2-4 본문 |
| 3 | `frontend/tests/` 디렉토리 구조 발행 + fixtures | qa | 본 spec §2-3 본문 |
| 4 | S-1 ~ S-5 시나리오 발행 (5 spec file) | qa | 본 spec §2-5 본문 |
| 5 | baseline 스크린샷 생성 + git tracked | qa | `tests/visual/snapshots/*.png` |
| 6 | `.gitignore` 추가 (`playwright-report/`, `test-results/`) | backend-core | gitignore 갱신 |
| 7 | CI 통합 검토 — pre-push hook OR npm script 단독 | architect | 사용자 메모리 `feedback_no_github_actions` 정합 |
| 8 | `package.json` script 추가 (`pnpm qa:visual`, `pnpm qa:e2e`) | backend-core | npm script |
| 9 | docs/operations runbook 추가 — qa Visual 실행 절차 | backend-core | `docs/operations/runbook/qa-visual.md` |
| 10 | first dispatch 사이클 — S-1 ~ S-5 실행 + evidence | qa | 첫 주간 보고서 |

### 4-2. 운영 단계 진입 후 시나리오 확장 (P1 backlog)

- S-6 ~ S-10 시나리오: backtest E2E · 시그널 자동 발사 · 매매일지 표시 · AI 분석 (W4 가동 시) · MCP 노출 검증
- 멀티 브라우저 확장 (Firefox · WebKit) — 운영 부담 ↑ 평가 후
- 모바일 viewport 추가 (Pixel 5 외)
- accessibility 검증 (axe-playwright 통합)

---

## §5. 검증 — 사용자 메모리 정합 매트릭스

| 사용자 메모리 | 본 spec 정합 |
|--------------|------------|
| `feedback_qa_on_demand` | qa는 on-demand default 보존 + frontend 핵심 화면 변경 시 자동 dispatch + 정기 주간 트리거 (게이트 차단 X — evidence 수집) |
| `feedback_no_github_actions` | Playwright 로컬 실행 + pre-push hook 통합 검토 + 외부 SaaS 0 (Percy·Chromatic 거부) |
| `feedback_no_mock_data` | qa fixture는 KIS 키 mock layer 사용 (test 전용 — 실 KIS 호출 0) + 사용자 흐름 mock 0 LOCK 보존 |
| `feedback_v3_skepticism_no_regression` | qa 격상 fitness 평가 의무 — past 7w 1건 dispatch root cause 분석 + 격상 후 dispatch 빈도 회복 측정 |
| `project_system_vision` | qa "품질 헌터" 페르소나는 사용자(최고 관리자) 보호 lens — 사용자가 보기 전에 결함 1차 차단 |
| `feedback_orchestrator_active_parallel` | qa 자동 dispatch 트리거 매트릭스 (§3-2) — orchestrator 적극 병렬 디스패치 정합 |
| `feedback_spec_plan_scope` | qa 격상 = 신규 페르소나 + 신규 도구 매트릭스 + 신규 §10 헌법 룰 → spec 발행 의무 충족 |

### 5-1. 권위 ADR 정합

- ADR 0080 (전체 opus 통일) — qa 격상 후에도 opus 4.7 LOCK 유지
- ADR 0094 §2-2-B (qa 격상 결정) — 본 spec이 design 본문
- 헌법 §10-2 (변경 관리) — frontend 핵심 화면 변경 PR qa Visual 자동 dispatch 의무
- 헌법 §10-4 (DR drill) — qa Visual E2E 시나리오 자동 재현 의무
- 헌법 §10-5 (운영 ADR 양식) — 관측 매트릭스 슬롯에 Visual 회귀 시나리오 명시 의무 (frontend 영향 시)

---

## §6. 변경 이력

| 날짜 | 변경 | 작성자 |
|------|------|--------|
| 2026-05-21 | spec 신규 발행 — qa Visual 격상 design (ADR 0094 amend §2-4 본문 + Playwright 도구 매트릭스 + "품질 헌터" 페르소나 + 시나리오 5건 + 트리거 매트릭스 + 셋업 가이드 10단계) | architect (사용자 위임 결재) |
