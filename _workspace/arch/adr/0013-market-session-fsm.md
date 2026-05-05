# ADR 0013: Market Session FSM (KRX 영업일·장중·장외·휴장)

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M)
- master §위치: `docs/architecture/master-architecture.md` v2 §A.2·§3-2·§7
- 트랙: T2 (W1)
- 선행: ADR 0001 (오프라인 1순위 비전)

---

## 1. 맥락

magicJar는 KRX 영업일·장중·장외·휴장 인지 없이 시세·주문·지표 흐름을 운영해 왔다. 1번 트랙 dogfooding(2026-05-02)에서 발견:

- 토요일에 KIS 모의 주문 시도 → `40100000 영업일 아님` 거부.
- 장 마감 후 시세 차트가 과거 가격 그대로 표시되어 "stale" 인지 없음.
- mock profile은 `OPEN` 상태로 강제 가동하지만, 사용자가 dev에서 동일 가시성을 갖고 싶어 함.

장 상태 인지가 모든 시세·주문 흐름의 전제다.

## 2. 결정

`MarketSessionResolver` port를 도메인에 정의한다. 4 상태 FSM:

```kotlin
sealed class MarketSession {
    object PreMarket : MarketSession()    // 08:00 ~ 09:00 (장전 시간외)
    object Open : MarketSession()         // 09:00 ~ 15:30
    object PostMarket : MarketSession()   // 15:40 ~ 18:00 (장후 시간외)
    object Closed : MarketSession()       // 그 외 (영업일 외 시간 + 비영업일 + 임시휴장)
}

interface MarketSessionResolver {
    fun current(at: Instant): MarketSession
    fun businessDayOf(at: Instant): LocalDate?  // null = 비영업일
}
```

### 2-1. 전이 규칙

- 영업일 (KRX 캘린더 기준) — 08:00 PreMarket → 09:00 Open → 15:30 Closed → 15:40 PostMarket → 18:00 Closed
- 비영업일 (주말·공휴일·임시휴장) — 종일 Closed
- 임시휴장 (지진·전쟁 등) — KRX 캘린더 시드 갱신

### 2-2. 캐시

- Redis `session:cache:current` TTL 60초 — 분당 호출 폭증 차단.
- 영업일 갱신은 분기별 batch (post-MVP 자동 sync, 1차는 정적 시드).

### 2-3. Profile 분기

| profile | 동작 |
|---|---|
| mock | NoOp(항상 Open) — MockTickGenerator 자유 가동 |
| dev | active toggle — `MAGICJAR_MARKET_SESSION_ENFORCE=false` default, true 시 MarketSessionGuard active |
| prd MOCK_KIS | active(strict) — 영업일 외 주문 차단 |
| prd REAL_KIS | active(strict) — 동일 |

## 3. 결과·영향

- **MarketSessionGuard** (Risk Guard chain 6단의 1단) — `@Order(100)`. 비영업일 + Closed 상태 시 주문 reject.
- **Kafka 토픽 `market.session.v1`** — 장 상태 변경 시 publish. STOMP `/topic/market.session` fanout으로 frontend 배지 표시.
- **fallback 흐름의 트리거** — Closed 상태 시 TickFallbackEmitter 활성 (ADR 0014).
- **REJECTED audit** — Session reject도 orders INSERT (ADR 0015).

## 4. 검토 대안

- **(a) Spring AI 의 cron 기반** — 영업일 외 cron 자체 정지로 처리. **거부** — 사용자 dev 시 동일 가시성 부족.
- **(b) KIS API의 장 상태 응답 그대로 사용** — KIS 호출 의존도 ↑, 오프라인 동작 위반. **거부**.
- **(c) FSM port + 정적 캘린더 시드 1차 + 자동 sync post-MVP (채택)** — 오프라인 동작 정합.

## 5. References

- master-architecture.md §A.2 (Market Data Pipeline), §3-2 (장외 fallback)
- ADR 0001 (오프라인 1순위)
- ADR 0014 (Fallback 3-tier)
- ADR 0015 (REJECTED Audit)
- 트랙2 spec `docs/superpowers/specs/2026-05-02-track2-market-session-and-data-mgmt-design.md`
