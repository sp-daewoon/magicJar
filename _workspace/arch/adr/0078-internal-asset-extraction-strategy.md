# ADR 0078: 내부 자산 추출 전략 — 재사용 가능한 프로젝트 부트스트랩 템플릿

- **상태**: Proposed (시동 시점 미정 — 사용자 명시적 트리거 단일 조건)
- **작성일**: 2026-05-16
- **결정자**: 사용자 (최고 관리자), magicjar-architect (오케스트라)
- **관련 ADR**: ADR 0030 (Master architecture SoT), ADR 0035 (Agent model tiering), ADR 0036 (Author whitelist + Task cycle), ADR 0052 (Baseline release lock), ADR 0053 (Living policies SoT)
- **Depends-on**: 없음 (시동 시점 사용자 트리거에 의존)

---

## 1. 맥락 (Context)

magicJar는 W1~W5 트랙 진행 중인 한국 주식 자동매매 시스템이지만, **본체 가치 외에 운영 패턴 자체가 재사용 가치를 갖는다**:

- SoT 5층 구조 (INDEX · policies · ADR · master-architecture · tracks-roadmap)
- harness 패턴 (사용자 → orchestrator → architect → sub-agent 3단 hierarchy)
- Task cycle (`agent/<a>/<t>` + `_workspace/tasks/*.md` + author email whitelist + pre-commit ktlint)
- 개발 룰북 5건, Release minor LOCK, 운영 함정 가드 패턴, Memory 시스템

이 패턴들은 자동매매 도메인과 무관하게 다른 프로젝트(특히 Claude Code 기반 다인 에이전트 협업 프로젝트)에 적용 가능.

**2026-05-16 사이클 배경**: caveman·graphify 외부 도구 도입 검토 후 모두 fitness 검증 실패로 revert. 사용자가 "magicJar 말고 새로운 프로젝트 하더라도 공통 룰처럼 가지고 갈 것을 만드는 게 목표였거든. 그래서 MCP화도 있었던거고"임을 명시. **외부 도구 도입 방향 ➜ 내부 자산 추출 방향으로 트랙 전환**.

---

## 2. 검토한 대안 (Options)

| 대안 | 장점 | 단점 |
|---|---|---|
| **Alt 1**: 외부 도구 도입 (caveman·graphify 등) | 즉시 사용 가능 | 기존 SoT 체계와 marginal 가치 또는 충돌. 사용자 진짜 목표(재사용 가능 룰셋)와 방향 정반대. 2026-05-16 검증 결과 reject |
| **Alt 2**: 추출 안 함, magicJar 단독 운영 | 작업 0 | 사용자 명시 목표("새 프로젝트에도 공통 룰처럼 가지고 갈 것")와 어긋남. 룰셋 재사용 기회 상실 |
| **Alt 3**: 즉시 추출 시작 (현재 사이클 내) | 빠른 부트스트랩 키트 확보 | W4·W5 우선순위 침해. 현재 트랙 마감 지연 |
| **Alt 4 (채택)**: 추출 트랙 등록 + 시동 시점 사용자 트리거 위임 | 현재 사이클 영향 0, 시동 유연성 100%, 트래킹 가능 | 시동 시점 모호성 — 사용자 호출 누락 risk (backlog 추적으로 보완) |

---

## 3. 결정 (Decision)

magicJar의 검증된 운영 자산을 별도 **재사용 가능한 프로젝트 부트스트랩 템플릿**으로 추출하는 트랙을 가져간다. 시동 시점은 **사용자 명시적 트리거** 시 — 남은 사이클 또는 backlog 모니터링 중 사용자가 호출하는 시점.

### 3.1 추출 자산 우선순위

**Phase 1 (★★★)** — 도메인 비종속, 즉시 재사용 가능

- SoT 5층 구조: `INDEX.md` · `policies/` · ADR · `master-architecture.md` · `tracks-roadmap.md`
- harness 패턴: `.claude/agents/*` + `.claude/skills/*` + 3단 hierarchy (ADR 0035 · 0036)
- Task cycle: agent 브랜치 + tasks/ + email whitelist + pre-commit

**Phase 2 (★★)** — 약간의 도메인 추출 필요

- 개발 룰북 5건 (`development-rules.md`)
- Release minor LOCK + baseline 사이클 (ADR 0052)
- 운영 함정 가드 패턴 (stale jar · `--env-file` · 이중 게이트)
- Memory 시스템 4 타입 (user · feedback · project · reference)
- branching · 머지 정책 (agent/* + rebase + main protect)

**Phase 3 (★)** — 부수적

- CHANGELOG 한 줄 정책 + devlog HTML 자동화
- 시크릿 처리 정책 (평문 노출 시 재발급 권고)

### 3.2 패키징 형태 (잠정 추천)

| 옵션 | 형태 | 부트스트랩 명령 | 자동화 |
|---|---|---|---|
| A. GitHub Template Repository | template repo + placeholder | "Use this template" 클릭 | 중 |
| B. Cookiecutter Template (Python) | cookiecutter 패키지 | `cookiecutter <repo>` | 상 |
| C. Monolithic Documentation Repo | docs-only repo | `git clone` 또는 submodule | 하 |

**잠정 추천: A + C 결합** — 코드·스크립트는 GitHub template, 문서·룰북은 별도 docs repo로 분리해 magicJar living policy 변경 시 동기화 가능. **시동 시점에 최종 결정**.

### 3.3 MCP화와의 분리

**룰셋의 MCP화는 부적합** — MCP는 함수 호출 인터페이스(`query_database` 같은 동적 동작), 룰셋은 코드/문서 패턴이라 인터페이스 불필요.

**magicJar 본체의 MCP 노출은 별건으로 유효** — v4.0 비전 또는 별도 ADR로 분리. 적합 인터페이스:

- 시그널 · 백테스트 · 전략 평가
- KIS 호가/체결 조회
- AI 분석 호출

### 3.4 시동 트리거 조건

**사용자 명시적 트리거 단일 조건**. 자동 시동 트리거 없음 — 사용자가 남은 사이클 또는 backlog 모니터링 중 호출 시점에 시동.

---

## 4. 결과 (Consequences)

### 긍정

- 운영 노하우 형식화 · 재사용 가능화
- 새 프로젝트 부트스트랩 시간 단축 (수일 → 수시간)
- magicJar의 검증된 패턴이 다른 도메인에도 검증 기회 확보
- 룰셋 추출 과정에서 magicJar 자체의 도메인 결합 부분 명확화 (리팩터 기회)

### 부정

- 추출 작업 시간 소요 (Phase 1 만 ~1주 추정)
- magicJar와 template repo 양쪽 sync 부담 (living policies 변경 시)
- placeholder/`{{var}}` 처리에 수작업

### 중립

- 시동 시점 미정 → 현재 W4 · W5 우선순위에 영향 0

### 후속 과제

- 시동 시점 별도 spec/plan 발행 ([[feedback_spec_plan_scope]] 정합 — 신규 트랙이라 spec/plan 의무)
- Phase 1 완료 후 Phase 2 시동 여부 재검토
- magicJar 본체 MCP 노출은 별도 ADR (v4.0 비전)

---

## 5. References

- `_workspace/INDEX.md` — SoT 단일 진입점
- ADR 0030 — Master architecture SoT
- ADR 0035 — Agent model tiering
- ADR 0036 — Author whitelist + Task cycle
- ADR 0052 — Baseline release lock
- ADR 0053 — Living policies SoT
- `docs/architecture/development-rules.md` — 개발 룰북 5건
- `_workspace/backlog.md` §55 — 진행 트랙 추적 (시동 시점 모니터링)
