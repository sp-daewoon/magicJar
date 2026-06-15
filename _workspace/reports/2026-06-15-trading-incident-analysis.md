# 2026-06-15 장중 거래 장애 분석 보고서

- 작성 시각: 2026-06-15 12:41 KST
- 기준 코드: `origin/main` = `e72c1ef41a7628c06ee6aafee3f6b0e3755c5cd3`
- 분석 범위: 2026-06-15 KST 장중 시그널, 주문, 체결, 포지션, Docker/Dozzle 로그
- 대상 컨테이너: `magicjar-consumer`, `magicjar-api`
- 데이터 변경: 없음. DB 조회와 로그 분석만 수행.

---

## 1. 결론

오늘 장애는 단일 원인이 아니라 여러 문제가 동시에 겹친 상태다.

| 구분 | 판단 | 영향 |
|---|---|---|
| KIS REST rate limit/원장 TPS 초과 | 확실 | 잔고/자본 조회 실패, watchlist polling 실패, 캔들/상태 경로 불안정 |
| 전략 85번 `005930` 과매매/반대 시그널 churn | 확실 | 3회 왕복 체결, 자동 익절 주문 생성 후 즉시 취소 반복, 실제 추정 손익 음수 |
| 전략 포지션 원장 drift/유령 포지션 | 확실 | 실제 보유 0인데 전략 ledger가 1로 튀고 take-profit watcher 경고 반복 |
| 전략 86번 `093370` canary 수량 cap 불일치 | 확실 | BUY 7건 모두 `CANARY_QTY_CAP:17>1`로 주문 전 거절 |
| 거절 주문이 일일 거래 횟수로 소진 | 확실 | 실제 체결 0건인데 12:39부터 `TRADE_FREQUENCY_DAY`로 차단 |
| 실시간 unsubscribe `H0UNASP0` invalid_tr_type | 확실 | 전략 85 pause 이후에도 KIS가 unsubscribe를 거절 |
| realtime slot/subscription 상태 불일치 가능성 | 강한 의심 | 현재 status는 fresh지만 slot ledger에 `005930` residue가 남아 보임 |

운영 관점에서는 “전략 성능이 안 좋았다”보다 더 심각하다. 주문 전 cap, 포지션 원장, rate limit 격리, 실시간 구독 정리 경로가 모두 동시에 흔들렸고, 그 결과 정상적인 canary 판정이 어려운 상태가 됐다.

---

## 2. 현재 상태 스냅샷

### 전략 상태

| strategy_id | 이름 | 상태 | 종목 | 관찰 |
|---:|---|---|---|---|
| 85 | `canary_1m_ma10_touch_005930` | `PAUSED` | `005930` | 12:35 KST에 pause. 이전까지 3회 왕복 체결 발생 |
| 86 | `canary_1m_ma10_touch_093370` | `ACTIVE` | `093370` | BUY 신호는 나왔지만 전부 canary 수량 cap으로 reject |
| 87 | `[페이퍼] 거래량 폭발 20봉 신고가 돌파 setup` | `PAUSED` | 7종목 | 오늘 실주문 흐름과 직접 관련 낮음 |

### 실시간 상태

12:40 KST 기준 `/api/admin/realtime-status`는 `stale=false`, `ws=true`, `executionNotice=true`로 fresh 상태였다.

다만 slot에는 `H0UNCNT0` 기준 `005930`, `093370`이 보이고, 실제 subscription 목록/owner 기준으로는 `093370`만 명확하다. 전략 85 pause 후 `005930` tick slot이 정리되지 않았거나, ledger/status 표현이 서로 다를 가능성이 있다.

---

## 3. 시그널 분석

오늘 KST 기준 시그널 총 42건이 관측됐다.

| strategy_id | code | side | risk_status | 건수 | 첫 시각 | 마지막 시각 |
|---:|---|---|---|---:|---|---|
| 85 | 005930 | BUY | PENDING | 1 | 12:11 | 12:11 |
| 85 | 005930 | BUY | ALLOWED | 3 | 12:12 | 12:19 |
| 85 | 005930 | BUY | BLOCKED | 5 | 12:16 | 12:24 |
| 85 | 005930 | SELL | ALLOWED | 3 | 12:14 | 12:31 |
| 85 | 005930 | SELL | BLOCKED | 4 | 12:32 | 12:35 |
| 86 | 093370 | BUY | ALLOWED | 7 | 12:21 | 12:38 |
| 86 | 093370 | BUY | BLOCKED | 1 | 12:39 | 12:39 |
| 86 | 093370 | SELL | BLOCKED | 18 | 11:03 | 12:32 |

핵심 관찰:

- 전략 85는 12:12~12:31 사이에 BUY/SELL이 번갈아 발생했다.
- 전략 86은 보유 포지션이 없는 상태에서 SELL 신호가 18건 발생했고, 전부 `SELL_WITHOUT_POSITION`으로 차단됐다.
- 전략 86의 BUY는 risk 단계에서는 7건 허용됐지만, 실제 주문 라우터에서 모두 수량 cap으로 거절됐다.
- 12:39부터 전략 86은 `TRADE_FREQUENCY_DAY`로 차단됐다. 실제 체결은 0건인데, cap reject 7건이 일일 거래 횟수로 소비된 것으로 보인다.

---

## 4. 주문/체결 분석

### 주문 요약

| strategy_id | code | side | status | 건수 | 의미 |
|---:|---|---|---|---:|---|
| 85 | 005930 | BUY | FILLED | 3 | 실제 매수 체결 |
| 85 | 005930 | SELL | FILLED | 3 | 실제 매도 체결 |
| 85 | 005930 | SELL | CANCELLED | 3 | 자동 익절 지정가 주문 취소 |
| 86 | 093370 | BUY | REJECTED | 7 | canary hard cap 거절 |

### 전략 85 체결 타임라인

| 시각(KST) | order_id | side | qty | 체결가 | 비고 |
|---|---:|---|---:|---:|---|
| 12:12:01 | 298 | BUY | 1 | 339,000 | 신호 978 |
| 12:14:09 | 300 | SELL | 1 | 338,000 | 신호 980 |
| 12:15:12 | 301 | BUY | 1 | 339,000 | 신호 982 |
| 12:17:08 | 303 | SELL | 1 | 338,500 | 신호 986 |
| 12:19:09 | 304 | BUY | 1 | 339,250 | 신호 989 |
| 12:31:01 | 308 | SELL | 1 | 339,000 | 신호 1003 |

단순 lot pair 기준 추정 손익은 수수료/세금 전 `-1,750원`이다.

| pair | 매수 | 매도 | 손익 |
|---|---:|---:|---:|
| 1 | 339,000 | 338,000 | -1,000 |
| 2 | 339,000 | 338,500 | -500 |
| 3 | 339,250 | 339,000 | -250 |
| 합계 |  |  | -1,750 |

주의: `strategy_position.realized_pnl`에는 `32,750`이 기록되어 있어 실제 체결 pair와 맞지 않는다. 이 값은 현재 신뢰하기 어렵고, 포지션 ledger drift와 함께 별도 정정 대상이다.

### 전략 86 주문 거절

전략 86의 BUY 주문 7건은 모두 동일 사유로 거절됐다.

| order_id | 시각(KST) | code | qty | status | reject_reason |
|---:|---|---|---:|---|---|
| 306 | 12:21:04 | 093370 | 17 | REJECTED | `CANARY_QTY_CAP:17>1` |
| 307 | 12:22:03 | 093370 | 17 | REJECTED | `CANARY_QTY_CAP:17>1` |
| 309 | 12:33:00 | 093370 | 17 | REJECTED | `CANARY_QTY_CAP:17>1` |
| 310 | 12:34:00 | 093370 | 17 | REJECTED | `CANARY_QTY_CAP:17>1` |
| 311 | 12:35:01 | 093370 | 17 | REJECTED | `CANARY_QTY_CAP:17>1` |
| 312 | 12:37:00 | 093370 | 17 | REJECTED | `CANARY_QTY_CAP:17>1` |
| 313 | 12:38:00 | 093370 | 17 | REJECTED | `CANARY_QTY_CAP:17>1` |

이 문제는 “주문이 안 나가서 안전했다”로 볼 수 없다. risk 단계에서 이미 ALLOWED가 발생했고, outbox에도 order intent가 publish됐다. 마지막 방어선인 OrderRouter hard cap이 막은 상태다.

---

## 5. Dozzle 로그 기반 장애 증거

### 5-1. KIS REST rate limit/원장 TPS 압박

관측 로그:

- 12:11 KST: 전략 85 BUY signal 976에서 잔고/자본 조회 실패. `KIS_LOOKUP_FAILED:500 ... inquire-balance`
- 12:15~12:23 KST: `ConsumerRedisKisRateLimiterAdapter acquire TIMEOUT`, `priority=BACKFILL`, `trId=FHKST03010100` 다수
- 12:34 KST: API 잔고 조회 실패. KIS 응답 `EGW00215 원장에서 허용 가능한 초당 거래건수를 초과`
- 12:35 이후: `KisRestPriceAdapter inquire-price Rate Limit ... EGW00201`, `WatchlistPollingWorker polling fetch 실패` 반복

해석:

- 장중 주문/잔고/실시간 관측이 필요한 시간에, 캔들 refresh/backfill/watchlist polling 계열 REST 호출이 KIS quota를 압박했다.
- 사용자 화면에서 “구독 상태가 자꾸 끊김/비정상 신호”처럼 보이는 증상과 연결될 가능성이 높다.
- 실제 주문 경로에서도 capital lookup 실패가 발생했다. 12:11 BUY signal 976은 이 영향으로 `PENDING`에 머물렀다.

### 5-2. 전략 85 포지션 ledger drift

관측 로그:

- 12:12 체결 직후: `STRATEGY_POSITION_DRIFT_CORRECTED: strategySum=1->0 corrections=85:1->0`
- 12:14 매도 체결 후: `strategy_position SELL overflow ... currentQty=0 fillQty=1`
- 12:14 이후 반복: `take-profit skip(유령 포지션 — position 권위 잔량 0): userId=74683223 strategyId=85 code=005930 ledgerQty=1`
- 12:35: `STRATEGY_POSITION_REBUILD_SKIPPED(교차검산 불일치): rebuildTotal=1 authority=0 ... 미적용(KIS 권위 우선)`

해석:

- 실제 `position` 권위 테이블은 `005930 quantity=0`으로 정상 종료됐지만, 전략별 ledger가 0/1 사이에서 흔들렸다.
- take-profit watcher가 이 ledger를 보고 유령 포지션 경고를 반복했다.
- `strategy_position.realized_pnl=32750`도 체결 pair와 맞지 않아 신뢰할 수 없다.

### 5-3. 자동 익절 주문 생성 후 반대 SELL 신호로 취소 반복

관측 흐름:

- BUY 체결 후 AutoExitOrderCoordinator가 take-profit LIMIT SELL을 생성했다.
- 이후 SELL 신호가 발생하면 `RestingSellOrderCanceller`가 기존 익절 지정가 주문을 취소하고 market SELL을 냈다.
- 이 패턴이 3회 반복됐다.

해석:

- 자동 익절 경로 자체는 동작했지만, 1m 전략 신호가 너무 빠르게 반전되면서 보호 주문이 매번 취소됐다.
- 최소 보유 시간, 반대 신호 debounce, 이미 열린 exit 주문과 신규 SELL 신호의 우선순위 정책이 부족하다.

### 5-4. 전략 86 canary cap과 risk/order 기준 불일치

관측 로그:

- 12:21~12:38 KST: 7건의 BUY intent가 모두 `CANARY_QTY_CAP:17>1`로 거절
- 12:39 KST: `RiskGatewayEvaluator BLOCKED ... TRADE_FREQUENCY_DAY ... Strategy daily trade count 7 >= limit 7`

해석:

- canary 수량 cap은 OrderRouter 마지막 단계에서만 적용되고 있다.
- risk/order intent 생성 이전에 수량이 1로 제한되지 않아 17주 intent가 반복 생성됐다.
- 거절된 cap 초과 시도까지 일일 거래 횟수로 계산되어, 실제 체결 0건인데 전략이 일일 거래 한도에 막혔다.

### 5-5. 실시간 unsubscribe invalid_tr_type

관측 로그:

- 12:35:33 KST: 전략 85 pause 이후 `H0UNASP0` unsubscribe 전송 원문 기록
- 원문에는 `tr_type:"0"`이 포함되어 있었다.
- 직후 KIS 응답: `OPSP8997 invalid tr_type`

해석:

- 지난주/이전 분석에서 보였던 `H0UNASP0` unsubscribe invalid_tr_type 문제가 오늘도 재현됐다.
- 원문상 `tr_type` 값은 들어가 있으므로 단순 누락보다는 KIS 프로토콜 세부 조건, header/body 조합, unsubscribe 대상 상태, 전송 race 중 하나를 의심해야 한다.

---

## 6. 알려진 별도 장애와의 관계

이 보고서의 중심은 2026-06-15 장중 실제 거래 흐름이다. 다만 오늘 운영 불신을 키운 아래 이슈들은 같은 장애 묶음으로 관리해야 한다.

| 이슈 | 관계 |
|---|---|
| 2026-06-12 일봉 0원 오염 | 오늘 주문 005930/093370 직접 원인은 아니지만, 데이터 신뢰도 장애. 재수집 필요 |
| 403870 실시간 1m 미수신 | 오늘 활성 전략의 직접 주문 경로는 아니지만, realtime 상태 신뢰도 장애 |
| realtime status stale/스케줄러 병목 | 현재 `origin/main` 배포 후 status 자체는 fresh지만, rate limit과 polling 실패가 여전히 상태 체감에 영향을 줌 |
| `H0UNASP0` unsubscribe invalid_tr_type | 오늘도 재현. 전략 pause/구독 정리 경로의 별도 결함 |

따라서 이전 결함 목록과 오늘 분석은 합집합으로 보는 것이 맞다. 오늘 새로 확실해진 추가 장애는 주문/체결 경로의 canary cap 불일치, 포지션 ledger drift, rate limit이 주문/잔고/상태 경로에 미치는 영향이다.

---

## 7. 즉시 권장 조치

### 장중 즉시

1. 전략 85는 계속 `PAUSED` 유지.
2. 전략 86은 수량 cap 문제가 해결되기 전까지 실주문 목적의 canary로 신뢰하지 않는다. 최소한 전략 산출 qty를 1로 맞추거나, cap 초과 intent가 발생하지 않는지 확인 후 재개한다.
3. 장중에는 1d refresh/backfill/대량 watchlist polling을 줄이거나 중지한다. 주문/잔고/체결 sync가 KIS quota를 우선 사용해야 한다.
4. `005930` 실시간 slot/subscription residue 여부를 확인한다. 상태 API의 slot ledger와 실제 owner/subscription 목록이 일치해야 한다.
5. 오늘 `strategy_position`의 `005930` realized PnL/filled lot은 운영 판단에 사용하지 않는다.

### 다음 배포 우선순위

| 우선순위 | 수정 대상 | 내용 |
|---:|---|---|
| P0 | canary 수량 cap | order intent 생성 전에 qty를 cap 이하로 clamp하거나 risk 단계에서 fail-close |
| P0 | trade frequency count | `CANARY_QTY_CAP` reject는 일일 체결/거래 횟수에 포함하지 않도록 기준 재정의 |
| P0 | strategy_position ledger | authority position 0일 때 strategy ledger가 유령 포지션을 만들지 않도록 drift correction/rebuild 수정 |
| P0 | KIS quota 분리 | 주문/잔고/체결 sync와 backfill/watchlist polling의 rate limiter budget 분리 또는 장중 backfill circuit breaker |
| P1 | auto-exit vs SELL signal 정책 | 보유 직후 반대 신호 debounce, 최소 보유 시간, resting exit 주문 우선순위 정리 |
| P1 | unsubscribe invalid_tr_type | `H0UNASP0` unsubscribe raw/header/body/correlation evidence 추가와 KIS 프로토콜 조건 재검증 |
| P1 | realtime status 정합 | slot ledger, subscribedCodes, owner demand, actual KIS WS 상태를 같은 기준으로 reconcile |

---

## 8. 운영 판단

오늘 상태는 “전략 2개를 켜서 부하가 조금 늘어난 정도”가 아니다.

- KIS quota가 장중 핵심 경로까지 압박했다.
- 한 전략은 실제 매매가 churn으로 손실을 만들었고, 포지션 ledger까지 불일치했다.
- 다른 전략은 주문이 전부 cap에 막혔는데도 일일 거래 한도를 소진했다.
- 실시간 unsubscribe 문제는 여전히 재현됐다.

따라서 현재는 정상 canary 상태로 보기 어렵다. 최소 P0 항목을 고친 뒤, 전략 1개/종목 1개/수량 1주/장중 backfill 제한 상태에서 다시 관찰하는 것이 맞다.

---

## 9. 추가 검증 — 전략 86 거래 빈도 알림

추가 신고 알림:

- 사용자: `7468****`
- 전략: `canary_1m_ma10_touch_093370` (#86)
- 종목: 후성 `093370`
- 알림: `D. 거래 빈도 한도 도달 - 일별 최대 거래`
- 수식: `오늘 허용 주문 수 7회 >= 일별 최대 거래 7회/일`

검증 결과, 알림의 7회는 실제 매수 체결 7회가 아니었다. Redis 거래빈도 key `risk:trade-freq:86:74683223:day`에 아래 7개 signalId가 남아 있었고, 모두 주문 라우터에서 canary cap으로 거절된 BUY intent였다.

| signal_id | order_id | side | qty | status | reject_reason |
|---:|---:|---|---:|---|---|
| 992 | 306 | BUY | 17 | REJECTED | `CANARY_QTY_CAP:17>1` |
| 994 | 307 | BUY | 17 | REJECTED | `CANARY_QTY_CAP:17>1` |
| 1006 | 309 | BUY | 17 | REJECTED | `CANARY_QTY_CAP:17>1` |
| 1008 | 310 | BUY | 17 | REJECTED | `CANARY_QTY_CAP:17>1` |
| 1010 | 311 | BUY | 17 | REJECTED | `CANARY_QTY_CAP:17>1` |
| 1012 | 312 | BUY | 17 | REJECTED | `CANARY_QTY_CAP:17>1` |
| 1013 | 313 | BUY | 17 | REJECTED | `CANARY_QTY_CAP:17>1` |

원인:

- `SignalGeneratedKafkaListener`는 risk `ALLOWED` 후 `order.intent.v1` 발행에 성공하면 `TradeFrequencyGuard.record(...)`를 호출한다.
- 이후 `OrderRouterKafkaListener`에서 canary hard cap이 `CANARY_QTY_CAP:17>1`로 주문을 거절했다.
- 하지만 이미 Redis 거래빈도 count는 증가해 있었고, gate reject 시 이를 회수하는 경로가 없었다.
- 그 결과 실제 체결 0건인 전략 86이 일일 거래 빈도 7회를 모두 사용한 것처럼 보였다.

수정 방향:

- `TradeFrequencyGuard.removeRecord(strategyId, userId, signalId)`를 추가했다.
- `TradeFrequencyRedisGuard`는 minute/day ZSET 양쪽에서 signalId member를 제거한다.
- `OrderRouterKafkaListener.handleRejected(...)`의 KIS submit 전 gate reject 경로에서 AUTO signal이면 거래빈도 record를 회수한다.
- 따라서 canary cap, market closed, real gate, availability 같은 submit 전 reject는 더 이상 전략 거래빈도 한도를 오염시키지 않는다.
- KIS까지 실제 제출된 뒤 KIS가 거절한 주문은 이번 범위에서 여전히 거래 시도로 남긴다.

검증:

- `TradeFrequencyRedisGuardTest.removeRecord`
- `OrderRouterKafkaListenerTest.(canary-10) cap gate REJECTED 는 signal 단계 거래빈도 record 를 회수한다`
- `./gradlew :consumer:test --tests "kr.spdaewoon.magicjar.consumer.risk.TradeFrequencyRedisGuardTest" --tests "kr.spdaewoon.magicjar.consumer.order.OrderRouterKafkaListenerTest" --tests "kr.spdaewoon.magicjar.consumer.ingestion.HoldingDemandRegistrarTest"` — GREEN
- `./gradlew :consumer:ktlintCheck :domain:ktlintCheck` — GREEN

운영 주의:

- 배포 전 Redis에는 기존 7개 signalId가 그대로 남아 있다.
- 이를 장중 수동 삭제하면 전략 86이 다시 자동 주문을 시도할 수 있으므로, 코드 배포 또는 전략 설정 qty 정정 없이 단독 삭제하는 것은 권장하지 않는다.

---

## 10. 추가 검증 — 005930 HOLDING 구독 잔류

첨부된 Claude 분석의 핵심 방향은 맞다.

- DB 권위 기준 `position`의 `005930 REAL quantity=0`
- `strategy_position`의 `005930` 관련 row도 모두 `quantity=0`
- 그런데 realtime status는 `subscriptions`에는 093370만 보이는데, slot ledger에는 `H0UNCNT0` 기준 `005930`이 계속 남아 있었다.
- 12:31~12:35 KST 로그에서는 `strategy_position` ledger가 1로 튀며 `take-profit skip(유령 포지션)` 경고가 반복됐다.

보강:

- `HoldingDemandRegistrar.refreshHoldingDemands()`가 매 주기 `HoldingPositionIndex.refresh()`를 먼저 호출하도록 수정했다.
- 이전에 등록했던 HOLDING code가 현재 보유 목록에서 사라지면 명시적으로 `RealtimeSubscriptionRouter.release(HOLDING, ...)`를 호출한다.
- 기존에는 `onFullyExited()` 즉시 해제 또는 TTL 만료에 기대는 구간이 있었고, 인덱스 stale/ledger drift가 있으면 HOLDING demand가 잔류할 수 있었다.

남은 범위:

- 현재 status에서 `subscriptions`와 `slots.codesByTrId`가 서로 다르게 보이는 문제는 WS slot ledger/unsubscribe 정합 이슈와도 연결된다.
- 이 부분은 `H0UNMKO0/H0UNASP0 invalid_tr_type` 및 slot ledger 정리 작업과 함께 추가 추적이 필요하다.

---

## 11. 추가 검증 — KIS rate-limit 잔불

canary 거래빈도 오염 수정 배포 뒤에도 Dozzle/consumer 로그에서 KIS rate-limit 잔불이 남아 있었다.

관측 로그:

- `KisRestPriceAdapter - KIS inquire-price 실패 ... EGW00201`
- `WatchlistPollingWorker - polling fetch 실패`
- `ConsumerKisBalanceQueryAdapter - consumer KIS 잔고조회 실패 ... EGW00215`
- `WatchlistPollingWorker`와 `BalanceReconcileJob` 로그가 모두 `rt-status-1` thread에서 출력됨
- 첫 배포 후 `EGW00201/EGW00215`는 멈췄지만 `Connection prematurely closed BEFORE response`가 watchlist polling에서 반복됨

해석:

- `realtimeStatusTaskScheduler`가 유일한 `TaskScheduler` bean 이라 Spring이 이를 모든 `@Scheduled`의 기본 scheduler로도 사용했다.
- 그 결과 status 전용으로 분리하려던 `rt-status-1` thread에 watchlist polling, balance reconcile까지 올라와 격리가 깨졌다.
- watchlist polling은 화면 보조 경로인데 `KisRestPriceAdapter.fetchPrice(... USER_PATH)`를 통해 주문/잔고와 같은 USER bucket을 사용했다.
- 전체 watchlist pool을 5초마다 한꺼번에 조회하면서 `EGW00201`이 반복됐고, 같은 시간대 잔고/자본 조회가 `EGW00215`로 밀릴 수 있는 조건을 만들었다.

보강:

- 기본 `@Scheduled`용 `taskScheduler` bean을 별도로 등록하고, realtime status publisher는 `realtimeStatusTaskScheduler`를 명시 사용하도록 격리했다.
- watchlist 전용 `KisRestPriceAdapter.fetchPollingPrice(...)`를 추가해 현재가 polling을 `INGESTION` bucket으로 낮췄다.
- `WatchlistPollingWorker`는 5초 tick마다 전체 pool 대신 round-robin slice(default 2종목)만 조회한다.
- 종목 사이 기본 500ms delay를 두고, KIS rate-limit 응답, local acquire timeout, KIS request failure가 나면 남은 slice를 즉시 중단한 뒤 30초 cooldown 동안 polling을 skip한다.
- `KisRestPriceAdapter`의 acquire 후 통계 미발행 경고 라벨을 `[KisRateLimit]`에서 `[KisCallAccounting]`으로 바꿔 네트워크 실패가 rate-limit으로 오해되지 않게 했다.

운영 trade-off:

- watchlist 화면용 quote freshness는 낮아진다. 예를 들어 pool 10종목이면 기본값 기준 한 종목이 다시 polling되기까지 약 25초가 걸릴 수 있다.
- 대신 주문, 잔고, 자본 조회에 필요한 USER bucket을 보호하고, realtime status scheduler가 다시 status 발행만 담당하도록 되돌린다.

배포 후 관찰:

- PR #981/#982 merge 후 consumer 재배포 완료.
- 안정 부팅 이후 `/api/admin/realtime-status`는 `stale=false`, `wsConnected=true`, `slotsUsed=2`, `subscriptions=2`로 fresh.
- 관찰 창에서 `EGW00201`, `EGW00215`, 잔고조회 실패 재발 없음.
- `Connection prematurely closed BEFORE response`는 1회 있었지만 `watchlist polling KIS failure ... reason=request-failure ... cooldownMs=30000`로 남은 slice 중단 + cooldown 처리됨.

추가 잔불:

- 14:04 KST 전략 #86 BUY 17주가 실제 체결됐다. 이는 코드의 canary cap 우회가 아니라 운영 `.env`에서 `MAGICJAR_CANARY_MAX_ORDER_QTY=0`, `MAGICJAR_CANARY_MAX_ORDER_NOTIONAL_KRW=0`, `MAGICJAR_CANARY_MAX_ORDERS_PER_DAY=0`으로 전용 cap이 비활성화된 결과다. `.env` 주석도 "금액 cap 35만원으로 통일" 결재 상태를 가리킨다.
- 14:05 KST 다음 BUY 신호는 `POSITION_LIMIT`으로 차단됐지만, 그 전에 KIS 잔고조회가 `EGW00215`를 다시 맞았다. 직전 14:04 성공 잔고가 있었는데도 1분 뒤 KIS 원장을 다시 직접 조회해 USER 경로 압박을 만든 것이다.
- 보강: `KisCapitalSnapshotProviderAdapter`에 90초 fresh-cache reuse window를 추가한다. 직전 성공 잔고가 이 창 안이면 KIS 직접 조회를 생략하고 stale=true Available로 재사용해 1분 신호 반복의 원장 TPS 자가압박을 줄인다.

추가 배포 관찰:

- PR #984 merge 후 열린 실매도 ACK 1건 때문에 일반 배포 drain guard가 중단됐다. consumer 부팅 backfill이 체결 복원 경로를 갖고 있어 `SKIP_DRAIN_CHECK=1`로 consumer만 재배포했다.
- 배포 직후 `/api/admin/realtime-status`는 `stale=false`, `wsConnected=true`, `executionNotice.subscribed=true`로 fresh였다.
- 그러나 첫 1분 신호(14:15 KST)에서 `EGW00215`가 재발했다. 원인은 PR #984의 fresh-cache가 in-memory라 consumer 재기동 직후 cache miss였고, 같은 정각 0초에 `BalanceReconcileService` 시장 OPEN cron과 BUY risk path가 모두 KIS 잔고조회 endpoint를 건드린 점이다.
- 같은 로그에서 `KisCapitalSnapshotProvider stale-cache 없음 — 시그널 skip`이 확인되어 0 fabricate/POSITION_LIMIT false violation은 재발하지 않았다. 남은 문제는 거래 허용 여부가 아니라 KIS 원장 호출 압박이었다.
- 추가 보강: BUY는 체결 backfill 전 capital preflight를 먼저 수행한다. capital unavailable이면 KIS 체결조회도 태우지 않고 `SIGNAL_SKIPPED_CAPITAL_UNAVAILABLE`로 끝낸다.
- 추가 보강: capital 직접 조회 실패 직후 기본 120초 cooldown 동안 같은 KIS 잔고조회를 재시도하지 않는다. 직전 성공값이 있으면 stale-cache, 없으면 Unavailable(skip)으로 반환한다.
- 추가 보강: `BalanceReconcileJob` 시장 OPEN cron 기본 초를 0초에서 30초로 옮겨 1분봉 신호 정각 호출과 분산한다.

PR #985 배포 후 관찰:

- consumer 재배포 완료. 열린 실매도 ACK 1건 때문에 drain guard는 계속 대기하므로 `SKIP_DRAIN_CHECK=1`로 배포했다.
- `/actuator/health=UP`, `/api/admin/realtime-status`는 `stale=false`, `wsConnected=true`, `executionNotice.subscribed=true`.
- `BalanceReconcileJob`은 14:25:30 KST에 실행되어 기존 정각 0초 충돌에서 벗어난 것을 확인했다.
- 배포 후 관찰 창에서 `EGW00201`, `EGW00215`, `잔고조회 실패`는 재발하지 않았다.
- watchlist polling의 `Connection prematurely closed BEFORE response`는 계속 간헐 발생하지만 `watchlist polling KIS failure ... reason=request-failure ... cooldownMs=30000`로 남은 slice 중단 + cooldown 처리된다.
- DB 기준 전략 #86은 `PAUSED`, 현재 실보유는 후성 17주(avg 19,570원), 열린 주문은 auto take-profit SELL ACK 1건(`ODNO=0027188000`, 17주, 19,760원)이다. 이 주문 때문에 배포 drain guard의 in-flight 1건은 계속 남는다.

추가 잔불 — WS unsubscribe invalid_tr_type:

- 14:29 KST 열린 후성 SELL ACK가 체결되며 position/strategy_position이 0으로 정리됐고, HOLDING/VI demand가 해제됐다.
- 해제 직후 H0UNCNT0/H0UNMKO0 unsubscribe 원문에 `tr_type:"0"`이 포함되어 KIS가 `OPSP8997 JSON PARSING ERROR : invalid tr_type`을 반환했다.
- 로컬 status는 `stale=false`, `wsConnected=true`, `subscriptions=0`으로 정리됐지만, KIS가 unsubscribe를 거절한 이상 원격 구독 잔류 가능성이 남는다.
- 보강: 일반 unsubscribe와 graceful shutdown unsubscribe를 모두 `TR_TYPE_UNSUBSCRIBE="2"`로 통일하고, 원문 진단 로그도 실제 trType을 출력한다.

PR #987 배포 후 관찰:

- consumer 재배포는 일반 drain guard로 통과했다. 배포 직전 in-flight 주문은 0건이었다.
- `/actuator/health=UP`, `/api/admin/realtime-status`는 `stale=false`, `wsConnected=true`, `slotsUsed=0`, `subscriptions=0`, `executionNotice.subscribed=true`.
- DB 기준 전략 #86은 `PAUSED`, `strategy_position(093370)`과 계좌 `position(093370)`은 모두 0으로 정리됐다. 열린 주문도 0건이다.
- 배포 후 관찰 창에서 `OPSP8997`, `invalid_tr_type`, `EGW00201`, `EGW00215`, `잔고조회 실패`는 재발하지 않았다.
- 다만 배포 후에는 active holding/detail subscription이 없어 실제 live unsubscribe 이벤트가 새로 발생하지 않았다. payload 형식은 회귀 테스트에서 raw JSON `tr_type:"2"`로 잠갔다.

## 6. 후성 전략 #86 자동매매 중지 사유 분석

요약:

- 14:08 KST 매도는 익절/손절 라인 체결이 아니라 전략 DSL 매도 신호(`STRATEGY_SIGNAL`)였다.
- 전략 #86 설정에는 `take_profit_pct=1.0000`, `fixed_stop_loss_pct=2.0000` 외에 별도 청산식 `dsl_exit = close < ma(10)`이 들어가 있었다.
- `min_hold_bars`가 비어 있어, 매수 직후라도 다음 1분봉에서 `close < ma(10)`이면 DSL 매도 신호가 즉시 허용된다.
- 14:29 KST 매도는 두 번째 매수 직후 걸린 자동 익절 선주문(`AUTO_EXIT`)이 체결된 것이다.

거래 타임라인:

| KST | order | trigger | 내용 |
| --- | --- | --- | --- |
| 14:04:01 | #328 | `STRATEGY_SIGNAL` | BUY 17주 @ 19,530 체결 |
| 14:04:02 | #329 | `AUTO_EXIT` | 익절 LIMIT SELL 17주 @ 19,720 접수 |
| 14:07:00 | signal #1079 | `close < ma(10)` | SELL 신호 발생했으나 KIS 잔고조회 실패로 차단 |
| 14:08:01 | #329 | - | 기존 익절 LIMIT SELL 취소 처리 |
| 14:08:02 | #330 | `STRATEGY_SIGNAL` | MARKET SELL 17주 @ 19,500 체결 |
| 14:10:01 | #331 | `STRATEGY_SIGNAL` | BUY 17주 @ 19,570 체결 |
| 14:10:02 | #332 | `AUTO_EXIT` | 익절 LIMIT SELL 17주 @ 19,760 접수 |
| 14:29:01 | #332 | `AUTO_EXIT` | LIMIT SELL 17주 @ 19,760 체결 |

14:08 매도 근거:

- signal #1080: `side=SELL`, `reason=THRESHOLD: close vs ma(10)`, `risk_status=ALLOWED`.
- candle 재계산 기준 14:07 close=19,500, MA10=19,519.50, 따라서 `close < ma(10)`가 참이었다.
- 기존 auto-tp #329는 전략 SELL 발행 전에 `RestingSellOrderCanceller` 경로로 취소됐다. KIS는 미체결 SELL이 보유 수량을 잠그므로, DSL SELL/손절이 먼저 오면 기존 auto-tp를 취소한 뒤 시장가 SELL을 내는 구현이다.

14:29 매도 근거:

- order #332는 `order_trigger=AUTO_EXIT`, `price_type=LIMIT`, `limit_price=19760`.
- 두 번째 BUY 평단 19,570원에 `take_profit_pct=1%`를 적용하면 19,765.7원이고, 호가단위 내림으로 19,760원이 된다.
- 따라서 14:29 매도는 시스템 기준 익절 선주문 체결이다.

손익:

- 1차 왕복: 19,530 매수 → 19,500 매도, 총 -510원.
- 2차 왕복: 19,570 매수 → 19,760 매도, 총 +3,230원.
- 합산 총 +2,720원으로 `strategy_position.realized_pnl=2720`과 일치한다.

판정:

- 데이터 기준으로는 “익절/손절 watcher가 오작동해 임의 매도했다”는 증거는 없다. `STOP_LOSS`, `VI_AUTO_EXIT` 트리거도 발견되지 않았다.
- 실제 문제는 전략 설정과 운영 기대가 어긋난 것이다. #86은 “매수 후 익절/손절 라인만 대기”하는 전략이 아니라, `close < ma(10)` DSL 청산도 동시에 켜진 전략이었다.
- `min_hold_bars` 미설정으로 매수 직후 DSL 청산을 막는 완충 구간이 없었다.
- 체결 알림 본문에는 `orderTrigger`가 드러나지 않아, 사용자가 `STRATEGY_SIGNAL` 매도와 `AUTO_EXIT` 매도를 즉시 구분하기 어렵다.

권고:

- #86을 “TP/SL만으로 청산”하려면 `dsl_exit`를 비우거나 비활성화하고, 필요한 경우 `min_hold_bars`를 설정해 매수 직후 DSL SELL을 막아야 한다.
- 전략 체결 알림 본문에 `주문출처: 전략 SELL / 자동 익절 / 손절 / VI 자동청산`을 명시해야 한다.
- 운영 UI에도 현재 전략의 청산 경로(`dsl_exit`, take-profit, stop-loss, VI policy, minHold)를 한 화면에 표시해야 한다.

## 7. 20개 이상 다전략 장중 운용 준비도

사용자 목표:

- 20개 이상 전략을 동시에 켜고, 5분봉/15분봉/1분봉이 혼재해도 각 전략이 독립적으로 진입/청산해야 한다.
- 같은 종목에 같은 1분봉이더라도 서로 다른 DSL을 쓰는 전략이 공존할 수 있어야 한다.
- 매도 사인이 오면 그 전략의 보유분을 팔 수 있어야 한다.

현재 판정:

- 시그널 생성기는 같은 `(code, interval)`에 여러 전략이 있으면 boundary 1건마다 전략 수만큼 순차 평가한다. 즉 평가 자체는 가능하다.
- 그러나 주문/포지션/구독/리스크 쪽은 아직 “다전략 오케스트레이션”이라기보다 “전략별 가상 lot을 보수적으로 지키는 단일 계좌 투영”에 가깝다.
- 따라서 20전략 운영은 기능적으로 일부 가능하지만, 운영 안정성은 아직 부족하다.

핵심 문제 1 — 전략별 포지션 원장 race:

- 15:10 KST 삼성전자 #88 BUY 1주가 실제 체결됐다.
- 직후 로그 흐름:
  - `execution position update ... nextQty=1 source=REAL`
  - `strategy_position upsert ... strategyId=88 ... nextQty=1`
  - `STRATEGY_POSITION_DRIFT_CORRECTED ... strategySum=1->0 ... authorityQuantity=0`
- 결과:
  - `position(REAL,005930)=1`
  - `strategy_position(88,005930)=0`
  - `order_request #334 AUTO_EXIT SELL ACK 1주`는 남아 있음
  - `strategy_position quantity > 0` row는 0건
- 추정 원인:
  - `position`은 native upsert로 갱신되지만, 같은 transaction/persistence context에서 직전에 읽힌 stale entity quantity=0이 남아 있을 수 있다.
  - strategy_position drift 보정이 그 stale authority=0을 보고 방금 정상 귀속한 #88 보유 1주를 즉시 0으로 내렸다.
- 영향:
  - 보유 기반 HOLDING 구독과 tick 손절/익절 watcher가 전략 보유를 못 볼 수 있다.
  - 실제 계좌에는 1주가 있는데 전략별 원장에는 0이라, 이후 청산 신호/알림/리스크가 꼬인다.
  - 다전략 이전에 단일 전략에서도 P0급 정합 결함이다.

핵심 문제 2 — 같은 종목 다전략 SELL 충돌:

- 브로커 계좌는 종목별 순수량만 알고, 전략별 보유 태그를 알지 못한다.
- 시스템은 이를 `strategy_position`으로 가상 분리한다.
- 하지만 한 전략이 SELL을 내기 전에 같은 종목 open SELL을 검사하면, 수동/타전략 SELL은 침범 금지로 취소하지 않고 차단한다.
- 이 설계는 안전하지만 다음 상황에서 사용자가 기대한 독립 운용과 충돌한다:
  - 삼성전자 1분봉 전략 A와 B가 동시에 각 1주 보유
  - A의 auto-tp가 open SELL로 걸려 있음
  - B의 손절/DSL SELL이 먼저 발생
  - B는 A의 open SELL을 타전략 예약으로 보고 보류/차단될 수 있음
- 즉 “같은 종목 여러 전략이 각자 매도 사인에 독립 반응”은 현재 완전 지원이라고 보기 어렵다.

핵심 문제 3 — DSL exit와 TP/SL이 모두 청산 경로:

- #85/#86 오늘 거래에서 반복 확인됐다.
- BUY 체결 직후 auto-tp LIMIT이 걸리고, 이후 DSL SELL이 오면 기존 auto-tp를 취소한 뒤 전략 SELL이 시장가로 나간다.
- 사용자가 “익절/손절 라인만 청산”으로 이해하면 실제 동작과 다르다.
- 다전략에서는 이 혼선이 더 커진다. 각 전략마다 `dsl_exit`, `take_profit_pct`, `fixed_stop_loss_pct`, `vi_action_policy`, `min_hold_bars`를 한눈에 보여줘야 한다.

핵심 문제 4 — KIS USER 경로 호출 압박:

- 오늘 `KIS_TPS_EXCEEDED` system event 39건, `SIGNAL_SKIPPED_CAPITAL_UNAVAILABLE` 4건, `EGW00215` 재발이 있었다.
- 일부 hotfix로 fresh-cache/cooldown/preflight를 넣었지만, 20전략이 1분봉 boundary에서 동시에 BUY/SELL을 만들면 다음 호출이 다시 몰린다:
  - capital/balance 조회
  - execution backfill 조회
  - asking-price/quote 조회
  - 주문 hashkey/주문/취소
- 현재는 시그널 단위 처리라 “같은 user 같은 초에 여러 전략 신호”를 batch로 묶어 KIS 조회 1회로 공유하는 계층이 약하다.

핵심 문제 5 — 구독과 readiness가 전략 규모에 민감:

- realtime subscription은 code 단위 owner ledger로 dedup하지만, 오늘 watchlist polling network failure와 `무tick 감지`가 계속 보였다.
- 15:10 #88 보유 원장 race 후 005930 HOLDING demand가 `refresh-missing`으로 release됐고, 이후 005930 구독이 빠졌다.
- 즉 구독 시스템 자체보다, 구독의 입력인 strategy_position/holding owner ledger가 깨지면 realtime도 같이 흔들린다.

핵심 문제 6 — 평가 경로 최적화 부족:

- 같은 `(code, interval)` boundary에서 여러 전략이 있으면 전략마다:
  - strategy row 조회/DSL parse 결과 사용
  - candle history 조회
  - indicator 계산
  - signal 저장/publish
  - risk context 구성
- code+interval 단위 candle/indicator snapshot을 한 번 만들고 여러 DSL이 공유하는 구조가 아직 강하지 않다.
- 전략 20개가 전부 삼성전자 1분봉이면, 논리적으로는 1개 candle snapshot에 20 DSL 평가면 되지만 현재는 중복 비용이 크다.

우선순위:

| 우선순위 | 항목 | 이유 |
| --- | --- | --- |
| P0 | strategy_position race 수정 | 실제 보유와 전략 원장이 갈라져 청산/구독/리스크가 붕괴 |
| P0 | 동일 종목 다전략 SELL 충돌 정책 재설계 | 전략별 독립 청산 목표와 현재 fail-closed 정책이 충돌 |
| P1 | user+초 단위 KIS 조회 coalescing | 20전략에서 KIS USER path 자가 압박 재발 가능 |
| P1 | 청산 경로 UI/알림 명확화 | 사용자가 DSL SELL, AUTO_EXIT, STOP_LOSS를 구분 못함 |
| P1 | code+interval 평가 batch/cache | 같은 종목/분봉 다전략 비용 감소 필요 |
| P2 | 다전략 simulation harness | 같은 종목 1m 전략 A/B, 5m/15m 혼재, 동시 SELL/BUY race를 자동 검증 |

단기 안전 운용 가이드:

- 같은 종목에 실계좌 전략 2개 이상을 동시에 켜는 것은 아직 피한다.
- 실계좌는 종목당 1전략부터 확장하고, 같은 종목 다전략은 페이퍼/소액 격리로 먼저 검증한다.
- `dsl_exit`가 있는 전략은 TP/SL만 청산하는 전략이 아님을 전제로 운용한다.
- `min_hold_bars` 없이 1분봉 DSL exit가 있는 전략은 매수 직후 청산이 정상 동작으로 발생할 수 있다.
