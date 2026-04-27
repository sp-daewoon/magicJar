# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트: magicJar(마법의항아리)

Spring Boot 3.x + Spring AI + 한국투자증권 KIS OpenAPI 기반 **개인 맞춤형 한국 주식 자동매매 시스템**. 단계적 개발 순서: **백테스트 → 모의투자 → 실전**. 디렉토리명은 `magic_ch1`이지만 정식 프로젝트명은 `magicJar`.

원격 저장소: https://github.com/sp-daewoon/magicJar.git

## 하네스: magicJar 자동매매 팀

**목표:** 7인 전문 에이전트 팀(architect · backend-core · market-data · strategy-engine · ai-engineer · frontend · qa)을 `magicjar-orchestrator` 스킬이 하이브리드 모드(팀/서브/팀)로 조율해 설계·구현·검증을 자동화한다.

**트리거:** magicJar 관련 모든 비트리비얼 작업은 `magicjar-orchestrator` 스킬을 사용하라. 단순 질문(설명·조회)은 직접 응답 가능. 단일 파일 소소한 편집도 직접 가능. 모듈 추가·전략 추가·AI 기능·화면 추가·통합 검증·부분 재실행·이전 결과 기반 개선 등은 반드시 오케스트레이터를 거친다.

**에이전트·스킬 위치:** `.claude/agents/*.md`, `.claude/skills/magicjar-*/SKILL.md`. 목록·역할은 오케스트레이터 스킬 내부에 정의되어 있으니 여기서 중복 기재하지 않는다.

**커밋 author:** `sp-daewoon <sp-daewoon@gmail.com>` (repo-local git config).

**변경 이력:**

| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-04-25 | 초기 하네스 구성 (7 에이전트 + 7 스킬 + 오케스트레이터) | 전체 | 프로젝트 부트스트랩 |
| 2026-04-25 | 매매일지(magicjar-trading-journal) + 하네스 운영 페이지(magicjar-harness-dashboard) 스킬 추가, strategy-engine·backend-core·frontend 에이전트 책임 보강, 오케스트레이터에 harness-state 기록 규약 추가 | skills/, agents/strategy-engine.md, agents/backend-core.md, agents/frontend.md, skills/magicjar-orchestrator | 사용자 요청: 매매일지 필수 + 에이전트 업무상황을 볼 수 있는 페이지 필요 |
| 2026-04-25 | 언어 Java 21 → **Kotlin 2.0** 전면 전환 (K2 컴파일러). ADR 0001 개정, tech-stack.md·domain-model-draft.md·setup-notes.md·magicjar-spring-boot-scaffold·agents/backend-core.md 동기화. Lombok/MapStruct 제거, MockK·kotest 도입 | `_workspace/arch/adr/0001`, `_workspace/arch/tech-stack.md`, `_workspace/backend/*.md`, `skills/magicjar-spring-boot-scaffold/SKILL.md`, `agents/backend-core.md` | 사용자 지시(Phase A 중) |
| 2026-04-25 | 브랜치 전략·Husky·QA 스크린샷 게이트 도입 (ADR 0009·0010·0011). 운영 문서, QA 에이전트·스킬, 오케스트레이터에 반영. `package.json`+`.husky/`+`.github/pull_request_template.md` 추가, `_workspace/`를 커밋 대상으로 포함 | `_workspace/arch/adr/0009~0011`, `_workspace/arch/branching-and-qa-gates.md`, `agents/qa.md`, `skills/magicjar-boundary-qa`, `skills/magicjar-orchestrator`, `.husky/`, `package.json`, `.github/pull_request_template.md`, `.gitignore` | 사용자 지시: husky 설치 + QA 스크린샷 완료까지 머지 불가 + B옵션(_workspace 커밋) |
| 2026-04-25 | `.claude/settings.json` 추가(기본 자동 허용 + `gh pr merge`·`git push main`·`npm publish` 등만 `ask` + force-push·실계좌 도메인 등 `deny`) + Feature→QA→Main 2단 머지 흐름(ADR 0012). 영속 `qa` 브랜치 도입, `branching-and-qa-gates.md`·orchestrator·qa 에이전트·boundary-qa 스킬·pre-push 훅·PR 템플릿 동기화 | `.claude/settings.json`, `_workspace/arch/adr/0012`, `_workspace/arch/branching-and-qa-gates.md`, `skills/magicjar-orchestrator`, `agents/qa.md`, `skills/magicjar-boundary-qa`, `.husky/pre-push`, `.github/pull_request_template.md`, `README.md` | 사용자 지시: "다 줘 너가 알아서" + 머지 승인은 qa 진입·main 진입 두 지점만 |
| 2026-04-25 | Phase B 종료 — 4개 PR(ai #4, web #5, market-data #6, strategy #7) qa 브랜치 squash 머지 완료. 1차 QA 검수(3 APPROVED + 1 CHANGES_REQUESTED → frontend 보강 push로 해소). 본 머지에서 `application.yml` 충돌 1건 발생, ai/market-data/strategy/web 4개 import 통합으로 해소. Husky pre-push의 `qa 직접 push 차단` 룰 1회 우회(--no-verify, 사용자 승인). 회고 문서 + 다음 사이클 개선안 작성 | `qa` 브랜치(d451bee~18a0dfd 4 commits), `app/src/main/resources/application.yml`, `_workspace/_retrospective-phase-b.md`, `_workspace/_harness-state.json` | 사용자 지시: "이정도로 완료하고 머지하자, 단 기록은 정확히 남겨" + Phase C 재검수는 회고로 대체, OPEN 이슈는 후속 PR |
| 2026-04-25 | **하네스 v2 — 6 패키지 일괄 적용** (Phase B 회고 반영). ADR 0013(단일 worktree·순차) · 0014(모델 차등 architect/ai/strategy=opus, 나머지=sonnet · 1회 escalation) · 0015(머지 1단 `agent/* → main`, qa 영속 브랜치 폐기) · 0016(QA On-Demand 7개 트리거 + 프로덕트 코드 기준) · 0017(에이전트별 git author 분리) · 0018(Task 파일 시스템 + `scripts/task.mjs` CLI). 7 에이전트 frontmatter `model`+`git-author`, 오케스트레이터 v2, boundary-qa on-demand, pre-push qa차단 폐기, settings.json 정리, PR 템플릿에 Model/Task/QA 라인 추가, branching-and-qa-gates v2 전면 재작성, render-status task 파일 파싱 | `_workspace/arch/adr/0013~0018`, `_workspace/arch/adr/README.md`, `_workspace/arch/branching-and-qa-gates.md`, `_workspace/tasks/README.md`, `scripts/task.mjs`, `scripts/render-status.mjs`, `.claude/agents/*.md` (7개), `.claude/skills/magicjar-orchestrator`, `.claude/skills/magicjar-boundary-qa`, `.claude/settings.json`, `.husky/pre-push`, `.github/pull_request_template.md`, `README.md` | 사용자 진단: 토큰 막대 소모·머지 시간 길어짐·worktree 격리 가치 미실현 + QA 매번 강제는 형식주의 |
| 2026-04-26 | **ADR 0017 강제화** — Husky `pre-commit` author 화이트리스트 가드 추가(`sp-daewoon@gmail.com` / `seonjupark94@gmail.com` / `bot+<agent>@magicjar.local`만 통과, 나머지 reject). 머지 정책 `--squash` → `--rebase` 전환(GitHub squash가 author를 web user로 덮어쓰는 문제 회피). 부수: `.gitignore .kotlin/` 추가 + `.kotlin/sessions/` 추적 해제. 과거 commit 소급 수정은 main 보호 위반이라 보류 — 향후 commit만 적용. | `.husky/pre-commit`, `.gitignore`, 머지 운영(orchestrator) | 사용자 지적: ADR 0017 채택 후 0/14 준수 — local 가드 + 머지 정책 둘 다 필요 |
| 2026-04-27 | **Phase D-P1 진행 — MSA 재구성 1단계.** 7 모듈(app·core·market-data·strategy·ai·order-exec·web) → 4 Gradle 모듈(domain·api·batch·consumer) + frontend·pyworker·docker 디렉토리 신설. 패키지 prefix `kr.spdaewoon.magicjar.*` → `com.mj.*` 일괄 변경(사용자 지시: 카멜 깨짐 회피 위해 mj 축약). domain은 hexagonal 정석으로 순수 도메인+port만 보존(퀀트 트레이너 미래 모듈 의존성 깨끗하게); Spring/JPA 의존 코드는 `_workspace/_legacy/`로 백업해 P2/P3에서 application layer로 영구 이전. spec docs/superpowers/specs/2026-04-27-msa-restructure-design.md + plan docs/superpowers/plans/2026-04-27-msa-restructure.md + ADR 0019~0026 발행. 빌드: 4 모듈 BUILD SUCCESSFUL, :domain:test 25 PASS. | `domain/`, `api/`, `batch/`, `consumer/`, `frontend/`, `pyworker/`, `docker/`, `settings.gradle.kts`, `_workspace/_legacy/`, `_workspace/arch/adr/0019~0026`, `_workspace/_harness-state.json`, `docs/superpowers/{specs,plans}/` | 사용자 지시: 프로젝트 구조 변경 + React 전환 + Python 모듈 + Docker MSA + LWC 차트(커스텀 지양) + 퀀트 트레이너 미래 고려 |
