# magicJar — QA 디펙 순환 루프 설계 (Spec)

- **작성일**: 2026-05-01
- **작성자**: Claude (orchestrator=architect) + 사용자 (sp-daewoon)
- **상태**: Draft → User Review
- **관련 ADR**: 0011 개정 후보, 0016 보강, 0030 신규 후보
- **Phase**: F+ (실 매매 진입 토대 5개 영역 중 1차 — QA 자동화 살리기)
- **트랙**: 5/5 (전체 트랙 중 첫 번째 진행분)

## 0. 개요

QA를 **"사용자 보고 → 시각 증거 → 수정 → 재검증"** 순환 루프로 살린다. ADR 0011·0016·0022·0023·0026이 설계만 되고 1주일간 캡처 0장으로 사실상 죽어있던 상태를 끝낸다. **강제 머지 게이트·카탈로그 운영 부담 없이** 다단계 시나리오 디펙을 시각 증거와 함께 추적한다.

## 1. 배경 및 진단

### 1.1 인프라 vs 운영 갭

| 항목 | 상태 |
|---|---|
| ADR 0011 (스크린샷 게이트) | 설계 완료, 운영 0 |
| ADR 0016 (On-Demand 7 트리거) | 설계 완료, 트리거 발동 시도는 있으나 캡처 단계 미작동 |
| pyworker `POST /screenshot` | 라우트·MinIO·Kafka 인프라 살아있음 (단일 URL 1장 한정) |
| 캡처 PNG 누적 | **0장** (Playwright driver 내부 파일 1건 제외) |
| QA 보고서 | curl·로그 기반 텍스트 보고만 (시각 증거 없음) |

### 1.2 사용자 의도

> "QA는 주(主)가 아니다. 사용자가 화면 보고 안 보인다 하면, 안 보이는 걸 캡처로 확인하고, 요청 받아서 진짜 안 보인다 하고, 다시 개발 범위 검토해서 수정하고, 다시 QA 가서 확인하는 절차."

→ QA = **사용자 보고에 대한 시각 증거 생성기 + 디펙 추적기**. 강제 게이트·매번 발동 자동화는 비범위.

## 2. 핵심 원칙

1. **사용자 명시 호출 시만 발동** — 메인 세션이 자율 판단(=ADR 베이스)으로 시나리오 작성. ADR 충돌·코드 부자연·범위 밖만 사용자 컨펌
2. **다단계 시나리오** — 단일 URL 캡처는 1단계 시나리오로 통합
3. **캡처만, 디펙 판정은 사용자** — Playwright `expect` 자동 검증은 비범위 (다음 사이클)
4. **카탈로그·영속 시나리오 0** — 매 호출마다 architect가 새 `scenario.py` 작성, ISSUE 카드에 코드 블록 인용으로만 보존
5. **시각 증거의 source of truth = MinIO** — git에는 메타·카드만

## 3. 책임 boundary

| 컴포넌트 | 책임 | 비책임 |
|---|---|---|
| **architect** (=오케스트레이터=메인 세션) | 시나리오 `scenario.py` 작성, ADR 해석, 사용자 인터페이스, 컨펌 판단, 디펙 확정 후 수정 dispatch | 직접 캡처 실행 |
| **QA 에이전트** (sub-agent) | 받은 스크립트를 `qa-run.mjs`로 호출, 결과(단계별 PNG·실패 단계·소요 시간) 분석, ISSUE 카드 작성·갱신 | 시나리오 작성, 디펙 확정 판정 |
| **pyworker** | `POST /screenshot/scenario` — Playwright runner, 단계별 캡처, MinIO 업로드, presigned URL 발급 | 시나리오 해석·재작성 |
| **`scripts/qa-run.mjs`** (신규 CLI) | 호출 어댑터 — pyworker 호출 + 응답 파싱 + ISSUE 카드 갱신 + presigned URL 콘솔 출력 | 시나리오 생성 |
| **MinIO** (`magicjar-screenshots`) | 캡처 영속 store | 메타데이터 영속 |
| **`_workspace/qa/issues/ISSUE-NNN/`** | 회고 컨텍스트 git 영속 (카드·시나리오 인용·실행 보고) | 캡처 바이너리 |

## 4. 흐름 (시퀀스)

```
1. 사용자 보고
   "차트 안 보여" / "봉 단위 바꿨는데 안 그려져" / "QA 체크해 /symbol/005930"

2. 메인 세션(=architect)
   - 직전 작업 컨텍스트 + ADR 0011·0016·0022·0023 + 사용자 보고를 바탕으로
     Playwright scenario.py 시나리오 작성
   - ADR 정합·기존 코드와 자연스러우면 → 진행
   - ADR 충돌 또는 부자연 → 한 줄 컨펌 ("이 단계로 돌릴게요. OK?")

3. 메인 세션 → QA 에이전트 dispatch
   입력: ISSUE-NNN(신규 채번) + scenario.py(인라인 코드) + attempt="before"
   QA 에이전트가 sub-agent로 작업

4. QA 에이전트 → scripts/qa-run.mjs 실행
   qa-run.mjs scenario --issue ISSUE-008 --spec /tmp/scenarioscenario.py --attempt before

5. qa-run.mjs → pyworker POST /screenshot/scenario
   요청: {specCode, issueId, attempt, retry, viewport}
   응답: {steps[], failedAtStep, presignedUrls, totalDurationMs}

6. pyworker
   - specCode를 임시 파일에 기록
   - Playwright headless 실행 (단일 브라우저 컨텍스트, 단계 누적)
   - 단계별 PNG → MinIO `magicjar-screenshots/issues/ISSUE-NNN/before/{ts}/NN-step.png`
   - 각 키마다 presigned URL(24h) 발급
   - 임시 파일 폐기

7. qa-run.mjs → ISSUE 디렉토리 생성·갱신
   _workspace/qa/issues/ISSUE-008/
     ├─ card.md          (frontmatter + 증상·재현·라이프사이클)
     ├─ scenario-before.py (인용)
     └─ report-before.md (단계별 결과 + presigned URL 목록)

8. QA 에이전트 → 메인 세션 보고
   "ISSUE-008 INVESTIGATING. 단계 03에서 fail. before URLs: [...]"

9. 메인 세션 → 사용자 채팅
   presigned URL 안내 → 사용자 브라우저 클릭 → 시각 검토

10. 사용자: "진짜 안 보인다" → 디펙 확정
    메인 세션이 수정 작업 dispatch (frontend/backend-core 등)

11. 수정 PR 머지 후
    메인 세션이 변경 반영해 scenario.py 재작성(셀렉터/단계 변경 시) 또는 동일 코드 재사용
    → 재dispatch, attempt="after"

12. after 캡처 → 사용자 검토 → "이제 보임"
    qa-run.mjs close --issue ISSUE-008 --pr 50
    카드 status: FIXED, 인덱스 issues.md 갱신
```

## 5. 데이터·디렉토리 구조

### 5.1 git 영속 (`_workspace/qa/`)

```
_workspace/qa/
├─ issues.md                          인덱스 (요약 카탈로그) — ISSUE-NNN 한 줄씩
├─ issues/
│  ├─ ISSUE-008/
│  │  ├─ card.md                      frontmatter + 증상·재현·라이프사이클·관련 PR
│  │  ├─ scenario-before.py       (인용용, qa-run.mjs가 카드 작성 시 함께 저장)
│  │  ├─ scenario-after.py        (있는 경우)
│  │  ├─ report-before.md              단계 결과 + presigned URL 목록 + MinIO 키
│  │  └─ report-after.md               (있는 경우)
│  └─ ...
└─ report-{ts}.md                     (legacy 보고서, ISSUE-007까지 — 그대로 유지)
```

**legacy 호환**: 기존 `issues.md`의 ISSUE-001~007은 단일 파일 양식 그대로 보존. ISSUE-008부터 디렉토리 양식 적용. 인덱스 `issues.md`는 두 양식을 모두 표기.

### 5.2 MinIO (`magicjar-screenshots`)

```
magicjar-screenshots/
└─ issues/
   └─ ISSUE-008/
      ├─ before/
      │  ├─ 2026-05-01T15-00-12Z/
      │  │  ├─ 01-goto-symbol-page.png
      │  │  ├─ 02-click-interval-d.png
      │  │  └─ 03-after-toggle-FAIL.png
      │  └─ 2026-05-01T15-30-44Z/         (retry)
      │     └─ ...
      └─ after/
         └─ 2026-05-01T16-15-08Z/
            ├─ 01-goto-symbol-page.png
            ├─ 02-click-interval-d.png
            └─ 03-after-toggle-PASS.png
```

키 패턴: `issues/{ISSUE-NNN}/{before|after}/{ISO-ts-with-dashes}/{NN}-{step-slug}.png`

### 5.3 ISSUE 카드 양식 (`card.md`)

```markdown
---
id: ISSUE-008
status: INVESTIGATING
reportedAt: 2026-05-01T15:00:00+09:00
reportedBy: user
symptom: 종목 상세 봉 단위 'D' 클릭 시 차트 안 그려짐
target:
  url: /symbol/005930
  scenarioMode: scenario
relatedAdr: [0011, 0022]
relatedPr: null
fixedAt: null
---

## 증상
사용자가 종목 상세 화면에서 봉 단위를 'D'로 토글하면 차트 영역이 비어있음.

## 재현 단계
1. /symbol/005930 진입
2. 봉 단위 토글 'D' 클릭
3. 차트 영역 확인 → 캔들 미렌더

## before 캡처
- run: 2026-05-01T15-00-12Z (실패 단계: 3)
- presigned URLs: report-before.md 참조

## after 캡처
(수정 후 갱신)

## 시나리오 코드 (인용)
\`\`\`python
# scenario-before.py 인용 — 위 파일과 동일
async def scenario(page):
    await page.goto("http://frontend:80/symbol/005930")
    await page.screenshot(path="01-goto-symbol-page.png")
    await page.click("[data-testid=interval-D]")
    await page.screenshot(path="02-click-interval-d.png")
    await page.wait_for_timeout(500)
    await page.screenshot(path="03-after-toggle.png")
\`\`\`
```

### 5.4 라이프사이클

```
OPEN → INVESTIGATING → FIXED
                    ↘ WONTFIX
                    ↘ DEFERRED
```

- **OPEN**: 사용자 보고 직후, 카드 채번 직후
- **INVESTIGATING**: architect 시나리오 작성 + before 캡처 첨부 + 디펙 확정 (사용자 시각 검토 OK)
- **FIXED**: 수정 PR 머지 + after 캡처로 검증 완료
- **WONTFIX**: 디펙은 확정됐으나 수정 보류
- **DEFERRED**: 다음 사이클로 미룸

## 6. pyworker 변경

### 6.1 신규 endpoint

```
POST /screenshot/scenario
```

**요청**:
```json
{
  "specCode": "<Playwright Python async 코드 inline>",
  "issueId": "ISSUE-008",
  "attempt": "before",
  "retry": 1,
  "viewport": {"width": 1440, "height": 900},
  "requestedBy": "qa-agent"
}
```

**응답**:
```json
{
  "issueId": "ISSUE-008",
  "attempt": "before",
  "retry": 1,
  "runTimestamp": "2026-05-01T15:00:12Z",
  "steps": [
    {
      "index": 0,
      "name": "01-goto-symbol-page",
      "status": "pass",
      "durationMs": 845,
      "storageKey": "issues/ISSUE-008/before/2026-05-01T15-00-12Z/01-goto-symbol-page.png",
      "presignedUrl": "https://minio.../signed?...",
      "error": null
    },
    {
      "index": 2,
      "name": "03-after-toggle",
      "status": "fail",
      "durationMs": 612,
      "storageKey": "issues/ISSUE-008/before/2026-05-01T15-00-12Z/03-after-toggle-FAIL.png",
      "presignedUrl": "https://...",
      "error": "selector .candle-series not visible"
    }
  ],
  "failedAtStep": 2,
  "totalDurationMs": 2103
}
```

**구현**:
- `app/routers/scenario.py` 신규 (또는 `screenshot.py` 확장)
- `specCode` → 임시 파일 (`/tmp/scenario-{uuid}scenario.py`)
- Playwright headless 단일 브라우저 컨텍스트 실행
- `page.screenshot({path})` 호출 시 자동 단계 추적 (파일명 = 단계명)
- Playwright runtime error(timeout, selector not found, navigation error 등) 발생 시 해당 단계를 `status: fail`로 표기. 그 외(시각 디펙) 판정은 사용자 시각 검토에 위임
- 단계별 PNG → MinIO 업로드 + presigned URL 발급 (24h TTL)
- 임시 파일 폐기 (always)
- `ScreenshotCapturedEvent`는 시나리오 단위로 1건만 발행 (단계별 이벤트는 비범위 — 카드에 묻힘)

### 6.2 기존 endpoint 보존

`POST /screenshot` (단일 URL 1장)은 그대로 유지. 호환성 + 단순 캡처 케이스용. 내부적으로 `/screenshot/scenario`의 1단계 시나리오로 위임도 가능하지만, 스코프 밖.

## 7. `scripts/qa-run.mjs` (신규 CLI)

`scripts/task.mjs` 패턴 재사용. 명령 어휘:

| 명령 | 효과 |
|---|---|
| `qa-run.mjs new --symptom "..." --target /symbol/005930` | ISSUE-NNN 신규 채번 + 디렉토리 생성 + card.md 초안 |
| `qa-run.mjs scenario --issue ISSUE-008 --spec ./tmpscenario.py --attempt before` | pyworker `/screenshot/scenario` 호출 + 응답을 `report-before.md`로 저장 + 카드 status `INVESTIGATING` 갱신 + presigned URL 콘솔 출력 |
| `qa-run.mjs scenario --issue ISSUE-008 --spec ./tmpscenario.py --attempt after` | 동일 (after 양식) |
| `qa-run.mjs close --issue ISSUE-008 --pr 50` | status `FIXED` + `relatedPr` 갱신 + 인덱스 `issues.md` 정렬 |
| `qa-run.mjs wontfix --issue ISSUE-008 --reason "..."` | status `WONTFIX` |
| `qa-run.mjs list` | 활성 이슈 목록 (OPEN/INVESTIGATING) |

CLI는 frontmatter 파싱·갱신을 task.mjs와 동일 헬퍼 함수로 구현 (코드 공유).

### 7.1 호스트·네트워크 정합

| 호출 주체 | 호출 대상 | 호스트 |
|---|---|---|
| `qa-run.mjs` (host node) | pyworker `/screenshot/scenario` | `http://localhost:8000` (docker 포트 매핑) |
| pyworker Playwright | frontend / api | `http://frontend:80` / `http://api:8080` (docker network 내부) |
| 사용자 브라우저 | MinIO presigned URL | `http://localhost:9000/...` (host 포트 매핑) — pyworker가 발급 시 사용자 접근 가능한 호스트로 만들어야 함 |

→ pyworker 환경변수에 `MINIO_PUBLIC_HOST` 추가 (기본값 `localhost:9000`). presigned URL 발급 시 그 호스트 사용.

## 8. 시나리오 작성 규약 (architect)

### 8.1 입력 컨텍스트

architect가 `scenario.py` 작성 시 참고하는 정보:

1. 사용자 보고 (자연어)
2. 직전 작업 컨텍스트 (메인 세션이 보유)
3. 관련 ADR (특히 0011·0022·0023)
4. 라우트 목록 (`frontend/src/router.tsx` 자동 추출 가능)
5. DOM 셀렉터 컨벤션 (`data-testid` 관행 — frontend 내부 합의)

### 8.2 출력 규약

- **Python async** — `async def scenario(page):` 단일 함수 패턴 (in-process `playwright.async_api`, Node.js 의존 0)
- 한 함수 = 한 시나리오 (test runner 사용 안 함)
- `await page.screenshot(path="NN-step-name.png")` 패턴 (NN: 두 자리, step-slug: kebab) — pyworker가 `page.screenshot`을 monkey-patch로 감싸 단계 자동 추적
- 진입 URL은 docker network 내부 호스트네임(`http://frontend:80/...`) — pyworker가 같은 네트워크 안에 있음
- 외부 자원 의존(API 키 등) 미사용

### 8.3 자율 vs 컨펌

| 케이스 | 행동 |
|---|---|
| ADR 정합 + 기존 코드 패턴과 자연 | 자율 진행 |
| ADR 모호 또는 신규 결정 필요 | 사용자 한 줄 컨펌 |
| 외부 자원·실 매매 도메인 침범 | 사용자 컨펌 + 별도 ADR 검토 |
| 시나리오가 비결정적/플레이키 위험 | 사용자 안내 + 1회 재시도 후 보고 |

### 8.4 영속화 = 0

매 호출마다 새 작성. ISSUE 카드의 코드 블록 인용으로만 보존. `scenarios/` 디렉토리 운영 안 함.

## 9. ADR 영향

| ADR | 변경 |
|---|---|
| **ADR 0011 (스크린샷 게이트)** | 개정 — 다단계 시나리오 모드 명문화, presigned URL을 사용자 노출 정식 경로로 추가, "캡처만 (expect 비범위)" 명시 |
| **ADR 0016 (QA On-Demand 7 트리거)** | 보강 — 트리거 발동 시 시나리오 모드 사용 명시, scripts/qa-run.mjs 호출 어댑터 명시 |
| **ADR 0030 (신규 후보)** | 오케스트레이터 = architect 메타 규약 — ADR 베이스 자율 + 충돌·부자연·외부자원 시 사용자 컨펌. 5번뿐 아니라 1~4 전체 트랙에 적용 |

## 10. 보안·접근

- presigned URL TTL 24h (사용자 검토 후 자동 만료)
- pyworker는 외부 입력이 아니라 내부 architect 권한자 작성 코드만 실행 (ADR 0017 author 화이트리스트)
- 시나리오 코드의 외부 네트워크 호출은 docker network 내부로 한정 (frontend·api 등). 외부 도메인 호출은 컨펌 필요
- pyworker sandbox 강화는 다음 사이클 (Phase G+) — 현 단계는 신뢰 영역 가정

## 11. 비범위 (다음 사이클로 미룸)

- Playwright `expect` 기반 자동 검증
- 픽셀 diff (Percy/Chromatic 또는 자체)
- 시나리오 카탈로그 영속 누적
- 대시보드 ISSUE 카드 통합 (대시보드 페이지에 OPEN/INVESTIGATING 카드 미리보기)
- 머지 게이트 강제 (Husky pre-push에서 ISSUE 미해소 차단)
- 자동 dispatch (메인 세션 키워드 감지 자율 발동)
- pyworker 외부 코드 sandbox 격리

## 12. 마이그레이션

- ISSUE-001~007: 기존 `issues.md` 단일 파일 양식 보존, 상태만 갱신
- ISSUE-008부터: 디렉토리 양식 적용
- pyworker 기존 `/screenshot`: 그대로 유지 (단일 캡처 호환)
- `_workspace/qa/screenshots/` 디렉토리: 본 spec 채택 시 미사용 (ADR 0011 개정으로 명시 폐기)

## 13. 테스트 전략

- **pyworker `/screenshot/scenario` 단위 테스트**: mock Playwright + mock MinIO. 단계 추적·실패 단계 표기·presigned URL 발급 흐름.
- **`qa-run.mjs` 통합 테스트**: 임시 디렉토리에 ISSUE-NNN 카드·report 생성 → frontmatter 갱신 검증.
- **end-to-end dogfooding**: ISSUE-008 첫 시나리오 — 종목 상세 차트 표시 검증. spec 채택 직후 즉시 실행.

## 14. 운영 플레이북

```
사용자: "QA 체크해 — 종목 상세 봉 단위 바꿨는데 차트 안 그려져"
↓
메인 세션(=architect):
  - ADR 0011·0022 참조 (시나리오 모드, LWC 공식 API)
  - 직전 작업 컨텍스트(F R2 차트 통합) 인지
  - scenario.py 작성:
      goto /symbol/005930 → screenshot 01
      click [data-testid=interval-D] → screenshot 02
      wait 500 → screenshot 03 (예상: 캔들 표시)
  - ADR 정합·자연 → 자율 진행
↓
메인 세션 → QA 에이전트 dispatch
  ISSUE-008 신규 채번 + scenario.py 인라인 + attempt=before
↓
QA 에이전트 → qa-run.mjs scenario --issue ISSUE-008 --attempt before
↓
qa-run.mjs → pyworker POST /screenshot/scenario
↓
pyworker: Playwright 실행 → 3단계 캡처 → MinIO 업로드 → presigned URL 3개 응답
↓
qa-run.mjs: ISSUE-008 디렉토리 생성 + card.md(INVESTIGATING) + report-before.md
↓
QA 에이전트 → 메인 세션 보고
  "ISSUE-008 INVESTIGATING. 단계 3의 .candle-series 찾을 수 없음. URLs: [...]"
↓
메인 세션 → 사용자
  "ISSUE-008 등록. before 캡처 3장 — 단계 3에서 차트 미렌더 확인.
   01: <URL1>  02: <URL2>  03(FAIL): <URL3>"
↓
사용자 시각 검토 → "맞다, 진짜 안 보임"
↓
메인 세션 → frontend 에이전트 dispatch (수정)
↓
PR #N 머지
↓
메인 세션 → QA 재dispatch (동일 시나리오, attempt=after)
↓
after 캡처 → 사용자 검토 → "이제 보임"
↓
qa-run.mjs close --issue ISSUE-008 --pr N
  status FIXED, 인덱스 갱신, 사이클 종료
```

## 15. 위험 + 완화

| 위험 | 완화 |
|---|---|
| architect 시나리오 작성 부담 | ADR 명확하면 빠름. 모호 시 사용자 컨펌으로 분산. 매 호출 영속 0이라 누적 부담 없음 |
| pyworker 외부 코드 실행 보안 | architect 권한자 작성 한정 (ADR 0017). docker network 내부 호출 한정. sandbox 강화는 다음 사이클 |
| 사용자 시각 검토 부담 (단계 N장) | architect가 핵심 단계만 캡처하도록 가이드. 평균 3~5장 권고 |
| presigned URL 만료 후 회고 | MinIO 키는 영속. 회고 시 신규 URL 발급 명령 추가 (선택, 다음 사이클) |
| QA 에이전트 sub-agent의 docker 접근 | sub-agent에서 막히면 메인 세션 폴백(qa-run.mjs를 메인 세션이 직접 호출) — CLI 동일하므로 자연 해소 |

## 16. 성공 지표

- ISSUE-008 첫 dogfooding 1주 내 완주 (보고 → before → 수정 → after → close)
- 1개월 운영 시 누적 캡처 ≥ 30장
- 사용자 보고 시 "QA 돌릴까요?" 흐름 80% 이상에서 캡처 도달 (오케스트레이터 자율 작성 비율)
- ADR 0011·0016이 죽은 문서가 아니라 일상 작업 참조 ADR로 살아남

## 17. 다음 단계

- 본 spec 사용자 review
- writing-plans 스킬로 implementation plan 작성 (pyworker 변경·CLI 신규·ADR 개정·dogfooding ISSUE-008)
- plan 채택 후 phase별 구현
- 5번 완료 후 1차 회고 → 다음 트랙(2차: KIS 주문 API 어댑터) 진입
