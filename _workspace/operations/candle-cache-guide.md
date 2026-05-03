# Candle Cache 운영 가이드 (T19-T8)

> 캔들 데이터 정책 v1(`docs/architecture/candle-data-policy.md`)의 운영 절차·장애 대응을 정리. T19 트랙 백엔드(PR #100·#102·#105) 적용 후 기준.

## 1. 데이터 흐름 한 장 요약

```
KIS OpenAPI (REST/WS)
  ↓ FHKST03010100 (일봉) / FHKST03010200 (분봉)
batch :: MinuteCandleBackfillJob · DailyCandleBackfillJob
  ↓ INSERT
PostgreSQL :: candle (D1 영구) + 분봉 (LRU 관리)
  ↓
api :: CandleCacheService.fetchOrTrigger
  ↓
api :: InstrumentController GET /api/instruments/{code}/candles
  ↓
frontend :: useCandles + candleAggregator (M5→M15/M30/H1 클라 합성)
```

## 2. 영속 정책

| Interval | 영속 정책 | 시드 lookback (default) | 비고 |
|---|---|---|---|
| D1 | 영구 — 절대 eviction 안 함 | 180 영업일 | KIS `FHKST03010100`. corp action 보정 컬럼 적용 (V14). |
| H1 | LRU eviction 대상 | 365 영업일 | M5에서 합성 가능하지만 backend도 직적재. |
| M30 | LRU eviction 대상 | 180 영업일 | KIS `FHKST03010200` interval=30. |
| M15 | **direct 적재 안 함** — 클라 합성 | 180 영업일 (M5 fetch 후) | `candleAggregator.aggregateToM15`. |
| M5 | LRU eviction 대상 (대표 인터벌) | 90 영업일 | KIS interval=5. M15·M30·H1 합성의 source. |
| M1 | LRU eviction 대상 | 7 영업일 | KIS interval=1. 가장 자주 회수. |

> **`defaultFrom`은 KST 자정 align**: 시드 분봉(09:00 시작)이 from 이후로 들어오게 보장. PR #105 정정.

## 3. Cache Hit/Miss 판정 (PR #105 이후)

`CandleCacheService.fetchOrTrigger`는 D1을 우회 직접 조회하고, 분봉은 다음 분기로 응답:

| 케이스 | 응답 | 부수효과 |
|---|---|---|
| candles 있고 `instrument_cache_meta` 있음 → **HIT** | 분봉 그대로 | `last_accessed_at = NOW()` 갱신 (LRU 기준) |
| candles 0건 → **MISS** | 빈 배열 `[]` | `triggerBackgroundFetchAsync(code)` — batch에 비동기 fetch trigger |
| candles 있는데 meta 없음 → **PARTIAL (legacy seed)** | 분봉 그대로 | trigger 안 함 (V19 적용 전 시드 호환) |

> **D1 fallback 정책 폐기 (PR #105)**: 사용자가 분봉 선택했는데 일봉이 오면 혼란. 빈 배열 + 백그라운드 trigger만 발사하는 게 정확.

## 4. 백그라운드 Fetch Trigger

```
api :: CandleCacheService.triggerBackgroundFetchAsync
  ↓ HTTP POST http://batch-app:8081/jobs/minute-candle-backfill
batch :: JobTriggerController → MinuteCandleBackfillJob
  ↓ KIS REST (FHKST03010200)
PostgreSQL :: INSERT candle (interval=M1/M5/M30/H1)
api :: 다음 fetch 호출 시 hit
```

- 응답 차단 X — `@Async` thread pool로 발사하고 즉시 빈 배열 반환.
- batch-app 미가용 시 warning log + 무시 (사용자 응답 흐름 차단 방지).
- KIS rate limit 회피: batch 측에서 종목당 호출 빈도 제한 (`MinuteCandleBackfillJob`이 153000→090000 역방향 페이지 순회).

## 5. EvictionScheduler — LRU 6시간 cron

`EvictionScheduler` (api 모듈, PR #102):
- `@Scheduled(cron = "0 0 */6 * * *", zone = "Asia/Seoul")` — 매일 00·06·12·18시 KST 정각.
- `@ConditionalOnProperty default=true` — mock·dev에서도 ON.
- 호출: `CacheEvictionService.evictIfNeeded()`.

`CacheEvictionService` 동작:
1. `instrument_cache_meta` 합산 `byte_estimate` 계산.
2. `magicjar.candle.cache.max-bytes` (default 8 GB) 초과 시:
   - `last_accessed_at` ASC ORDER로 (code, interval) 순회.
   - candle 테이블 + meta row DELETE.
   - 누적 회수 byte ≥ overshoot + 10% buffer 도달 시 종료.
3. structured log: `[evict] freed={bytes} rows={n}`.

## 6. 운영 절차

### 6.1 분봉 backfill 수동 트리거

특정 종목·interval을 강제 backfill:

```bash
# 호스트에서 batch-app 직접 호출 (호스트→컨테이너 포트 매핑 18081)
curl -X POST http://localhost:18081/jobs/minute-candle-backfill \
  -H 'Content-Type: application/json' \
  -d '{"code":"005930","interval":"M5","fromDate":"2026-04-01","toDate":"2026-05-03"}'

# 진행 상태
curl http://localhost:18081/jobs/{runId}
```

> KIS 모의 키 환경(`SPRING_PROFILES_ACTIVE=dev,real-kis`)에서만 실제 KIS 호출. mock profile은 stub adapter라 0건 응답.

### 6.2 cache meta 수동 점검

```sql
-- 종목별 cache 점유
SELECT code, interval, byte_estimate / 1024 / 1024 AS mb,
       newest_bar AT TIME ZONE 'Asia/Seoul' AS newest,
       last_accessed_at AT TIME ZONE 'Asia/Seoul' AS last_acc
FROM instrument_cache_meta
ORDER BY byte_estimate DESC
LIMIT 30;

-- 전체 점유 byte
SELECT SUM(byte_estimate) / 1024 / 1024 / 1024 AS total_gb
FROM instrument_cache_meta;
```

### 6.3 LRU 임계값 조정

`docker/.env` 또는 docker-compose env에 추가:

```yaml
MAGICJAR_CANDLE_CACHE_MAX_BYTES: 17179869184   # 16 GB
MAGICJAR_CANDLE_EVICTION_ENABLED: "true"        # 임시 OFF: false
```

api 컨테이너 재기동:
```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml --env-file .env up -d --force-recreate --no-deps api-app
```

### 6.4 즉시 eviction 강제 트리거 (테스트·복구)

`@Scheduled` 6h 대기 없이 강제 호출 — actuator scheduledtasks endpoint나 별도 admin endpoint가 아직 없음. 1차 수동:

```sql
-- 가장 오래된 cache 항목 1건 강제 DELETE (긴급 시)
DELETE FROM candle WHERE (code, interval) IN (
  SELECT code, interval FROM instrument_cache_meta ORDER BY last_accessed_at ASC LIMIT 1
);
DELETE FROM instrument_cache_meta WHERE (code, interval) IN (
  SELECT code, interval FROM instrument_cache_meta ORDER BY last_accessed_at ASC LIMIT 1
);
```

> 향후 admin endpoint `POST /api/admin/cache/evict` 추가 검토 (권한 게이트 필수).

## 7. 장애 대응 플레이북

### 7.1 사용자 보고 "차트에 데이터 안 나옴"

진단 순서:
1. **DB 직접 확인** — 해당 종목·interval row 수.
   ```sql
   SELECT interval, COUNT(*), MIN(open_time), MAX(open_time)
   FROM candle WHERE code='005930' GROUP BY interval;
   ```
2. **API 직접 호출** — Cache 경유.
   ```bash
   curl 'http://localhost:18080/api/instruments/005930/candles?interval=M5&limit=10'
   ```
3. **Background trigger log 확인**:
   ```bash
   docker logs magicjar-api 2>&1 | grep '\[cache\]' | tail -20
   ```
4. **Batch app 도달 가능성**:
   ```bash
   docker exec magicjar-api wget -qO- http://batch-app:8081/actuator/health
   ```

### 7.2 "오래된 데이터만 나옴 / 최신 시점 안 채워짐"

- `MinuteCandleBackfillJob`이 KIS 응답을 받았는지 batch log 확인.
- KIS rate limit hit 가능성 — 1시간 대기 후 재시도.
- KIS 영업일 외(휴장) 응답 0건은 정상.

### 7.3 "디스크 가득 참 / postgres I/O 에러"

- `instrument_cache_meta.byte_estimate` 합 확인 → 8GB 임계값 초과 여부.
- EvictionScheduler 마지막 실행 시각 확인 (log keyword `[evict]`).
- 즉시 수동 eviction (§6.4) 또는 max-bytes 임계 하향.

### 7.4 "EvictionScheduler 작동 안 함"

- `MAGICJAR_CANDLE_EVICTION_ENABLED=true` 확인.
- api 부팅 시 `@EnableScheduling` 적용 여부 (`MagicJarApiApplication.kt`).
- application.yml에 `spring.task.scheduling.pool.size` 명시 안 했어도 default 1 thread로 동작.
- log: `docker logs magicjar-api | grep -iE 'evict|scheduled'`.

## 8. 모니터링 키워드

| 위치 | 키워드 | 의미 |
|---|---|---|
| api log | `[cache] HIT` | 정상 cache 응답 |
| api log | `[cache] MISS` | 0건 → 백그라운드 trigger 발사 |
| api log | `[cache] PARTIAL` | legacy seed (V19 미적용 시드) |
| api log | `[cache] minute-backfill triggered` | batch에 trigger 성공 |
| api log | `[cache] minute-backfill trigger failed` | batch unhealthy 또는 네트워크 |
| api log | `[evict] freed=` | eviction 회수 결과 |
| batch log | `MinuteCandleBackfillJob completed` | KIS fetch 완료 |
| postgres | `flyway_schema_history` | V19 = `instrument_cache_meta` 적용 확인 |

## 9. 향후 개선 (post-MVP)

| 항목 | 우선순위 | 비고 |
|---|---|---|
| L2 Redis hot cache (`candle:hot:{code}:{interval}`) | High | 정책 v1 §3 명시. 현재는 PostgreSQL 직접 조회. |
| Admin endpoint — 즉시 eviction trigger | Mid | 권한 게이트 필수. |
| Bytes estimator 정확도 — 현재 `row_count × 80B` 근사 | Mid | 실제 row size로 산출. |
| 전 종목 일괄 backfill cron (야간 batch) | Mid | 사용자 흐름과 별개로 미리 채움. |
| MarketCalendar 정확 영업일로 lookback 산출 | Low | 현재 단순 KST 자정 align. |
| backfill 실패 시 재시도 큐 (Kafka DLT) | Low | KIS rate limit hit 자동 회복. |

## 변경 이력

| 날짜 | 변경 | PR |
|---|---|---|
| 2026-05-03 | T19 1차 백엔드 — `CandleCacheService` + `instrument_cache_meta` (V19) + `MinuteCandleBackfillJob` + infinite scroll endpoint | #100 |
| 2026-05-03 | T19-T7 — `EvictionScheduler` + `CacheEvictionService` 6h LRU cron | #102 |
| 2026-05-03 | D1 fallback 정책 폐기 + `defaultFrom` lookback 확장 + 자정 align (사용자 보고 회귀) | #105 |
| 2026-05-03 | T19-T8 운영 가이드 신설 (본 문서) | TBD |
