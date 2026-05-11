# magicJar v3.3.0 Baseline — 실시간 boundary fanout 미러스톤 종합 기능 정의서

> **태그 컷**: 2026-05-12 v3.3.0 (release commit `ccbf11b`, `release/v3.3.0` 이미지 immutable)
> **사이클**: PR-?? (#310 ~ #323, 14 PR · 약 0.5일 단축 사이클 — v3.2.2 baseline 디스패치 마무리 + v3.2.3 minor doc cut + v3.3.0 §34 미러스톤)
> **상태**: PR-49 architect 영역(README + 01 + 06 + 07) 본격 작성. backend-core(02·04) · market-data(03) · frontend(05) 영역은 sub-agent 별도 디스패치.
> **권위**: 본 baseline 폴더는 v3.3.0 시점 **종합 기능 정의서**. SoT는 `docs/architecture/master-architecture.md` v2 (ADR 0030).

---

## 한 장 요약

magicJar v3.3.0은 **실시간 boundary fanout 미러스톤** — 장중 5m·15m·30m·90m·1h boundary 마감 시점(예: 09:05·09:10·…·19:55·20:00)에 즉시 합산·Redis·DB·Kafka·STOMP fanout이 발생해서 frontend 차트가 실시간 갱신되는 흐름을 LOCK한 minor 릴리즈다. v3.2.2(KIS-only · NXT cover · 합산 가공 — 3중 데이터 정책 묶음) → v3.2.3(ADR 매트릭스 NXT/합산 5종 보강 + KIS 500 retry 3회 doc cut) → v3.3.0(실시간 boundary fanout)으로 1일 내 3 minor 진행. ADR 1건 신규(0064) + 사용자 dogfooding 결함 1건 정정(차트 5m bar 장중 stale → boundary 마감 시점 즉시 갱신).

**v3.3.0 시점 동작하는 핵심 흐름** (v3.2.2 W1·W2 풀세트 + W3 T-BT 코드 진입 + v3.3.0 실시간 fanout):

1. **W1 풀세트 + W2 4 트랙 + W3 T-BT 코드 진입 (v3.2.2 baseline 그대로 보존)** — T-ONBOARD·T-W1·T-W2·T-S1·T-MS1·T-CA1·T-IM1·T-IND·T-DSL·T-PAT·T-SB·T-BT 12 트랙. v3.3.0에서 본격 변경 0 (실시간 fanout 트랙만 add — 5m~1h 데이터 흐름은 v3.2.2 base 그대로 활용).
2. **v3.2.3 doc cut (PR #315·#316·#317)** — backlog §35 architect ADR 본문 매트릭스 재통합(0019·0028·0029 §2 매트릭스 NXT 시각 행 + 합산 5종 row 보강) + external-api-policy §3 + candle-data-policy-v2 정합 갱신 + §33 market-data hotfix(KIS API 500 retry 1회→3회 exponential 500ms→1s→2s, `KisRestCandleAdapter` cold-start PARTIAL_FAIL 개선). doc + 1 hotfix.
3. **ADR 0064 — Redis boundary fanout + STOMP 실시간 합산 갱신 (PR #318 plan / #319 Accepted / #320 market-data 구현 / #321 frontend 구현)** — A-1 5종 별도 Redis hash 키(`candle:current:{code}:5m`·`:15m`·`:30m`·`:90m`·`:1h`) + B-1 KIS WS H0STCNT0 in-process boundary 검사 + C-1 5종 별도 STOMP 토픽(`/topic/market.candle.{interval}.{code}`) 채택. 5종 별도 Kafka 토픽(`market.candle.{5m·15m·30m·90m·1h}.closed.v1`) + 멱등 가드(`ConcurrentHashMap<Pair<code, interval>, LocalDate>` + DB `ON CONFLICT DO NOTHING` 이중) + latency <50ms 측정.
4. **v3.3.0 신규 컴포넌트 5건** (모두 add-only · 기존 모듈 흡수):
   - `BoundaryDetector` (domain, Pure Kotlin object · 44 unit test GREEN)
   - `BoundaryAggregator` (consumer, @Component · 1m closing → 5종 순차 처리)
   - `RedisCandleCache` (consumer, 5종 별도 hash 키 + TTL 25h)
   - `KisWebSocketBridge` 확장 (H0STCNT0 tick 핸들러 boundary trigger 진입점)
   - `CandleClosedStompListener` (api, 5 KafkaListener → STOMP fanout)
5. **frontend STOMP 5종 dynamic subscribe** (PR #321) — `useSymbolCandles.ts` IntervalToggle 5m·15m·30m·30m·1h 선택값 정합 dynamic subscribe/unsubscribe + LWC v4 `series.update()` boundary 메시지 수신 시 마지막 bar 실시간 갱신. dogfooding evidence E2E 검증.

**v3.3.0 누적 ADR 61건** (v3.2.2 베이스 60건 + 0064 Accepted 1건 · 자세한 인덱스는 `_workspace/arch/adr/README.md`). ADR 0019 supersede 부분 revoke 유지(1m 합산 가공 트랙 완료 + boundary fanout 트랙 진입 → v3.4.x minor에서 §2 본문 재통합 architect 단독 PR 예정).

**v3.3.0 시점 자동매매 본체 정합 핵심 충족**:
- **T-SIG (Signal Engine) 실시간 시그널 본질** — 5m·15m 룰 평가 latency <50ms (boundary 마감 시점 즉시 evaluation 진입 가능 baseline)
- **T-DSL (Strategy DSL) 5m·30m 인터벌 조건식 fresh** — 합산 stale 0
- **MCP 외부 client 정합** — 외부 client 장중 5m~1h interval 요청 fresh 응답 가능 baseline
- **사용자 dogfooding 결함 해소** — 장중 5m bar missing → boundary 마감 즉시 갱신

**다음 Wave**: W3 본격 진입 — Backtest(T-BT 데이터 적재 + 백테스트 실행 + 결과 영속) · Screener(T-SC) · Signal Engine(T-SIG, ADR 0064 baseline 활용) · Risk Guard(T-RISK) · Order Executor(T-O) · Reconciliation · Performance · Notification.

---

## 인덱스

| 파일 | 작성자 | 내용 | 상태 |
|------|--------|------|------|
| **[01-architecture.md](01-architecture.md)** | architect | 시스템 전경(Mermaid 5 모듈 + boundary fanout 흐름) + 모듈 책임 매트릭스 + 외부 통합 매트릭스(KIS·DART·Anthropic) + Flyway V1~V11(v3.2.2 보존) + frontend 라우팅 + deployment 토폴로지 + **v3.3.0 신규 컴포넌트 5건**(BoundaryDetector·BoundaryAggregator·RedisCandleCache·KisWebSocketBridge 확장·CandleClosedStompListener) + Kafka 5 토픽 매트릭스 | 본격 작성 완료 (PR-49) |
| [02-data-flow.md](02-data-flow.md) | backend-core | 모듈별 데이터 흐름(REST·Kafka 토픽 매트릭스·STOMP·Redis 캐시) — **실시간 boundary fanout 흐름 add** (KIS WS H0STCNT0 tick → BoundaryDetector → BoundaryAggregator → Redis 5종 갱신 + DB INSERT + Kafka publish 5 토픽 + STOMP fanout) | sub-agent 디스패치 |
| [03-use-sequences.md](03-use-sequences.md) | market-data | 사용자 유즈 시퀀스 (v3.2.2 보존) + **장중 5m boundary 마감 실시간 차트 갱신 시퀀스 add**(09:05 boundary 도달 → KIS WS tick → BoundaryDetector → 5m 합산 → STOMP fanout → LWC `series.update()`) + KIS-first raw 출처(H0STCNT0 ccnl_krx 44 cols · cols[1]=STCK_CNTG_HOUR) | sub-agent 디스패치 |
| [04-feature-spec.md](04-feature-spec.md) | backend-core | W1·W2·W3 T-BT 트랙 기능 명세(v3.2.2 보존) + **v3.3.0 실시간 boundary fanout 기능 명세 add**(BoundaryDetector 44 unit test 매트릭스 + BoundaryAggregator 멱등 가드 + Kafka 5 토픽 명세 + STOMP 5종 토픽 명세) | sub-agent 디스패치 |
| [05-ui-inventory.md](05-ui-inventory.md) | frontend | 화면·메뉴·버튼 인벤토리(v3.2.2 보존) + **useSymbolCandles STOMP 5종 dynamic subscribe** add + **LWC v4 series.update() 실시간 갱신 시각화** + IntervalToggle 5종 활성화 dogfooding E2E evidence | sub-agent 디스패치 |
| **[06-history-traceability.md](06-history-traceability.md)** | architect | PR·ADR·기능 3축 추적성 매트릭스 (v3.2.2 → v3.3.0 14 PR + ADR 1건 신규 + v3.2.3 doc cut + 실시간 boundary fanout 미러스톤) | 본격 작성 완료 (PR-49) |
| **[07-policy-evolution.md](07-policy-evolution.md)** | architect | 운영 헌법 7 § (보존) + ADR 0064 발행 + 정책 진화 추가 1건(실시간 fanout baseline 확립) + v3.2.3 doc cut(0019·0028·0029 매트릭스 본문 LOCK) | 본격 작성 완료 (PR-49) |

> **인덱스 갱신 권한**: architect 단독 책임 (충돌 회피 — 헌법 §4 정합). sub-agent는 본인 파일만 in-place edit, README 인덱스는 변경 안 함.

---

## 베이스라인 정합 ADR (v3.3.0 시점 권위)

본 v3.3.0 시점 데이터 흐름·정책 결정의 baseline ADR 목록:

| ADR | 제목 | 위치·연결 |
|-----|------|----------|
| **0019** | 캔들 저장 정책 — Watchlist 기반 | Accepted (부분 revoke 유지) — Yahoo 의존 부분 보류 · 1m 합산 가공 트랙 완료 + boundary fanout 트랙 진입 → v3.4.x minor §2 재통합 architect 단독 PR 예정 (v3.2.3 doc cut에서 §2-1 매트릭스 NXT/합산 5종 row 보강 완료 — PR #316) |
| **0028** | Watchlist Ingestion Worker (5초 polling LOCK) | Accepted — v3.2.3 doc cut에서 §2-1·§2-3 cold-start 흐름 정합 갱신 (KOSPI=UN·KOSDAQ=J 분기 + AggregateBackfillRunner 호출 + halt/liquidation skip 명시) |
| **0029** | 당일 진행 중 캔들 Redis 캐시 | Accepted — v3.2.3 doc cut에서 §2-1 Redis 키 매트릭스 5m~1h boundary 캐시 키 5건 add (v3.3.0 §34 작업 가이드) + v3.3.0 ADR 0064 채택으로 §2-1·§2-5 본문 LOCK 후속(별도 사이클) |
| **0048** | KIS WebSocket 41건 구독 한도 | Accepted — H0STCNT0 stream baseline. ADR 0064 §1-3에 의해 본 stream의 tick 핸들러가 boundary 검사 trigger 진입점으로 LOCK |
| **0052** | Baseline Documentation Cycle | Accepted — 본 baseline 발행이 4번째 적용 사례 (v3.1.0·v3.2.0·v3.2.2에 이어 v3.3.0) |
| **0057** | KIS OpenAPI 단일 데이터 출처 | Accepted — prod credential 단일 채널 보존 (ADR 0064 KIS WS 체결 수신도 prod credential) |
| **0062** | 5m·15m·30m·90m·1h 합산 가공 plan | Accepted — §4-3 boundary 마감 흐름 본문이 ADR 0064 권위로 위임 + v3.3.0 §34 미러스톤 LOCK |
| **0063** | NXT/통합거래 매트릭스 cover | Accepted — boundary 시각 매트릭스 08:00~20:00 KST cover baseline (ADR 0064 §4-2 boundary 시각 매트릭스 정합) |
| **0064** | Redis boundary fanout + STOMP 실시간 합산 갱신 | **Accepted (2026-05-12 사용자 A-1·B-1·C-1 채택) — v3.3.0 §34 미러스톤 본 ADR 권위** |

---

## 어떻게 읽을지 (역할별 진입 경로)

### 신규 개발자 (시스템을 처음 본다)

1. **[01-architecture.md](01-architecture.md) §1 시스템 전경** — Mermaid 그림으로 5 JVM 모듈 + frontend + 외부 4 + 인프라 3 + 디버깅 2 + **boundary fanout 흐름**(KIS WS → BoundaryDetector → BoundaryAggregator → Redis·DB·Kafka·STOMP) 한 눈에
2. **[01-architecture.md](01-architecture.md) §2 모듈 책임 매트릭스** — 어떤 코드가 어디 있는지 (api·batch·consumer·pattern-engine·domain·frontend × 트랙 13건 — boundary fanout 트랙 add)
3. **[01-architecture.md](01-architecture.md) §7 v3.3.0 신규 컴포넌트 매트릭스** — `BoundaryDetector`(domain) · `BoundaryAggregator`(consumer) · `RedisCandleCache`(consumer) · `KisWebSocketBridge` 확장(consumer) · `CandleClosedStompListener`(api) — 실시간 fanout 트랙 핵심 5건
4. **[02-data-flow.md](02-data-flow.md)** — REST + Kafka topic + STOMP + Redis 캐시 흐름 + **boundary fanout 흐름 add** (backend-core 영역)
5. **[03-use-sequences.md](03-use-sequences.md)** — 셋업 → Watchlist → Symbol(NXT cover) → 5m~1h 합산 → **장중 5m boundary 마감 실시간 차트 갱신 시퀀스 add** (market-data 영역, KIS-first raw 출처)
6. `docs/architecture/master-architecture.md` v2 정독 — SoT (19 컴포넌트 + 4 영역 + 5 Wave)
7. `_workspace/INDEX.md` — 단일 진입점 인덱스 (의도별 진입 경로 5축, ADR 0053)

### 운영자 (배포·환경 설정)

1. **[01-architecture.md](01-architecture.md) §6 deployment 토폴로지** — `docker-compose.yml` 9 컨테이너 (v3.2.2 보존 — 컨테이너 추가 0)
2. **[01-architecture.md](01-architecture.md) §3 외부 통합 매트릭스** — KIS REST/WS · DART · Anthropic URL·tr_id 정합 (Yahoo `@Deprecated` 보존 — v3.4.x minor 일괄 삭제 예정)
3. **[01-architecture.md](01-architecture.md) §4 Flyway V1~V11** — v3.2.2 보존 (v3.3.0에서 신규 마이그레이션 0건 — boundary 합산 결과는 기존 candle 테이블의 5m~1h interval row 활용)
4. **[01-architecture.md](01-architecture.md) §7 신규 컴포넌트 매트릭스** — `BoundaryDetector` Pure Kotlin · `BoundaryAggregator` 5종 순차 처리 · `RedisCandleCache` 5종 별도 hash 키 + TTL 25h · Kafka 5 토픽 + STOMP 5종 fanout
5. `docs/operations/initial-setup.md` — 초기 셋업
6. `docs/architecture/external-api-policy.md` — KIS 호출 빈도·토큰 정책 (v3.2.3 doc cut으로 §3·§2-5 NXT/합산 매트릭스 정합 갱신 완료)
7. `docs/architecture/candle-data-policy-v2.md` — 매트릭스 §3·§4·§5·§6 갱신 (v3.2.3 doc cut에서 ADR 0057·0062·0063 정합 + ADR 0064 fanout 후속 정합 갱신 별도 사이클)
8. `docs/architecture/policies/` — Living Policy 6건 (ADR 0053 §1 — 갱신 0)
9. `CLAUDE.md` "운영 함정 가드" — stale jar · docker compose env 우선순위 · default profile · 실계좌 이중 게이트

### 전략 작성자 (W2 마감 후 본격 사용 가능 + W3 T-BT 코드 진입 + v3.3.0 실시간 fanout baseline)

1. `docs/architecture/master-architecture.md` §B 분석·전략 영역 (5 컴포넌트 — Indicator·Pattern·DSL·Strategy Builder·Backtest)
2. **[03-use-sequences.md](03-use-sequences.md) Strategy Builder 시퀀스** — `/strategy/builder` 라우트에서 트리 폼 빌더로 룰 작성 → DSL 직렬화 → backend `KisYamlDslParser` 검증 → `JvmStrategyEvaluator` 평가 → 결과 카드 → Watchlist 추가
3. **[03-use-sequences.md](03-use-sequences.md) 장중 boundary 마감 실시간 차트 갱신 시퀀스** — 5m·15m·30m·90m·1h interval 차트 진입 시 boundary 마감 즉시 실시간 갱신 (T-SIG·T-DSL fitness 충족)
4. **[04-feature-spec.md](04-feature-spec.md) W3 T-BT 기능 명세** — Backtest Engine 룩어헤드·세·수수료·슬리피지 + sandbox 통합 + W3 후속 데이터 적재 트리거
5. `_workspace/strategy/` 카탈로그 (KIS 80지표 · 57패턴 cross-check fixture)
6. `frontend/src/api/strategy.ts` 7 endpoint + `frontend/src/components/strategy/` 8 컴포넌트 (PR-45 v3.2.0)
7. **[07-policy-evolution.md](07-policy-evolution.md) §6 다음 Wave 정책 후보** — W3 본격 진입 시 backtest_run·screener_result·signal_event·order_audit Flyway 발행 + ADR 0064 baseline 활용 T-SIG 실시간 시그널 본격 발행

### architect / orchestrator / sub-agent (운영 룰 정독 의무)

1. **[07-policy-evolution.md](07-policy-evolution.md)** — 운영 헌법 7 § 인용(보존) + ADR 0064 발행 + 정책 진화 추가 1건
2. `_workspace/arch/agent-charter.md` — 운영 헌법 본문 (헌법 §1~§7 정독 의무 — architect 단독 PR로만 갱신)
3. **[06-history-traceability.md](06-history-traceability.md) §4 hotfix 패턴** — v3.3.0 사이클 1 hotfix(KIS API 500 retry 3회 — §33 dogfooding PARTIAL_FAIL 정정)
4. `_workspace/arch/branching-rules.md` — 브랜치·머지·author 정책
5. `.claude/agents/<agent>.md` SKILL — 영역별 특수 룰만
6. `docs/architecture/policies/` — Living Policy 6건 (ADR 0053 — 현행 권위 단일 위치)

---

## v3.2.2 → v3.3.0 사이클 핵심 통계

| 지표 | v3.2.2 (2026-05-11) | v3.3.0 (2026-05-12) | 증감 |
|------|---------------------|---------------------|------|
| PR # 누적 | #309 | #323 | +14 (#310~#314 v3.2.2 baseline 마감 + #315·#316·#317 v3.2.3 doc cut + #318·#319·#320·#321 v3.3.0 §34 미러스톤 4 PR + #322·#323 v3.3.0 cut + baseline init) |
| ADR 수 | 60 (실제 신규 4건 + 슬롯 4건 0058~0061) | 61 (0064 Accepted 추가) | +1 |
| Flyway 버전 | V11 | V11 (보존 — boundary 합산 결과는 기존 candle 테이블 활용) | 0 |
| JVM 모듈 | 5 (domain·api·batch·consumer·pattern-engine) | 5 (보존) | 0 |
| W3 ★ 트랙 코드 진입 | 1 (T-BT) | 1 (T-BT) — v3.3.0에서 T-BT API REST·영속·frontend 라우트 발행 0 | 0 |
| 외부 통합 (활성) | KIS + DART + Anthropic (3) | KIS + DART + Anthropic (3) | 0 |
| Kafka 토픽 (활성) | 7 + 도메인 이벤트 (보존) | 7 + **5종 boundary fanout 추가** = 12 | +5 |
| Redis 키 (활성) | quote·session·instrument·position·kis token + indicator/pattern cache + candle:current:{code}:1m | + **candle:current:{code}:{5m·15m·30m·90m·1h} 5종 별도 hash 키 add** | +5 |
| STOMP 토픽 (활성) | tick · candle/1m · orderbook (보존) | + **/topic/market.candle.{5m·15m·30m·90m·1h}.{code} 5종 add** | +5 |
| `BoundaryDetector` unit test | 0 | **44 GREEN (5m 마감·15m·30m·90m 8 anchor·1h·NXT 범위 밖·parseHhmmssToInstant·boundaryStartTs cover)** | +44 |
| end-to-end latency (5m boundary 마감 → STOMP fanout 도착) | — (장중 fanout 0건) | **<50ms 측정 (KIS WS tick → BoundaryDetector → BoundaryAggregator → Redis put + DB INSERT + Kafka publish → STOMP fanout 합계)** | -∞ → <50ms |
| 운영 헌법 § 수 | 7 | 7 (보존) | 0 |
| Living Policy 문서 | 6 | 6 (보존) | 0 |

상세는 **[06-history-traceability.md](06-history-traceability.md) §6 결론**.

---

## v3.2.2 → v3.3.0 사이클 머지된 주요 PR (요약)

PR 본문 인용 — 시계열 전체는 [06-history-traceability.md](06-history-traceability.md) §2.

| PR | mergedAt | 트랙 / 종류 | 한 줄 요약 |
|----|----------|------------|------------|
| #310 | 2026-05-11 | frontend · v3.2.2 baseline | docs(baseline): PR-48 frontend — v3.2.2 05-ui-inventory |
| #311 | 2026-05-11 | backend-core · v3.2.2 baseline | chore(baseline): v3.2.2 baseline 02-data-flow + 04-feature-spec |
| #312 | 2026-05-11 | market-data · v3.2.2 baseline | docs(baseline): v3.2.2 03-use-sequences — KIS-first raw 출처 11건 LOCK |
| #313 | 2026-05-11 | architect · v3.2.2 baseline | docs(baseline): PR-48 architect — v3.2.2 baseline 4 파일 (README + 01 + 06 + 07) |
| #314 | 2026-05-11 | meta · v3.2.2 baseline | chore(baseline): v3.2.2 html 변환 + task archive 4건 |
| #315 | 2026-05-12 | market-data · hotfix · §33 | fix(consumer): KIS HTTP 500 retry 3회 exponential backoff 보강 (backlog §33) |
| #316 | 2026-05-12 | architect · §35 doc cut | docs(arch): backlog §35 — ADR 0019·0028·0029 §2 매트릭스 NXT/합산 5종 보강 (v3.2.3 doc cut) |
| #317 | 2026-05-12 | release | release: v3.2.3 — ADR 매트릭스 NXT/합산 보강 + KIS 500 retry 3회 |
| #318 | 2026-05-12 | architect · ADR 0064 plan | docs(architect): ADR 0064 plan — Redis boundary fanout + STOMP 실시간 합산 갱신 (v3.3.0 §34, Proposed) |
| #319 | 2026-05-12 | architect · ADR 0064 Accepted | chore(adr): ADR 0064 Proposed → Accepted (사용자 A-1·B-1·C-1 채택) |
| #320 | 2026-05-12 | market-data · §34 본격 | feat(market-data): ADR 0064 §34 boundary fanout + STOMP 실시간 합산 갱신 구현 |
| #321 | 2026-05-12 | frontend · §34 본격 | feat(frontend): ADR 0064 §2-C STOMP 5종 boundary subscribe + LWC series.update 실시간 갱신 |
| #322 | 2026-05-12 | release | release: v3.3.0 — 실시간 boundary fanout 미러스톤 (Redis + STOMP + KIS WS) |
| #323 | 2026-05-12 | baseline init | chore(baseline): v3.3.0 init — 폴더 + CHANGELOG 5 slot prepend (PR-49) |

---

## 작성 규약 (sub-agent 디스패치 시 준수)

- 본문은 한국어, 코드/명령은 영어 그대로
- **Mermaid 다이어그램 의무** (시각화 가능 수준) — `_workspace/baseline/v3.3.0/html/` 변환 시 `scripts/baseline-render-html.sh` (ADR 0052)
- **raw 출처 인용 의무** — KIS API · DART · 코드 path 명시 (`consumer/.../BoundaryAggregator.kt:L40` 등)
- 추측·관념적 서술 금지 — 코드 inspection 결과만 인용
- **TBD 표시**: 미작성 영역은 명시. 다른 sub-agent 영역은 cross-reference (`→ 02-data-flow.md 참조` 식)
- `docs/architecture/master-architecture.md` §X · `_workspace/arch/adr/NNNN-*.md` 같은 SoT 출처 명시 의무
- v3.2.2 baseline 보존 영역은 **변경 0** — 추가 분만 v3.3.0 add (예: 01-architecture.md §1 Mermaid는 v3.2.2 베이스에 `BoundaryDetector`·`BoundaryAggregator`·`RedisCandleCache`·`CandleClosedStompListener` 박스 + 화살표 5종만 추가)

---

## 다음 Wave 진입 시 본 baseline 갱신

W3 본격 진입(Backtest 데이터 적재·실행·결과 영속 + Screener · Signal Engine[ADR 0064 baseline 활용] · Risk Guard · Order Executor · Reconciliation · Performance · Notification) 시 본 폴더는 immutable(ADR 0031). v3.4.x 또는 v3.5.0 신규 baseline 컷 권장(`_workspace/baseline/v3.X.X/`).

W3 본격 진입 시 본 §1·§3·§4가 본격 확장:
- **§1 Mermaid** — backtest_run 영속·screener·signal·order·reconcile·notify 박스 add + **ADR 0064 boundary fanout이 T-SIG 시그널 evaluation trigger로 진입** (master §C 영역)
- **§3 외부 통합** — KIS 주문 endpoint(TTTC·VTTC) + KIS WS H0STCNI0 체결통보 + Notification(Slack webhook) add + **Yahoo 박스 완전 제거** (v3.4.x minor)
- **§4 Flyway** — V12~ backtest_run·screener_result·signal_event·order_audit 테이블 add
- **§5 frontend 라우트** — `/backtest`·`/screener`·`/dashboard` 본격 add

W4(AI 옵션 + MCP 서버) 진입 시 mcp 컨테이너 + AI Console 박스 add. **본 v3.3.0 시점 Anthropic 통합은 T-DSL 자연어→DSL helper 한정 (사용자 트리거 + 매매 결정권 0)** — ADR 0023·0026 정합. ADR 0064 boundary fanout이 MCP 외부 client 장중 5m~1h 정합 baseline.
