# 2번 트랙 — 장 상태(Market Session) 인지 + 시세 Fallback + REJECTED Audit Trail + EGW00202 진단 설계

> 산출물: spec (이 문서) + plan + ADR draft 인라인 (사용자 검토 후 정식 발행).
> 본 spec은 코드 변경 0건 — 실행은 plan T1 이후.
>
> **v2 정합 노트 (2026-05-03)**: 본 spec은 v2 마스터 아키텍처(`docs/architecture/master-architecture.md`)의 T2 트랙 그 자체로 흡수됨. AI 매매 가정 부분은 본 spec 본문에 없음(트랙2는 시세·세션·주문 거부 audit이므로 AI 무관). 인라인 ADR draft 0031~0035 → v2 정식 발행 ADR 매핑:
> - 0031 (Market Session FSM) → **0032** (`_workspace/arch/adr/0032-market-session-fsm.md`)
> - 0032 (시세 Fallback) → **0033** (`_workspace/arch/adr/0033-data-fallback-three-tier.md`)
> - 0033 (REJECTED Audit) → **0034** (`_workspace/arch/adr/0034-rejected-audit-trail.md`)
> - 0034 (Profile 매트릭스 정밀화) → master §7로 흡수, 별도 ADR 없음
> - 0035 (EGW00202 hotfix) → **0035** (`_workspace/arch/adr/0035-kis-http-precision.md`)
>
> 트랙 진입 시(W1 R1) plan T20·T21·T22 (ADR 발행)는 v2 정식 발행분 참조로 변경 — 별도 발행 작업 불필요.

- **작성일**: 2026-05-02
- **작성자**: architect (opus 4.7 1M)
- **상태**: DRAFT — 사용자 검토 대기
- **선행 트랙**: 1번 트랙 KIS 주문 어댑터 (PR #65·#66 머지 완료)
- **트리거**: 1번 트랙 dogfooding 1차 시도 중 발견된 **2건의 운영 결함** + 사용자 명시 데이터 관리 체계 요구

---

## §1. 배경 — 사용자 인용·dogfooding 발견

### 1-1. 사용자 인용 (원문)

> "장중이 아니라고 안되는건 문제가 있는거야 실시간 데이터를 가져오는건 장 상태를 확인하는걸테고 상중이아니라면 기존에 값들로던 어떤 처리가 있어야하는데 지금 너무 매끄럽지 못해 최고관리자야 너 생각을해봐라 이게 자동 매매프로그램인데 장이 멈췄다고 그냥 이것도 만들때 프로덕트형태의 서비스를 만들라했는데 이렇게 먹통되면 어떻게되겠냐 데이터를 어떻게 처리할지 데이터 관리 실시간 데이터 등에 관리 체계가 확실히되야지 지금 문제되는게 많은데 일단은 dev환경에서이 기본 모의투자를 할 수 있는 환경은 만들어야지 일단 지금 kis 다시 폐기하라고하는데 이거 서비스 정상화 될때까지는 유지할거니깐 그냥 써 나만 쓰는 키로는 문제될 일 없으니깐"

### 1-2. dogfooding 1차 시도 발견 (2026-05-02 토요일)

상세 기록: `_workspace/dogfooding-2026-05-02.md`.

#### F-T2-001 (P1) — 토요일·휴장일에 KIS 모의 주문 자체 거부
KIS 모의 server 응답:
```json
{"rt_cd":"1","msg_cd":"40100000","msg1":"모의투자 영업일이 아닙니다."}
```
- **현상**: 자동매매 프로덕트가 장이 닫혔다는 이유로 사용자 흐름 전체가 먹통.
- **영향**: dogfooding 시작 시점이 토요일이라 dev 환경 검증을 월요일까지 미뤄야 함.
- **본질적 결함**: 시스템이 **장 상태를 인지하지 않은 채** 주문을 KIS로 무조건 보내고, 거부 응답을 단순 에러로 취급.

#### F-T2-002 (P1) — `EGW00202 GW 라우팅 오류`
직접 `curl` 검증과 어댑터 호출의 차이:
- **direct curl**: KIS gateway 통과 → `40100000` 영업일 거부 (정상 흐름)
- **`RealKisOrderAdapter` (Spring RestClient)**: gateway에서 `EGW00202 GW 라우팅 중 오류` 반환 → KIS 도달 실패

같은 endpoint·키·body지만 차이가 있음. RestClient의 추가 헤더 또는 body 직렬화 차이가 의심되며, **평일에도 같은 에러 가능성**이 있어 별도 hotfix가 필수.

#### F-T2-003 (P2) — Guard reject·KisApiException 시 `orders` row INSERT 0
1번 트랙 spec 의도대로 Guard reject·KisApiException은 `OrderResult.REJECTED`만 반환하고 DB 영속은 PLACED 시점에만 일어남. 사용자 입장에서:
- "방금 매수 시도했는데 `/orders`에 안 보임" → 의도/시스템 동작 불일치
- 운영 디버깅 시 `order_events` audit trail에 reject 기록이 없어 **사후 추적 불가**

`_workspace/dogfooding-2026-05-02.md`의 **옵션 C**가 본 spec에서 정식 채택됨.

#### F-T2-004 (P3) — `KisModeBootLogger`·`InitialUserSeeder` 실행 순서
부팅 로그가 misleading. `[KIS_MODE] active=DEV (KisCredentialProvider 미등록 또는 seed 부재)` 출력 후 직후에 `[SEED] default user seeded` — 사용자 혼동.

#### F-T2-005 (P3) — `docker compose --env-file .env` 명시 누락
working dir이 `docker/`일 때 `.env`를 자동 로드하지 않음. `scripts/build-and-deploy.sh`에 명시 필요.

#### F-T2-006 (P3) — `.env`에 `MAGICJAR_SECRET_MASTER_KEY` 2건 중복
운영 가이드 §9 master key rotation 시 정리.

### 1-3. 사용자 의도 추출 (architect 해석)

| 키워드 | 사용자 요구 | 본 spec의 응답 |
|---|---|---|
| "프로덕트 형태" | 자동매매 시스템이 **장외 시간에도 매끄럽게 작동** | §3 시스템 구조 + §5 데이터 관리 매트릭스 |
| "데이터 관리 체계" | 실시간 ↔ 캐시 ↔ 영속의 3-tier 정합성 | §5 + ADR 0033 (3-tier) |
| "장 상태 확인" | KRX 영업일·장중·장외 인지 FSM | §4.1 MarketSession 도메인 + ADR 0031 |
| "기존 값들로 처리" | 장외 시 last-cached or last-day-close fallback + 명시 표시 | §5 + ADR 0032 (fallback) |
| "dev에서 기본 모의투자" | 토요일에도 dev에서 주문 흐름 검증 가능 | §8 profile 매트릭스 + §10 시나리오 1·2 |
| "KIS 키 유지" | 폐기 안 함, 사용자 본인 책임 | ADR 0035 §"전제 조건"에 한 줄 |

---

## §2. 목표·비목표

### 2-1. 목표 (이번 트랙 범위 내)

1. **장 상태 인지 (Market Session FSM)**
   - KRX 영업일·장중·장외·점심시간·동시호가·휴장 상태를 시스템이 명시적으로 알고 의사결정.
   - 한국 시간대 기반 + 캘린더 데이터(공휴일·임시휴장) 시드.
2. **시세 Fallback 정책**
   - 장 OPEN 시 KIS WebSocket → tick → STOMP fanout (현행 유지).
   - 장외 시 last-cached quote 또는 last-day-close → STOMP에 `stale=true` 표시.
   - frontend는 stale 배지(예: "휴장 중", "장 마감, 14:30 기준") 노출.
3. **주문 처리 정책 (장외)**
   - 신규 `MarketSessionGuard` Guard chain 합류.
   - `dev` profile에선 mock 체결로 우회 (토요일에도 dogfooding 가능).
   - `prd` + `MOCK_KIS`·`REAL_KIS` 둘 다 영업일 외 주문은 명시 REJECTED.
4. **REJECTED Audit Trail (옵션 C)**
   - Guard reject·KisApiException 시도 `orders` row INSERT (`status=REJECTED`).
   - `order_events` row INSERT (`event_type=REJECTED`, `payload`에 reason).
   - frontend `/orders` 페이지에서 보이게.
5. **EGW00202 진단 + hotfix**
   - `RealKisOrderAdapter` raw HTTP 로깅 → curl 응답과 비교 → 차이 식별.
   - hashkey·헤더 정렬·body 직렬화 hotfix.
6. **dev 환경 기본 모의투자 정상 작동**
   - 토요일에도 사용자가 Dashboard 수동 주문 → 흐름 검증 + UI 표시 가능.
   - mock·dev·prd profile 매트릭스 명시.

### 2-2. 비목표 (다음 트랙 또는 보류)

- 분봉·일봉 backfill 강화 (3번 트랙 후보 — 실 KIS REST + scheduler).
- 외국인 수급·기관 수급 데이터 연동 (4번 트랙 후보).
- 자동 KillSwitch trigger (예: 일일 손실 -3% 도달 시 자동 ON) — 3번 Risk Guard 트랙.
- TimescaleDB 도입 — 데이터 양 임계점 도달 후 (현행 PostgreSQL 단일 인스턴스).
- KRX 캘린더 외부 동기화 (예: 한국거래소 공식 API 호출) — 1차는 정적 시드 + 수동 갱신.
- 알림(Slack·이메일) 통합 — 5번 트랙 후보.

---

## §3. 시스템 구조

### 3-1. 전체 흐름도

```
┌─────────────────── consumer (JVM) ──────────────────────┐
│                                                          │
│  ┌── MarketSessionResolver (시간 + 캘린더 FSM) ──┐       │
│  │   - now(KST)                                  │       │
│  │   - market_calendar(date) lookup              │       │
│  │   - state ∈ {PRE_MARKET, OPEN, LUNCH(폐지)*,  │       │
│  │              POST_MARKET, CLOSED, HOLIDAY}    │       │
│  │   * KRX는 2000년 점심시간 폐지 → CLOSED만 사용│       │
│  └───────────────────┬───────────────────────────┘       │
│                      │ MarketSessionState                │
│         ┌────────────┴────────────┐                      │
│         │                         │                      │
│  ┌──────▼─────────┐  ┌────────────▼────────────┐         │
│  │ TickIngest     │  │ Guard chain (수동 주문) │         │
│  │ (KIS WS or     │  │ - KillSwitch            │         │
│  │  CachedQuote)  │  │ - DailyOrderLimit       │         │
│  │                │  │ - PriceSanity           │         │
│  │ - OPEN: WS     │  │ - **MarketSessionGuard**│ ← 신규  │
│  │ - else: cache  │  │   (mock profile에선 PASS)│         │
│  │   replay or    │  └────────────┬────────────┘         │
│  │   silent       │               │                      │
│  └──────┬─────────┘               │                      │
│         │                         │                      │
│         │ TickEvent + stale flag  │ Pass / Reject(reason)│
│         │                         │                      │
└─────────┼─────────────────────────┼──────────────────────┘
          │                         │
          ▼                         ▼
   Kafka market.tick.v1     KisOrderSender / MockOrderSender
   (stale=false: 실시간)    (Reject도 orders·order_events INSERT)
   (stale=true: 캐시)
          │
          ▼
   STOMP /topic/market.tick.{code}    POST /api/orders 응답
          │                                  │
          ▼                                  ▼
   frontend Dashboard          frontend /orders 리스트
   - 가격 표시                    - REJECTED row 가시화
   - stale 배지 ("장 마감")       - reason 표시 ("MARKET_CLOSED" 등)
```

### 3-2. 모듈별 변경 포인트

| 모듈 | 신규 파일 / 변경 | 책임 |
|---|---|---|
| **domain** | `market/session/MarketSessionState.kt` (sealed) | FSM 상태 enum-like sealed class |
| | `market/session/MarketSession.kt` (data) | 단일 시점 스냅샷 (state, kstAt, asOfBusinessDate) |
| | `market/session/port/MarketCalendarRepository.kt` | 영업일·휴장일 조회 |
| | `market/session/port/MarketSessionResolver.kt` | 시간+캘린더 → MarketSession |
| | `market/cache/port/QuoteCache.kt` | last-quote 캐시 read/write |
| | `order/event/OrderRejectedEvent.kt` (신규) | REJECTED 분기용 도메인 이벤트 |
| | `common/RejectReason.kt` (sealed) | KILL_SWITCH·DAILY_LIMIT·PRICE_SANITY·MARKET_CLOSED·KIS_API_ERROR 등 |
| **consumer** | `application/session/MarketSessionResolverImpl.kt` | KST + 캘린더 lookup |
| | `application/session/MarketCalendarBootSeeder.kt` | V10 시드 보강 (가능 시 KIS API로 갱신 검토) |
| | `application/guard/MarketSessionGuard.kt` (신규) | profile 분기 — mock에선 PASS |
| | `application/guard/GuardChainConfig.kt` | MarketSessionGuard 추가 (`@Order(150)`) |
| | `application/order/KisOrderSender.kt` (변경) | Reject·KisApiException 분기에 orders/order_events INSERT |
| | `application/order/MockOrderSender.kt` (변경) | dev profile에서도 사용 가능하도록 Bean 분리 + REJECTED audit 통합 |
| | `application/cache/QuoteCacheRedis.kt` (신규) | Redis 어댑터 |
| | `application/market/TickFallbackEmitter.kt` (신규) | 장외 시 last-cached emit (1초 주기 또는 WS 끊김 시) |
| | `infrastructure/kis/RealKisOrderAdapter.kt` (변경) | EGW00202 hotfix (hashkey·헤더 추가) + raw HTTP 로깅 옵션 |
| | `application/order/OrderSenderProfileConfig.kt` (변경) | dev·prd 분기 정밀화 (§8 매트릭스) |
| **api** | `infrastructure/persistence/MarketCalendarJpaAdapter.kt` (신규) | 영업일 조회 |
| | `controller/MarketSessionController.kt` (신규) | `GET /api/market/session` 현재 상태 노출 |
| | `controller/dto/MarketSessionResponse.kt` (신규) | state·kstAt·nextOpenAt 등 |
| | `messaging/listener/TickStompFanoutListener.kt` (변경) | TickEvent에 `stale` flag + STOMP payload 확장 |
| **batch** | `job/calendar/MarketCalendarSyncJob.kt` (선택) | 분기·연도 단위 캘린더 갱신 (1차는 수동 SQL OK) |
| **frontend** | `hooks/useMarketSession.ts` (신규) | `/api/market/session` polling (1분) |
| | `components/MarketSessionBadge.tsx` (신규) | "장중"·"장 마감 14:30 기준"·"휴장" 등 배지 |
| | `pages/Dashboard/index.tsx` (변경) | 종목 카드에 stale 배지 + asOf 표시 |
| | `pages/SymbolDetail/index.tsx` (변경) | 차트 상단 MarketSessionBadge |
| | `pages/Orders/index.tsx` (변경) | REJECTED row 가시화 + reason 컬럼 |
| | `api/types.ts` (변경) | MarketSessionResponse·TickMessage `stale` 추가 |
| **infra (Flyway)** | `V10__market_calendar.sql` (신규) | `market_calendar` 테이블 + 2026·2027 시드 |
| | `V11__order_reject_reason_enum.sql` (선택) | `orders.reject_reason_code` 컬럼 추가 (구조화) |

### 3-3. 컨테이너 배치 (변경 없음)

api·batch·consumer·frontend·pyworker 5 JVM/SPA + postgres·kafka·redis·minio·kafka-ui 5 인프라 = 10 컨테이너 (현행 9 + Redis가 이미 존재하지만 사용처 확장).

---

## §4. 도메인 모델 변경

### 4-1. `MarketSessionState` (sealed class) — 신규

```kotlin
package com.mj.domain.market.session

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * KRX 단일 거래소 기준 장 상태 FSM.
 *
 * KRX 운영 시간 (현행, 2026-05 기준):
 *   - 정규장: 09:00 ~ 15:30
 *   - 시간외 단일가: 15:40 ~ 16:00 (별도 PRE_MARKET·POST_MARKET 분리 안 함, 단순화)
 *   - 동시호가: 08:30~09:00 (PRE_MARKET) / 15:20~15:30 (정규장 종료 직전, OPEN 유지)
 *   - 점심시간: 폐지 (2000년부터 연속 운영)
 *
 * sealed class를 쓰는 이유:
 *   - Kotlin exhaustive when 강제 → 신규 상태 추가 시 컴파일 에러로 누락 방지
 *   - 각 상태가 추가 데이터 보유 가능 (예: HOLIDAY는 reason 동반)
 */
sealed class MarketSessionState {
    /** 09:00 이전 (영업일 한정). 동시호가 포함. */
    data object PRE_MARKET : MarketSessionState()

    /** 09:00 ~ 15:30 (영업일 한정). 정규장. */
    data object OPEN : MarketSessionState()

    /** 15:30 이후 ~ 자정 (영업일 한정). 시간외 단일가 포함. */
    data object POST_MARKET : MarketSessionState()

    /** 비영업일 — 토·일 또는 평일 휴장. */
    data class CLOSED(val reason: String) : MarketSessionState()

    /** 캘린더에 명시된 공휴일·임시휴장. */
    data class HOLIDAY(val reason: String, val name: String) : MarketSessionState()

    fun isTradingAllowed(): Boolean = this is OPEN
    fun isMarketOpen(): Boolean = this is OPEN
    fun isOrderAcceptable(): Boolean = this is OPEN || this is PRE_MARKET || this is POST_MARKET
    // ※ KIS 모의는 PRE/POST에서도 받아주지만 정규장 외 거부 가능성 — §10 시나리오 검증 후 결정
}

data class MarketSession(
    val state: MarketSessionState,
    val kstAt: OffsetDateTime,
    /** 가장 최근 (현재 또는 직전) 영업일 — 휴장 시 fallback asOf 기준 */
    val lastBusinessDate: LocalDate,
    /** 다음 정규장 시작 시각 (없으면 null) */
    val nextOpenAt: OffsetDateTime?,
)

fun zoneKst(): ZoneId = ZoneId.of("Asia/Seoul")
```

### 4-2. `RejectReason` (sealed class) — 신규

```kotlin
package com.mj.domain.common

/**
 * 주문 거부 사유 통합 표현. Guard·KisApiException 양쪽을 같은 분류 체계로 표현 →
 * orders.reject_reason_code 구조화 + frontend label 일관.
 */
sealed class RejectReason(val code: String, val display: String) {
    data object KillSwitchActive : RejectReason("KILL_SWITCH", "비상 정지(KillSwitch ON)")
    data class DailyLimitCount(val limit: Int) : RejectReason("DAILY_LIMIT_COUNT", "일일 주문 건수 한도 초과($limit)")
    data class DailyLimitValue(val limitKrw: Long) : RejectReason("DAILY_LIMIT_VALUE", "일일 주문 금액 한도 초과(${limitKrw.format()}원)")
    data class PriceOutOfRange(val low: java.math.BigDecimal, val high: java.math.BigDecimal) :
        RejectReason("PRICE_SANITY", "가격 범위 초과 (${low}~${high})")
    data class MarketClosed(val sessionState: String, val nextOpenAt: String?) :
        RejectReason("MARKET_CLOSED", "장 외 시간 ($sessionState)" + (nextOpenAt?.let { ", 다음 개장 $it" } ?: ""))
    data class KisApiError(val rtCd: String, val msgCd: String, val msgRaw: String) :
        RejectReason("KIS_API_ERROR", "KIS 거부: $msgCd $msgRaw")

    private companion object {
        fun Long.format(): String = "%,d".format(this)
    }
}
```

### 4-3. `OrderRejectedEvent` — 신규

```kotlin
package com.mj.domain.order.event

import com.mj.domain.common.Order
import com.mj.domain.common.RejectReason
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Guard reject 또는 KIS API exception 발생 시 발행.
 * KisOrderSender 분기에서 직접 orders·order_events INSERT 후 본 이벤트도 Kafka로 발행 →
 * api STOMP fanout으로 frontend /orders 즉시 갱신.
 */
data class OrderRejectedEvent(
    val intentId: UUID,
    val userId: UUID,
    val accountNo: String,
    val kisMode: String,
    val code: String,
    val reason: RejectReason,
    val rejectedAt: OffsetDateTime,
)
```

### 4-4. `QuoteCache` (port) — 신규

```kotlin
package com.mj.domain.market.cache.port

import com.mj.domain.market.event.TickEvent
import java.time.Instant

interface QuoteCache {
    fun put(code: String, tick: TickEvent)
    fun get(code: String): CachedQuote?

    data class CachedQuote(
        val code: String,
        val price: java.math.BigDecimal,
        val volume: Long,
        val source: String,
        val cachedAt: Instant,
    )
}
```

### 4-5. `MarketCalendarRepository` (port) — 신규

```kotlin
package com.mj.domain.market.session.port

import java.time.LocalDate

interface MarketCalendarRepository {
    fun isBusinessDay(date: LocalDate): Boolean
    fun getHolidayReason(date: LocalDate): String?
    /** 주어진 시점 이후 가장 가까운 다음 영업일 */
    fun nextBusinessDay(after: LocalDate): LocalDate
    /** 주어진 시점 이전 가장 가까운 직전 영업일 */
    fun previousBusinessDay(before: LocalDate): LocalDate
}
```

### 4-6. 기존 도메인 변경 — `TickEvent`

```kotlin
data class TickEvent(
    val code: String,
    val at: Instant,
    val price: BigDecimal,
    val volume: Long,
    val source: String = "KIS_WS",
    val stale: Boolean = false,         // ← 신규 (default false 하위호환)
    val asOf: Instant? = null,           // ← 신규 (cached일 때 원본 시각)
)
```

기존 producer는 default 값으로 stale=false라 회귀 0. consumer 측 listener는 두 신규 필드를 STOMP payload에 그대로 통과.

### 4-7. 기존 도메인 변경 — `OrderRecord`

`reject_reason TEXT`만 있는 현행에 추가:
- `reject_reason_code VARCHAR(32)` (예: `KILL_SWITCH`, `MARKET_CLOSED`, `KIS_API_ERROR`)
- `reject_reason TEXT` (기존, display 메시지)

V11 Flyway에서 ALTER + 백필 (NULL OK).

---

## §5. 데이터 관리 매트릭스 — 가장 중요한 표

### 5-1. profile × session × source × fallback

| profile | MarketSession | 시세 source (primary) | 시세 fallback | 주문 sender | 비고 |
|---|---|---|---|---|---|
| `mock` | 무관 | MockTickGenerator (1초/5종목) | n/a | MockOrderSender (즉시 FILLED) | offline 데모·CI |
| `dev` | OPEN | KIS WS (실 모의 시세) | n/a | KisOrderSender → KIS 모의계좌 | **사용자 정상 운영** |
| `dev` | PRE/POST_MARKET | KIS WS or last-cached (KIS WS 정지 가능성) | QuoteCache (Redis) | KisOrderSender → KIS 모의계좌 (받아주면 OK) | KIS PRE/POST 미지원 시 mock fallback |
| `dev` | CLOSED/HOLIDAY | n/a | **QuoteCache (last-day-close)** + stale=true | **MockOrderSender** (dogfooding) | **토요일 검증 가능** ✓ |
| `prd` MOCK_KIS | OPEN | KIS WS (모의) | n/a | KisOrderSender → KIS 모의계좌 | 모의 운영 |
| `prd` MOCK_KIS | CLOSED/HOLIDAY | QuoteCache | last-day-close + stale | KisOrderSender → KIS 모의 (40100000 거부 — 그대로 REJECTED audit) | UI 표시는 stale, 주문은 차단 |
| `prd` REAL_KIS | OPEN | KIS WS (실전) | n/a | KisOrderSender → KIS 실계좌 | **실거래** |
| `prd` REAL_KIS | CLOSED/HOLIDAY | QuoteCache | last-day-close + stale | **REJECTED at MarketSessionGuard** (KIS 호출 0) | 실계좌는 더 엄격 |

### 5-2. 핵심 결정 — dev 환경 토요일 흐름

사용자 의도: "dev에서 기본 모의투자를 토요일에도 검증". 두 가지 설계 옵션:

| 옵션 | dev + CLOSED 시 OrderSender | 트레이드오프 |
|---|---|---|
| **A. 그대로 KisOrderSender → KIS 모의 → 40100000 거부 → REJECTED audit** | KisOrderSender (with REJECTED audit trail) | 실 KIS 거부 흐름 그대로 검증, but 주말 흐름 자체 검증 불가 |
| **B. dev + CLOSED 시 자동 MockOrderSender 전환** | MockOrderSender (즉시 FILLED) | 토요일에도 흐름 끝까지 검증, but mock 흐름이 실제와 다름 |
| **C. dev에서 사용자가 명시 toggle (`MAGICJAR_DEV_OFFLINE_FALLBACK=true`)** | toggle on이면 MockOrderSender, off이면 KisOrderSender | 사용자 의도 명시 + 둘 다 가능 |

**채택: 옵션 C** (사용자 의지 명시 + 안전). default `false` (KIS 호출 시도 → 40100000 → REJECTED audit). dogfooding 시 사용자가 toggle on. `MAGICJAR_DEV_OFFLINE_FALLBACK=true` 환경변수로 명시.

### 5-3. 캐시 레이어 — 3-tier 데이터 흐름

```
실시간 (휘발)              캐시 (Redis)              영속 (PostgreSQL)
─────────────             ──────────────            ──────────────────
TickEvent (1초/종목)  →   QuoteCache               candle (D1·1m·5m 등)
- KIS WS 직접 흐름        - last-quote (key:        - DailyCandleBackfillJob
- Kafka market.tick.v1      "quote:last:005930")    - V7·V8 시드
- STOMP /topic/...        - TTL 7일 (영업일 5+여분)  - api 영구 보존
- 갱신: KIS WS or         - 갱신: TickIngest
  TickFallbackEmitter        listener가 매 tick에     market_calendar
                              put                     - V10 시드
                            - read: TickFallback     - 분기·연도 갱신
                              Emitter, MarketSession  
                              Controller
```

### 5-4. Redis Key 설계 (신규)

| key | type | TTL | 갱신 주체 |
|---|---|---|---|
| `quote:last:{code}` | hash {price, volume, source, cachedAt} | 7일 | TickIngest 매 tick에 put |
| `quote:lastbusinessday:{code}:{yyyyMMdd}` | hash | 14일 | 일 1회 cron (장 마감 직후 16:30) |
| `session:cache:current` | hash {state, lastBusinessDate, nextOpenAt} | 60초 | MarketSessionResolver 1분 cache (자체) |

기존 사용처: pyworker 내부 + 향후 token cache. 본 트랙에서 시세 캐시 도입.

---

## §6. 인터페이스·이벤트

### 6-1. REST endpoint (api 모듈)

| Method | Path | 설명 | 신규/변경 |
|---|---|---|---|
| GET | `/api/market/session` | 현재 MarketSession 스냅샷 | **신규** |
| GET | `/api/market/calendar?from=&to=` | 영업일·휴장일 목록 (frontend 캘린더 위젯 후속) | **신규** |
| GET | `/api/orders` | 주문 목록 (REJECTED 포함) | 변경 (filter param `status`) |
| GET | `/api/orders/{id}/events` | 주문별 이벤트 lifecycle | **신규** (audit trail 가시화) |

#### `GET /api/market/session` 응답 예시

```json
{
  "state": "CLOSED",
  "stateDetail": {
    "type": "CLOSED",
    "reason": "Saturday"
  },
  "kstAt": "2026-05-02T14:30:00+09:00",
  "lastBusinessDate": "2026-05-01",
  "nextOpenAt": "2026-05-04T09:00:00+09:00",
  "isTradingAllowed": false,
  "isOrderAcceptable": false
}
```

휴일(공휴일):
```json
{
  "state": "HOLIDAY",
  "stateDetail": {
    "type": "HOLIDAY",
    "reason": "PUBLIC_HOLIDAY",
    "name": "어린이날"
  },
  "kstAt": "2026-05-05T10:00:00+09:00",
  "lastBusinessDate": "2026-05-04",
  "nextOpenAt": "2026-05-06T09:00:00+09:00",
  ...
}
```

### 6-2. Kafka 토픽

| 토픽 | producer | consumer | 변경 |
|---|---|---|---|
| `market.tick.v1` | consumer (KIS WS·MockTickGenerator·TickFallbackEmitter) | api (STOMP fanout) | TickEvent에 `stale`·`asOf` 추가 (하위호환) |
| `order.rejected.v1` | consumer (KisOrderSender Reject 분기) | api (STOMP fanout) | **신규 토픽** |
| `market.session.v1` | consumer (MarketSessionResolver 상태 전환 시) | api (STOMP fanout) | **신규 토픽** (선택 — frontend polling으로도 충분) |

### 6-3. STOMP 토픽 (api → frontend)

| 토픽 | 메시지 |
|---|---|
| `/topic/market.tick.{code}` | TickMessage (with stale/asOf) |
| `/topic/orders` | OrderMessage (PLACED·EXECUTED·REJECTED 모두) |
| `/topic/market.session` | MarketSessionResponse (선택) |

### 6-4. 환경변수 (신규·변경)

| env | default | 설명 |
|---|---|---|
| `MAGICJAR_MARKET_SESSION_ENABLED` | `true` | MarketSessionResolver 활성 (false 시 항상 OPEN으로 가정) |
| `MAGICJAR_DEV_OFFLINE_FALLBACK` | `false` | dev profile + CLOSED 시 MockOrderSender 자동 전환 (§5.2) |
| `MAGICJAR_QUOTE_CACHE_TTL_DAYS` | `7` | Redis last-quote TTL |
| `MAGICJAR_KIS_RAW_HTTP_LOGGING` | `false` | RealKisOrderAdapter raw HTTP 로깅 (EGW00202 진단용) |
| `MAGICJAR_KIS_HASHKEY_ENABLED` | `true` | EGW00202 hotfix — POST 요청 hashkey 자동 첨부 (T6·T7에서 도입) |

---

## §7. Guard chain 변경

### 7-1. 신규 Guard — `MarketSessionGuard`

```kotlin
package com.mj.consumer.application.guard

@Component
@Order(150)  // KillSwitch(100) → MarketSessionGuard(150) → DailyOrderLimit(200) → PriceSanity(300)
class MarketSessionGuard(
    private val resolver: MarketSessionResolver,
    private val env: org.springframework.core.env.Environment,
) : OrderGuard {

    override fun check(userId: UUID, accountNo: String, order: Order): GuardDecision {
        // mock profile에선 PASS (단위테스트·CI 흐름 검증)
        if (env.activeProfiles.contains("mock")) return GuardDecision.Pass

        val session = resolver.resolve()
        return when (val state = session.state) {
            MarketSessionState.OPEN -> GuardDecision.Pass

            MarketSessionState.PRE_MARKET, MarketSessionState.POST_MARKET ->
                // 1차: PASS (KIS가 받아주는지 시도). KIS rt_cd 거부 시 KisApiException → REJECTED audit
                GuardDecision.Pass

            is MarketSessionState.CLOSED -> {
                // dev + offline-fallback이면 OrderSenderProfileConfig가 MockOrderSender로 라우팅
                // → 본 Guard는 PASS (MockOrderSender는 시간 무관 FILLED 시뮬)
                if (isDevOfflineFallback()) GuardDecision.Pass
                else GuardDecision.Reject(
                    RejectReason.MarketClosed(state.reason, session.nextOpenAt?.toString())
                )
            }

            is MarketSessionState.HOLIDAY -> {
                if (isDevOfflineFallback()) GuardDecision.Pass
                else GuardDecision.Reject(
                    RejectReason.MarketClosed("${state.reason}: ${state.name}", session.nextOpenAt?.toString())
                )
            }
        }
    }

    private fun isDevOfflineFallback(): Boolean =
        env.activeProfiles.contains("dev") &&
            env.getProperty("magicjar.dev.offline-fallback", Boolean::class.java, false)
}
```

### 7-2. Guard 순서

```
@Order(100) KillSwitchGuard         — 비상 정지 최우선
@Order(150) MarketSessionGuard      — ★신규 — 장 상태
@Order(200) DailyOrderLimitGuard    — 폭주 한도
@Order(300) PriceSanityGuard        — 가격 범위
```

### 7-3. Guard reject 시 audit trail 통합 — `KisOrderSender` 변경

기존 (1번 트랙):
```kotlin
when (val d = guard.check(userId, accountNo, order)) {
    is GuardDecision.Reject -> {
        log.warn(...)
        return OrderResult(REJECTED, rejectReason=d.reason)  // ← DB INSERT 0
    }
}
```

신규 (2번 트랙):
```kotlin
when (val d = guard.check(userId, accountNo, order)) {
    is GuardDecision.Reject -> {
        recordRejected(order, userId, accountNo, kisMode, d.reason, source = "GUARD")
        return OrderResult(REJECTED, rejectReason=d.reason.display)
    }
}
```

`recordRejected`는:
1. `orders.save(OrderRecord(status=REJECTED, kis_order_no = "REJECTED-${intentId}", reject_reason_code, reject_reason))`
   - **kis_order_no UNIQUE 제약 우회**: 거부된 주문은 KIS에 도달하지 않아 kis_order_no가 없음. 합의 — sentinel 값 `REJECTED-{uuid}` 또는 NULLABLE로 변경.
   - 채택: 컬럼을 NULLABLE로 변경 + UNIQUE는 `(kis_mode, kis_order_no) WHERE kis_order_no IS NOT NULL` 부분 인덱스 (V11 Flyway).
2. `orderEvents.insertIdempotent(OrderEventRecord(event_type=REJECTED, payload=reason JSON))`
3. `OrderRejectedEvent` Kafka publish → STOMP fanout

KisApiException 분기도 동일하게 `recordRejected(source = "KIS_API")` 호출.

### 7-4. 기존 Guard reject 메시지 통일

각 Guard의 `Reject(reason: String)`을 `Reject(reason: RejectReason)`으로 변경:

```kotlin
sealed interface GuardDecision {
    data object Pass : GuardDecision
    data class Reject(val reason: RejectReason) : GuardDecision  // ← String → RejectReason
}
```

기존 Guard 3개 (KillSwitch·DailyOrderLimit·PriceSanity)는 RejectReason의 적절한 case를 반환하도록 수정. 영향: 기존 테스트 assertion 업데이트.

---

## §8. Profile 분기 매트릭스

### 8-1. component × profile

| component | mock | dev | prd MOCK_KIS | prd REAL_KIS |
|---|---|---|---|---|
| **MarketSessionResolver** | NoOp (always OPEN) | active | active | active |
| **MarketSessionGuard** | bypass (PASS) | active (with offline-fallback toggle) | active | active (strict) |
| **MarketCalendarRepository** | InMemoryDummy | JpaAdapter | JpaAdapter | JpaAdapter |
| **MockTickGenerator** | active (1초/5종목) | inactive | inactive | inactive |
| **TickFallbackEmitter** | inactive | active (CLOSED 시) | active (CLOSED 시) | active (CLOSED 시) |
| **KisRestPort (시세)** | StubKisRestAdapter | RealKisRestAdapter (모의) | RealKisRestAdapter (모의) | RealKisRestAdapter (실전) |
| **KisOrderPort** | n/a | RealKisOrderAdapter (모의) | RealKisOrderAdapter (모의) | RealKisOrderAdapter (실전) |
| **OrderSender** | MockOrderSender | KisOrderSender (단, CLOSED + offline-fallback이면 Mock) | KisOrderSender | KisOrderSender (KillSwitch+이중 게이트) |
| **QuoteCache** | InMemoryQuoteCache | RedisQuoteCache | RedisQuoteCache | RedisQuoteCache |
| **WebSocket KIS** | inactive | active (영업일 OPEN) | active | active |

### 8-2. Profile 결정 흐름도

```
SPRING_PROFILES_ACTIVE 환경변수
   │
   ▼
mock?  ── yes ──→ MockOrderSender, MockTickGenerator, NoOp resolver
   │ no
   ▼
dev?   ── yes ──→ MarketSessionGuard active
                   ├─ session==OPEN?       → KisOrderSender (KIS 모의)
                   ├─ session==CLOSED + 
                   │     MAGICJAR_DEV_OFFLINE_FALLBACK=true → MockOrderSender (FILLED)
                   └─ session==CLOSED + flag=false → KisOrderSender → KIS 거부 → REJECTED audit
   │ no
   ▼
prd  → user_trading_config.trading_mode 분기
       ├─ MOCK_KIS → KisOrderSender → KIS 모의서버
       └─ REAL_KIS → KisOrderSender → KIS 실전서버 (MAGICJAR_ALLOW_REAL=true 강제)
```

---

## §9. 마이그레이션·캐시·캘린더

### 9-1. V10 — `market_calendar` 테이블 + 시드

```sql
-- V10__market_calendar.sql

CREATE TABLE market_calendar (
  calendar_date DATE PRIMARY KEY,
  is_business_day BOOLEAN NOT NULL,
  holiday_name VARCHAR(64),
  holiday_reason VARCHAR(32),  -- PUBLIC_HOLIDAY / TEMPORARY_CLOSURE / WEEKEND
  source VARCHAR(16) NOT NULL DEFAULT 'STATIC_SEED',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_market_calendar_business ON market_calendar(is_business_day, calendar_date);

-- 2026 한국 공휴일 시드 (요약)
INSERT INTO market_calendar (calendar_date, is_business_day, holiday_name, holiday_reason) VALUES
  ('2026-01-01', false, '신정', 'PUBLIC_HOLIDAY'),
  ('2026-02-16', false, '설날', 'PUBLIC_HOLIDAY'),
  ('2026-02-17', false, '설날 연휴', 'PUBLIC_HOLIDAY'),
  ('2026-02-18', false, '설날 대체공휴일', 'PUBLIC_HOLIDAY'),
  ('2026-03-01', false, '삼일절', 'PUBLIC_HOLIDAY'),  -- 일요일이지만 명시
  ('2026-05-01', false, '근로자의 날', 'PUBLIC_HOLIDAY'),  -- KRX 휴장 (법정 공휴일 아니지만 KRX 휴장)
  ('2026-05-05', false, '어린이날', 'PUBLIC_HOLIDAY'),
  ('2026-05-25', false, '부처님 오신 날', 'PUBLIC_HOLIDAY'),
  ('2026-06-06', false, '현충일', 'PUBLIC_HOLIDAY'),  -- 토요일
  ('2026-08-15', false, '광복절', 'PUBLIC_HOLIDAY'),  -- 토요일
  ('2026-08-17', false, '광복절 대체공휴일', 'PUBLIC_HOLIDAY'),
  ('2026-09-25', false, '추석 연휴', 'PUBLIC_HOLIDAY'),
  ('2026-09-26', false, '추석', 'PUBLIC_HOLIDAY'),  -- 토요일
  ('2026-09-27', false, '추석 연휴', 'PUBLIC_HOLIDAY'),  -- 일요일
  ('2026-09-28', false, '추석 대체공휴일', 'PUBLIC_HOLIDAY'),
  ('2026-10-03', false, '개천절', 'PUBLIC_HOLIDAY'),  -- 토요일
  ('2026-10-09', false, '한글날', 'PUBLIC_HOLIDAY'),
  ('2026-12-25', false, '성탄절', 'PUBLIC_HOLIDAY'),
  ('2026-12-31', false, '연말 휴장', 'TEMPORARY_CLOSURE')  -- KRX 매년 12-31 휴장
ON CONFLICT (calendar_date) DO NOTHING;

-- 주말 자동 시드 (2026-01-01 ~ 2027-12-31)
INSERT INTO market_calendar (calendar_date, is_business_day, holiday_reason)
SELECT d::date, false, 'WEEKEND'
  FROM generate_series('2026-01-01'::date, '2027-12-31'::date, '1 day'::interval) d
 WHERE EXTRACT(DOW FROM d) IN (0, 6)
ON CONFLICT (calendar_date) DO NOTHING;

-- 평일이면서 휴장이 아닌 날 자동 시드
INSERT INTO market_calendar (calendar_date, is_business_day, holiday_reason)
SELECT d::date, true, NULL
  FROM generate_series('2026-01-01'::date, '2027-12-31'::date, '1 day'::interval) d
 WHERE EXTRACT(DOW FROM d) NOT IN (0, 6)
ON CONFLICT (calendar_date) DO NOTHING;

-- 2027 휴장일은 운영 시 수동 또는 batch job (T8)으로 갱신
```

### 9-2. V11 — `orders.reject_reason_code` 컬럼 + `kis_order_no` NULLABLE

```sql
-- V11__order_reject_audit.sql

ALTER TABLE orders ADD COLUMN reject_reason_code VARCHAR(32);
ALTER TABLE orders ALTER COLUMN kis_order_no DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN krx_fwdg_ord_orgno DROP NOT NULL;

-- 기존 UNIQUE 제약 변경
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_kis_mode_kis_order_no_key;
CREATE UNIQUE INDEX uq_orders_kis_order
  ON orders(kis_mode, kis_order_no)
  WHERE kis_order_no IS NOT NULL;

-- order_events에서도 REJECTED는 kis_order_no가 없을 수 있음
ALTER TABLE order_events ALTER COLUMN kis_order_no DROP NOT NULL;
ALTER TABLE order_events DROP CONSTRAINT IF EXISTS order_events_kis_mode_kis_order_no_event_type_cumulative_qty_key;
CREATE UNIQUE INDEX uq_order_events_idem
  ON order_events(kis_mode, COALESCE(kis_order_no, ''), event_type, COALESCE(cumulative_qty, -1))
  WHERE kis_order_no IS NOT NULL OR event_type = 'REJECTED';
-- ※ REJECTED는 (intentId, event_type) 단일성으로도 충분 — alternative:
CREATE UNIQUE INDEX uq_order_events_rejected
  ON order_events(order_id, event_type)
  WHERE event_type = 'REJECTED';

CREATE INDEX idx_orders_reject_code ON orders(reject_reason_code) WHERE reject_reason_code IS NOT NULL;
```

### 9-3. Redis 캐시 부팅·갱신

- 부팅 시 cold cache: 첫 KIS WS tick 도착 또는 첫 RealKisRestAdapter quote 호출 시 자동 채워짐.
- 부팅 직후 사용자가 페이지 진입 시 stale=null·"데이터 수집 중" 상태 → frontend는 Skeleton 또는 "데이터 없음" 표시.
- 장 마감 직후 16:30 cron (별도 batch job) — 모든 watchlist 종목의 last-quote을 `quote:lastbusinessday:{code}:{date}`로 복사 (D+N 영업일 동안 보존).

---

## §10. 시나리오

### S1. 토요일 dev 환경 매수 시도 (사용자 dogfooding)

**사전 상태**: profile=dev, MAGICJAR_DEV_OFFLINE_FALLBACK=false, KillSwitch OFF.

```
1. 사용자가 Dashboard에서 "005930 1주 LIMIT 60000원 매수" 버튼 클릭
2. POST /api/orders → Kafka order.intent.v1
3. consumer가 OrderIntent 수신 → KisOrderSender.send()
4. GuardChain:
   - KillSwitch: PASS (OFF)
   - MarketSessionGuard: resolver.resolve() → MarketSession(state=CLOSED("Saturday"), kstAt, lastBusinessDate=2026-05-01, nextOpenAt=2026-05-04T09:00)
     → isDevOfflineFallback() = false → REJECT(MarketClosed("Saturday", "2026-05-04T09:00:00+09:00"))
5. recordRejected(order, reason) →
   - orders INSERT (status=REJECTED, kis_order_no=NULL, reject_reason_code="MARKET_CLOSED",
     reject_reason="장 외 시간 (Saturday), 다음 개장 2026-05-04T09:00:00+09:00")
   - order_events INSERT (event_type=REJECTED, payload={...})
   - Kafka order.rejected.v1 publish
6. api STOMP fanout /topic/orders → frontend
7. frontend /orders 리스트 즉시 갱신 — REJECTED row 표시 (이유: "장 외 시간 (Saturday)...")
8. Dashboard에 stale 배지 "장 마감 (5/1 기준)" 노출
```

**검증 포인트**: orders 테이블 row 1건 INSERT, reject_reason_code='MARKET_CLOSED', /orders 페이지에 가시화.

### S2. 토요일 dev + offline-fallback toggle ON (dogfooding 흐름 검증)

**사전 상태**: profile=dev, **MAGICJAR_DEV_OFFLINE_FALLBACK=true**, KillSwitch OFF.

```
1~3. 동일
4. GuardChain:
   - KillSwitch: PASS
   - MarketSessionGuard: isDevOfflineFallback() = true → PASS (mock 우회 표시 로그)
   - DailyOrderLimit: PASS
   - PriceSanity: PASS (cached close 71,500 ±30%)
5. OrderSenderProfileConfig가 dev+CLOSED+fallback 조건이면 MockOrderSender 빈을 inject
   → MockOrderSender.send() → OrderResult(FILLED)
6. KisOrderSender wrapper에서 FILLED 결과를 받아:
   - orders INSERT (status=FILLED, kis_order_no="MOCK-{intentId}", filled_qty=1, avg_fill_price=60000)
   - order_events INSERT (event_type=PLACED, source=INTERNAL)
   - order_events INSERT (event_type=EXECUTED, source=INTERNAL, cumulative_qty=1, exec_price=60000)
7. Kafka order.executed.v1 → STOMP /topic/orders
8. frontend /orders에 FILLED row + Dashboard 잔고 갱신
```

**검증 포인트**: 토요일에도 사용자가 매수→체결 흐름 검증 가능.

### S3. 평일 09:30 정상 매수 (목표 정상 흐름)

**사전 상태**: profile=dev, session=OPEN, KillSwitch OFF.

```
1~3. 동일
4. GuardChain 모두 PASS
5. KisOrderSender → RealKisOrderAdapter.placeOrder(userId, order)
6. KIS 모의서버 응답 정상 → orders INSERT (PENDING) + order_events (PLACED)
7. 잠시 후 KIS WS H0STCNI0 체결통보 → KisExecutionNoticeListener → order_events (EXECUTED) + OrderProjectionUpdater → orders.status=FILLED
8. STOMP 두 차례 fanout (PLACED, EXECUTED)
```

### S4. EGW00202 발생 → 진단·hotfix 검증

**사전 상태**: T6에서 raw HTTP 로깅 활성, T7에서 hashkey 추가 후.

```
1. 사용자가 매수 시도
2. KisOrderSender → RealKisOrderAdapter.placeOrder
3. RestClient 로그:
   - request: POST /uapi/domestic-stock/v1/trading/order-cash
     headers: authorization=Bearer ..., appkey=..., appsecret=..., tr_id=VTTC0012U, custtype=P,
              hashkey=<sha256 of body>, content-type=application/json; charset=utf-8
     body: {"CANO":"...","ACNT_PRDT_CD":"...","PDNO":"005930",...}
4. KIS 응답: 정상 (rt_cd=0 또는 영업일 거부 — 평일/주말 따라)
5. EGW00202가 사라졌으면 hotfix 성공
```

**대조 검증**: hashkey 비활성 시 EGW00202 재현 (`MAGICJAR_KIS_HASHKEY_ENABLED=false`).

### S5. 장 마감 직후 시세 fallback

**사전 상태**: profile=dev, session OPEN→CLOSED 전환 시점 (15:30 직후).

```
1. KIS WS H0STCNT0 끊김 또는 마지막 tick (15:29:58)
2. TickFallbackEmitter @Scheduled(fixedDelay=10s) 활성:
   - resolver.resolve() = CLOSED 또는 POST_MARKET
   - QuoteCache.get(code) → CachedQuote(price=71500, cachedAt=15:29:58)
   - publisher.publish(TickEvent(code, at=now, price=71500, volume=0,
     source="CACHE", stale=true, asOf=15:29:58))
3. STOMP fanout → frontend
4. Dashboard 종목 카드:
   - 가격 71,500 (deemphasized 표시)
   - 배지 "장 마감 (15:30 기준)" 노출
   - 변화율 0% 또는 "장중 변동 없음" 표시
```

### S6. 휴일 (어린이날 5/5)

**사전 상태**: profile=dev, 5/5 평일이지만 공휴일.

```
1. resolver.resolve() = HOLIDAY("PUBLIC_HOLIDAY", "어린이날")
2. /api/market/session 응답: state=HOLIDAY, name=어린이날, nextOpenAt=2026-05-06T09:00
3. frontend 모든 페이지 상단 MarketSessionBadge "휴장 — 어린이날 (다음 개장 5/6)"
4. 주문 시도 → MarketSessionGuard REJECT(MarketClosed("PUBLIC_HOLIDAY: 어린이날", ...))
```

---

## §11. 비범위·후속 트랙

| 항목 | 사유 | 후속 트랙 |
|---|---|---|
| KRX 캘린더 외부 동기화 (한국거래소 API) | 1차는 정적 시드로 충분, 갱신 빈도 낮음 | 3번 트랙 후보 |
| 자동 KillSwitch trigger (drawdown·연속 손실) | Risk Guard 본격 도입 시 | 3번 트랙 (Risk Guard) |
| 동시호가·시간외단일가 정밀 처리 | KIS가 시점별 거부 정책 다름 — 운영하며 학습 | dogfooding 결과 반영 후 |
| 알림 (Slack·이메일·푸시) | UI 알림 우선 | 5번 트랙 |
| 분봉 backfill (1m·5m 영업일별) | 본 트랙은 일봉·last-quote 위주 | 4번 트랙 (시세 깊이 강화) |
| 외국인·기관 수급 데이터 | KIS 별도 endpoint, 본 트랙 범위 외 | 4번 트랙 |
| TimescaleDB | 데이터 양 임계점 도달 후 | post-MVP |

---

## §12. 12개 핵심 결정 (architect brainstorm)

| # | 결정 | 채택 옵션 | 근거 |
|---|---|---|---|
| **D1** | 장 상태 표현 | sealed class `MarketSessionState` (PRE_MARKET/OPEN/POST_MARKET/CLOSED/HOLIDAY) | exhaustive when + 신규 상태 추가 시 컴파일 에러 |
| **D2** | LUNCH 상태 분리 여부 | **분리 안 함** (KRX는 2000년부터 점심 폐지) | 단순화 + 정확성 |
| **D3** | KRX 캘린더 데이터 source | **정적 Flyway 시드 (V10) + 수동/batch 갱신** | 갱신 빈도 낮음·외부 API 종속성 회피, 1차 단순 |
| **D4** | 시세 fallback 저장소 | **Redis hash** (`quote:last:{code}`) | 휘발성·TTL·읽기 부하 분산. PostgreSQL은 일·분봉 영구 |
| **D5** | TickEvent 하위호환 | **`stale: Boolean = false`·`asOf: Instant? = null` 추가** | default 값으로 producer 무변경 |
| **D6** | dev 환경 토요일 흐름 | **`MAGICJAR_DEV_OFFLINE_FALLBACK` toggle (default false)** | 사용자 의지 명시 + KIS 흐름·mock 흐름 둘 다 검증 가능 |
| **D7** | REJECTED audit trail | **`orders` row INSERT (kis_order_no=NULL) + `order_events` REJECTED** | 사용자 가시성·운영 trail. UNIQUE 제약은 partial index로 우회 |
| **D8** | RejectReason 표현 | **sealed class** (KillSwitch·DailyLimit·PriceSanity·MarketClosed·KisApiError) | 구조화 + UI label 일관 + 분석 통계 가능 |
| **D9** | MarketSessionGuard 위치 | **Guard chain 합류, `@Order(150)`** (KillSwitch 다음, DailyLimit 앞) | 비상 정지 다음, 정상 흐름 차단 우선순위 |
| **D10** | EGW00202 hotfix 방향 | **hashkey 헤더 자동 첨부 + 직렬화 차이 진단** | KIS 공식 sample이 hashkey 사용. 직렬화는 raw 로깅으로 비교 |
| **D11** | OrderRejectedEvent 발행 | **Kafka `order.rejected.v1` 신규 토픽** | STOMP fanout + 향후 알림·통계 consumer 분리 가능 |
| **D12** | Frontend stale 표시 방식 | **MarketSessionBadge 페이지 상단 + 종목 카드 작은 배지** | 한 번에 두 위치 — 페이지 컨텍스트 + 종목별 최신성 둘 다 가시 |

---

## §13. ADR draft (인라인 — 사용자 검토 후 정식 발행)

본 spec에서 도출되는 ADR 후보 5개. plan T20·T21에서 정식 파일 발행.

### ADR 0031 (draft) — Market Session FSM 도입

- **상태**: Proposed
- **결정자**: architect
- **맥락**: 자동매매 시스템이 KRX 영업일·장 상태를 인지하지 못한 채 주문을 KIS로 무조건 보내고 거부 응답을 단순 에러로 취급. 1번 트랙 dogfooding에서 토요일 거부 발견.
- **검토 대안**:
  - A. 시간 비교만 (`LocalTime.now().isAfter(09:00) && isBefore(15:30)`) — 휴일 인지 0
  - B. 정적 holidays 리스트 + 시간 비교 — 임시휴장 반영 어려움
  - **C. `MarketSessionState` sealed class + `market_calendar` 테이블 + `MarketSessionResolver` (1분 cache)** — 채택
- **결정**: 옵션 C. 5 상태(PRE_MARKET/OPEN/POST_MARKET/CLOSED/HOLIDAY). 1분 자체 cache로 호출 부하 감소. 캘린더는 V10 정적 시드 + 분기 단위 수동 갱신.
- **결과**: 모든 주문·시세 흐름이 명시적으로 장 상태를 인지. 향후 자동 KillSwitch trigger·시세 fallback·UI 표시의 단일 진실원.
- **후속**: 한국거래소 API 자동 동기화 (3번 트랙 후보).

### ADR 0032 (draft) — 시세 Fallback 정책 (3-tier 데이터)

- **상태**: Proposed
- **맥락**: 장외 시간에 시세 0건이면 사용자 입장에서 "프로덕트가 먹통". 마지막 알려진 가격을 표시하면서 stale임을 명시해야 자동매매 시스템답다.
- **검토 대안**:
  - A. 장외엔 시세 0건 (현행) — 사용자 불만
  - B. last-quote을 STOMP에 계속 emit (stale flag 없이) — 사용자 혼동
  - **C. 3-tier (실시간 Tick → Redis QuoteCache → PostgreSQL candle) + TickFallbackEmitter + stale flag** — 채택
- **결정**: 옵션 C. Redis `quote:last:{code}` 7일 TTL. 장외 시 TickFallbackEmitter가 10초 주기로 cached emit. TickEvent에 `stale`·`asOf` 추가 (하위호환). frontend는 stale 배지 + 시각 표시.
- **결과**: 장외에도 사용자가 종목 가격·차트 정보 확인 가능. 명확한 표시로 혼동 방지.
- **후속**: D+N 보존 정책 (lastbusinessday key) · 분봉 backfill 강화.

### ADR 0033 (draft) — REJECTED Audit Trail (옵션 C)

- **상태**: Proposed
- **맥락**: 1차 dogfooding에서 사용자가 "주문 시도했는데 안 보임" 보고. 운영 디버깅 시 reject 기록 부재.
- **검토 대안**:
  - A. 현행 유지 (Reject만 OrderResult 반환, DB 0) — 가시성 문제
  - B. order_events만 INSERT (orders는 PLACED만) — REJECTED row가 orders에 없어 일관성 떨어짐
  - **C. orders + order_events 둘 다 INSERT** (kis_order_no NULLABLE, RejectReason sealed class) — 채택
- **결정**: 옵션 C. V11 마이그레이션으로 orders.kis_order_no NULLABLE + UNIQUE 부분 인덱스. RejectReason sealed class로 코드·display 통일. Kafka `order.rejected.v1` 토픽 신설. STOMP fanout으로 frontend 즉시 갱신.
- **결과**: 모든 주문 시도 가시성 확보. 운영 통계·디버깅·사용자 신뢰 모두 향상.
- **후속**: 알림 통합·distinct reject 통계 대시보드.

### ADR 0034 (draft) — Profile 분기 매트릭스 정밀화 (mock/dev/prd × session)

- **상태**: Proposed
- **맥락**: 1번 트랙에서 mock/dev/prd 3 profile 명명은 정리됐으나 component × profile × session 매트릭스가 산발. dev에서 토요일 흐름 검증 어려움.
- **결정**: §8 매트릭스 채택. dev + CLOSED + `MAGICJAR_DEV_OFFLINE_FALLBACK=true` 조합 시 MockOrderSender 자동 라우팅. 사용자 의지 명시 + 안전.
- **결과**: dogfooding 흐름이 시간·요일에 무관하게 검증 가능. profile별 부팅 의도 명확.
- **후속**: 운영 가이드 §3 Profile 전환 매트릭스 갱신 (T19).

### ADR 0035 (draft) — EGW00202 hotfix · KIS 헤더 정밀화

- **상태**: Proposed
- **맥락**: `RealKisOrderAdapter`의 KIS 호출이 gateway에서 EGW00202로 거부. 동일 endpoint·키·body로 직접 curl은 정상.
- **검토 대안**:
  - A. RestClient의 자동 헤더(`Accept`·`User-Agent`)를 명시 override
  - B. hashkey 헤더 추가 (KIS 공식 sample은 _url_fetch가 자동 처리)
  - C. body Map → Object DTO 변환 (직렬화 일관성)
  - **A+B+C 동시 적용 + raw HTTP 로깅으로 전후 비교** — 채택
- **결정**: T6 `MAGICJAR_KIS_RAW_HTTP_LOGGING=true` 활성 후 진단 → T7 hotfix:
  1. `Accept: application/json; charset=utf-8` 명시
  2. `User-Agent: magicjar/1.x` 명시
  3. POST 요청 body에 대한 SHA-256 hashkey 자동 계산 + `hashkey` 헤더 첨부 (KIS hashkey 발급 endpoint `/uapi/hashkey` 호출)
  4. body Map → DTO data class 변환
- **결과**: 평일 정상 응답 검증 + EGW00202 재현 0. 향후 KIS POST 어댑터 표준 패턴 확립.
- **참고**: KIS 모의 키는 사용자 본인 책임 (사용자 명시) — 폐기 대신 서비스 정상화까지 유지. ADR 자체엔 한 줄 명시.

---

## §14. 사용자 결정 필요 사항 (검토 단계)

본 spec 검토 시 사용자가 확정해야 할 항목:

1. **ADR 5개 발행 승인** (0031~0035) — 번호·제목·요지 그대로 진행할지.
2. **D6 (dev offline fallback toggle)** — 채택 OK?
   - `MAGICJAR_DEV_OFFLINE_FALLBACK=true`로 토요일에도 흐름 검증 (mock 체결).
   - 또는 항상 KIS 호출 → 거부 → REJECTED audit만 (옵션 A).
3. **D11 (Kafka `order.rejected.v1` 신규 토픽 필요?)**
   - 옵션 a. 신규 토픽 (별도 분석·알림 consumer 분리)
   - 옵션 b. 기존 `order.executed.v1`에 합치기 (event_type=REJECTED 추가)
4. **D3 (캘린더 갱신 자동화)** — 1차는 정적 시드 + 분기 수동 갱신 OK? 아니면 batch job (T8) 처음부터?
5. **EGW00202 hotfix 우선순위** — T6·T7을 R2 R3 어디에 배치할지 (사용자 채팅 톤상 우선이면 R1).
6. **frontend MarketSessionBadge 위치** — 페이지 상단(global) + 종목 카드(local) 둘 다? 또는 페이지 상단만?

---

## 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-02 | 초안 작성 (architect, opus 4.7 1M) |
