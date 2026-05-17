# ADR 0079 — 마스터 시각화 HTML 표준화

**Status**: Accepted (2026-05-17)
**작성**: orchestrator (brainstorming 흐름 사용자 결정 8회 종합)
**연관 spec**: `_workspace/specs/2026-05-17-master-html-design.md`
**연관 plan**: `_workspace/specs/2026-05-17-master-html-plan.md`
**Phase PRs**: #461 (Phase 1) · #462 (Phase 2) · Phase 3 (본 ADR 포함)

## §1. Context

magicJar는 SoT 5층 + 다중 README + ADR 74건 + CHANGELOG 341 line + SKILL 5+ + 에이전트 6 + baseline 7 minor + devlog + backlog 57 § 등 12 카테고리 풍부한 문서·정책·결정 자산을 보유한다. 기존 시각화는 운영 상태성(`_workspace/dashboard.html`) · 시점 스냅샷성(baseline) · 일자 이력성(devlog)으로 모두 **시간축 누적형**. 카테고리축 + 중요도축 + 상시 최신 마스터가 없었다.

2026-05-17 W3 마감 cut(v3.6.10) 후 W4 진입 전 사용자가 본 결손을 명시 — "주요도에 나뉘어서 탭 등을 활용한 html로 정리, 표준화해서 관리하고 싶다".

## §2. Decision

`_workspace/master.html` 단일 파일 + `scripts/master-index.mjs` 자동 생성 + `/master-index` slash command 수동 trigger + `scripts/release.sh` 자동 통합으로 마스터 시각화 HTML을 표준화한다.

12 카테고리는 권위(A·B·C·D·J·K) + 시계열(E·F·G·H·I·L) 두 축으로 분류, 그룹탭 2 + 하위탭 6 + 카테고리별 본질 맞춤 입도 + 상단 banner(현재 release · 사이클 단계 · 다음 마일스톤 · 이번 작업 timeline) 구조로 발행한다.

## §3. Architecture

- Node ESM single script (의존 0)
- 파일 system + git + gh CLI 데이터 추출 (graceful fallback)
- 인라인 HTML + CSS + vanilla JS 단일 파일 출력
- idempotent regen (같은 입력 → 같은 출력)
- AI 호출 0 LOCK

## §4. Alternatives 거절

- **Alt 1**: SPA 프레임워크(React) — overkill, magicJar 패턴(devlog·baseline·dashboard 인라인) 어긋남
- **Alt 2**: 기존 dashboard.html 흡수 — 운영 상태 vs 지식 인덱스 다른 본질, 분리 보존이 적합
- **Alt 3**: 외부 라이브러리(Alpine.js) — CDN 의존, 오프라인 보장 어려움
- **Alt 4**: 카테고리 균일 입도 — ADR 74건 vs devlog 4건 같은 카드는 가치 잃음

## §5. Consequences

**긍정**:
- 사용자 한눈에 모든 문서·정책·결정 진입
- 매 release cut 시 자동 갱신 — 항상 최신
- 의존 0 — 어떤 환경에서도 열림
- 신규 카테고리 추가 시 코드 1 항목 + 즉시 합류

**부정**:
- 단일 파일 크기 증가 가능 (1MB 임계 selfCheck로 방어)
- gh CLI 외부 호출 1건 (실패 시 git tag fallback)
- ADR/spec 추가 시 페이지 무겁기 누적 가능 (post-MVP 검토)

## §6. Phase 분리

- **Phase 1** — script + slash + 6 카테고리 (A·B·C·H·E·F) — PR #461
- **Phase 2** — 나머지 6 카테고리 + banner + 인터랙션 — PR #462
- **Phase 3** — test 9건 + release.sh 통합 + 본 ADR (PR 본 사이클)

## §7. Trigger 정합

- `/master-index` slash command (사용자 수동 trigger)
- `scripts/release.sh` 매 cut 자동 호출 (sync-readme-releases.mjs 직후)
- GitHub Actions 트리거 0 ([[feedback_no_github_actions]] 정합)

## §8. 사용자 메모리 정합

- [[feedback_qa_on_demand]] AI 호출 0
- [[feedback_no_mock_data]] graceful "데이터 없음"
- [[user_language]] 한국어
- [[feedback_honorific]] 존댓말
- [[feedback_no_github_actions]] GHA 트리거 0
- [[feedback_release_policy_patch_first]] patch cycle 정합
- [[feedback_kis_first_policy]] KIS 호출 0

## §9. 비범위 (spec §11 인용)

- **운영 상태 영역**: `_workspace/dashboard.html`은 별도 유지 (운영 상태 = Phase·Task·PR·commit·QA·에이전트). master.html L 카테고리에서 link만.
- **실시간 데이터**: master.html은 static snapshot. 실시간성 필요는 dashboard.html이 담당.
- **모바일 반응형**: post-MVP. 데스크탑 우선.
- **다국어**: 한국어 fixed.
- **다크/라이트 모드 토글**: 다크 fixed v1.
- **AI 자동 요약**: AI 호출 0 LOCK.
- **로그인/권한**: 단일 사용자 정합.

## §10. 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-17 | ADR 0079 발행 — Phase 1·2·3 PR 머지 완료 후 (orchestrator) |
