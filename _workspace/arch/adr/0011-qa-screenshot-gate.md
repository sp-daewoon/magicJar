# ADR 0011: QA 스크린샷 게이트

- 상태: Accepted
- 작성일: 2026-04-25
- 결정자: architect (협의: qa, frontend, 사용자 지시)

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

### 저장 규칙

```
_workspace/qa/screenshots/{pr-id or branch-slug}/
├── 01-home.png
├── 02-journal-list.png
├── 03-journal-detail-happy.png
├── 04-journal-detail-empty.png
├── 05-harness-dashboard.png
├── INDEX.md          # 각 스크린샷에 대한 설명 + 캡처 시각 + 어떤 화면 어떤 상태
```

파일명: `NN-{screen}-{state}.png` — 순번으로 정렬, 상태 명시(`happy`/`error`/`empty`/`loading`).

### 캡처 방법 (qa 에이전트 책임)

1. **자동 캡처 선호**: Playwright(Java/Kotlin)·Selenium으로 시나리오 주도 + 정지 시점 캡처.
2. **수동 캡처 허용**: 초기 MVP에서 Playwright 셋업 전에는 QA 에이전트가 앱 실행 → Chrome `--headless=new --screenshot` 사용.
3. 해상도: 1440×900 기본 (데스크톱 기준). 모바일 반응형 검증 필요 시 추가.
4. 동적 요소(시각, 실시간 시세)는 마스킹 또는 고정값(테스트 픽스처) 사용.

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
