# `_workspace/_archive/` — magicJar 과거 산출물 보관소

> 본 디렉토리는 v2 베이스라인(2026-05-03) 진입 시 청소된 과거 산출물의 **읽기 전용 보관소**다.
> 새 트랙은 본 디렉토리를 변경하지 않는다. 참조 시 `_archive/qa-issues/ISSUE-008` 식으로 명시.

## 구성

| 디렉토리 | 내용 | 보존 기간 |
|---|---|---|
| `legacy-code/_legacy/` | Phase D-P1(2026-04-27)에 백업된 19 디렉토리 — `ai-app/`·`market-kis/`·`market-persistence/`·`strategy-*/`·`web/` 등 | W5 종료 후 일괄 삭제 후보 |
| `phase-a-drafts/` | Phase A(2026-04-25) 도메인 모델 draft 5 영역 — backend·market-data·strategy·frontend·ai | W5 종료 후 일괄 삭제 후보 |
| `retrospective/phase-b.md` | Phase B(2026-04-25) 회고 | 영구 보존 |
| `dogfooding/2026-05-02.md` | 1번 트랙 KIS 주문 dogfooding 1차 시도 + 일시 중단 기록 — EGW00202 발견 등 | W3 진입까지 참조 |
| `qa-issues/ISSUE-008/` | 5번 트랙 QA 디펙 1차 결과 — PR #63에서 FIXED | 영구 보존 (회고 자료) |
| `qa-issues/report-20260501-1042.md` | Phase F R2 통합 QA 보고서 — PR #38·#39·#40·#41로 종결 | 영구 보존 |
| `qa-issues/issues.md` | 과거 QA 카드 AUTO-INDEX (ISSUE-001~007) | 영구 보존 |
| `tasks-done/` | 과거 task 파일 (T-20260425~T-20260501) — Phase B/D/E/F 진행 기록 | W5 종료 후 일괄 삭제 후보 |
| `dashboard.html` | 만들다 만 운영 대시보드 (render-status.mjs는 task 파일에서 직접 렌더) | 즉시 삭제 후보 |

## 운영 규칙

- **변경 금지** — 기록 가치만 있는 자료. 새 산출물은 `_workspace/` 본 디렉토리 또는 `docs/` 신설.
- **삭제 시점** — 사용자 명시 "release외 싹 지울 예정" 정합 시점. v2 베이스라인 컷 후 W5 종료까지 보존, 그 후 일괄 삭제 결정.
- **참조 표기** — README/PR/ADR에서 archive 인용 시 `_archive/qa-issues/ISSUE-008/card.md`처럼 절대 경로 명시.

## 청소 이력

| 날짜 | 청소 | 사유 |
|---|---|---|
| 2026-05-03 | v2 베이스라인 진입 — `_workspace/_legacy/` 19 디렉토리 + Phase A drafts 5 + retrospective/dogfooding/qa-issues/tasks-done 등 30건 일괄 archive | 사용자 명시: "이젠 실수 용납안된다, 클린 베이스라인" |
