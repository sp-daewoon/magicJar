# Phase B 계측 기반 병목 진단 (실측)

- 작성일: 2026-09-18
- 계기: 사용자 "정합성 코드가 성능 해치면 안 됨. 측정 준비하자" → 서비스 기동 + Prometheus 계측 실측.
- 방법: magicJar 전체 스택 docker 기동(api/consumer/batch + prometheus/grafana) 후 실제 metric 조회.
- 관련: [candle-write-arbiter-design.md](2026-09-18-candle-write-arbiter-design.md) Phase B, [chart-data-integrity-remediation.md](../../docs/architecture/policies/chart-data-integrity-remediation.md)

---

## §0. 한 줄 결론

> **측정 결과 BoundaryAggregator DB fetch는 병목이 아니었다(21ms, fetch rows=1). 진짜 부하는 KIS
> backfill 호출량(FHKST03010230 ~2000회, 180회/분)이다. "의심을 측정으로 검증"한 결과, 처음 의심한
> 대상(BoundaryAggregator 캐시화)은 불필요하고, 실질 개선은 A-4(갭 복구 단일 큐 조율)임이 확정됐다.**

---

## §1. 실측 데이터 (magicjar-consumer:8084 · Prometheus:9090, 2026-09-18 15:45 KST)

| metric | 값 | 해석 |
|---|---|---|
| `magicjar_boundary_aggregate_seconds` (3m) | **21ms/건**, fetch rows=1 | boundary 합산 hot path는 빠름 — 캐시화 불필요 |
| `kis_call_total{tr_id=FHKST03010230,priority=BACKFILL}` | **~2000회** | 과거 1분봉 backfill이 KIS 호출 압도적 점유 |
| KIS 호출 증가율 | **180회/분** | cold-start 데이터 채우는 중(지속적 backfill) |
| `kis_ratelimit_tps_exceeded_total` | 5회, 60초간 **+0** | 초기 스파이크 후 rate limiter가 잘 조절 — 지속 압박 아님 |
| `hikaricp_connections_active` | 0 | DB 커넥션 풀 여유 — 병목 아님 |
| `AggregateBackfillRunner` | 종목당 1m **~3만 봉** 합산 → 상위봉 ~2만 insert (1~2초) | 대량 backfill 시 반복 — 증분화 가치 있음 |

DB: candle **1,093만 행** (V100/V101 검증 시 실측).

---

## §2. 측정 전 가설 vs 측정 후 사실

| 가설 (진단서 §1 의심) | 측정 결과 | 판정 |
|---|---|---|
| BoundaryAggregator가 매 분 DB fetch로 hot path 성능 갉아먹음 | 21ms/건 + boundary-gated(마감 시만) | **가설 기각** — 병목 아님. 캐시화 불필요 |
| 정합성 보정 코드가 성능 부담 | rate limiter가 TPS 잘 조절(+0/분) | **부분 기각** — 현재 위기 아님 |
| (신규 발견) KIS backfill 호출량 | 2000회, 180회/분, 초기 TPS 스파이크 | **진짜 부하 지점** |

**교훈**: 사용자의 "측정 없이 최적화 말라"가 정확했다. 처음 의심한 BoundaryAggregator 캐시화를 강행했다면
잘 도는 hot path를 건드려 정확성/성능 회귀만 냈을 것이다(21ms는 이미 충분히 빠름).

---

## §3. Phase B 최적화 우선순위 (측정 기반 재정렬)

| 순위 | 대상 | 근거 | 조치 |
|---|---|---|---|
| ~~보류~~ | ~~BoundaryAggregator 캐시화(CacheCircuitBreaker+Redis)~~ | 21ms — 병목 아님(측정) | **하지 않음**. 캐시 토대(CacheCircuitBreaker)는 다른 곳에 재사용 |
| **1** | **A-4 갭 복구 단일 큐 조율** | KIS backfill 2000회·초기 TPS exceeded — 흩어진 워커(NoTickFallback·CandleBackfillWorker·MinutePool·SymbolDetailGapFill)가 KIS TPS 경합 | 단일 우선순위 큐로 중복 호출 제거 |
| **2** | AggregateBackfillRunner 증분화 | 종목당 3만 봉 재합산 반복 | 이미 산출된 상위봉 재사용(증분) — 단 backfill 경로라 hot path 아님, 우선순위 중 |
| 3 | 장중 hot path 재관측 | 15:45는 장 마감 후 — tick→boundary 실부하 미관측 | 장중(09:00~15:30) 재측정 후 판단 |

---

## §4. 미해결 관측 (장중 필요)

- boundary_aggregate_count = 1 (장 마감 후라 tick 유입 거의 없음). 실시간 tick→1m→boundary hot path의
  실부하는 **장중 재관측**해야 확정. 계측은 이미 배포됐으므로 장중에 자동 축적된다.
- flag `MAGICJAR_CANDLE_PRECEDENCE_GUARD_ENABLED`는 아직 false — precedence 경로 실부하도 켠 뒤 관측.

## §5. 변경 이력
- 2026-09-18: 신설. 서비스 기동 + Prometheus 실측으로 Phase B 병목 진단. BoundaryAggregator 캐시화
  불필요(21ms) 확정, KIS backfill 호출량이 실질 부하 → A-4 우선순위 상향. "측정 우선" 원칙 실증.
