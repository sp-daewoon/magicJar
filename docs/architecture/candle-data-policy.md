# 캔들 데이터 정책 (Candle Data Policy)

> **Status**: ACTIVE v1 (2026-05-03 사용자 결정)
> **Stewards**: market-data 에이전트 + architect
> **References**: ADR 0033 (3-tier fallback), ADR 0037 (Instrument Master), 마스터 §3-5 (데이터 정합성 7원칙)
> **변경 정책**: 본 문서 변경 시 사용자 컨펌 필수 — 데이터 모델·운영 비용에 직결

---

## 1. 핵심 원칙

1. **broker(KIS)가 데이터 마트, magicJar는 edge cache**
   - HTS 패턴 정합 — 사용자 PC 영구 보관 X, 필요 시 KIS API 재조회
   - KIS 시세 조회는 무료 (모의·실전 둘 다, 주문 수수료만 부과)
   - rate limit ~20 req/s · 1분 1,000 req (콘솔 정확 값 확인)

2. **3-tier 하이브리드 저장**

   | Layer | 기술 | 용도 | 데이터 |
   |---|---|---|---|
   | **L1 영속 (source of truth)** | PostgreSQL | 차트·백테스트·screener 응답 | D1 영구 + 분봉 LRU cache (5~10 GB target) |
   | **L2 hot cache (read-through)** | Redis | 자주 보는 종목 분봉 in-memory mirror | 사용자 watch list 5종목 × 1년 5분봉 (~수백 MB) |
   | **L3 실시간 stream** | Kafka | 실시간 tick fanout | retention 1시간, 영속 X |

   - **분봉을 Redis 단독 저장은 부적합** — 메모리 비용·range query 비효율
   - **Kafka는 시계열 random access 불가** — 차트용 부적합 (실시간 fanout 전용)

3. **LRU 롤링 eviction**
   - target 용량: **8 GB** (사용자 5~10 GB 범위 중간)
   - 종목당 최대: 3년 D1 + 1년 5분봉 + 6개월 1분봉 ≈ 200~300 MB
   - 동시 보유 종목: ~30개 (8 GB / 300 MB)
   - 그 이상 진입 시 `last_accessed_at` 가장 오래된 종목 데이터 일괄 DELETE
   - 삭제된 종목은 다음 진입 시 KIS 재 fetch (무료)

4. **interval별 영속 정책**

   | Interval | 영속 정책 | 합성 정책 |
   |---|---|---|
   | **D1 (일봉)** | 전종목 10년 영구 영속 | — |
   | **W (주봉)** | 합성 only (영속 X) | D1 → W 클라/server 합성 |
   | **MO (월봉)** | 합성 only (영속 X) | D1 → MO |
   | **Y (연봉)** | 합성 only (영속 X) | D1 → Y |
   | **H1 (60분봉)** | 합성 only (영속 X) | M5 → H1 |
   | **M30 (30분봉)** | 첫 진입 fetch 영속 (LRU) | 또는 M5 → M30 합성 |
   | **M15 (15분봉)** | 합성 only | M5 → M15 |
   | **M5 (5분봉)** | LRU cache 영속 (1년 lookback) | — (raw) |
   | **M1 (1분봉)** | lazy fetch 영속 (사용자 zoom in 시 6개월 lookback) | — (raw) |
   | **틱** | 실시간 KIS WS only, 영속 X | Kafka retention 1시간 |

   - **W/MO/Y/H1/M15는 항상 합성** — 저장 X (frontend `candleAggregator` 활용)
   - **M30은 선택** — 합성 가능하지만 첫 진입 시 30분봉 1년만 KIS fetch (5초 → 사용자 즉시 분봉 토글 가능) 운영 편의

---

## 2. 사용자 진입 단계적 fetch 정책

### 첫 진입 (cache miss)

| 단계 | 시점 | 동작 | 사용자 체감 |
|---|---|---|---|
| **0초** | 진입 즉시 | D1 표시 (이미 영속) | 즉시 차트 |
| **5초** | 백그라운드 1차 | **30분봉 3년 fetch** → DB 영속 | 분봉 토글 가능 |
| **35초** | 백그라운드 2차 | 5분봉 1년 fetch → DB 영속 | 5분 zoom in 자유 |
| **lazy** | 사용자 5분봉 zoom in 시 | 1분봉 1개월 lazy fetch | 깊은 zoom |
| **scroll-end** | 차트 끝까지 스크롤 시 | 추가 lookback fetch (자동) | 과거 누적 |

### 재진입 (cache hit)
- D1·M5·M30 모두 영속이라 즉시 표시
- `last_accessed_at` 갱신 → LRU eviction 우선순위 갱신
- 사용자 zoom out 시 추가 fetch 불필요 (cache 범위 안)

### 데이터 끝 도달 (infinite scroll)
- 사용자가 차트 끝까지 스크롤 → "더 fetch?" auto-trigger
- cache miss 범위 KIS fetch + DB append
- `INSERT ON CONFLICT DO NOTHING` 멱등 보장
- 반복 시 **결국 최초 상장일까지** 누적 가능

---

## 3. KIS API 호출 비용 (3년 fetch 기준)

| Interval | 3년 bars | KIS 호출 (100 bars/page) | 시간 (20 req/s) |
|---|---|---|---|
| **틱** | 무한 | — | 영속 X |
| **1분 (M1)** | ~290K | ~2,900 | **~145초** |
| **5분 (M5)** | ~58K | ~585 | **~30초** |
| **15분 (M15)** | ~19K | ~195 | **~10초** |
| **30분 (M30)** | ~9.7K | ~97 | **~5초** |
| **60분 (H1)** | ~4.9K | ~49 | **~2.5초** |
| **일 (D1)** | ~750 | ~8 | **~0.4초** |

- **30분봉이 5분봉보다 6배 빠름** → 첫 진입 분봉 표시는 30분봉 권장
- 5분봉은 백그라운드로 추가 fetch (사용자 35초 후 zoom in 가능)
- 1분봉은 사용자 명시 zoom in 시만 (lazy)

---

## 4. 룩어헤드 차단 (백테스트·screener·signal)

- **adjusted close 우선** (corp action 보정 — ADR 0038)
- 봉 t에서 평가 시 t시점 이용 가능한 정보만 사용 (`asOfBar+1` sublist)
- retroactive 재계산은 `CorpActionBackfillJob`에서만 (cron 06:30 KST)
- 백테스트 런타임은 절대 retroactive 재계산 금지

---

## 5. 영속 테이블 스키마 (요약)

```sql
-- candle (Flyway V5, V14 corp action 컬럼 추가)
CREATE TABLE candle (
  code            VARCHAR(12)   NOT NULL,
  interval        VARCHAR(8)    NOT NULL,  -- D1, M5, M1, M30, ...
  open_time       TIMESTAMPTZ   NOT NULL,
  open, high, low, close NUMERIC(18,4) NOT NULL,
  volume          BIGINT        NOT NULL,
  trade_count     BIGINT        NOT NULL DEFAULT 0,
  open_adjusted, high_adjusted, low_adjusted, close_adjusted NUMERIC(18,4),
  volume_adjusted BIGINT,
  adjustment_factor NUMERIC(20,8),
  PRIMARY KEY (code, interval, open_time)
);

-- instrument_cache_meta (Flyway V19 — T19 신규)
CREATE TABLE instrument_cache_meta (
  code              VARCHAR(12)  NOT NULL,
  interval          VARCHAR(8)   NOT NULL,
  last_accessed_at  TIMESTAMPTZ  NOT NULL,
  oldest_bar        TIMESTAMPTZ  NOT NULL,
  newest_bar        TIMESTAMPTZ  NOT NULL,
  byte_estimate     BIGINT       NOT NULL,
  PRIMARY KEY (code, interval)
);
CREATE INDEX idx_cache_meta_lru ON instrument_cache_meta (last_accessed_at);
```

---

## 6. 운영 — eviction·모니터링

- **eviction 트리거**:
  1. 매 6시간 cron job (`@Scheduled`)
  2. 차트 진입 시 trigger (현재 용량 > 8 GB일 때만)
- **eviction 로직**:
  ```sql
  -- 최근 30일 미접근 + 용량 큰 종목 우선 삭제
  WITH lru AS (
    SELECT code, interval
    FROM instrument_cache_meta
    WHERE last_accessed_at < now() - interval '30 days'
      AND interval IN ('M1', 'M5', 'M30')  -- D1은 영구 보존
    ORDER BY last_accessed_at ASC, byte_estimate DESC
    LIMIT 5
  )
  DELETE FROM candle USING lru
  WHERE candle.code = lru.code AND candle.interval = lru.interval;
  ```
- **모니터링**:
  - `GET /api/admin/cache/usage` — interval별 용량·종목 수·LRU 통계
  - cache hit rate (Prometheus metric)·KIS rate limit 사용량

---

## 7. 변경 이력

| 날짜 | 변경 | 사유 |
|---|---|---|
| 2026-05-03 (v1) | 초기 정책 확립 — 3-tier·LRU·단계적 fetch·infinite scroll | 사용자 sanity 테스트 중 분봉 부재 발견·HTS 패턴 정합 결정 |

## 8. 본 정책 변경 시

본 문서는 데이터 모델·운영 비용 직결이라 **사용자 컨펌 필수**. 변경 시:
1. 본 문서 변경 + ADR 발행 (0050 이후 신규 번호)
2. `_workspace/_harness-state.json`에 정책 변경 이력 기록
3. T19 후속 트랙으로 마이그레이션 (V20+ Flyway)
4. 사용자 사전 승인 후 진행
