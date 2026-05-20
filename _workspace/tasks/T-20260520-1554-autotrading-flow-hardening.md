---
id: T-20260520-1554-autotrading-flow-hardening
agent: orchestrator
model: opus
branch: agent/orchestrator/autotrading-flow-hardening
status: RUNNING
startedAt: 2026-05-20T15:54:01.798+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
자동매매·수동매매·차트지표 기반 매매 플로우 핵심 결함 개선

## 결과물 체크리스트
- [x] 개발 작업지시서
- [x] 자동매매 시그널-주문 연결 보강
- [x] 수동 취소 KIS 실취소 경로 보강
- [x] 검증 결과

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-20T15:54:01.808+09:00 — 작업 시작

## 비고

---

# 개발 작업지시서 — 자동매매·수동매매 주문 플로우 하드닝

## 배경

2026-05-20 main 별도 worktree 분석 결과, 문서상 의도와 실제 코드 플로우 사이에 자본 path 결함이 남아 있다.

- 자동매매 실시간 시그널이 consumer 런타임에서 전략 조회·시그널 저장 bean 부재로 끊길 수 있다.
- 지표 기반 전략 평가가 방금 닫힌 캔들 1개만 받아 MA·RSI·MACD 등 기간 지표가 HOLD로 빠질 수 있다.
- `sellCondition` 기반 자동 매도 시그널이 아직 처리되지 않는다.
- 수동 취소 API가 KIS 취소 호출 없이 DB 상태만 `CANCELLED`로 바꿀 수 있다.
- 정정은 아직 P2 후속으로 남아 있다.
- 자동 주문 수량은 전략 sizing 없이 1주 시장가 default다.

본 작업은 "실계좌에서 잘못된 상태를 만들지 않는다"를 1순위로 한다. 완전한 UX 개선보다 자본 path의 거짓 성공(false success)을 제거한다.

## 1차 구현 범위

이번 PR에서 직접 처리할 항목:

1. consumer 런타임에 `StrategyLookupPort` 구현을 추가한다.
   - `consumer`는 `api` 모듈에 의존하지 않는다.
   - `strategy` 테이블을 consumer 자체 JPA entity/repository/adapter로 읽는다.
   - `dsl_entry`/`dsl_exit` 텍스트를 `KisYamlDslParser`로 AST 재파싱해 `buyCondition`/`sellCondition`을 채운다.

2. consumer 런타임에 `SignalRepository` 구현을 추가한다.
   - `signal` 테이블을 consumer 자체 JPA entity/repository/adapter로 저장한다.
   - 저장된 signal id가 `signal.generated.v1` payload에 들어가 risk listener가 정상 소비하도록 한다.

3. 실시간 평가에 candle history를 공급한다.
   - `RealtimeSignalGenerator`는 `CandleRepository.findByCodeAndInterval(code, interval)` 결과와 boundary candle을 병합해 시간순 캔들 목록을 evaluator에 전달한다.
   - 기본 lookback은 설정값 `magicjar.signal.eval-lookback-candles`로 두고 default 500개로 시작한다.

4. `sellCondition` 평가를 추가한다.
   - `buyCondition`이 true면 BUY 우선.
   - BUY가 아니고 `sellCondition`이 true면 SELL signal을 발행한다.
   - HOLD는 기존처럼 영속·발행하지 않는다.

5. 수동 취소 API의 거짓 성공을 제거한다.
   - `OrderCancelService`는 KIS `order-rvsecncl` 성공 ACK 이후에만 DB를 `CANCELLED`로 전이한다.
   - KIS 주문번호/전송조직번호가 없는 주문은 취소 성공 처리하지 않고 409로 막는다.
   - KIS reject/exception이면 `KIS_CANCEL_REJECT` 또는 `KIS_EXCEPTION` audit 후 DB 상태를 유지한다.
   - API 모듈에 KIS hashkey/cancel adapter를 추가하되, 주문 신규 발행 path는 consumer 기존 adapter를 유지한다.

6. API 전략 저장/조회 raw DSL 유실을 보강한다.
   - `StrategyService.create`가 받은 `entryText`/`exitText`를 도메인 정의에 보존한다.
   - `StrategyJpaAdapter`는 raw DSL 텍스트를 DB에 저장한다.
   - 조회 시 `dsl_entry`/`dsl_exit`를 AST로 재파싱한다.

## 1차 범위에서 제외

이번 PR에서 직접 구현하지 않는 항목:

- 주문 정정(`RVSE_CNCL_DVSN_CD=01`) REST/API/UI 전체 플로우.
- 전략 sizing을 실제 quantity로 변환하는 portfolio allocator.
- KIS cancel 5xx/timeout의 별도 `PENDING_CANCEL_RECONCILE` 상태.
- REST backfill cumulative 체결과 WS 분할 체결의 중복 수량 정교 reconcile.
- frontend cancel UX 문구/상태머신 개선.

위 항목은 후속 PR로 분리한다. 특히 정정과 sizing은 도메인/API/UI/테스트가 모두 얽혀 1차 자본 path 하드닝과 섞지 않는다.

## 담당 영역 메모

- orchestrator: 본 작업 전체 통합, 문서, 안전 범위 결정.
- backend-core: API strategy persistence, cancel service, migration/JPA 정합.
- market-data: KIS hashkey/cancel raw mapping, token/rate-limit 정합.
- strategy-engine: DSL AST 재파싱, buy/sell 평가 순서, candle history 평가.
- qa: 자동매매 signal→risk→order 최소 단위 테스트, cancel false success 방지 테스트.
- chart-curator: candle history 공급이 차트 데이터 SLA와 충돌하지 않는지 후속 확인.

## 검증 기준

필수:

- `./gradlew check`
- `frontend` 변경 시 `pnpm typecheck && pnpm build`

완료 검증:

- `./gradlew :domain:compileKotlin :strategy-engine:compileKotlin :api:compileKotlin :consumer:compileKotlin`
- `./gradlew :api:test --tests kr.spdaewoon.magicjar.api.order.OrderCancelServiceTest`
- `./gradlew :consumer:test`
- `./gradlew :consumer:test --tests kr.spdaewoon.magicjar.consumer.signal.RealtimeSignalGeneratorTest`
- `./gradlew ktlintCheck`
- `./gradlew check`

권장 단위 테스트:

- `RealtimeSignalGeneratorTest`
- `ConsumerStrategyLookupJpaAdapterTest`
- `ConsumerSignalJpaAdapterTest`
- `OrderCancelServiceTest`
- `StrategyControllerTest` 또는 strategy persistence adapter test

## 금지사항

- KIS 취소 실패를 DB `CANCELLED`로 표시하지 않는다.
- consumer가 api 모듈에 의존하도록 바꾸지 않는다.
- AI/LLM provider import를 W1~W3 자본 path에 추가하지 않는다.
- 기존 사용자 untracked 파일(`.agents/`, `.codex/`, `AGENTS.md`)을 건드리지 않는다.
