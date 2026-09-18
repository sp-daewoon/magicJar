# CandleWriteArbiter 설계 (P0-A) — 정합성 + 성능 동시 설계

- 작성일: 2026-09-18
- 상태: **Proposed (사용자 결재 대기 — 되돌리기 어려운 실시간 자본 경로 변경이라 착수 전 방향 확정)**
- 결정자: architect (협의: 최고 관리자)
- 근거: 감사 [`2026-09-17-chart-data-integrity-audit.md`](../../_workspace/audit/2026-09-17-chart-data-integrity-audit.md) §1-② · [chart-data-integrity-remediation.md](../../docs/architecture/policies/chart-data-integrity-remediation.md) P0-A/P0-B/P0-D · [candle-data-canon.md](../../docs/architecture/candle-data-canon.md) §1·§2
- 사용자 방향(2026-09-18): "실시간 tick은 무너질 수 있어 방어체계가 필요하지만, 정합성 맞추는 코드가 오히려 성능을 느리게 할 수 있다. 백필을 어떻게 처리할지까지 감안하라." + "거래시간 하드코딩은 무의미. KIS 신호(tick·봉 마감)가 정확히 쌓이는 게 본질."

---

## §0. 문제 정의 (코드 근거)

캔들 `(code, interval, ts)` 한 행에 쓰는 경로가 **8개**이고 UPSERT 규칙(DO NOTHING vs DO UPDATE)이 혼재해 최종값이 실행 순서에 의존한다(감사 §1-②). writer 대장(canon §2):

| writer | source 등급 | UPSERT |
|---|---:|---|
| LiveCandleUpdater/TickToMinuteAggregator | 3 (WS live) | INSERT |
| BoundaryAggregator/upsertAggregateCandles | 3 | DO UPDATE (무가드) |
| CandleBackfillWorker | 2 (REST 확정) | DO NOTHING |
| MarketDailyBackfillCampaign | 2 | DO NOTHING |
| MarketDailyNightlySnapshotService | 1 (공식 마감) | DO UPDATE |
| MarketDailyDateRepairService | 2 | DO UPDATE/DELETE |
| DailyFamilyRollupRunner | 2 | DO UPDATE/DELETE |
| NoTickMinuteFallbackService/SymbolDetailMinuteGapFillService | 2~4 | DO NOTHING |

**성능 문제 (사용자 지적)**: 정합성 보정이 실시간 hot path에 얹혀 성능을 갉아먹는다. 실측 근거:
- `BoundaryAggregator.onTickClosed`가 **매 1m closing마다** DB에서 boundary 범위 1m을 range fetch → 합산(감사 §1: 종전 전량 풀스캔은 P1-① fix됐으나 여전히 매 분 8개 interval × 종목수 DB 조회).
- `CandleQueryService.getCandles`가 **조회할 때마다** DB+Redis+quote:last 3중 병합·재조립(감사 §1-③).
- 정합성 방어가 각기 흩어짐: NoTickFallback(지수백오프), CandleBackfillWorker(throttle+gap guard), SymbolDetailGapFill이 서로 조율 없이 각자 KIS TPS 소비.

---

## §1. 설계 원칙 (사용자 방향 반영)

1. **KIS 신호를 그대로 신뢰** — 거래시간 하드코딩 0. tick·봉마감이 오면 그대로 적재. 세션 판별로 봉을 버리거나 만들지 않는다(시각 하드코딩은 KRX 애프터마켓처럼 계속 바뀌는 부채).
2. **실시간 hot path는 가볍게** — tick 적재 경로(WS→Redis→1m closing)는 최소 연산. 정합성 검증·합산 재계산 같은 무거운 작업을 hot path에서 분리.
3. **정합성 보정은 비동기·백그라운드** — 갭 복구·재합산·검증은 별도 워커에서 throttle·backoff로. 실시간 경로를 막지 않는다.
4. **단일 진입점 + precedence 가드** — 모든 writer가 `CandleWriteArbiter` 경유. 낮은 신뢰 source가 높은 신뢰 확정봉을 덮지 못하게(canon §1).
5. **되돌릴 수 있게** — 단계적 적용(strangler). 기존 경로를 한 번에 걷어내지 않고 arbiter를 우회 가능한 feature flag 뒤에 둔다.

---

## §2. CandleWriteArbiter 인터페이스 (제안)

```kotlin
// domain (Pure Kotlin — Spring/JPA 의존 0, 결정론)
interface CandleWriteArbiter {
    /** 단일 봉 쓰기 요청. precedence 판정 후 실제 반영 여부/방식 결정. */
    fun write(request: CandleWriteRequest): CandleWriteOutcome
    fun writeAll(requests: List<CandleWriteRequest>): CandleWriteBatchOutcome
}

data class CandleWriteRequest(
    val code: String,
    val interval: CandleInterval,
    val ts: Instant,
    val ohlcv: Ohlcv,
    val source: CandleSource,   // enum — P0-D (VARCHAR 자유문자열 12종 → enum + priority)
)

enum class CandleSource(val priority: Int) {
    OFFICIAL_DAILY_CLOSE(1),   // KIS_DAILY_CLOSE, MKT-DAILY-SNAP(마감후)
    REST_CONFIRMED(2),         // KIS-BACKFILL, RANGE-GAPFILL, MKT-DAILY-BF
    WS_LIVE(3),                // KIS (tick 집계 closing)
    POLLING_FALLBACK(4),       // KIS-POLLING, NOTICK-FALLBACK
}
```

**precedence 가드 (P0-B)** — DB UPSERT에 `WHERE EXCLUDED.source_priority >= candle.source_priority` (또는 arbiter가 사전 판정). 등급 낮은 값이 높은 확정봉을 덮지 못함. `source`가 `EXCLUDED.source`로 무조건 덮이던 출처 소실도 차단.

---

## §3. 성능 설계 (사용자 핵심 지적)

### 3-1. Hot path (실시간) — 가볍게 유지
- tick → Redis current candle 갱신은 **현행 유지**(빠름, DB 안 침). 1m closing 시 arbiter.write(WS_LIVE) 1회 INSERT.
- **BoundaryAggregator 재합산을 hot path에서 제거 검토**: 매 1m closing마다 8개 상위 interval을 DB fetch+합산하는 대신, Redis current candle을 증분(incremental) 갱신으로 유지하고 상위봉 DB 확정은 boundary 마감 시 1회만. (현행도 boundary에서만 DB write하나, fetch 비용이 매분 발생 — 증분 누적으로 fetch 제거 가능.)

### 3-2. 정합성 보정 — 비동기 단일 조율자
- 흩어진 갭 복구 워커(NoTickFallback·CandleBackfillWorker·SymbolDetailGapFill)의 **KIS 호출을 단일 큐로 조율**. 현재 각자 throttle/backoff를 가지나 서로 모름 → 같은 종목을 중복 호출하거나 TPS 경합.
- 제안: `CandleReconcileScheduler`가 우선순위 큐(보유>ACTIVE전략>detail>watchlist)로 갭 복구를 직렬화. 각 워커는 이 큐에 요청만 등록. (ADR 0105 §3-5 managed universe priority 재사용.)

### 3-3. 조회 재조립 제거 (P1-A, 성능+정합 동시)
- `CandleQueryService`의 3중 병합을 축소: 확정봉=DB 단일, 진행봉=Redis current 1개 오버레이. 조회마다 재계산하던 비용 제거 + "볼 때마다 값 달라짐" 해소.

### 3-4. 백필 전략 (사용자 명시)
| 백필 종류 | 트리거 | 성능 정책 |
|---|---|---|
| 실시간 gap (WS 끊김) | NoTickFallback watchdog | 지수 백오프(현행 유지) + 단일 큐 등록. **최신 1봉만** 발행(과거 재생 안 함) |
| 차트 진입 gap | SymbolDetail ENTER | bounded range(lastDb+1m..now), 큐 우선순위 detail |
| 장기 backfill | admin/scheduled | BACKFILL priority 최저, 저부하 시간대, throttle gate |
| 일봉 universe | 야간 snapshot | 기존 배치(hot path 무관) |

핵심: **백필은 절대 실시간 경로를 막지 않는다.** 전부 비동기 큐 + priority + throttle. tick이 무너져도 hot path는 계속 돌고, 백필이 뒤에서 조용히 채운다.

---

## §4. 단계적 적용 (strangler — 되돌릴 수 있게)

| Phase | 범위 | feature flag | 검증 |
|---|---|---|---|
| A-1 | CandleWriteArbiter 인터페이스 + CandleSource enum + precedence 판정 (domain, 순수) | — | 단위 테스트(precedence 매트릭스) |
| A-2 | DB UPSERT precedence 가드 (P0-B) + source enum 컬럼 마이그레이션 (P0-D) | `magicjar.candle.arbiter.enabled` | 낮은신뢰가 높은신뢰 못덮음 테스트 |
| A-3 | 8개 writer를 arbiter 경유로 전환 (1개씩, flag off 시 기존 경로) | 〃 | 각 writer 회귀 테스트 |
| A-4 | 갭 복구 단일 큐 조율 (§3-2) | `magicjar.candle.reconcile.queue.enabled` | 중복 KIS 호출 0 테스트 |
| B | BoundaryAggregator 증분화 (§3-1) + 조회 재조립 제거 (§3-3) | 각 flag | 성능 벤치 + 정합 테스트 |

각 Phase는 독립 PR + flag로 즉시 롤백 가능. A-1은 순수 도메인이라 자본 영향 0으로 먼저 착수 가능.

---

## §5. 자본 안전
- 모든 Phase는 flag off 시 기존 동작 100% 보존(zero-regression).
- arbiter는 데이터 정합만 — 주문 발행 0, KIS 주문 호출 0.
- precedence 가드는 **오염 방향으로만 차단**(낮은 신뢰가 높은 신뢰 못 덮음) — 정상 데이터 유실 0.
- date repair DELETE는 tombstone으로(P0-C) — 되돌릴 수 없는 삭제 제거.

## §6. 결정 필요 사항 (사용자 결재)
1. Phase A-1(순수 도메인 arbiter + enum) 먼저 착수 승인?
2. source enum 마이그레이션(P0-D) — 기존 12종 문자열 → enum 매핑 시 백필 데이터 재라벨 범위?
3. BoundaryAggregator 증분화(Phase B)는 성능 벤치 후 결정 — 우선순위?

## §7. 변경 이력
- 2026-09-18: 신설(Proposed). 사용자 방향(tick 방어 + 정합성 성능 부담 + 백필 전략 + 시간 하드코딩 배제) 반영한 CandleWriteArbiter 단계적 설계. 착수 전 결재 대기.
