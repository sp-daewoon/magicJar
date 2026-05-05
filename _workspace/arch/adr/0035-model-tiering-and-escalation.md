# ADR 0035: 에이전트별 모델 차등 + 에스컬레이션

- 상태: Accepted
- 작성일: TBD
- 결정자: architect

## 맥락

Phase A 시점엔 모든 에이전트를 `model: opus`로 통일했다. 결과: **반복적·패턴화된 작업까지 비싼 모델**이 처리해 토큰 비용이 불필요하게 커졌다.

작업 성격별 추론 깊이 요구는 명확히 다르다:
- **깊은 추론 필요**: 시스템 설계, 프롬프트·LLM 출력 스키마, 지표 수학·DSL 평가기
- **패턴화·반복 작업**: Spring Boot 스캐폴딩, KIS API 클라이언트 구현, Thymeleaf UI, 체크리스트 검증

## 결정

**에이전트별 기본 모델을 작업 성격에 맞춰 차등 지정. 부족 시 1회 escalate.**

### 모델 매트릭스

| 에이전트 | 기본 모델 | 사유 |
|---------|----------|------|
| architect | **opus** | 시스템 설계·ADR·트레이드오프 판단 |
| ai-engineer | **opus** | 프롬프트 설계·구조화 출력·미묘한 판단 |
| strategy-engine | **opus** | 지표 수학·DSL 파서/평가기·백테스트 정밀도 |
| backend-core | **sonnet** | Spring Boot 스캐폴딩·도메인 모델은 ADR 따라가기 |
| market-data | **sonnet** | KIS API 클라이언트는 문서 따라가는 패턴 |
| frontend | **sonnet** | React·LWC·CSS는 반복적 |

> Haiku는 채택 안 함 — 절약보다 재작업 비용이 큼. Sonnet이 sweet spot.

### 에스컬레이션 규칙

```
[기본] 에이전트 정의의 model로 시작
   ├─ 결과 OK → 진행
   └─ 부족 (메인 세션 또는 사용자 판단)
        └─ 같은 에이전트, 모델만 임시 opus로 1회 재시도
              ├─ 통과 → 진행
              └─ 또 부족 → 작업 분할 또는 사용자 의사결정 요청 (무한 escalation 금지)
```

### 구현

- 각 `.claude/agents/<name>.md` frontmatter에 `model:` 필드 명시
- 메인 세션이 Agent 도구 호출 시 frontmatter `model` 사용 (기본값)
- escalation 시: 호출 측에서 `model: "opus"` 파라미터로 임시 override
- task 파일(`_workspace/tasks/<id>.md`) frontmatter의 `model:` 필드가 그 작업의 실제 사용 모델 기록

### 토큰 영향 (예측)

이전: 7명 모두 opus 평균 → 100% 기준
이후: 3 opus + 4 sonnet → 약 50~60% (작업 분포에 따라)
+ 단일 worktree(ADR 0013) 효과까지 합치면 **40~50% 수준**으로 절감 기대

## 결과

- 긍정: 대규모 토큰 절감 + 작업 성격에 맞춘 모델 선택의 명시화
- 부정: sonnet으로 처리한 작업 일부에서 품질 손실 가능 → escalation 규칙으로 보완
- 후속:
  - 에이전트 frontmatter 7개 일괄 갱신
  - 오케스트레이터 스킬에 escalation 절차 명시
  - 한 사이클 후 실측(token usage / re-run rate) 기반 모델 매트릭스 재검토
