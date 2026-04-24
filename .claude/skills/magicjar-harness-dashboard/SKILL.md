---
name: magicjar-harness-dashboard
description: magicJar 하네스(에이전트 팀 + 스킬 + 오케스트레이터)의 "운영 상황"을 사용자가 웹에서 직접 볼 수 있는 **Harness Dashboard** 페이지를 설계·구현·확장·수정한다. 에이전트 목록·역할·최근 활동, Phase 진행 상태, `_workspace/` 산출물 목록, QA 이슈 카탈로그, CLAUDE.md 변경 이력을 모두 한 화면에서 보여준다. "에이전트 상황 보여줘", "하네스 대시보드", "내부 진행 상태 페이지", "에이전트 업무 현황 페이지"도 포함.
---

# magicjar-harness-dashboard — 하네스 운영 상황 페이지

사용자가 "지금 에이전트가 뭘 하고 있는가"를 **코드를 안 열어도** 확인할 수 있는 웹 페이지. 읽기 전용.

## 핵심 뷰 구성

| 섹션 | 설명 | 데이터 소스 |
|------|------|-----------|
| 에이전트 카드 | 이름·역할 요약·담당 스킬·상태 뱃지(IDLE/RUNNING/BLOCKED) | `.claude/agents/*.md` frontmatter + 런타임 힌트 |
| Phase 타임라인 | Phase 0/A/B/C 진행 상태, 현재 Phase, 각 Phase 시작/종료 시각 | `_workspace/_harness-state.json` (오케스트레이터가 기록) |
| Workspace 트리 | `_workspace/` 하위 파일 목록과 최종 수정 시각, 작성 에이전트 | 파일 시스템 스캔 |
| QA 이슈 보드 | `_workspace/qa/issues.md` 를 파싱하여 OPEN/FIXED 분류 | 마크다운 파싱 |
| 변경 이력 | `CLAUDE.md`의 "변경 이력" 테이블 | 마크다운 파싱 |
| 최근 활동 로그 | 에이전트별 최신 산출물 10건 | `_workspace/{agent}/` mtime 정렬 |

## 기술 구현 (backend-core + frontend 협업)

### 백엔드 — `harness-dashboard` 패키지 (backend-core 책임)

```
web/harness/
├── HarnessController       # GET /harness, GET /harness/api/state
├── HarnessStateReader      # 파일 시스템 + 런타임 상태 합성
├── WorkspaceScanner        # _workspace/ 트리 + mtime
├── AgentMetaLoader         # .claude/agents/*.md frontmatter 파싱
└── HarnessState            # DTO
```

**API**
- `GET /harness/api/state` — 전체 상태 JSON
- `GET /harness/api/agents` — 에이전트 메타만
- `GET /harness/api/phase` — 현재 Phase (SSE 스트림 선택)
- `GET /harness/api/workspace` — 파일 트리
- `GET /harness/api/issues` — QA 이슈 목록

**HarnessState JSON 예시**
```json
{
  "generatedAt": "2026-04-25T14:30:00+09:00",
  "currentPhase": "B",
  "phaseHistory": [
    {"phase": "A", "startedAt": "...", "finishedAt": "..."}
  ],
  "agents": [
    {
      "name": "strategy-engine",
      "roleSummary": "기술적 지표·DSL·백테스트·주문 로직",
      "skills": ["magicjar-backtest-engine","magicjar-trading-journal"],
      "lastActivity": "2026-04-25T14:22:11+09:00",
      "lastArtifacts": ["_workspace/strategy/indicator-catalog.md"],
      "status": "RUNNING"
    }
  ],
  "issuesOpen": 2,
  "issuesFixed": 5
}
```

### 프론트 — `/harness` 페이지 (frontend 책임, magicjar-dashboard 스킬과 연계)

- 상단: 현재 Phase 배너 (색상 코드: A=파랑, B=보라, C=초록, 완료=회색)
- 좌측: 에이전트 카드 그리드 (클릭 → 해당 에이전트 상세 패널)
- 우측 메인: 선택된 에이전트의 최근 산출물·담당 스킬·역할 요약
- 하단: 탭(Workspace 트리 / QA 이슈 / 변경 이력)
- HTMX 폴링 (5초 간격 상태 갱신) 또는 SSE (`/harness/api/phase`)

## 오케스트레이터 상태 기록 규약

오케스트레이터는 Phase 전환 시 `_workspace/_harness-state.json`을 업데이트한다:

```json
{
  "currentPhase": "B",
  "phaseHistory": [
    {"phase": "0", "startedAt": "...", "finishedAt": "...", "mode": "initial"},
    {"phase": "A", "startedAt": "...", "finishedAt": "...", "agents": ["architect","backend-core"]},
    {"phase": "B", "startedAt": "...", "agents": ["market-data","strategy-engine","ai-engineer","frontend"]}
  ],
  "activeAgents": ["market-data","strategy-engine"],
  "blockedAgents": []
}
```

## 보안

- `/harness/**` 는 기본적으로 로컬/내부 네트워크 한정 (Spring Security `hasIpAddress('127.0.0.1/32')` + 프로덕션 프로파일에선 비활성화 옵션).
- 민감 정보(API 키·시크릿) 노출 금지 — 에이전트 정의에 포함되더라도 대시보드 렌더 시 마스킹.

## 하지 말 것

- 편집 기능 제공 금지. Harness Dashboard는 **읽기 전용**.
- `.claude/settings.local.json`·시크릿 파일 노출 금지.
- 에이전트를 UI에서 직접 호출하는 기능 금지 (오케스트레이터만이 진입점).

## 재실행/업데이트 지침

- 에이전트/스킬이 추가·삭제되면 `/harness` 는 자동 반영(파일 시스템 기반). 별도 수정 불필요.
- Phase 타임라인 UI 개선은 frontend 단독 작업.
- 상태 JSON 스키마 변경 시 `_workspace/_harness-state.schema.json` 업데이트 후 프론트 파서 갱신.
