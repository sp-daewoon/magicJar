# ADR 0053 — Living Policy Docs (현행 권위 분리)

> **Status**: Accepted (2026-05-08)
> **Deciders**: 사용자(최고 관리자), orchestrator
> **Related**: ADR-0050 (CHANGELOG 슬롯), ADR-0031 (release immutable), ADR-0036 (task.mjs)

## Context

v3.1.0 시점 ADR 52건 + backlog 23§ + CHANGELOG 시간역순 누적. 사용자 관점·LLM context 적재 관점에서 다음 결함 식별:

1. **현행 규약 lookup 비용 큼** — "지금 무엇이 권위인가" 답하려면 ADR 시계열 따라가야 함
2. **append-only가 진화하면서 backlog/CHANGELOG에 동일 정책이 흩어짐** — 사용자 보고: *"기존 규약을 다른 곳 백로그 같은 문서에 다 넣고 그랬는데 이렇게 바꿨다 이런식으로 정리해나가는 게 더 좋은 방법 아닌가"*
3. **LLM context 비효율** — 매 사이클 모든 자원 cache 적재되면 prompt 비대 + 토큰 낭비
4. **새 진입자 인지 비용** — 정책 권위 위치 분산되어 있음

## Decision

**append-only ADR 패턴은 결정 이력 위치로 유지**, 별도로 **현행 권위 정책 단일 위치(Living Policy Docs)** 도입.

### 1. 단일 진입점 — `_workspace/INDEX.md`

magicJar 모든 자원의 첫 번째 진입점. 의도별 진입 경로(현행 규약 / 결정 이력 / 진행 중 의제 / snapshot / 활성 task) 명시.

### 2. Living Policy 폴더 — `docs/architecture/policies/`

영역별 단일 권위 문서. 변경 시 본문 직접 update + ADR 후속 발행 + cross-ref 갱신.

v3.1.0 1차 도입 5건:
- `kis-credential-identity.md` (REAL/MOCK 식별 규약)
- `logging-levels.md` (모듈별 로그 정책)
- `kis-first-raw-fetch.md` (KIS 자체 추론 금지)
- `task-cycle.md` (task.mjs 4단계 + 머지 후 정리)
- `mermaid-conventions.md` (mermaid 10 호환 4 룰)

각 문서 표준 구조:
- 머리 — `> 권위`, `> 결정 이력 ADR-X / backlog §N / PR #N`, `> 마지막 갱신`
- 본문 — 현행 규약(단답·표·코드)
- 적용 위치 — 코드 path
- 변경 시 의무
- 변경 이력 footer (한 줄씩)

### 3. backlog 처리 완료 §은 자동 archive

`✅ 처리 완료` 마킹 후 minor release(v3.X.0→v3.Y.0)마다 `_workspace/backlog/done/v3.X.x.md`로 이동. 본 backlog는 진행 중만 유지.

### 4. ADR 인덱스 갱신 의무

새 ADR 발행 시 `_workspace/arch/adr/README.md` + 관련 living policy `결정 이력` ref 갱신.

### 5. Cross-Reference 표준 형식

| 형식 | 예 |
|------|-----|
| ADR | `ADR-0046` |
| backlog | `backlog §23` |
| PR | `PR #246` |
| 코드 | `consumer/.../KisApprovalAdapter.kt:124` |
| policy | `policy: kis-credential-identity` |

## Consequences

### 긍정

1. **사용자·LLM 모두 단일 진입점** — `_workspace/INDEX.md` 한 곳
2. **현행 규약 lookup O(1)** — policies/ 폴더 직접 참조
3. **토큰 효율** — 매 사이클 living docs(작음) + ADR lazy load. ADR 200건 누적 시 더 큰 효과
4. **변경 시 정합성 의무 명시** — 본문 + ADR + backlog close + ref 4축 갱신
5. **새 진입자 인지 비용 ↓** — "어디 보면 되는가" 명확

### 부정·트레이드오프

1. **갱신 비용 분산** — 변경 시 정책 본문 + ADR + ref 둘 다 update 필요
2. **2차 도입 일감** — 시간 흐름에 따라 추가 정책 분리(W3·W4 진입 시) 누적
3. **inconsistent risk** — 본문과 ADR이 어긋나면 정합 깨짐. INDEX cross-ref가 검증 기준

### 점진 도입

v3.1.0에서 1차 5건만 분리. 큰 재정렬 X — 사용자 우려("틀이 더 깨질 수 있으니") 정합. 다음 사이클부터 새 정책 추가/W2 진입 시 점진 확장.

## Alternatives Considered

### A. 큰 재정렬 (52 ADR + 23 backlog 일괄 living docs로 분해)

**기각**: 비용 매우 큼(수일 단위). v3.1.0 hotfix·dogfooding 우선. 사용자 직접 우려 명시.

### B. 그대로 유지 (append-only)

**기각**: 사용자 의도 정합 X. LLM 토큰 효율 + 사용자 인지 비용 감소 의제 해결 안 됨.

### C. ADR 본문 자체를 living으로 (Markdown frontmatter `status: active|superseded`)

**기각**: ADR의 immutable 이력 가치 훼손. 기존 ADR 다수 superseded 처리 작업 큼.

### D. 백로그 본문에 모든 현행 규약 누적

**기각**: backlog 본질이 "진행 중 의제"인데 영구 권위 문서 역할까지 짊어지면 비대화.

## Verification

본 ADR 머지 시 다음 자산 동시 생성 (PR #248):
- [x] `_workspace/INDEX.md` (단일 진입점)
- [x] `docs/architecture/policies/README.md` (정책 인덱스)
- [x] `docs/architecture/policies/kis-credential-identity.md`
- [x] `docs/architecture/policies/logging-levels.md`
- [x] `docs/architecture/policies/kis-first-raw-fetch.md`
- [x] `docs/architecture/policies/task-cycle.md`
- [x] `docs/architecture/policies/mermaid-conventions.md`
- [x] CLAUDE.md SoT 표 갱신 (INDEX.md + policies/ 추가)

## References

- 사용자 본 사이클 보고: *"한 곳만 보면 되는구나 하고 바로 찾아갈 수 있는 인덱싱이 잘 되어야"*
- ADR-0050 — CHANGELOG 슬롯 채번룰 (parallel sub-agent 충돌 회피)
- ADR-0031 — release/v* immutable (baseline 폴더 보존 정책 정합)
- backlog §23 — 첫 번째 living policy 분리 사례 (KIS credential)
