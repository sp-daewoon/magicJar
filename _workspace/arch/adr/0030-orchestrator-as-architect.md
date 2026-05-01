# ADR 0030: 오케스트레이터 = architect 일체화 (메타 운영 규약)

- 상태: Accepted
- 작성일: 2026-05-01
- 결정자: architect (사용자 직접 진단)
- 관계: ADR 0013·0014·0016 운영 규약 통합

## 맥락

ADR 0013(단일 worktree·순차)·0014(모델 차등)·0016(QA On-Demand)이 각각 운영 패턴을 정해놨으나, **"메인 세션이 ADR을 어디까지 자율 해석하고 어디부터 architect sub-agent를 dispatch해야 하는가"** 가 명문화 안 됨. 그 결과 매 작업마다 architect dispatch → 토큰·시간 낭비, 또는 메인 세션이 ADR 무시하고 즉흥 결정 → 짜임새 깨짐, 양극단을 오감.

## 결정

**메인 세션(=오케스트레이터) = architect의 일상적 권한자.** ADR 신규 작성·개정·spec/plan 산출 같은 무거운 결정만 architect 에이전트 sub-agent로 dispatch. 나머지(ADR 참조·해석·일상 결정)는 메인 세션이 직접 수행.

### 자율 vs 컨펌

| 케이스 | 행동 |
|---|---|
| ADR 정합 + 기존 코드 패턴과 자연 | 메인 세션 자율 진행 |
| ADR 모호 / 신규 결정 필요 | 사용자 한 줄 컨펌 |
| 외부 자원·실 매매 도메인 침범 | 사용자 컨펌 + ADR 검토 |
| 시나리오 비결정적·플레이키 | 사용자 안내 후 진행 |

### architect sub-agent dispatch 조건

- ADR 신규 작성·개정
- spec 작성 (`docs/superpowers/specs/`)
- plan 작성 (`docs/superpowers/plans/`)
- Phase 단위 큰 결정 (예: MSA 재구성·CI/CD 도입)

이외에는 메인 세션 자율.

## 결과

- 긍정: 토큰·시간 절약, 일상 결정 빠름, ADR이 죽은 문서가 아니라 일상 참조 권위로 살아남
- 부정: ADR 해석 다툼 시 architect dispatch까지 단계 추가 — 기준 모호 시 사용자 컨펌으로 해소
- 후속: 5번 QA spec(2026-05-01) 첫 적용. 1~4 트랙 진입 시에도 동일 규약 적용.
