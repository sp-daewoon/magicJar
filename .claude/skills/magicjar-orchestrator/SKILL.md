---
name: magicjar-orchestrator
description: magicJar(마법의항아리) 한국 주식 자동매매 프로젝트의 모든 구현·설계·확장·유지보수 작업을 전문 에이전트 팀으로 조율하는 오케스트레이터. Spring Boot + Spring AI + KIS OpenAPI 기반 백테스트/모의/실전 시스템 구축, 모듈 추가, 전략 추가, AI 기능 추가, 화면 추가, 경계면 검증, 재실행/업데이트/부분 수정 요청에 반드시 이 스킬을 사용. "magicJar 시작", "자동매매 기능 추가", "전체 빌드", "백테스트 돌려줘", "부분 재실행", "이전 결과 기반 개선"도 포함.
---

# magicjar-orchestrator — magicJar 팀 조율자

magicJar 프로젝트의 모든 비트리비얼 작업은 이 오케스트레이터를 통한다. 7명의 전문 에이전트(`architect`, `backend-core`, `market-data`, `strategy-engine`, `ai-engineer`, `frontend`, `qa`)를 Phase별 하이브리드 모드로 조율한다.

## 에이전트 지도

| 에이전트 | 전용 스킬 | 주 담당 |
|---------|---------|--------|
| architect | magicjar-adr | 시스템 설계·ADR·모듈 경계 |
| backend-core | magicjar-spring-boot-scaffold, magicjar-harness-dashboard | Spring Boot 뼈대·도메인 모델·공용 인프라·하네스 대시보드 백엔드 |
| market-data | magicjar-kis-openapi | KIS API·시세·뉴스·공시 수집 |
| strategy-engine | magicjar-backtest-engine, magicjar-trading-journal | 지표·DSL·백테스트·주문 로직·매매일지 자동 기록 |
| ai-engineer | magicjar-spring-ai | Spring AI·프롬프트·감성·조언·매매 AI 복기 |
| frontend | magicjar-dashboard, magicjar-trading-journal, magicjar-harness-dashboard | UI·차트·매매일지 페이지·하네스 운영 페이지 |
| qa | magicjar-boundary-qa | 경계면 교차 검증 |

모든 Agent 호출은 `model: "opus"` 명시.

## Phase 0: 컨텍스트 확인 (항상 첫 단계)

오케스트레이터가 트리거되면 **가장 먼저** 다음을 확인해 실행 모드를 결정한다:

1. `_workspace/` 존재 여부 → 초기 실행 vs 후속
2. 사용자 요청 패턴 분석:
   - "처음부터", "새로 시작" → **초기 실행**. 기존 `_workspace/`가 있다면 `_workspace_prev/`로 이동
   - "{모듈명}만 다시", "{에이전트}에게 {작업}" → **부분 재실행**. 해당 에이전트만 재호출
   - "전체 업데이트", "이전 결과 개선" → **전체 증분 실행**. 각 에이전트가 기존 산출물 기반으로 보완
3. 필요 시 `MEMORY.md`와 `memory/project_overview.md`를 확인하여 프로젝트 제약 재확인

결정된 모드를 한 줄 요약으로 사용자에게 알린 뒤 Phase A로 진입.

**Phase 전환 시 반드시 `_workspace/_harness-state.json` 갱신** — 아래 스키마로 기록하여 `/harness` 대시보드가 현재 상태를 읽을 수 있게 한다:

```json
{
  "currentPhase": "A|B|C|DONE",
  "phaseHistory": [
    {"phase": "0", "startedAt": "ISO-8601", "finishedAt": "ISO-8601", "mode": "initial|partial|incremental"},
    {"phase": "A", "startedAt": "...", "finishedAt": "...", "agents": ["architect","backend-core"]}
  ],
  "activeAgents": ["..."],
  "blockedAgents": [],
  "updatedAt": "ISO-8601"
}
```

(상세는 `magicjar-harness-dashboard` 스킬 참조.)

## Phase A: 설계 (에이전트 팀 모드)

**실행 모드:** 에이전트 팀 (실시간 토론·합의)

**멤버:** `architect`, `backend-core`

**목표:** 이번 작업에 필요한 ADR·모듈 경계·도메인 모델 합의.

**실행:**
```
TeamCreate(team_name="magicjar-design", members=["architect","backend-core"])
TaskCreate로 세부 작업 할당 (예: "KIS 연동 모듈의 포트 인터페이스 설계", "백테스트 엔진 위치 결정")
→ architect가 ADR 초안 → backend-core가 구현 타당성 검토 → 합의
→ 결과물: _workspace/arch/* (ADR, module-boundaries, tech-stack)
TeamDelete 후 Phase B 진입
```

**통과 조건:** 필요한 ADR이 Accepted 상태, 모듈 경계 문서 존재, 공용 도메인 모델 확정.

## Phase B: 구현 (서브 에이전트 병렬 모드)

**실행 모드:** 서브 에이전트 병렬 (`run_in_background: true`)

**멤버:** `market-data`, `strategy-engine`, `ai-engineer`, `frontend` (+ 필요 시 `backend-core`)

**목표:** Phase A의 설계를 각 도메인별로 병렬 구현.

**실행:**
- 각 에이전트는 독립 모듈을 작업. 공용 의존은 backend-core가 먼저 제공.
- 병렬 호출 시 backend-core를 먼저 완료시키고, 그 이후에 나머지 4개를 병렬 실행하는 **2단 병렬**을 기본으로 한다:
  ```
  1단: Agent(backend-core)            # 공용 타입·설정 먼저
  2단: Agent(market-data) || Agent(strategy-engine) || Agent(ai-engineer) || Agent(frontend)
  ```
- 각 에이전트는 `_workspace/{module}/` 에 산출물 저장.

**통과 조건:** 각 모듈 빌드 성공(`./gradlew :{module}:build`), 각 모듈의 `_workspace/` 스키마·샘플 파일 존재.

## Phase C: 검증 (에이전트 팀 모드, 증분)

**실행 모드:** 에이전트 팀 (qa 중심)

**멤버:** `qa` + Phase B에서 산출물을 낸 에이전트들

**목표:** 경계면 교차 검증, 스모크 시나리오 통과, 이슈 리포트.

**실행:**
```
TeamCreate(team_name="magicjar-qa", members=["qa", <Phase B 참여자들>])
TaskCreate: qa가 `_workspace/qa/boundary-matrix.md` 갱신 → 이슈 발견 시 해당 에이전트에 수정 요청
스모크 실행: ./gradlew :app:bootRun (profile=mock) → 종목조회→백테스트→AI설명→대시보드
이슈가 블로커(P0)면 Phase B로 되돌아감, 그렇지 않으면 리포트 저장 후 종료
```

**통과 조건:** `_workspace/qa/issues.md`에 OPEN·P0 이슈 없음. 스모크 시나리오 전 구간 통과.

## 실행 모드 선택 요약

| 상황 | 모드 |
|------|------|
| 초기 빌드 (A→B→C 전체) | 하이브리드 (팀/서브/팀) |
| 단일 에이전트 작업(예: "프롬프트만 고치자") | 서브 에이전트 1회 호출, QA 스킵 또는 부분 QA |
| 설계 재검토 | Phase A만 실행 |
| 통합 이슈만 집중 | Phase C만 실행 |

## 데이터 전달 프로토콜

- **파일 기반(1순위):** 모든 중간 산출물은 `_workspace/{module}/` 에. 이전 Phase 산출물은 보존.
- **태스크 기반:** `TaskCreate/TaskUpdate`로 의존성 관리 (예: market-data의 샘플 데이터 준비 완료 → strategy-engine 작업 시작).
- **메시지 기반:** 팀 모드에서만 `SendMessage` 사용. 서브 에이전트 모드에선 Agent 반환값으로 수집.

파일명 규칙: `_workspace/{module}/{artifact-kebab-case}.{ext}`.
최종 사용자 산출물은 프로젝트 루트(`src/`, `README.md` 등)에 출력, 중간 산출물은 `_workspace/` 유지.

## 에러 핸들링

| 유형 | 전략 |
|------|------|
| 에이전트 1회 실패 | 동일 입력으로 1회 재호출 → 재실패 시 해당 결과 없이 진행 + `_workspace/qa/issues.md`에 BLOCKED 기록 |
| 빌드 실패 | backend-core에 즉시 SendMessage, 다른 에이전트 작업 정지 |
| 서로 다른 에이전트의 결론 상충 | 둘 중 어느 쪽도 삭제 금지, 양쪽 출처 병기하여 architect에 중재 요청(Phase A 팀 재소집) |
| 외부 API 장애(KIS/LLM) | 해당 기능만 mock 폴백, 리포트에 명시 |
| 비트리비얼 결정 요구 | 사용자에게 AskUserQuestion으로 확인 후 진행 |

## 사용자에게 투명하게 보고할 것

각 Phase 종료 시 한 단락 요약:
- 무엇을 했는가 (에이전트별 산출물 간단 리스트)
- 남은 이슈 (있으면)
- 다음 Phase 진입 여부 (자동 vs 사용자 확인)

## Phase 7: 피드백 수집 & 진화

전체 실행 완료 후 반드시 사용자에게 묻는다:
- "결과 중 개선하고 싶은 부분이 있나요?"
- "팀 구성·워크플로우에 바꾸고 싶은 점이 있나요?"

피드백 유형별 수정 대상:
- 결과물 품질 → 해당 에이전트의 전용 스킬 수정
- 에이전트 역할 → `.claude/agents/{name}.md` 수정
- Phase 순서 → 본 오케스트레이터 수정
- 팀 구성 → 오케스트레이터 + 에이전트

모든 변경은 **CLAUDE.md의 변경 이력 테이블**에 기록.

## 테스트 시나리오

### 정상 흐름
1. 사용자: "magicJar MVP 전체 구축해줘"
2. Phase 0: `_workspace/` 없음 → 초기 실행
3. Phase A: architect+backend-core 팀이 ADR 3~5건 생성, 모듈 경계 확정
4. Phase B: backend-core 스캐폴딩 후 4개 에이전트 병렬 구현, `_workspace/` 산출물 쌓임
5. Phase C: qa가 스모크 통과 확인, issues.md OPEN=0
6. 종료 요약 + 피드백 요청

### 에러 흐름
1. 사용자: "백테스트 엔진 개선해줘"
2. Phase 0: `_workspace/strategy/` 존재 → 부분 재실행
3. strategy-engine만 재호출, ai-engineer에 `SendMessage`로 DSL 변경사항 고지
4. strategy-engine이 기존 DSL 버전과 호환성 검토 → 호환 불가 발견 → DSL v2 ADR 요청
5. 오케스트레이터가 Phase A 팀을 소규모로 재소집(architect+strategy-engine)하여 ADR 추가
6. DSL v2 적용 후 qa가 경계 매트릭스 재검증
7. 종료 요약
