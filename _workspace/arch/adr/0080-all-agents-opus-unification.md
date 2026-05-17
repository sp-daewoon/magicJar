# ADR 0080: 전체 에이전트 Opus 4.7 통일 (ADR 0035 supersede)

- 상태: Accepted
- 작성일: 2026-05-18
- 결정자: 사용자(최고 관리자) — orchestrator 위임 수행
- Supersedes: ADR 0035 (에이전트별 모델 차등 + 에스컬레이션)

## 맥락

ADR 0035 (2025-Q4 PoC 단계) 결정 — 작업 성격별 모델 차등:
- opus 3명: architect · ai-engineer · strategy-engine (깊은 추론)
- sonnet 3명: backend-core · market-data · frontend (패턴화·반복)
- qa(별도 .md 없음 — 메타 문서상 sonnet 표기)

차등 사유는 PoC 시점 토큰 비용 절감이 1차 목표였기 때문이다. 그러나 magicJar는 v3.6.x 시점부터 다음 단계로 진입한다:
- W3 자동매매 본체 종착(T-SIG·T-RISK·T-O 잔여 — 실계좌 자본 변동 첫 점)
- W4 옵션 트랙(T-AI·T-MCP — MCP 서버 노출)
- 코드 한 줄 수정도 룰 본체·자본 흐름에 영향 가능

이 단계에서 sonnet의 패턴화 추론은 다음 위험을 동반한다:
- Spring Boot 스캐폴딩이 "단순 패턴"이 아닌 도메인 invariant 결합 코드로 진화 (T-SIG의 RuleEngine·T-O의 OrderDispatcher)
- KIS API 클라이언트가 "문서 따라가기"가 아닌 OPSP8996(ADR 0077) 같은 미묘한 분기·실계좌 게이트 결합으로 진화
- React 화면이 "반복"이 아닌 실시간 시그널·매매일지·리스크 timeline 같은 상태 머신 결합으로 진화

## 결정

**전체 7명 에이전트 기본 모델을 `opus` (claude-opus-4-7)로 통일.** ADR 0035 차등 매트릭스 폐기.

### 신 모델 매트릭스

| 에이전트 | 기본 모델 | 사유 |
|---------|----------|------|
| architect | **opus** | 시스템 설계·ADR·트레이드오프 판단 (변동 없음) |
| ai-engineer | **opus** | 프롬프트 설계·구조화 출력 (변동 없음) |
| strategy-engine | **opus** | 지표 수학·DSL·백테스트 정밀도 (변동 없음) |
| backend-core | **opus** | ★ Spring Boot 도메인 invariant 결합 코드 — 신중 필요 (sonnet → opus) |
| market-data | **opus** | ★ KIS API 미묘한 분기·실계좌 게이트 결합 — 신중 필요 (sonnet → opus) |
| frontend | **opus** | ★ 실시간 시그널·매매일지·리스크 상태 머신 결합 — 신중 필요 (sonnet → opus) |
| qa | **opus** | ★ 경계면 교차 검증 (sonnet → opus, 메타 표기) |

> Haiku 미채택 정책 유지 (ADR 0035 §결과 정합 — 절약보다 재작업 비용이 큼).

### 에스컬레이션 규칙

ADR 0035의 에스컬레이션 규칙은 **무효화**한다 — 기본 모델이 이미 opus이므로 escalation 대상 없음.

품질 부족 시: 작업 분할 또는 사용자 의사결정 요청 (무한 재시도 금지 — ADR 0035 §결과 후속 정합).

### 구현

- `.claude/agents/backend-core.md` · `market-data.md` · `frontend.md` frontmatter `model: sonnet → opus`
- `_workspace/arch/adr/0035-model-tiering-and-escalation.md` — status: `Accepted → Superseded by ADR 0080`
- `_workspace/arch/adr/README.md` §14 변경 이력 row 추가
- `CLAUDE.md` 15번 줄 — 모델 차등 줄 갱신
- `.claude/skills/magicjar-orchestrator/SKILL.md` 에이전트 표 model 컬럼 통일
- `CHANGELOG.md` row 추가

## 결과

- **긍정**: 코드 영향이 큰 사이클(T-SIG·T-O·T-AI·T-MCP) 직전 단계에서 sub-agent 출력 품질 일관성 확보. 실계좌 자본 흐름 결합 코드 신뢰성 향상
- **부정**: 토큰 비용 ADR 0035 예측 대비 약 2배 (3 opus + 4 sonnet → 7 opus). 사용자가 명시적으로 수용 — "지금부터는 코드 한 개 수정도 신중해져야 함"
- **후속**:
  - 본 ADR 머지 후 즉시 sub-agent 디스패치 시 opus 적용 (frontmatter SoT)
  - 한 사이클(v3.6.12 또는 v3.7.0) 후 실측(token usage vs 재작업 회피) 기반 재검토 옵션 (사용자 명시 트리거 시)

## 사용자 명시 인용

> "에이전트 모델 snet 사용하던거 다 opus레벨로 높여줘 에이전트 정책 변경할께. 이유는 지금부터는 상당히 코드들이 한개 수정하더라도 신중해져야하니깐. 전체 모델 opus4.7로 진행해줘" (2026-05-18 사용자 채팅)

## 관련 결정

- ADR 0035 — 본 ADR이 supersede
- ADR 0036 — agent/* 브랜치 규약 (변동 없음)
- ADR 0077 — OPSP8996 deep dive (market-data sonnet 처리 시 디버깅 사이클 길어진 사례)
