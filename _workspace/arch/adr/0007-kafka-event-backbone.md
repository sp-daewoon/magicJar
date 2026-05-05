# ADR 0007: Kafka 이벤트 백본 (Spring Kafka, JSON 스키마)

- 상태: Accepted
- 작성일: 2026-04-27
- 결정자: architect (협의: backend-core, market-data, strategy-engine, ai-engineer)
- 관계: ADR 0004(MSA 재구성), ADR 0005(하이브리드 런타임)에 종속

## 맥락

ADR 0004로 `api`/`batch`/`consumer` 3 JVM 런타임이 분리되면서, 그들 사이 결합 수단이 필요하다. 후보:

- **HTTP 직호출**: 단순하지만 폭증 시 백프레셔 어려움, 재시도/순서 보장 약함, 시간 디커플링 불가
- **DB 풀링**: 늦고 폴링 비용 큼
- **메시지 브로커(Kafka·RabbitMQ·NATS)**: 시간 디커플링·내구성·재처리 가능

자동매매 도메인은 (1) **시세 틱 폭주 흡수**, (2) **신호 생성 ↔ 주문 디스패치 분리**, (3) **뉴스 크롤링 ↔ AI 분석 시간 디커플링**, (4) **이벤트 재생(Replay)으로 백테스트·디버그**가 핵심이라 메시지 브로커가 압도적으로 자연스럽다.

## 검토한 대안

| 대안 | 장점 | 단점 |
|------|------|------|
| A. Spring Application Event 유지 (in-process) | 외부 의존 없음 | 프로세스 분리 못함, 재처리 불가 |
| B. RabbitMQ | 라우팅 유연, 운영 단순 | 재생/오프셋 약함, 백테스트용 이벤트 재생 부적합 |
| C. NATS / Redis Streams | 경량 | 한국 자료·생태계 빈약, Spring 통합 약함 |
| D. **Apache Kafka** (본 결정) | 내구성·재생·생태계·Spring Kafka·KRaft로 ZK 불필요 | 운영 학습 곡선, 디스크 자원 |

## 결정

**Apache Kafka(KRaft 모드)를 이벤트 백본으로 채택한다.**

### 클라이언트

- JVM: `org.springframework.kafka:spring-kafka` (consumer-app 기본, api-app 일부 listener)
- 로컬: `bitnami/kafka:3.7` (KRaft, ZooKeeper 미사용)

### 토픽 명명 규약

- 형식: `<domain>.<event>.<variant>.v<N>` — **모든 토픽에 `v1` suffix 강제** (스키마 진화 대비, 무중단 v2 도입 가능)
- 초기 토픽:

| 토픽 | 프로듀서 | 컨슈머 |
|------|---------|--------|
| `market.tick.v1` | consumer-app(KisWsBridge) | consumer-app(StrategyRunner), api-app(STOMP fan-out) |
| `market.candle.1m.v1` | consumer-app(TickAggregator) | consumer-app, DB persist |
| `news.raw.v1` | api(외부 어댑터) | consumer-app(Spring AI 분석) |
| `news.analyzed.v1` | consumer-app | api-app(STOMP) + DB |
| `dart.disclosure.v1` | api(외부 어댑터) | consumer-app |
| `signal.v1` | consumer-app(StrategyRunner) | consumer-app(OrderDispatcher), api-app |
| `order.intent.v1` | api-app, consumer-app | consumer-app(OrderDispatcher) |
| `order.executed.v1` | consumer-app | api-app(STOMP), JournalRecorder |
| `journal.entry.v1` | consumer-app(JournalRecorder), batch-app | api-app, frontend |

### 프로듀서 / 컨슈머 규약

- **프로듀서**: 클래스 단위 캡슐화 (`TickPublisher`, `OrderIntentPublisher`, `JournalEntryPublisher` 등). **토픽 문자열을 호출부에 직접 노출 금지** — publisher 내부에 토픽·키·헤더를 가둠.
- **컨슈머**: 클래스 단위 `@KafkaListener` (예: `TickAggregatorListener`, `StrategyRunnerListener`, `OrderDispatcherListener`). **한 클래스 = 한 책임** (multi-method listener 금지).
- 키 정책: 종목 단위 토픽(market.*, signal.*, order.*)은 **종목코드(KrxCode)를 키로** → 종목 단위 순서 보장.

### 직렬화 — JSON (Phase D)

- JVM 측: Kotlin data class + Jackson (`spring-kafka` 기본).
- Python 측: pydantic v2 모델 + `model_dump_json()`.
- 양 끝에서 동일 필드명·타입을 유지하기 위해 **스키마 정의는 spec(2026-04-27) §4 + 본 ADR 부록을 single source**로 둔다.
- 헤더 `schema-version: 1` 필수.
- **Avro / Schema Registry는 Phase E+ 검토** (별도 ADR 후보, 본 ADR에 미래 후보로만 명시).

### 운영 기본값 (MVP)

- 파티션: 토픽당 3 (시세 토픽은 6) — 단일 호스트 환경에 맞춘 보수값
- 복제 인수: 1 (로컬·MVP). 프로덕션 진입 시 ≥3
- retention: market.tick.v1 = 24h, 그 외 = 7d (이벤트 재생 가능 윈도우)
- 컨슈머 그룹: 앱별 1 그룹 (`api-app`, `consumer-app`, `batch-app` 등)

## 결과

- 긍정: 시간 디커플링·재처리·이벤트 재생 자연 확보. consumer-app 스케일아웃 단위 명확.
- 부정: 로컬 개발 시 Kafka 컨테이너 상시 필요(메모리 ~500MB). 운영 학습 곡선.
- 후속:
  - 토픽별 스키마 부록 작성 (별도 문서 `_workspace/arch/event-schemas.md` 후속 PR)
  - dead-letter 토픽 정책은 첫 운영 사고 후 ADR로 정식화
  - Avro/Schema Registry 도입 트리거: 프로듀서가 ≥3개 팀이 되거나 스키마 v2 필요해질 때

## Links

- spec: `docs/superpowers/specs/2026-04-27-msa-restructure-design.md` §4
- 관련 ADR: 0019, 0021, 0023
