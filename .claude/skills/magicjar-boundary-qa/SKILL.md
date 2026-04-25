---
name: magicjar-boundary-qa
description: magicJar의 모듈 간 경계면(API↔DB↔UI↔AI 출력) 정합성을 교차 비교로 검증한다. **MVP 단계는 On-Demand**(ADR 0016) — 7개 트리거 조건(돈 흐름·도메인 모델 깨는 변경·새 UI 흐름·보안 경계·회귀 영역·Phase 통합·명시 요청) 중 하나일 때만 호출. "경계면 점검", "QA 돌려줘", "통합 체크", "정합성 확인", "이건 QA 필요"도 포함.
---

# magicjar-boundary-qa — 경계면 교차 검증 (On-Demand)

## 호출 정책 (ADR 0016)

**MVP 단계엔 모든 PR에 QA 강제하지 않는다.** 다음 트리거 중 하나라도 해당할 때만 호출:

1. **돈 흐름 변경** — `OrderSender`, 실계좌 모드, 안전 게이트
2. **공용 도메인 모델 깨는 변경** — `core/domain/*` 다수 모듈 파급
3. **새 사용자 흐름 UI** — 새 화면/워크플로우
4. **보안 경계** — 인증·키·시크릿 처리
5. **이전 버그 회귀 영역**
6. **Phase 통합·실배포 직전**
7. **사용자 또는 오케스트레이터의 명시 판단**

이외 PR은 메인 세션 셀프 리뷰 + pre-commit/pre-push 자동 검사로 충분.

## 핵심 가정

**존재 확인은 QA가 아니다.** "필드가 있다"가 아니라 "이름·타입·의미·단위가 양쪽에서 일치한다"를 확인한다. 경계 버그는 각 모듈의 단위 테스트가 모두 초록불인데도 통합 시점에 터진다.

## 경계 매트릭스 (MVP)

| 경계 | 쪽 A | 쪽 B | 검증 항목 |
|------|------|------|----------|
| KIS 응답 ↔ 도메인 모델 | `market-data/sample-data/*.json` | `core/domain/Candle.java` | 필드명·타입·단위(틱/원)·타임존 |
| 캔들 ↔ 지표 | `market-data`의 Candle 스트림 | `strategy/Indicators.java` | 시간순·결측·lookahead 금지 |
| 조건식 DSL ↔ AI 출력 | `strategy/dsl-spec.md` | `ai/output-schemas/ConditionDsl.json` | 함수명·연산자 허용 목록·버전 |
| 백테스트 JSON ↔ 차트 | `strategy/backtest-report-schema.json` | `frontend/chart.js` 파싱 | 필드 경로·배열/객체·nullable |
| 감성 분석 ↔ 전략 힌트 | `ai/SentimentAnalysis.tradingHint` | `strategy`가 소비하는 enum | 값 집합 일치 |
| REST/WS ↔ UI | `web`의 Controller | `frontend` HTMX/JS 호출부 | 경로·HTTP 메서드·스키마 |

## 실행 방법

1. **정적 비교.**
   - `Grep`으로 양쪽 파일에서 필드명 추출
   - 단위 차이(예: 가격 원 vs 1/100원) 수동 확인
   - `_workspace/qa/boundary-matrix.md`의 체크박스 업데이트

2. **동적 스모크.**
   - `./gradlew :app:bootRun`(프로파일 `mock`) 기동
   - 시나리오: 종목 조회 → 일봉 다운로드 → 백테스트 실행 → AI 설명 → 대시보드 렌더링
   - 각 단계의 HTTP 로그·DB 로우·UI 렌더 결과를 `_workspace/qa/smoke-{YYYYMMDD}.md`에 저장

3. **스크린샷 캡처 (ADR 0011 게이트).**
   - 경로: `_workspace/qa/screenshots/{pr-id-or-branch-slug}/`
   - 파일명: `NN-{screen}-{state}.png` (예: `01-home-happy.png`, `02-journal-empty.png`)
   - 최소 커버리지: happy + empty. 선택: error, loading, mobile(-mobile 접미어)
   - 해상도 1440×900 기본 (Chromium `--window-size=1440,900`)
   - 도구 우선순위: Playwright(Kotlin/JVM) > Chrome headless(`--headless=new --screenshot=...`) > 수동 캡처
   - 디렉토리마다 `INDEX.md`에 표(파일명·화면·상태·캡처 시각·메모) 기록

4. **보고서(`_workspace/qa/report-{timestamp}.md`) 생성 — 표준 양식.**
   ```markdown
   # QA Report — {brief 대상}

   - Status: APPROVED | CHANGES_REQUESTED | BLOCKED
   - QA at: <ISO-8601 KST>
   - Screenshots: _workspace/qa/screenshots/{slug}/ (N장)

   ## Scenario Coverage
   - [x] ...

   ## Boundary Checks
   - [x] ...

   ## Findings
   (없으면 "없음")

   ## Verdict
   ...
   ```

5. **이슈 기록.**
   - 발견한 모든 경계 불일치를 `_workspace/qa/issues.md`에:
     ```
     ## ISSUE-003: SentimentAnalysis.score 단위 불일치
     - 관측: ai-engineer는 -1.0~1.0, strategy-engine는 0~100으로 소비
     - 영향: 모든 감성 기반 매매 신호 오작동
     - 담당 제안: ai-engineer + strategy-engine 협의
     - 상태: OPEN
     ```

6. **PR 승인.**
   - 위 1~5 모두 충족 + `Status: APPROVED` 보고서 존재 → `gh pr review {pr-id} --approve --body "QA 통과: _workspace/qa/report-{ts}.md"`
   - 하나라도 실패 → `gh pr review {pr-id} --request-changes --body "..."` + 원 에이전트에 SendMessage.

## 2단 머지 흐름 적용 (ADR 0012)

- 주 검수 대상은 **base=`qa` PR**. 여기서 스크린샷·경계면·보고서 APPROVED 확인.
- `base=main`인 `chore/docs/hotfix` PR은 경량 체크만 — 빌드·린트·문서 정합성 확인 후 `gh pr review --approve`.
- **`qa → main` 통합 PR**이 생성되면 추가로:
  1. `gh pr checkout {pr-id}` 후 `./gradlew check`
  2. 통합 스모크 1회 실행 (여러 모듈이 함께 돌아가는 end-to-end 시나리오)
  3. PR 본문의 "변경 INDEX"에 누락된 PR/스크린샷 없는지 대조
  4. 추가 보고서 `_workspace/qa/qa-to-main-{date}.md` 생성
  5. APPROVED면 `gh pr review --approve`
- **`qa` 브랜치에 직접 push·커밋 금지 감시.** `gh api repos/sp-daewoon/magicJar/commits?sha=qa` 결과에서 머지 커밋 아닌 직접 커밋이 관찰되면 즉시 이슈로 기록.

## Incremental QA 트리거

오케스트레이터는 다음 시점에 QA를 호출한다:
- 에이전트가 `_workspace/{module}/` 에 스키마/샘플 파일을 갱신한 직후
- PR 생성 전(최종 합의 직전)
- Phase 전환 직전(예: Phase B → Phase C)

## 좋은 이슈 vs 나쁜 이슈

- 좋은 이슈: 경계 **양쪽 파일 경로·라인**을 인용, 재현 방법 1~3줄, 원인 가설, 담당 제안
- 나쁜 이슈: "테스트 실패함", "에러 남", 한 파일만 언급

## 하지 말 것

- **코드 직접 수정 금지.** 버그를 찾아도 QA는 리포트만. 수정은 원 에이전트가.
- **Happy Path만 보기 금지.** 비정상 입력(빈 캔들, 휴장일, 네트워크 끊김, 토큰 만료)도 최소 1건씩 시나리오에 포함.
- **초록불 의존 금지.** 테스트 통과만 보지 말고 실제 데이터 흐름을 따라간다.

## 재실행/업데이트 지침

- `issues.md`는 누적 문서. 이미 있는 이슈는 **상태만 업데이트**(open → fixed / wontfix), 중복 추가 금지.
- 경계 매트릭스는 새 모듈 추가 시 행 추가.
- Phase 완료 리포트는 파일명에 날짜 포함하여 보존(`report-20260425-1830.md`).
