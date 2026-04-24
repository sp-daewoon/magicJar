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
