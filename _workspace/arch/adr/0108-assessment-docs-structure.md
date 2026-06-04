# ADR 0108: 평가(assessment) 문서 docs/assessments 관리 구조

- 상태: Accepted
- 작성일: 2026-06-05
- 결정자: architect (사용자 최고 관리자 직접 지시 — 2026-06-05 "평가 또는 가이드 문서들을 docs 밑에서 관리하도록 하고 싶어, 평가는 주기적으로 요청할 거라서 그 부분에 대한 구조를 잡아서")

## 맥락(Context)

2026-06-04 자동매매 준비도 평가(`autotrading-readiness`)가 `_workspace/reports/` 밑에 HTML로 발행되었다. 사용자는 이런 **점수화·판정형 평가 문서를 주기적으로 요청**할 계획이며, 시계열 비교(준비도 71% → 다음 평가 시 변동)가 핵심 가치다. 그러나 `_workspace/reports/`는 에이전트의 일회성 조사·audit 보고서(md)가 섞인 작업 공간이라, 사용자 대면 주기 평가가 묻히고 이력 비교 구조가 없다. 평가 문서의 단일 위치·네이밍·템플릿·인덱스 규약이 필요하다.

## 검토한 대안(Options)

| 대안 | 장점 | 단점 |
|------|------|------|
| A. `_workspace/reports/` 유지 | 이동 0 | 일회성 조사와 혼재, 시계열 규약 없음, 사용자 대면 위치로 부적합 |
| B. `docs/assessments/` 신설 (채택) | docs = 사용자 대면 권위 위치(architecture·operations와 동급), 시계열 네이밍·인덱스 규약 명문화 가능 | 첫 사례 이동 1회 필요 |
| C. `_workspace/devlog/` 합류 | HTML 인덱스 패턴 기존재 | devlog는 "작업 일지", 평가는 "시스템 판정" — 의미 충돌 |

## 결정(Decision)

**B 채택 — `docs/assessments/` 신설.**

### 문서 분류 기준 (위치 권위)

| 문서 성격 | 위치 | 형식 |
|-----------|------|------|
| **주기 평가** — 점수화·판정·우선순위 가이드 (사용자 대면) | `docs/assessments/` | HTML |
| 일회성 조사·audit·결함 분석 (작업 evidence) | `_workspace/reports/` | md |
| 운영 가이드·셋업·runbook | `docs/operations/` (기존 권위 유지) | md |
| 작업 일지 | `_workspace/devlog/` (기존 권위 유지) | HTML |

### 네이밍 규약

`docs/assessments/YYYY-MM-DD-<assessment-slug>.html`

- `<assessment-slug>`는 **평가 종류 식별자** — 같은 slug 반복 발행이 시계열을 이룬다.
- 초기 slug 레지스트리: `autotrading-readiness` (자동매매 준비도). 신규 종류는 인덱스 README에 등록 후 사용.

### 표준 템플릿 (9 섹션)

1. **판정 헤더** — 종합 판정 1줄 + 대상 커밋 sha + 작성일
2. **종합 스코어** — 영역별 % (예: 종합 준비도 / MOCK 구현도 / REAL 안전 운영)
3. **한 줄 결론**
4. **실제 흐름 점검** — 평가 대상 파이프라인 단계별
5. **좋아진 점** (직전 평가 이후 개선)
6. **검증 evidence** — 테스트·DB·로그 실측 인용
7. **남은 위험** — P0/P1/P2 분류
8. **우선순위 표** — 항목별 +% · 우선순위 · 할 일 · 완료 기준
9. **직전 동종 평가 대비 diff** — 스코어 변동표 (첫 발행은 baseline 명시)

### 인덱스

`docs/assessments/README.md` — 표(날짜 · 종류 slug · 종합 판정 · 핵심 스코어 · 파일 링크). 신규 평가 발행 시 row prepend 의무.

### 주기·책임

- **트리거**: 사용자 on-demand가 1차. 권장 시점 — minor/patch release cut 직전, 대형 fix 사이클 종료 후.
- **발행 책임**: 평가 수행 주체(orchestrator·외부 Codex 등)와 무관하게 위치·네이밍·템플릿·인덱스 갱신은 본 ADR이 LOCK.
- 평가 본문은 **실측 evidence 의무** (검증 의무 메모리 정합 — 테스트·DB·로그 인용 없는 점수 금지).

### 첫 사례 이동

`_workspace/reports/2026-06-04-autotrading-readiness-current-code.html` → `docs/assessments/2026-06-04-autotrading-readiness.html` (slug 표준화, 본 ADR PR에서 `git mv`).

## SLO 영향

없음 (문서 구조 결정 — 운영 코드 0 touch). 평가 문서의 P0/P1 항목이 SLO 보강 사이클의 입력이 되는 간접 개선.

## rollback plan

디렉토리 이동 revert 1 commit. 평가 발행 중단 시에도 기존 HTML은 정적 보존.

## 관측 매트릭스

- `docs/assessments/README.md` row 수 = 발행 평가 수 (시계열 누적 확인)
- 동종 slug 평가 간 스코어 diff가 §9 섹션에 기록되는지 (다음 발행 시 검증)

## 결과(Consequences)

- 긍정: 사용자 대면 평가의 단일 위치 + 시계열 비교 가능 + 일회성 조사와 분리
- 부정: 평가 발행 시 인덱스 갱신 1단계 추가
- 후속 과제: (P2) `index.html` 자동 생성 헬퍼 (devlog 패턴 재사용) — 평가 5건 누적 시 착수
