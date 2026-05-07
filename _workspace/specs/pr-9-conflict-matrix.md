# PR-9 T-W2 충돌 행렬 (병렬 디스패치 결정 근거)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 정합 SoT: `_workspace/specs/pr-9-w2-design.md` v1 + `_workspace/specs/pr-9-dispatch-spec.md` v1 + ADR 0048(본 PR 발행)·0050
- 사용자 메모리 정합:
  - `feedback_orchestrator_active_parallel.md` — **architect "직렬 권장" 답습 금지**. add-only 충돌은 진짜 병렬 가능. modify-shared만 식별 후 진짜 병렬 가능 여부 검토
  - `feedback_3tier_hierarchy.md` — 3단 hierarchy: 오케스트라(지휘) → architect(설계) → 실무 sub-agents (병렬 실행)
  - `feedback_autonomy.md` — Claude 자율 실행
  - `feedback_kis_first_policy.md` — KIS WS 메시지·endpoint는 open-trading-api 출처 의무

---

## §1. 결론 (한 줄)

> **PR-9a (backend-core) + PR-9b (market-data) 완전 병렬 권장 — 양쪽 모두 consumer 모듈 변경이지만 디렉토리·클래스·책임이 완전 분리(backend-core = `kafka/·ingestion/·infrastructure/{cache,yahoo,session}/·persistence/`, market-data = `ws/·infrastructure/kis/{Approval,Price}/·market/·config/`). modify-shared 후보 3건(application.yml · CHANGELOG · domain port `TickEvent`/`KisRestPort` 확장)은 모두 사전 정책으로 충돌 0 보장. CHANGELOG는 ADR 0050 슬롯 채번룰 첫 적용으로 in-place edit, application.yml은 PR-9a 단독 격리, domain port는 dispatch §4-1 LOCK으로 누가 만들지 사전 결정.**

---

## §2. 인터페이스 동결 시점

본 spec 4종(`pr-9-w2-design.md` + `pr-9-dispatch-spec.md` + 본 문서 + ADR 0048 본문)이 PR로 머지되는 순간 = 인터페이스 동결.

동결되는 계약:
- `pr-9-w2-design.md` §3 (구독·polling 책임 경계 LOCK)
- `pr-9-w2-design.md` §4 (watchlist.changed.v1 흐름 LOCK)
- `pr-9-w2-design.md` §5 (Yahoo cold-start 매트릭스 LOCK)
- `pr-9-w2-design.md` §6 (priority eviction 정책 LOCK)
- `pr-9-w2-design.md` §7 (데이터 흐름 + Redis 키 + Kafka topic LOCK)
- `pr-9-w2-design.md` §8 (MarketSession 1차 stub LOCK)
- `pr-9-dispatch-spec.md` §4-1 (domain port 시그니처 LOCK)
- `pr-9-dispatch-spec.md` §4-2 (Kafka topic 시그니처 LOCK)
- `pr-9-dispatch-spec.md` §4-3 (Redis 키 매트릭스 LOCK)
- `pr-9-dispatch-spec.md` §4-4 (KIS WS subscribe/unsubscribe 메시지 + PIPE 구분자 형식 LOCK)
- `pr-9-dispatch-spec.md` §5 (CHANGELOG 슬롯 row 위치·형식 LOCK)
- ADR 0048 §2-1~§2-7 (slot 분배·priority·polling·shutdown·session·watchlist·Yahoo)

**인터페이스 동결 즉시** backend-core·market-data 양쪽이 spec만 보고 동시에 자기 worktree에서 작업 시작 가능.

---

## §3. 파일 단위 충돌 행렬

### §3-1. PR-9a (backend-core) 변경 파일 vs PR-9b (market-data) 변경 파일

| 파일/디렉토리 | PR-9a | PR-9b | 충돌 유형 | 결정 |
|---|---|---|---|---|
| `domain/.../market/port/SubscriptionPolicy.kt` | **신규** | 무관 | add-only | **격리** |
| `domain/.../market/port/YahooCandleFetcher.kt` | **신규** | 무관 | add-only | **격리** |
| `domain/.../market/port/SourceTracker.kt` | **신규** | 무관 | add-only | **격리** |
| `domain/.../market/port/CandleRepository.kt` | **신규** (없을 시) | 무관 | add-only | **격리** |
| `domain/.../market/TickEvent.kt` | 무관 (import만) | **신규** | add-only | **격리** (dispatch §4-1 — 권장: market-data가 만든다) |
| `domain/.../market/port/KisRestPort.kt` | 무관 (사용만) | **변경** (`fetchPrice` method 추가) | modify | **격리** (PR-9b 단독 변경 — backend-core는 호출만) |
| `api/.../persistence/CandleJpaEntity.kt` | **신규** | 무관 | add-only | **격리** |
| `api/.../persistence/CandleJpaRepository.kt` | **신규** | 무관 | add-only | **격리** |
| `api/.../persistence/CandleRepositoryAdapter.kt` | **신규** | 무관 | add-only | **격리** |
| `api/.../service/WatchlistService.kt` | (선택) **변경** 1줄 (default priority 0→100) | 무관 | modify | **격리** (PR-9a 단독, 선택) |
| `consumer/.../persistence/ConsumerCandleJpaEntity.kt` (consumer 자체) | **신규** | 무관 | add-only | **격리** |
| `consumer/.../persistence/ConsumerCandleJpaRepository.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/.../persistence/ConsumerCandleRepositoryAdapter.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/.../kafka/WatchlistChangedKafkaListener.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/.../kafka/WatchlistChangedKafkaConfig.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/.../ingestion/SubscriptionRouter.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/.../ingestion/WatchlistIngestionWorker.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/.../ingestion/WatchlistPollingWorker.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/.../ingestion/ColdStartExecutor.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/.../infrastructure/yahoo/YahooCandleFetcher.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/.../infrastructure/kis/KisRestCandleAdapter.kt` (1wk·1mo·1y) | **신규** | 무관 | add-only | **격리** |
| `consumer/.../infrastructure/kis/KisRestPriceAdapter.kt` (FHKST01010100 polling) | 무관 (사용만) | **신규** | add-only | **격리** |
| `consumer/.../infrastructure/kis/KisApprovalAdapter.kt` (approval_key) | 무관 | **신규** | add-only | **격리** |
| `consumer/.../infrastructure/cache/RedisQuoteCacheAdapter.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/.../infrastructure/cache/RedisSourceTrackerAdapter.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/.../infrastructure/session/SimpleMarketSessionResolver.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/.../ws/KisWebSocketBridge.kt` | 무관 | **신규** | add-only | **격리** |
| `consumer/.../ws/KisWebSocketSession.kt` | 무관 | **신규** | add-only | **격리** |
| `consumer/.../ws/KisWsMessageParser.kt` | 무관 | **신규** | add-only | **격리** |
| `consumer/.../market/TickToMinuteAggregator.kt` | 무관 | **신규** | add-only | **격리** |
| `consumer/.../config/KafkaTopicConfig.kt` | 무관 | **신규** | add-only | **격리** |
| `consumer/src/main/resources/application.yml` | **변경** (Kafka consumer + producer config) | **변경 0 권장** (PR-9a가 모두 처리) | **modify-shared 후보 1** | **격리 권고** — PR-9a 단독 변경. dispatch §3-2-G에 명시 |
| `consumer/src/test/kotlin/.../WatchlistChangedKafkaListenerTest.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/src/test/kotlin/.../SubscriptionRouterTest.kt` | **신규** | 무관 | add-only | **격리** |
| `consumer/src/test/kotlin/.../KisWebSocketBridgeTest.kt` | 무관 | **신규** | add-only | **격리** |
| `consumer/src/test/kotlin/.../KisWsMessageParserTest.kt` | 무관 | **신규** (또는 PollingWorkerTest 분담) | add-only | **격리** |
| `api/**` (controller·service·persistence 외) | **변경 0 권고** (CandleJpa·WatchlistService 외) | **변경 0** | (변경 0) | **격리** |
| `batch/**` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `frontend/**` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `docker/docker-compose*.yml` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `scripts/build-and-deploy.sh` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `.github/workflows/*` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `.env.example` | **변경 0** | **변경 0** | (변경 0) | **격리** |
| `CHANGELOG.md` | **변경** (PR-9a slot row in-place edit — 1줄) | **변경** (PR-9b slot row in-place edit — 1줄) | **modify-shared 후보 2** | **ADR 0050 슬롯 채번룰 첫 적용 — 충돌 0 보장** (in-place edit이 git diff로 다른 line 변경으로 인식) |
| `docs/architecture/*` | **변경 0** | **변경 0** | (변경 0) | **격리** (architect 단독) |
| `_workspace/arch/adr/*` | **변경 0** | **변경 0** | (변경 0) | **격리** (architect 단독) |
| `_workspace/specs/pr-9-*.md` | **변경 0** | **변경 0** | (변경 0) | **격리** (architect 단독) |
| `_workspace/tasks/T-*-pr-9-w2-backend.md` | **신규** (자기 task) | 무관 | add-only | **격리** |
| `_workspace/tasks/T-*-pr-9-w2-market-data.md` | 무관 | **신규** (자기 task) | add-only | **격리** |
| `README.md` (루트) | **변경 0** | **변경 0** | (변경 0) | **격리** |

### §3-2. modify-shared 후보 분석

총 3건 식별:

#### (1) `consumer/src/main/resources/application.yml`

- 변경 내용 — Kafka consumer config + producer config + listener config (§3-1 PR-9a 단독)
- **격리 권고**: dispatch §3-2-G에 PR-9a 단독 변경으로 명시. PR-9b는 application.yml 0 변경 (Kafka topic 자동 생성은 `KafkaTopicConfig.kt` Bean으로 처리 — yml 변경 없이)
- **두 sub-agent에게 dispatch 시 명시**: PR-9b는 application.yml 손대지 않음
- **검증**: PR-9b의 PR diff에 application.yml 0 변경 확인 (orchestrator)

#### (2) `CHANGELOG.md`

- 변경 내용 — 양쪽 sub-agent가 본인 슬롯 row 1줄 in-place edit (ADR 0050 슬롯 채번룰)
- **충돌 0 보장**: ADR 0050 §3-2 — git diff가 다른 row 변경으로 인식 → 양쪽 머지 시 conflict marker 발생 없음
- **본 PR-9가 ADR 0050 첫 적용 사이클** — 사용자가 처음 관찰 가능. 검증 의무 — 두 PR 모두 머지 후 CHANGELOG가 정합 상태(본인 row 채워짐, 다른 row 미수정)인지 확인
- 일탈 시 fallback (sub-agent가 추가 row 발생) — ADR 0050 §2-4 add-only insert + 자체 rebase 해결

#### (3) `domain/.../market/TickEvent.kt` + `domain/.../market/port/KisRestPort.kt`

- 변경 내용 — `TickEvent` 신규 + `KisRestPort.fetchPrice(code): TickEvent` method 추가
- **dispatch §4-1 LOCK** — 누가 만드는지 사전 결정
  - `TickEvent` → **PR-9b market-data가 만든다** (KIS WS 메시지 변환 책임 + market-data 도메인)
  - `KisRestPort.fetchPrice` → **PR-9b market-data가 추가** (PR-9a는 import 또는 호출만 — `WatchlistPollingWorker`에서)
- **격리 보장**: PR-9b 단독 변경. PR-9a는 도메인 port import (read) — git이 import 라인 add-only로만 인식 → 충돌 0
- **단 위험**: PR-9a와 PR-9b가 동시 push 시 PR-9a가 import 추가 + PR-9b가 동시 add 시 — git이 같은 파일 라인 추가로 인식 (두 sub-agent 모두 import statement 추가 — 다른 위치)
- **결론**: TickEvent를 PR-9b가 commit 1번에 만들고 머지 우선순위 1 (먼저 머지). PR-9a는 그 후 main rebase하여 import. 시나리오 C 권장 (병렬 작업 + market-data 머지 우선).

### §3-3. 격리 검증 — backend-core·market-data가 같은 디렉토리에 손대는 경우

**같은 디렉토리에서 다른 파일 작업** (모듈 분리는 아니지만 책임 분리):

| 디렉토리 | PR-9a 파일 | PR-9b 파일 | 충돌 유형 |
|---|---|---|---|
| `consumer/.../infrastructure/kis/` | `KisRestCandleAdapter.kt`(신규 — 1wk·1mo·1y backfill) | `KisRestPriceAdapter.kt`(신규 — 5s polling), `KisApprovalAdapter.kt`(신규) | **다른 파일** — git 충돌 0 |
| `consumer/.../persistence/` | `ConsumerCandleJpaEntity.kt`·`Repository.kt`·`Adapter.kt`(신규) | (변경 0) | 격리 |
| `consumer/.../infrastructure/cache/` | `RedisQuoteCacheAdapter.kt`·`RedisSourceTrackerAdapter.kt`(신규) | (변경 0) | 격리 |
| `consumer/.../ws/` | (변경 0) | `KisWebSocketBridge.kt`·`KisWebSocketSession.kt`·`KisWsMessageParser.kt`(신규) | 격리 |
| `consumer/.../market/` | (변경 0) | `TickToMinuteAggregator.kt`(신규) | 격리 |
| `consumer/.../ingestion/` | `SubscriptionRouter.kt`·`WatchlistIngestionWorker.kt`·`WatchlistPollingWorker.kt`·`ColdStartExecutor.kt`(신규) | (변경 0) | 격리 |
| `consumer/.../kafka/` | `WatchlistChangedKafkaListener.kt`·`WatchlistChangedKafkaConfig.kt`(신규) | (변경 0) | 격리 |
| `consumer/.../config/` | (선택) | `KafkaTopicConfig.kt`(신규) | 격리 |

**핵심**: `consumer/.../infrastructure/kis/`만 양쪽이 동시 신규 파일 추가 — 같은 디렉토리 다른 파일 → git 충돌 0.

---

## §4. pre-push hook 종속성 분석

| 이유 | 분석 |
|---|---|
| `./gradlew check` (pre-push 자동) | 양쪽 PR 모두 main 위에서 GREEN 가능 — 모듈 골격 + 도메인 port 정합 정상 |
| PR-9b가 main 위에서 import할 때 PR-9a 미머지 | domain port `SubscriptionPolicy`·`YahooCandleFetcher` 미존재 — PR-9b는 그것 import 0 (자기 영역 ws + kis polling adapter만). pre-push GREEN |
| PR-9a가 main 위에서 import할 때 PR-9b 미머지 | domain `TickEvent`·`KisRestPort.fetchPrice` 미존재 — `WatchlistPollingWorker.fetchPrice(code)` 컴파일 에러. **결론**: PR-9b가 먼저 머지하거나 PR-9a가 stub TickEvent를 자기 PR에 임시 추가 — 권장 시나리오 C: PR-9b 도메인 port 머지 우선 + PR-9a 합류 |
| consumer 모듈 본격 도입 — Spring Reactive WebSocket 의존 | `consumer/build.gradle.kts`에 `spring-boot-starter-webflux` 추가 의무 — PR-9b 단독 변경. PR-9a는 build.gradle 0 변경 |

**결론**: pre-push hook은 PR-9b 머지 우선 + PR-9a rebase 후 머지가 권장. **단 PR-9a가 처음에 stub `TickEvent`·`fetchPrice` placeholder 추가하면 PR-9b 미완 상태에서도 빌드 GREEN — 양쪽 동시 머지 가능**.

**1차 권장**: PR-9b 도메인 port 1 commit 머지 우선 (시간 ~30분) → 양쪽 합류.

---

## §5. 인터페이스 동결로 인한 병렬 가능 메커니즘

backend-core·market-data가 동시 작업하더라도 다음이 양쪽이 같은 spec을 바라보고 진행되도록 보장:

### §5-1. domain port 시그니처 LOCK (dispatch §4-1)

```
KisWsPort         (PR-5a 기존 — 변경 0)
KisRestPort       (PR-9b 확장 — fetchPrice method 추가)
SubscriptionPolicy (PR-9a 신규 add-only)
YahooCandleFetcher (PR-9a 신규 add-only)
SourceTracker      (PR-9a 신규 add-only)
CandleRepository   (PR-9a 신규 — PR-5a 부재 시)
QuoteCachePort     (PR-5a 기존 — 변경 0)
TickEvent          (PR-9b 신규 add-only)
MarketSessionResolver (PR-5a 기존 — 변경 0)
```

**누가 만드는지 사전 결정** — dispatch §4-1 명시. backend-core·market-data 동시 진행 가능.

### §5-2. Kafka topic 시그니처 LOCK (dispatch §4-2)

```
market.tick.v1                — PR-9b WS handler write + PR-9a polling worker write (둘 다 동일 schema)
market.candle.minute.closed.v1 — PR-9b TickToMinuteAggregator write
watchlist.changed.v1           — PR-9a Kafka listener consume (PR-7 producer)
```

### §5-3. Redis 키 매트릭스 LOCK (dispatch §4-3)

```
quote:last:{code}              — PR-9b WS write + PR-9a polling write (동일 schema JSON)
candle:current:{code}:1m       — PR-9b WS write
candle:today:{code}:1d         — PR-9b WS write
watchlist:source:{userId}      — PR-9a SubscriptionRouter write
kis:approval:{userId}          — PR-9b approval adapter write
kis:approval:lock:{userId}     — PR-9b approval adapter SETNX
kis:token:{userId}:{type}      — PR-7 KisAuthAdapter (재사용)
```

### §5-4. KIS WS 메시지 형식 LOCK (dispatch §4-4)

KIS open-trading-api `examples_llm/.../websocket_*.py` 출처 — PIPE 구분자 + tr_id `H0STCNT0`/`H0STASP0`/`H0STCNI0` + subscribe/unsubscribe JSON 메시지 형식 사전 LOCK. backend-core가 PIPE 파서 직접 짤 필요 0 (PR-9b 책임).

---

## §6. 병렬 가능한 시나리오별 검증

### §6-1. 시나리오 A — PR-9a·PR-9b 동시 시작 + 동시 머지

```
T0: spec 4종 머지 (architect)
T0+5분: backend-core 작업 시작 (worktree A)
T0+5분: market-data 작업 시작 (worktree B)
T+4시간: backend-core PR-9a 푸시 → CI green → 사용자 머지
T+4.5시간: market-data PR-9b 푸시 → CI green → 사용자 머지
```

**충돌 가능성**:
- CHANGELOG.md (ADR 0050 in-place edit) — 충돌 0
- application.yml — 격리 권고 (PR-9a 단독)
- domain `TickEvent`·`KisRestPort` — backend-core 임시 stub 또는 PR-9b 머지 우선 (시나리오 C)
- 그 외 — 모두 격리

**검증**:
- backend-core 머지 후 main에 ingestion·kafka·infrastructure 영역 모두 존재
- market-data rebase → main의 backend 변경에 영향 0 (디렉토리 분리)
- market-data 머지 후 통합 검증 (DoD #1~#20) 가능

### §6-2. 시나리오 B — market-data 먼저 머지 (backend 미완)

```
T0: spec 머지
T+2시간: market-data PR-9b 푸시 (도메인 port `TickEvent`·`KisRestPort.fetchPrice` 포함) → 머지
T+4시간: backend-core PR-9a 푸시 → 머지
```

**검증**: market-data만 있으면 사용자 흐름 부동작 (Watchlist 등록 → Kafka listener 부재 → 시세 수집 X). 단 빌드는 GREEN. 사용자 검증은 PR-9a 머지 후.

### §6-3. 시나리오 C — market-data 도메인 port 먼저 + 병렬 + market-data 본체 늦게 (권장)

```
T0: spec 머지
T0+5분: market-data가 우선 commit 1건 (TickEvent + KisRestPort.fetchPrice domain 변경) push → 빠른 머지 (~30분)
T+30분: backend-core·market-data 양쪽 합류 시작
T+4시간: 양쪽 PR push → 머지
```

**왜 권장**:
- backend-core가 `WatchlistPollingWorker.fetchPrice` 호출 시 `TickEvent`·`fetchPrice` 즉시 사용 가능 — stub 불요
- 시간 단축 — 양쪽이 동일 시간 작업

### §6-4. 시나리오 D — 직렬 (권장하지 않음)

```
T0: spec 머지
T0+5분: backend-core 시작
T+4시간: backend-core 머지 (TickEvent·fetchPrice를 backend-core가 만든다)
T+4시간 5분: market-data 시작 (TickEvent·fetchPrice 그대로 사용)
T+8시간: market-data 머지
```

**왜 권장하지 않는가**:
- 사용자 메모리 `feedback_orchestrator_active_parallel.md` — "직렬 권장 답습 금지"
- 인터페이스 동결로 양쪽이 즉시 시작 가능
- 직렬은 총 작업 시간 1.5배 (~8시간 vs 병렬 ~4.5시간)
- backend-core가 market-data 영역 코드(TickEvent — KIS WS 메시지 도메인 모델) 만드는 것은 책임 분리 위반

---

## §7. 사용자 메모리 정합 검증 (필수)

| 사용자 메모리 | 본 결정 정합 |
|---|---|
| `feedback_orchestrator_active_parallel.md` — architect "직렬 권장" 답습 금지 + add-only 충돌은 진짜 병렬 | ✅ — 본 행렬에서 modify-shared 3건(application.yml·CHANGELOG·domain port) 모두 격리 권고 + ADR 0050 슬롯 + 사전 LOCK으로 해소 + 병렬 권장 결론 |
| `feedback_3tier_hierarchy.md` — 오케스트라 → architect(설계) → 실무 sub-agents(병렬 실행) | ✅ — architect는 본 spec 4종 + ADR 0048 본문만 발행, 코드 0건. backend-core·market-data 병렬 코드 작성 |
| `feedback_autonomy.md` — Claude 자율 실행, 사용자 확인은 아키텍처·외부자원·블로킹상충 3가지만 | ✅ — 본 spec에 사용자 결정 필요 사항 0건 (모두 ADR 0028·0048·0040·0042·0014 정합 + 자율 결정 사유 §10 명시) |
| `feedback_v3_skepticism_no_regression.md` — 자동매매 적합성 의심 lens | 자동매매 적합성: ✅ T-W2는 magicJar 자동매매 본체 시세 인입 파이프라인 — 모든 시그널·룰·차트의 prerequisite (사용자 비전 ADR 0001 핵심). v1/v2 잔재 0건. fitness gap 0 |
| `feedback_kis_first_policy.md` — KIS MCP·open-trading-api 베이스 | ✅ — 본 PR-9의 KIS endpoint(`H0STCNT0`/`FHKST01010100`/`FHKST03010100`/`/oauth2/Approval`) 모두 KIS open-trading-api 출처 명시. PIPE 구분자 인덱스는 sub-agent가 정확 출처 사용 의무 (dispatch §3-2-B) + `kis-code-assistant` MCP 명시 |
| `feedback_no_mock_data.md` — 사용자 흐름에서 mock 합성 폐기 | ✅ — 41+1번째 등록 시 거부 안 함, polling으로 전환 + UI 배지 (T-S1 후속). Yahoo 실패 시 "데이터 수집 중" 표시 (mock 합성 0). PR-9 1차는 mock 미사용 (KIS WS·REST·Yahoo 실 호출) |
| `feedback_secret_handling.md` — 키 평문 응답 reflect 금지 | ✅ — 본 PR-9의 KIS REST 응답에 키 평문 reflect 0건 (PR-7 정합 + KisApprovalAdapter approval_key는 Redis만, 응답 reflect 0) |
| `project_kis_key_management.md` — 개발 중 ⚙️ SettingsModal 입력 + DB AES | ✅ — 본 PR-9는 PR-7·PR-6 등록된 AES BYTEA 키 그대로 복호화 사용 (KisApprovalAdapter는 PR-7 KisAuthAdapter 패턴 정합) |
| `feedback_qa_on_demand.md` — MVP 단계 QA 게이트 미적용 | ✅ — 본 PR-9 양쪽 smoke 2건만 + ktlintCheck + 셀프 리뷰. R3 일괄은 T-S1 또는 별도 묶음 |
| `feedback_spec_plan_scope.md` — spec/plan 발행 범위 (신규 트랙·새 모듈·다중 ADR) | ✅ — T-W2 신규 트랙 + market-data 첫 출연 + ADR 0048 본문 발행 → spec/plan 발행 정합 |

---

## §8. 결론 — 권장 디스패치 모드

### §8-1. 1차 권장 — **시나리오 C** (market-data 도메인 port 먼저 + 병렬)

오케스트라가 spec 4종 머지 후:

```bash
# Step 1: market-data가 도메인 port 1 commit 우선 push (30분 이내)
cd .claude/worktrees/agent-pr9-market-data
node scripts/task.mjs start --agent market-data --branch agent/market-data/pr-9-w2-market-data ...
# → TickEvent + KisRestPort.fetchPrice add 1 commit + push + 머지 (소량 PR)

# Step 2: backend-core·market-data 병렬 합류
# Terminal 1 (backend-core worktree)
git worktree add .claude/worktrees/agent-pr9-backend agent/backend-core/pr-9-w2-backend
cd .claude/worktrees/agent-pr9-backend
node scripts/task.mjs start --agent backend-core --branch agent/backend-core/pr-9-w2-backend ...

# Terminal 2 (market-data 동일 worktree 본격 작업 시작)
cd .claude/worktrees/agent-pr9-market-data
# (이미 task.mjs start 한 상태에서 본 commit 추가)
```

### §8-2. 2차 권장 — **시나리오 A** (완전 동시 시작)

market-data 도메인 port를 첫 commit으로 일괄 + 양쪽 동시. backend-core가 PR-9a 작업 중 PR-9b의 도메인 port 머지를 기다리는 lag 발생 가능 — 단 backend-core가 stub `TickEvent`·`fetchPrice` 임시 추가 후 머지 우선 시 처리 가능.

### §8-3. 직렬은 권장하지 않음

직렬 권장 시 사유 입증 의무:
- modify-shared가 진짜로 어디인지 → application.yml(격리 권고)·CHANGELOG(ADR 0050)·domain port(누가 만들지 사전 결정) 3건 모두 해소
- 인터페이스 동결 미달 → spec 4종이 동결 보장
- pre-push hook 종속 → 시나리오 C로 해소 (market-data 도메인 port 우선 머지)

**위 3건 모두 해소 — 직렬 권장 사유 부재**.

---

## §9. 운영 체크리스트 (오케스트라가 머지 시점 점검)

### §9-1. PR-9a (backend-core) 머지 시점

- [ ] PR-9b가 main rebase 가능한 상태 (충돌 없음 — application.yml 0 변경 의무 검증)
- [ ] PR-9a CI green
- [ ] spec §11-3·DoD 1·2·6·7·8·9·19·20 backend 영역 ✅
- [ ] PR-9a CHANGELOG slot row가 채워졌는지 확인 (`grep "(작성: backend-core)" CHANGELOG.md` → 결과 1건이지만 TBD 아닌 실제 사유)
- [ ] PR-9a가 `(작성: market-data)` row 미수정 확인 (영역 침범 0 검증)
- [ ] PR-9b 작업자에게 "main rebase + force push" 안내

### §9-2. PR-9b (market-data) 머지 시점

- [ ] PR-9b CI green
- [ ] DoD 1·3·4·5·10·11·12·15·16·18 market-data 영역 ✅
- [ ] PR-9b CHANGELOG slot row 채워짐 확인
- [ ] PR-9b가 `(작성: backend-core)` row 미수정 확인 (영역 침범 0)
- [ ] 양쪽 머지 후 통합 게이트 (DoD 모든 항목) 6건 모두 ✅ — 사용자 본인 KIS 키로 수동 검증

### §9-3. 머지 후 정리 (ADR 0033 §결과)

- [ ] worktree 2개 unlock + remove
- [ ] 로컬 브랜치 2개 -d
- [ ] task.mjs archive 2건
- [ ] CHANGELOG.md 정합 확인 (양쪽 row 모두 채워짐, TBD 잔재 0건)

---

## §10. 발견된 함정·위험 (사용자 알아야 할 것)

본 PR-9 진행 중 식별된 함정 — 솔직 보고:

### §10-1. ADR 0050 슬롯 채번룰 첫 적용 — 사용자 직접 관찰 시점

본 PR-9가 ADR 0050의 첫 적용 사이클. 사용자가 처음 다음을 관찰:
- spec PR diff에 CHANGELOG slot row 3건 (architect 1 + sub-agent 2 TBD) prepend 확인
- sub-agent PR diff에 CHANGELOG 1 row 변경(in-place edit) — 다른 row 미변경 검증
- 두 sub-agent 머지 시 CHANGELOG conflict 0건 검증

검증 실패 시 ADR 0050 정정 필요 (architect 후속 정정).

### §10-2. KIS WS 정확 메시지 인덱스 — sub-agent 자체 추론 위험

KIS WS PIPE 구분자 31개 필드의 정확 인덱스를 본 spec에 명시 안 함 (사용자 메모리 `feedback_kis_first_policy.md` 정합 — sub-agent가 KIS open-trading-api 출처 사용 의무).

**위험**: market-data sub-agent가 인덱스 자체 추론 시 데이터 깨짐.

**처리**: dispatch-spec §3-2-B에 KIS open-trading-api 출처 + `kis-code-assistant` MCP 사용 의무 명시. orchestrator가 dispatch 시 사용자 메모리 강조.

### §10-3. KIS WS approval_key vs access_token 혼동

`access_token` (KIS REST — PR-7) vs `approval_key` (KIS WS) 별도 발급. PR-7의 KisAuthAdapter는 access_token만. 본 PR-9b의 KisApprovalAdapter는 approval_key.

**위험**: market-data가 access_token으로 WS 연결 시도 시 KIS 거부.

**처리**: dispatch-spec §3-2-E에 별도 어댑터 명시 + spec §11-1에 함정 명시.

### §10-4. Yahoo `.KS` vs `.KQ` ticker fallback의 호출 비용 2배

instrument market='UNKNOWN'(PR-7 옵션 C) 정합 — `.KS` 시도 → 빈 응답이면 `.KQ` 시도. 잘못된 ticker는 양쪽 모두 시도 = 2회 호출 발생.

**위험**: Yahoo HTTP 429 risk 약간 증가 (drupally — 7 interval × 2 = 14회 호출).

**처리**: T-IM1 진입 시 정확화. 본 PR-9는 `.KS` 우선 (KOSPI 다수) + 60s 대기 정책 정합.

### §10-5. SubscriptionRouter race condition — 부팅 reconcile + Kafka listener 동시

부팅 시 `WatchlistIngestionWorker.@PostConstruct`에서 reconcile 실행 + 동시에 Kafka listener가 `watchlist.changed.v1` 메시지 수신 가능 → SubscriptionRouter 동시 호출.

**처리**: SubscriptionRouter 내부 `synchronized` 또는 `ReentrantLock`. 또는 `@PostConstruct`에서 Kafka listener를 잠시 일시정지 → reconcile 완료 → resume. dispatch-spec §C에 명시.

### §10-6. polling 풀 5초 주기 + KIS 사용자 path 동시 발생 시 Rate Limit risk

장중 사용자가 SymbolDetail 진입 → api 모듈에서 KIS REST 즉시 호출 + consumer polling 6 req/s 동시 → 합산 ≤ 10이지만 burst 가능.

**처리**: ADR 0037 토큰 버킷 분산 모드(Redis backend) 본격 도입은 **별도 트랙** (T-MS1 또는 후속). 본 PR-9는 단순 — Thread.sleep 마진. 1차 MVP single-tenant 풀 30 + 사용자 1명 path는 간헐적 → 실제 위반 risk 0에 가까움.

### §10-7. consumer 모듈 Spring Reactive WebSocket 의존 추가

`consumer/build.gradle.kts`에 `implementation("org.springframework.boot:spring-boot-starter-webflux")` 추가 의무. PR-9b가 추가.

**위험**: PR-7 consumer 부팅 — webflux 의존 추가 시 부팅 latency 약간 증가 (~ 1-2s). 사용자 자동 인지.

### §10-8. consumer 모듈 자체 candle JpaEntity (api 모듈과 코드 중복)

`api/.../persistence/CandleJpaEntity.kt` + `consumer/.../persistence/ConsumerCandleJpaEntity.kt` 중복.

**처리**: 1차 단순화 (모듈 분리 정합). 후속 트랙(T-S1 — api에서 candle 조회 + consumer에서 candle write)에서 본격 정합 결정. 또는 domain port에 자세한 책임 위임 + 어댑터 단일.

### §10-9. Kafka topic 자동 생성 vs 운영자 수동 생성

본 spec은 자동 생성 권장 (`KafkaTopicConfig` Bean). 단 운영 단계에서는 retention·partition 변경 어려움 — 운영자가 수동 관리 권장.

**처리**: 본 PR-9 1차는 자동 생성. 운영 단계 진입 시 `kafka-topics.sh --alter` 또는 별도 Terraform.

### §10-10. WatchlistService default priority (PR-7 정합 함정)

PR-7 코드는 `request.priority ?: 0` — 미지정 시 0(최상위). 본 spec §6-2 정책은 default 100(중간). 정책 충돌이지만 실제 동작에서는 created_at tie-breaker로 자연 분배 → 큰 영향 0.

**처리**: backend-core가 PR-9a에서 1줄 정정 (선택) — `request.priority ?: 100`. dispatch-spec §K에 옵션 명시. 의무 아님.

---

## §11. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-9 충돌 행렬 v1 발행. 결론: **PR-9a + PR-9b 완전 병렬 권장 (시나리오 C 1차)**. modify-shared 3건(application.yml·CHANGELOG·domain port `TickEvent`/`KisRestPort`) 모두 사전 정책으로 충돌 0 보장 — application.yml은 PR-9a 단독 격리, CHANGELOG는 ADR 0050 슬롯 채번룰 첫 적용, domain port는 dispatch §4-1 LOCK으로 누가 만들지 사전 결정. 발견된 함정 10건 명시. (architect, opus 4.7 1M) |
