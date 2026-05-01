# ADR 0011: QA 스크린샷 게이트

- 상태: Accepted (개정 by ADR 0016, 0022, 0023, 0026, **0011 자체 2026-05-01 / 2026-05-02 개정**)
- 작성일: 2026-04-25 (개정: 2026-04-27, 2026-05-01, 2026-05-02)
- 결정자: architect (협의: qa, frontend, 사용자 지시)

## 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-04-25 | 최초 채택 |
| 2026-04-25 | ADR 0016(QA On-Demand) 도입으로 "모든 PR 강제"는 7 트리거로 한정 |
| 2026-04-27 | **본 개정** — 스크린샷 캡처 경로를 클라이언트(LWC `chart.takeScreenshot()`) + 서버(pyworker Playwright) 2 경로로 명시. 저장소는 MinIO(ADR 0026)로 이전. `_workspace/qa/screenshots/` 는 INDEX.md·메타만 보관, 바이너리는 MinIO `chart-snapshots/...` 키 참조. |
| 2026-05-01 | **본 개정** — 다단계 시나리오 모드 명문화(단일 URL은 1단계 시나리오로 통합), pyworker `POST /screenshot/scenario` endpoint 신설, **MinIO presigned URL을 사용자 노출 정식 경로로 추가**, "캡처만 (Playwright `expect` 자동 검증은 비범위)" 명시. 시나리오 코드 영속화 0(매 호출 새 작성, ISSUE 카드에 인용으로만 보존). spec `docs/superpowers/specs/2026-05-01-qa-defect-loop-design.md` 참조. |
| 2026-05-02 | **ISSUE-008 hotfix** — presigned URL `SignatureDoesNotMatch` 해결. 단일 minio-py 클라이언트 + 호스트 후처리 치환(`_rewrite_host`) 방식 폐기 → **internal(업로드용 `minio:9000`) + public(presign용 `localhost:9000`) 두 클라이언트 분리**. AWS4-HMAC-SHA256 서명에 호스트가 포함되므로, 서명 자체를 외부 endpoint 기준으로 만들어야 사용자 GET 시 일치한다. public 클라이언트는 `presigned_get_object` 만 호출 → 네트워크 도달 가능 여부 무관. 환경변수 변화 0 (`MINIO_PUBLIC_HOST` 의미 그대로, host:port 형식 = endpoint). |

## 맥락

사용자 지시: **QA가 스크린샷으로 완료까지 되어야 머지할 수 있게끔** 처리. 자동 테스트의 초록불은 UI·통합 행동의 진짜 상태를 증언하지 못한다. 스크린샷은 "QA가 진짜로 실행해봤다"의 증거이자, 사용자가 코드를 읽지 않고도 변경의 결과를 눈으로 확인할 수 있는 게이트.

## 결정

**UI를 변경·추가하거나 사용자 흐름에 닿는 모든 PR은 스크린샷 아티팩트를 반드시 포함한다. QA 에이전트가 캡처·검수 완료한 뒤에만 APPROVED.**

### 스크린샷 요구 범위

| PR 유형 | 스크린샷 요구 |
|---------|--------------|
| UI(`web` 모듈) 변경 | **필수** — 영향받는 모든 화면·핵심 상태 (정상/에러/빈상태) |
| REST/WebSocket 스펙 변경 중 UI가 소비하는 것 | **필수** — 프론트 렌더 결과 |
| 전략/AI/데이터 수집 등 내부 모듈 변경이지만 대시보드·/harness 반영 | **필수** — 해당 페이지 |
| 순수 내부 변경(테스트만, 리팩터, 문서) | 면제 |

### 저장 규칙 (2026-04-27 개정 — MinIO 이전)

바이너리 저장: **MinIO `chart-snapshots/qa/{pr-id or branch-slug}/...`** (ADR 0026).

git 저장: 메타데이터만.

```
_workspace/qa/screenshots/{pr-id or branch-slug}/
└── INDEX.md          # 각 스크린샷에 대한 설명 + 캡처 시각 + 화면/상태 + MinIO key + source(CLIENT_LWC|SERVER_BROWSER)
```

(레거시: 초기 MVP 일부 PR은 git에 PNG 직접 두었음 — 점진 이전.)

파일명(MinIO key 마지막 segment): `NN-{screen}-{state}.png` — 순번으로 정렬, 상태 명시(`happy`/`error`/`empty`/`loading`).

### 캡처 방법 (qa 에이전트 책임) — 2 경로 (2026-04-27 개정)

| 경로 | 트리거 | 도구 | 사용처 |
|------|--------|------|--------|
| **클라이언트 (CLIENT_LWC)** | 사용자가 차트에서 "캡처" 버튼 → 프런트 `chart.takeScreenshot()` (LWC 공식 API) → api-app `/journal/{id}/snapshots`로 PUT → MinIO `chart-snapshots/...` | LWC 공식 (ADR 0022) | 매매일지 첨부, 사용자 자가 기록 |
| **서버 (SERVER_BROWSER)** | api-app → pyworker `POST /screenshot` (URL 전달) → Playwright 헤드리스 캡처 → MinIO `chart-snapshots/...` | pyworker Playwright (ADR 0023) | LWC 공식 캡처 실패 시 폴백, QA 자동 시나리오, 외부 페이지 캡처 |

`html-to-image` 등 외부 캡처 라이브러리 도입 금지 (ADR 0022 §"커스텀 지양 원칙").

`ChartSnapshot.source` 필드(`CLIENT_LWC` / `SERVER_BROWSER`)로 경로 추적. QA 보고서 INDEX.md에도 source 표기.

해상도: 1440×900 기본 (데스크톱). 동적 요소(시각·실시간 시세)는 마스킹 또는 고정 픽스처.

### 다단계 시나리오 (2026-05-01 추가)

기존 단일 URL 1장 캡처(`POST /screenshot`)에 더해, **사용자 흐름 중간의 인터랙션 디펙**을 검증하는 다단계 시나리오 모드를 신설한다.

| 항목 | 결정 |
|---|---|
| Endpoint | `POST /screenshot/scenario` |
| 입력 | `{specCode, issueId, attempt: "before"\|"after", retry, viewport}` — **Playwright Python async 코드** inline 문자열로 (in-process 실행, Node.js 의존 0) |
| 단계별 캡처 | `await page.screenshot(path="NN-step-name.png")` 패턴 — pyworker가 monkey-patch로 자동 추적 |
| 검증 | **캡처만**. Playwright runtime error(timeout/selector not found 등)만 자동 fail 표기. 시각 디펙 판정은 사용자 시각 검토 |
| 결과 노출 | MinIO presigned URL (TTL 24h). pyworker는 internal(`S3_ENDPOINT`)·public(`MINIO_PUBLIC_HOST`) 두 minio-py 클라이언트를 운영해, 서명 자체를 외부 endpoint 기준으로 만든다 (2026-05-02 ISSUE-008 hotfix) |
| 영속 | MinIO 키 `magicjar-screenshots/issues/ISSUE-NNN/{before\|after}/{ts}/NN-step.png`. 시나리오 코드는 git `_workspace/qa/issues/ISSUE-NNN/scenario-{before\|after}.py`로 인용 보존 |

상세는 spec `docs/superpowers/specs/2026-05-01-qa-defect-loop-design.md` 참조.

### QA 승인 프로세스

1. QA 에이전트가 PR 브랜치 체크아웃 → 앱 실행 → 스모크 시나리오 + 스크린샷 캡처
2. 경계면 매트릭스 재검증 (`magicjar-boundary-qa` 스킬)
3. `_workspace/qa/report-{timestamp}.md` 생성 — 아래 양식:
   ```markdown
   # QA Report — PR #{pr-id or branch}

   - Status: APPROVED | CHANGES_REQUESTED | BLOCKED
   - QA at: 2026-04-25T15:30+09:00
   - Screenshots: _workspace/qa/screenshots/{slug}/ (N장)

   ## Scenario Coverage
   - [x] 홈 대시보드 렌더
   - [x] 매매일지 목록/상세
   - [x] 하네스 운영 페이지
   - [ ] ...

   ## Boundary Checks
   - [x] KIS 응답 ↔ Candle 도메인 모델
   - [x] 매매일지 JSON ↔ /journal 화면 파서

   ## Findings
   (없으면 "없음")

   ## Verdict
   APPROVED / 이유 ...
   ```
4. `status: APPROVED` + 최소 요구 스크린샷 수 충족 시에만 PR 머지 가능.

### 머지 차단 자동화 (지금 ~ Phase B)

- **지금 (CI 없음)**: Husky pre-push가 PR 생성 브랜치에서 `_workspace/qa/report-*.md`의 최신 항목이 APPROVED인지 체크 (main으로 push할 때). 경고만 — 강제는 GitHub branch protection + Actions 도입 후.
- **Phase B 이후 (GitHub Actions)**: PR 워크플로우가 위 양식을 파싱해 상태 체크. 스크린샷 디렉토리 존재 여부 체크.

### 면제(Exempt) 라벨

UI·사용자 흐름과 무관한 PR은 `qa-exempt` 라벨 부여로 스크린샷 요구 면제. 오케스트레이터와 QA 에이전트가 공동 판단.

## 결과

- 긍정: 머지된 코드는 항상 "사람이 눈으로 본" 상태를 보장, 히스토리 탐색 시 시각적 회귀 쉽게 추적
- 부정: 순수 UI 변경 PR의 머지 속도가 다소 느려짐 — 단, QA 에이전트가 병렬 실행이라 체감 비용 제한
- 후속:
  - Playwright 셋업(frontend + qa 협업, 별도 PR)
  - 스크린샷 diff 도구(Percy·Chromatic 등) 도입은 규모 커질 때 재검토
