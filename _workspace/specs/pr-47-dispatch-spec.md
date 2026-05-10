# PR-47 dispatch spec — sub-agent 영역 LOCK + 회신 보고 의무

> **상태**: dispatch LOCK (architect 발행 — PR-47)
> **작성일**: 2026-05-11
> **연관 spec/plan**: `pr-47-w3-t-bt-design.md` · `pr-47-w3-t-bt-plan.md` · `pr-47-conflict-matrix.md`
> **연관 ADR**: 0034(머지 정책) · 0036(author email) · 0050(CHANGELOG 슬롯)

---

## §1. sub-agent 영역 LOCK 분배표

본 PR-47 사이클 채택 sub-agent: **backend-core 1건 (단독)**. plan §2-3 시나리오 A 채택 정합.

| sub-agent | PR # | 영역 | 분량 | LOCK 파일 (modify-shared) | add-only 영역 |
|---|---|---|---|---|---|
| **architect** | PR-47 | docs / spec / ADR / CHANGELOG slot | ~1150 LOC | `_workspace/arch/adr/README.md` (Edit) · `docs/architecture/master-architecture.md` (Edit §10 인덱스 + §12 후속 예정 시프트) · `CHANGELOG.md` (Edit prepend slot) | `_workspace/specs/pr-47-*.md` 4건 · `_workspace/arch/adr/0056-backtest-engine-architecture.md` 1건 |
| **backend-core** | PR-48 | domain backtest + api adapter + tests | ~1500 LOC code | (없음 — add-only 100%) | `domain/.../backtest/*` 11 파일 · `api/.../controller/BacktestController.kt` · `api/.../service/backtest/*` 7 파일 · `api/src/test/.../*BacktestTest.kt` 7 파일 |

### 1-1. backend-core 영역 LOCK 세부

**ADD-ONLY 영역 (수정 시 architect 재발행 사이클 필요 — 본 PR에서 LOCK)**:
- `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/backtest/` — 신규 디렉토리, 11 파일 add-only
- `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/BacktestController.kt` — 신규 컨트롤러
- `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/backtest/` — 신규 디렉토리, 7 파일

**MODIFY-SHARED 영역 (충돌 행렬 §1 정합)**:
- `domain/build.gradle.kts` — 변경 0건 (backtest 도메인은 순수 Kotlin sealed/data만 — 의존 추가 0)
- `api/build.gradle.kts` — 변경 0건 (BacktestController는 기존 spring-boot-starter-web + jpa + spring-ai-anthropic 등 의존만 사용)
- `CHANGELOG.md` — backend-core slot anchor (`<!-- pr-47-slot-backend-core -->`) 내부 row 1건 in-place edit만 허용 (다른 row 수정 금지)

**금지 영역 (backend-core 영역 밖)**:
- `consumer/.../strategy/JvmStrategyEvaluator.kt` 수정 금지 — strategy-engine 영역. 단 호출은 자유 (이미 public API)
- `frontend/*` 수정 금지 — backlog 합류
- `_workspace/arch/adr/*` 수정 금지 — architect 단독 LOCK
- `docs/architecture/master-architecture.md` 수정 금지 — architect 단독 LOCK

---

## §2. 머지 정책

| 항목 | 값 |
|---|---|
| 머지 방식 | `--rebase` (ADR 0034 LOCK — squash 금지, author 보존) |
| Base branch | `main` |
| Branch (architect) | `agent/architect/pr-47-w3-t-bt` |
| Branch (backend-core) | `agent/backend-core/pr-48-w3-t-bt-engine` |
| Author email (architect) | `bot+architect@magicjar.local` (`magicjar-architect` user.name) |
| Author email (backend-core) | `bot+backend-core@magicjar.local` (`magicjar-backend-core` user.name) |
| Commit signing | husky pre-commit author whitelist 통과 의무 (ADR 0032) |
| pre-push hook | `./gradlew check` 자동 실행 — 본인 모듈 pass 의무 |
| CI 트리거 | release/v*·v*.*.* 한정 (ADR 0049) — main PR push 시 GH Actions 0건 정상 |

---

## §3. 인터페이스 동결 시점

### 3-1. PR-47 머지 시점 = 동결

본 PR-47 (architect) 머지 시점에 다음 LOCK 동결 — 이후 sub-agent PR-48 작업 중 변경 금지:

1. **도메인 모델 5건** (`pr-47-w3-t-bt-design.md` §4):
   - `BacktestRequest` (data class + 의존 enum/sealed)
   - `BacktestResult` (sealed Success · Aborted)
   - `BacktestPosition`
   - `BacktestTrade` (+ TradeSide enum)
   - `BacktestPerformance`

2. **Port 4건** (`pr-47-w3-t-bt-design.md` §4):
   - `BacktestEngine`
   - `BacktestTaxModel`
   - `BacktestCommissionModel`
   - `BacktestSlippageModel`

3. **REST endpoint 동결** (`pr-47-w3-t-bt-design.md` §5):
   - `POST /api/backtest/run` 요청·응답 DTO 시그니처
   - Swagger 4단 어노테이션 룰 (한국어 + example 의무)

4. **세금·수수료·슬리피지 default 값** (`pr-47-w3-t-bt-design.md` §3):
   - 거래세 0.18% · 농특세 0.15% · VAT 10%
   - KIS 다이렉트 위탁 수수료 0.015% · 유관기관 0.0036%
   - 고정 슬리피지 0.05%

5. **ADR 0056 결정** (`pr-47-w3-t-bt-design.md` §11-2):
   - BacktestEngine 아키텍처 채택안
   - 룩어헤드 가드 메커니즘
   - timeout 1000ms + abort quota 50

### 3-2. 변경 절차

LOCK 변경 발생 시 backend-core sub-agent는 즉시 fail-fast — architect에게 재발행 요청. 임의 변경 금지.

---

## §4. KIS-first 의무 전파

### 4-1. backend-core PR-48 의무

KIS-first 정책(헌법 §3 + 사용자 메모리 `feedback_kis_first_policy.md`) 의무:

1. **세금 모델 raw 출처 인용**:
   - `KoreanStockTaxModel.kt` KDoc 헤더에 raw 출처 의무:
     ```kotlin
     /**
      * 한국 주식 세금 모델 — ADR 0056 §결정 정합.
      *
      * raw 출처:
      * - 증권거래세 0.18%: 국세청 · KRX https://www.krx.co.kr (거래수수료·세금 안내)
      * - 농어촌특별세 0.15% (KOSPI 한정): 국세청 https://www.nts.go.kr
      * - VAT 10%: 부가가치세법 (국세청)
      *
      * 자체 추론 0건 — 모든 세율은 위 raw 인용.
      */
     ```

2. **수수료 모델 raw 출처 인용**:
   - `KisDirectCommissionModel.kt` KDoc:
     ```kotlin
     /**
      * KIS 다이렉트 위탁 수수료 모델 — ADR 0056 §결정 정합.
      *
      * raw 출처:
      * - 위탁 수수료 0.015%: KIS https://securities.koreainvestment.com (수수료 안내)
      * - 유관기관 수수료 0.0036%: KRX · KSD · 증선위 합산
      */
     ```

3. **슬리피지 모델 — magicJar own 명시**:
   - `FixedRateSlippageModel.kt` KDoc:
     ```kotlin
     /**
      * 고정 슬리피지 모델 — magicJar own (KIS raw 부재 — 자체 결정).
      *
      * 사유: 한국 주식 시장 분봉 평균 spread + 1tick(0.1~0.5%)의 보수적 절반.
      * 후속 ADR 0080+에서 거래량 기반 동적 모델 검토.
      */
     ```

### 4-2. 자체 추론 0건 의무

backend-core sub-agent는 raw 부재 영역에서 자체 추론 발견 시 즉시 fail-fast 보고 — architect에 후속 결정 요청. **위반 시 사용자 비판 risk + fitness gap 누적**.

---

## §5. fitness gap 의무

### 5-1. backend-core PR-48 PR 본문 의무 섹션

PR 본문에 다음 섹션 의무:

```markdown
## fitness gap 검증

- [ ] v1/v2 잔재 0건 검증: `find . -name "*Backtest*"` 사전 결과 0건 → 본 PR은 v3 신규
- [ ] AI 매매 결정 0건: LLM provider import 0건 (룰 5 정합) — `grep -r "anthropic\|openai\|spring-ai" api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/backtest/` 0건
- [ ] 자동매매 적합성: 사용자가 실전 매매 활성화 전 백테스트 통과 의무 (룰 5 정합)
- [ ] 모듈 경계: domain backtest 도메인 모델은 Spring·JPA·Kafka import 0건 (헥사고날 정합)
- [ ] KIS-first 정합: 세금·수수료 raw 출처 KDoc 인용 의무 (§4-1)
- [ ] ADR 0047 정합: BacktestEngine은 반드시 strategy-eval-pool 경유 `evaluateBacktest()` 호출 — 직접 evaluator walkRoot 호출 0건
- [ ] mock 데이터 0건: candle 0건 시 503 + "데이터 없음" 명시 (사용자 메모리 `feedback_no_mock_data.md`)
```

### 5-2. 사용자 메모리 정합 의무

- `feedback_no_mock_data.md` — candle 부족·corp_action 부족 시 mock 합성 금지, "데이터 없음" 명시 503
- `feedback_swagger_pattern.md` — Swagger 4단 + 한국어 + example
- `feedback_kis_first_policy.md` — 세금·수수료 raw 출처 의무
- `feedback_honorific.md` — KDoc·로그 메시지 존댓말
- `feedback_conflict_resolution_autonomy.md` — git 충돌·ktlint·컴파일 에러 자율 처리 의무

---

## §6. 회신 보고 의무

### 6-1. backend-core PR-48 회신 보고

PR 본문 + 채팅 회신에 다음 의무:

1. **변경 파일 + LOC** 리스트
2. **테스트 결과** — `./gradlew :domain:check :api:check` GREEN 검증
3. **CHANGELOG slot row** — `<!-- pr-47-slot-backend-core -->` anchor 내부 in-place edit 1건만
4. **KIS-first raw 출처 인용 위치** — 각 모델 클래스 KDoc 라인 인용
5. **fitness gap 검증 7건 GREEN** 표
6. **모듈 경계 검증** — `domain/backtest/*` Spring·JPA·Kafka import 0건 (`grep` 결과 인용)
7. **ADR 0047 정합 검증** — strategy-eval-pool 경유 호출 라인 인용
8. **룰 5 정합** — LLM provider import 0건 검증 (`grep` 결과 인용)

### 6-2. architect 회신 보고

본 디스패치 사이클 종료 시 사용자 회신:
- 저장한 파일 절대 경로 5건 + CHANGELOG 갱신 위치
- ADR 0056 발행 결정 + 사유
- 시나리오 채택 (시나리오 A 단일 PR-48 backend-core)
- 머지 동시성 룰 결론 (직렬, 빌드 그래프 변경 0)
- LOC 추정 (architect ~1150 docs + backend-core ~1500 code)
- orchestrator 대행 메타 — branch 이름, commit 메시지 초안
