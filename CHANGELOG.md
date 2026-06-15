# magicJar CHANGELOG

> 2026-06-15에 CHANGELOG 전문이 커져 이전 기록을 [`_workspace/changelog/CHANGELOG-2026-06-15-pre-rollover.md`](./_workspace/changelog/CHANGELOG-2026-06-15-pre-rollover.md)로 백업했습니다.
> 이후 변경 이력은 이 파일에 이어서 기록합니다. 과거 릴리즈/작업 이력은 백업 파일을 확인하세요.

| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-06-15 | **fix(strategy-engine): canary cap REJECTED가 거래빈도 한도를 오염시키는 결함 + HOLDING demand 잔류 보강** — REAL 장중 실측: 전략 #86 `093370` BUY 7건이 모두 `CANARY_QTY_CAP:17>1`로 OrderRouter gate REJECTED였는데, `SignalGeneratedKafkaListener`가 `order.intent.v1` 발행 직후 `TradeFrequencyGuard.record`를 이미 수행해 Redis `risk:trade-freq:86:74683223:day`에 signalId 7개(992·994·1006·1008·1010·1012·1013)가 남음 → 실제 체결/매수 0건인데 `TRADE_FREQUENCY_DAY Strategy daily trade count 7 >= limit 7` 알림·BUY_BLOCKED 발생. **fix**: `TradeFrequencyGuard.removeRecord` 추가 + `TradeFrequencyRedisGuard`가 minute/day ZSET에서 signalId member 제거 + `OrderRouterKafkaListener.handleRejected`의 KIS submit 전 gate reject에서 AUTO signal record 회수. KIS까지 제출된 뒤 KIS reject는 거래 시도로 유지. 부수 보강: `HoldingDemandRegistrar`가 refresh 주기마다 `HoldingPositionIndex.refresh()`를 먼저 수행하고, 이전 등록 HOLDING code가 현재 보유 목록에서 사라지면 명시 release하여 005930 같은 미보유 HOLDING demand 잔류를 TTL 의존 없이 정리. 운영 보고서 `_workspace/reports/2026-06-15-trading-incident-analysis.md`에 §9·§10 addendum. `:consumer:test` targeted 3종 + `:consumer:ktlintCheck :domain:ktlintCheck` GREEN. | consumer · domain · reports | REAL canary 거래빈도 false block 제거 + 보유감시 구독 잔류 축소 |
