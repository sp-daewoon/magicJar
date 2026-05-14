# ADR 0071 — T-SC Screener spec/plan (W3 다섯 번째 트랙 — universe × DSL → 후보 + 랭킹 + 캐시)

- 상태: Accepted (architect 자율 결정 — 사용자 위임 2026-05-13 "W3 진행해서 마무리까지하자 W4 진입까지 자율 최종권한")
- 작성일: 2026-05-14
- 결정자: architect (사용자 위임 + orchestrator dispatch · W3 마무리 v3.6.3 patch 사이클)
- 관계: ADR 0017 정합(Instrument Master 1급 시민 — universe source) · ADR 0018 정합(Corp Action adjusted — 캔들 정합) · ADR 0019 정합(Watchlist 캔들) · ADR 0020 정합(KIS Strategy Builder 흡수 = 명세 port) · ADR 0022 정합(Strategy DSL `.kis.yaml` 호환) · ADR 0023 정합(Signal Engine 룰 매칭 only · AI 호출 0 정합) · ADR 0037 정합(KIS Rate Limit) · ADR 0047 정합(DSL sandbox · strategy-eval-pool 격리 · timeout) · ADR 0055 정합(Strategy Builder UI 합류점) · ADR 0056 정합(Backtest Engine — 같은 시계열 slice eval 패턴 reuse) · ADR 0061 정합(Backtest 결과 영속 — 운영 패턴 reuse) · ADR 0066 정합(T-SIG 실시간 시그널 — DSL evaluator 공유) · ADR 0067 정합(T-RISK Guard chain — 후속 Risk 평가는 SIG/Order 단계로 위임) · ADR 0070 정합(T-RECON·T-NOTIFY 안전망 — Screener 결과 알림은 후속 backlog)

---

## §1. 컨텍스트 (왜 이 결정이 필요한가)

### 1-1. T-SC 위치 — W3 다섯 번째 트랙, T-O·T-RECON·T-NOTIFY 마감 직후 단일 사이클

2026-05-13 v3.6.2 patch cut으로 **T-RECON + T-NOTIFY 묶음 spec/plan 마감** (ADR 0070 5 Phase 직렬 디스패치 정합). 자본 보호 prereq 완비 → 다음 단계는 **잠재 종목 발굴 자동화**:

| 트랙 | 사용자 비전 정합 역할 | 선행 |
|---|---|---|
| **T-SC (본 ADR)** | "**전략으로 종목 찾기**" UX 핵심 — Strategy Builder에서 만든 DSL을 universe(KOSPI·KOSDAQ ~3000 종목)에 적용해 매수 후보 + 랭킹 산출 | **T-DSL (W2 마감)** + **T-IM1 (W1 마감, instrument 테이블 본격 채움)** + **T-CA1 (W1 마감, 보정 캔들)** + **T-IND (W2 마감, 80지표)** + **T-PAT (W2 마감, 57패턴)** + **T-BT (W3 마감, DSL 평가 패턴 reuse)** |

### 1-2. 사용자 비전 정합 fitness 검증 (사용자 메모리 `feedback_v3_skepticism_no_regression` 의무)

**필수성** (자동매매에 진짜 필요한가?):
- ★★★ **"전략으로 종목 찾기" UX 핵심** — Strategy Builder(GUI 블록 빌더)에서 만든 DSL을 universe에 적용해 매수 후보 자동 발굴. **현재 사용자 흐름의 단절 회복** (Strategy Builder 저장 → ? → Watchlist 등록 사이의 연결고리)
- ★★ **자동매매 본체 가동의 universe 발굴 자동화** — Watchlist를 사용자가 1건씩 수동 등록하는 패턴(T-W1)에서 → DSL 기반 자동 발굴 → 일괄 Watchlist 등록으로 진화

**v1/v2 잔재 검증**: v1·v2는 수동 종목 등록 + 단일 trader 가정. T-SC는 v3 신규 의제 (DSL 평가 엔진 reuse + universe 자동 평가 + 랭킹). 잔재 0.

**AI 호출 0 검증**: 본 ADR 트랙은 룰 기반 DSL 평가만 — Spring AI·Anthropic·OpenAI import 0 의무 (개발 룰 5 정합). sub-agent 산출물에 grep 검증 의무:
```
grep -rn "import.*anthropic\|import.*openai\|ChatClient\|springframework\.ai" \
  consumer/screener domain/screener api/.../screener || echo "PASS — AI 호출 0"
```

**mock 합성 0**: universe screening은 **instrument 테이블(T-IM1 baseline) + candle 테이블(영속 일봉, T-W2 baseline) + Strategy DSL(T-DSL baseline)** 모두 실제 데이터. mock 합성 금지 (사용자 메모리 `feedback_no_mock_data` 정합).

### 1-3. ADR 0058·0059 vs ADR 0071 (본 ADR) 책임 분리

ADR 0058·0059 후속 ADR 슬롯은 README §12에 **"57 캔들 패턴 표준"·"Screener 표준"으로 시프트 예약**되어 있으나 **5개월 baseline 없이 슬롯만 예약된 상태**. ADR 0070 패턴(baseline reuse + 정밀 명세 묶음 spec ADR)을 따르지 않고 본 ADR 0071이 **결정 + 정밀 명세 일체형**으로 발행 — 사유:
- T-SC는 baseline ADR 부재 (ADR 0017 Instrument Master만 universe source 권위로 존재)
- T-SC 결정 의제 적정 규모 (8건 의제 ≤ ADR 0068 패턴, 본 ADR §2-1~§2-10에서 직접 결정 LOCK)
- 사용자 자율 최종권한 위임 — 결정 분리 사이클 비용 폐기

따라서 본 ADR 0071은 **ADR 0058·0059 슬롯을 reorder LOCK** (사용자 메모리 `feedback_v3_skepticism_no_regression` 정합 — v1/v2 잔재 의제 의심 lens로 0058 "57 패턴 표준"은 ADR 0054 pattern-engine + T-PAT outline으로 cover 완료 검증).

### 1-4. KIS-first raw 인용 (헌법 §3 적용 매트릭스)

T-SC는 **자체 KIS endpoint 호출 0** — universe(instrument)는 T-IM1 baseline이 영속 + candle은 T-W2 baseline이 영속 + DSL 평가는 T-DSL strategy-eval-pool reuse. 본 사이클 sub-agent는 **KIS endpoint 신규 호출 0** LOCK.

**예외 발생 시 헌법 §3 의무 발동**: 본 ADR §2-7 "universe 캔들 cold-start" 결정에서 부분 candle 결손 발견 시 KIS REST 보강 fetch 합류 가능 — 그 경우 raw 인용 의무 (KisCandleAdapter baseline reuse + KDoc inline). 본 사이클 1차 LOCK은 **영속 candle 미충족 종목은 screening 결과에서 graceful skip + reason 표기** (사용자 메모리 `feedback_no_mock_data` 정합).

---

## §2. 결정 (8 의제 architect 자율 LOCK)

### 2-1. (A) 평가 모드 — **A-1 동기 inline 평가 채택** (vs A-2 비동기 Kafka publish)

| 옵션 | 사유 | 채택 |
|---|---|---|
| **A-1** REST POST 요청 → 동기 inline 평가 → 응답 body에 결과 + 랭킹 | universe ~3000 종목 × DSL 평가 시간 (5초 이내 estimate) → REST timeout 30s 내 충분. UX 즉시성 우위. | **채택** |
| A-2 REST POST → 202 Accepted + Kafka publish → 비동기 평가 → STOMP push | universe 5000+ 가정 시 필요하나 1차 MVP는 과한 추상화 (개발 룰 — 과도한 추상화 금지) | 폐기 |
| A-3 dual (사이즈 threshold) | 1차에선 LOC 비용 ↑ | 폐기 (post-MVP backlog) |

**근거 추정**:
- universe ~3000 × DSL 평가 단건 ~1ms (T-BT 패턴 BacktestEngine slice eval reuse) = **~3초 inline**
- universe + 5분 캐시 hit 시 ~50ms (Redis fetch only)
- STOMP 비동기 fanout은 후속 backlog §N (universe 5000+ 도달 시 발동)

### 2-2. (B) Universe Source — **B-1 instrument 테이블 + 필터 매트릭스 채택** (vs B-2 외부 KIS endpoint 매번 fetch)

| 옵션 | 사유 | 채택 |
|---|---|---|
| **B-1** PostgreSQL `instrument` 테이블(T-IM1 baseline) + 필터(market·active·시총·거래대금) | 영속 데이터 reuse + KIS API rate limit 0 + 13시각 cron sync로 일별 정합 | **채택** |
| B-2 매 호출 시 KIS endpoint fetch | KIS Rate Limit 폭주 + 캐시 부재 시 시간 ↑↑ | 폐기 |
| B-3 Redis universe 캐시 | 영속 → Redis 캐시 추가는 본 사이클 비범위. screening 결과 캐시(§2-4)는 별개 |  부분 채택 (universe filter list만 Redis 캐시 TTL 1일) |

**필터 매트릭스**:

| 필터 | default | 사용자 토글 | 사유 |
|---|---|---|---|
| `market IN ('KOSPI', 'KOSDAQ')` | ON | OFF 옵션 (확장 시 KONEX 등) | core universe |
| `active = true` (상장폐지 제외) | ON LOCK | - | 생존자 편향 cover 위함 (delisted_at IS NULL) |
| `type IN ('STOCK', 'ETF')` | ON | OFF 옵션 (warrant·preferred 등) | 매매 대상 limited |
| 시총 ≥ N (옵션) | OFF | ON + threshold | screening 시간 단축 |
| 평균거래대금 ≥ M (옵션) | OFF | ON + threshold | 유동성 필터 |

**universe 캐시 키**: `screener:universe:{filterHash}` TTL 1일 (instrument 13시각 cron sync 이후 무효).

### 2-3. (C) DSL 평가 엔진 — **C-1 T-SIG strategy-eval-pool reuse 채택** (vs C-2 별도 풀)

| 옵션 | 사유 | 채택 |
|---|---|---|
| **C-1** ADR 0047 strategy-eval-pool reuse (`fixed 4` thread pool) + timeout 100ms·CPU budget 50ms | DSL evaluator 단일 출처 LOCK + 같은 sandbox 정책 reuse + 일관성 ↑ | **채택** |
| C-2 별도 screener-eval-pool 신규 | 격리 우위 있으나 코드 중복 + DSL evaluator 분리 cost ↑ | 폐기 (1차) |
| C-3 별도 pool + 사용자 토글 (5분 캐시 hit 시 회피) | 1차 MVP scope 초과 | 폐기 |

**universe ~3000 × timeout 100ms = 최대 ~5분 inline**: 따라서 본 사이클 timeout 의제 갱신 — **universe screening 모드 한정 timeout 500ms LOCK** (개별 DSL 평가 시간 — 5x 우위 후 fallback로 strategy.status=DISABLED 처리).

**eval 비동기 pool 적용**:
```kotlin
fun screenUniverse(query: ScreenerQuery): ScreenerResult {
    val universe = instrumentLookup.findActive(query.filters)
    val futures = universe.map { instrument ->
        strategyEvalPool.submit {
            evalSingle(query.strategy, instrument)  // timeout 500ms
        }
    }
    val matches = futures.mapNotNull { it.get(...) }
    return ScreenerResult(query, matches, ranked = rank(matches))
}
```

### 2-4. (D) 결과 영속 정책 — **D-1 Redis cache-only + 옵션 D-2 (영속 toggle) 채택**

| 옵션 | 사유 | 채택 |
|---|---|---|
| **D-1** Redis 캐시 only — `screener:cache:{dslHash}:{universeFilterHash}` TTL 30분 | 일별 batch (옵션) + 사용자 인터랙티브 호출 모두 5분 ~ 30분 단위 hit | **채택 (1차 MVP)** |
| D-2 영속 (screening_run 테이블) | 시계열 audit + Strategy 성능 변화 추적 + post-MVP T-PERF 합류 가능 | **옵션 채택 (env toggle `MAGICJAR_SCREENER_PERSIST=true` 시 영속, default false)** — Flyway V28 신규 (§3) |
| D-3 영속 only (캐시 없음) | UX 시간 ↑↑ | 폐기 |

**D-2 영속 ON 시 Flyway V28 → screening_run 테이블**: 본 ADR §3에서 DDL LOCK + IF NOT EXISTS 패턴.

**1차 MVP scope LOCK**: D-1 Redis cache-only. D-2는 Flyway slot V28 사전 예약 + 옵션 코드는 본 사이클 phase 2에서 backend-core 자율 결정 (Phase 2 LOCK은 cache-only 단독).

### 2-5. (E) 랭킹 알고리즘 — **E-1 multi-criteria + 사용자 선택 채택**

| 옵션 | 사유 | 채택 |
|---|---|---|
| **E-1** 룰 매칭 score + 거래대금 + 변동성 + 사용자 우선순위 가중치 | 트레이드 적합성 multi-axis 우위 | **채택** (1차 4 기준 fixed weights) |
| E-2 DSL 평가 score only (BUY 강도) | 단순 우위 있으나 비정상 outlier risk | 부분 채택 (E-1 baseline) |
| E-3 사용자 fully custom DSL `ORDER BY` 구문 | 사용자 비전 정합 우위 있으나 1차 LOC 비용 ↑ | 폐기 (post-MVP backlog) |

**1차 LOCK 랭킹 기준**:

| 기준 | weight (default) | 산정 |
|---|---|---|
| signal strength | 0.40 | DSL evalResult.strength (T-DSL StrategyEvalResult LOCK) — 0~1 normalize |
| 거래대금 (5일 평균) | 0.30 | log(avgVolume * close) normalize — 유동성 필터 |
| 변동성 (ATR(14)) | 0.20 | ATR/close ratio — 일정 범위(0.01~0.08) sweet spot |
| 시총 안정성 | 0.10 | log(marketCap) normalize — 너무 작거나 큰 종목 페널티 |

**가중치 사용자 토글**: 1차 LOCK은 fixed weights — sub-agent backend-core 자율 결정 (env `MAGICJAR_SCREENER_RANKING_WEIGHTS` 또는 사용자 DSL meta 합류). post-MVP backlog 의제.

### 2-6. (F) REST endpoint — **F-1 3 endpoint 채택** (POST evaluate + GET cache list + GET cache detail)

| 옵션 | 사유 | 채택 |
|---|---|---|
| **F-1** POST `/api/screener/evaluate` (inline 평가) + GET `/api/screener/runs?userId=` (영속 옵션) + GET `/api/screener/runs/{id}` | UX 즉시성 + audit | **채택** |
| F-2 inline POST only | audit 부재 | 폐기 (D-2 영속 옵션 합류 시 GET 2종 의무) |
| F-3 inline + 비동기 publish 분리 | A-1 채택과 모순 | 폐기 |

**Swagger 4단 LOCK** (사용자 메모리 `feedback_swagger_pattern` 정합 — §6 본문 참조).

### 2-7. (G) Universe 캔들 cold-start 결손 처리 — **G-1 graceful skip + reason 표기 채택**

| 옵션 | 사유 | 채택 |
|---|---|---|
| **G-1** 영속 candle 미충족 종목은 screening 결과에서 graceful skip + `skippedReasons` 매트릭스에 표기 | mock 합성 0 LOCK (사용자 메모리 정합) + 사용자에게 정직한 표시 | **채택** |
| G-2 cold-start KIS REST fetch | KIS Rate Limit 폭주 risk + universe ~3000 × API 호출 = 5분 이상 timeout | 폐기 |
| G-3 mock 합성 fallback | 사용자 메모리 `feedback_no_mock_data` 위반 | 폐기 (절대) |

**skip 매트릭스**:

| 사유 | 표기 |
|---|---|
| candle 0건 (영속 부재) | "캔들 미수집" |
| candle 부족 (지표 lookback 미충족) | "캔들 부족 (필요: {n}건, 보유: {m}건)" |
| DSL 평가 timeout | "DSL 평가 timeout (>500ms)" |
| DSL 평가 error | "DSL 평가 오류: {message}" |

### 2-8. (H) Strategy Builder UI 합류 — **H-1 합류 본 사이클 frontend Phase 진입 채택**

| 옵션 | 사유 | 채택 |
|---|---|---|
| **H-1** Strategy Builder(`/strategy`)에 "이 전략으로 종목 찾기" 버튼 추가 → ScreenerResultModal로 결과 표시 + Watchlist 등록 옵션 | 사용자 비전 정합 — 흐름 단절 회복(Strategy Builder → ? → Watchlist) | **채택 (Phase 3 frontend)** |
| H-2 별도 `/screener` 페이지 | UX 분리 비용 + universe screening은 Strategy Builder의 후속 행동 — 합류 적합 | 폐기 (1차) — post-MVP `/screener` 단독 페이지는 backlog §N |
| H-3 backend 단독 LOCK (frontend 0) | 사용자 비전 핵심 흐름 누락 risk | 폐기 |

**Phase 3 frontend 산출물**:
- Strategy Builder 페이지 "이 전략으로 종목 찾기" 버튼 추가 (StrategyBuilderForm.tsx 옆 actions)
- ScreenerResultModal.tsx — 결과 list table + 랭킹 점수 + "Watchlist 등록" 일괄 체크박스
- POST `/api/watchlist/bulk` (이미 baseline 존재 — 단건 POST endpoint multi 호출 또는 backend-core 신규 1 endpoint, 자율)
- useScreenerEvaluate React Query mutation

### 2-9. AI 호출 0 LOCK (개발 룰 5 + 사용자 메모리 정합)

| 검증 | 의무 |
|---|---|
| Spring AI/Anthropic/OpenAI import 0 | grep 검증 (§1-2) |
| DSL 평가 pure rule | strategy-eval-pool reuse — DSL evaluator는 룰 매칭 only (ADR 0047 baseline) |
| 랭킹 알고리즘 pure math | E-1 4 기준 fixed weights — LLM 호출 0 |
| 결과 explanation | DSL evalResult.reason (T-DSL baseline) + skip 매트릭스 표기 only — LLM 코멘트 부재 |

**AI 합류 후속 backlog**: W4 진입 시 AiCommentProvider 합류 가능 (사용자 "이 결과 해석해줘" 버튼 — D.18 AI Option Console). 본 사이클 비범위.

### 2-10. KIS Rate Limit 영향 (ADR 0037 정합 — 본 사이클 0 추정)

| 항목 | 산정 |
|---|---|
| KIS 호출 빈도 (본 사이클) | **0** — universe = instrument 테이블 영속 + candle = DB 영속 |
| 예외 (cold-start 합류 시) | G-2 폐기로 본 사이클 발동 0 |
| 후속 backlog 합류 시 | universe N 종목 × candle fetch — Rate Limiter acquire 의무 + 5분 throttle |

---

## §3. DB Flyway V28 매트릭스 (D-2 영속 옵션 사전 예약)

본 ADR §2-4 D-2 옵션 채택 — Phase 2 LOCK은 cache-only 단독. V28은 **slot 사전 예약 + 코드 발행 backend-core 자율** (헌법 §6 IF NOT EXISTS 강제 LOCK).

**V20~V27 사용 현황**:
- V20~V23: T-O (ADR 0068 §15-1)
- V24: T-O hotfix (position.created_at)
- V25: T-RECON Phase 2 (reconcile_run, ADR 0070)
- V26: T-RECON Phase 2 (risk_event enum placeholder, ADR 0070)
- V27: T-NOTIFY Phase 1 (notification_outbox + system_config.notification_slack_webhook, ADR 0070)

→ **V28**: T-SC Phase 2 옵션 영속 (`MAGICJAR_SCREENER_PERSIST=true` 시 ON, default OFF)

### 3-1. V28 — screening_run 테이블 (옵션 영속, default 비활성)

```sql
-- V28__screening_run.sql
-- T-SC Phase 2 옵션 영속 (ADR 0071 §2-4 D-2)
-- 기본 비활성(MAGICJAR_SCREENER_PERSIST=false) — env toggle 시 ON
CREATE TABLE IF NOT EXISTS screening_run (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    strategy_id BIGINT NOT NULL,
    universe_filter_hash VARCHAR(64) NOT NULL,
    dsl_hash VARCHAR(64) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    universe_size INT NOT NULL DEFAULT 0,
    evaluated_count INT NOT NULL DEFAULT 0,
    matched_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,        -- RUNNING · COMPLETED · ERROR
    last_error TEXT,
    trigger_source VARCHAR(16) NOT NULL -- API · CRON · MANUAL
);
CREATE INDEX IF NOT EXISTS ix_screening_run_user_started ON screening_run (user_id, started_at DESC);
CREATE INDEX IF NOT EXISTS ix_screening_run_strategy_started ON screening_run (strategy_id, started_at DESC);

CREATE TABLE IF NOT EXISTS screening_match (
    id BIGSERIAL PRIMARY KEY,
    screening_run_id BIGINT NOT NULL REFERENCES screening_run(id) ON DELETE CASCADE,
    instrument_code VARCHAR(16) NOT NULL,
    rank INT NOT NULL,
    score NUMERIC(10, 6) NOT NULL,
    score_components JSONB NOT NULL,  -- {"signal": 0.45, "volume": 0.20, "atr": 0.18, "marketCap": 0.08}
    reason TEXT
);
CREATE INDEX IF NOT EXISTS ix_screening_match_run_rank ON screening_match (screening_run_id, rank);
CREATE INDEX IF NOT EXISTS ix_screening_match_code ON screening_match (instrument_code);
```

**IF NOT EXISTS 강제 LOCK** (헌법 §6 + ADR 0068·0070 패턴 정합 — 사용자 hotfix 회피).

**Phase 2 LOCK은 V28 SQL 파일 신규 + JPA Entity·Adapter·Repository는 코드 0 옵션 toggle**: backend-core 자율 결정. 토글 OFF 시 Redis cache-only flow 단독 LOCK.

---

## §4. Kafka 토픽 매트릭스 (신규 0 LOCK)

| 항목 | 채택 |
|---|---|
| **신규 publish 토픽** | **0 LOCK** (본 사이클) |
| 사유 | A-1 동기 inline 평가 채택 — Kafka publish 패스 없음. 결과 즉시 REST body 반환 + Redis 캐시. |
| **후속 backlog** | universe 5000+ 도달 시 비동기 모드 합류 `screener.completed.v1` (post-MVP backlog §N) |

**listen 토픽**: 본 사이클 backend-core consumer가 listen할 신규 토픽 0. 기존 토픽(`watchlist.changed.v1` 등) 합류 없음.

---

## §5. Redis 키 매트릭스

ADR 0070 §2-5 패턴 정합. TTL 매트릭스 LOCK.

| Redis 키 | TTL | 용도 | sub-agent |
|---|---|---|---|
| `screener:cache:{dslHash}:{universeFilterHash}` | 30m | screening 결과 캐시 — A-1 동기 inline 평가 idempotency | backend-core (Phase 2) |
| `screener:universe:{filterHash}` | 24h | instrument 테이블 필터 캐시 — 13시각 cron sync 후 무효 | backend-core (Phase 2) |
| `screener:lock:{userId}:{strategyId}` | 1m | 동시 평가 방지 (분산락 SETNX) | backend-core (Phase 2) |

**`screener:lock` 분산락 패턴** (ADR 0038 KIS OAuth 분산락 + ADR 0070 §2-5 reconcile:lock 정합):
```kotlin
val locked = redis.opsForValue().setIfAbsent("screener:lock:$userId:$strategyId", "1", Duration.ofSeconds(60))
if (!locked) {
    // 동시 호출 — 직전 캐시 fetch 시도
    val cached = redis.get("screener:cache:${dslHash}:${filterHash}")
    return cached ?: throw ConcurrentScreeningException
}
try { ... } finally { redis.delete("screener:lock:$userId:$strategyId") }
```

**캐시 hit 시 sub-second response**: `screener:cache` HIT → Jackson deserialize → 즉시 REST body 반환 (50ms estimate).

---

## §6. REST endpoint 매트릭스 (3 endpoint Swagger 4단 LOCK)

ADR 0070 §2-6 패턴 정합. Phase 2 backend-core 책임.

### 6-1. POST /api/screener/evaluate — 동기 inline 평가

```kotlin
@Tag(name = "Screener", description = "종목 발굴 (universe × DSL → 후보 + 랭킹) — ADR 0071")
@RestController
@RequestMapping("/api/screener")
class ScreenerController(
    private val screenerService: ScreenerService,
) {

    @Operation(
        summary = "전략으로 종목 찾기 (inline 평가)",
        description = """
            등록된 Strategy DSL을 universe(KOSPI·KOSDAQ active instrument)에 적용해 매수 후보 + 랭킹 반환.
            30분 내 동일 DSL + 동일 필터 재호출 시 캐시 반환 (idempotency).
            universe size 산정 ~3000 × DSL eval 500ms timeout = 최대 inline ~25s.
        """
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "screening 결과 + 랭킹"),
        ApiResponse(responseCode = "404", description = "strategyId 미존재"),
        ApiResponse(responseCode = "409", description = "동시 평가 중 (lock)"),
        ApiResponse(responseCode = "500", description = "DSL 평가 엔진 오류"),
    ])
    @PostMapping("/evaluate")
    fun evaluate(@Valid @RequestBody request: ScreenerEvaluateRequest): ResponseEntity<ScreenerResultDto> = ...
}

@Schema(description = "screening 평가 요청")
data class ScreenerEvaluateRequest(
    @Schema(description = "사용자 ID (multi-tenant 격리)", example = "12345678")
    @field:NotNull val userId: Long,
    @Schema(description = "Strategy ID (저장된 DSL 참조)", example = "42")
    @field:NotNull val strategyId: Long,
    @Schema(description = "universe 필터 (옵션 — 기본 KOSPI+KOSDAQ active STOCK+ETF)")
    val filters: ScreenerFilterDto? = null,
    @Schema(description = "랭킹 최대 결과 수", example = "50")
    @field:Min(1) @field:Max(500) val limit: Int = 50,
)

@Schema(description = "screening 결과 + 랭킹")
data class ScreenerResultDto(
    @Schema(description = "screening 캐시 키 (debug용)", example = "screener:cache:abc123:xyz789")
    val cacheKey: String,
    @Schema(description = "universe 사이즈", example = "2847") val universeSize: Int,
    @Schema(description = "평가 완료 수", example = "2814") val evaluatedCount: Int,
    @Schema(description = "매칭 결과 수 (BUY 시그널 종목)", example = "23") val matchedCount: Int,
    @Schema(description = "skip 사유별 카운트", example = """{"캔들 미수집": 12, "캔들 부족": 21}""")
    val skippedReasons: Map<String, Int>,
    @Schema(description = "랭킹된 매칭 결과 (limit 적용)") val matches: List<ScreenerMatchDto>,
    @Schema(description = "평가 elapsed (ms)", example = "4521") val elapsedMs: Long,
    @Schema(description = "캐시 HIT 여부", example = "false") val cacheHit: Boolean,
)

@Schema(description = "screening 매칭 종목")
data class ScreenerMatchDto(
    @Schema(description = "랭킹 (1-base)", example = "1") val rank: Int,
    @Schema(description = "종목 코드", example = "005930") val instrumentCode: String,
    @Schema(description = "종목명", example = "삼성전자") val instrumentName: String,
    @Schema(description = "랭킹 점수 (0~1 normalize)", example = "0.854") val score: BigDecimal,
    @Schema(description = "점수 구성", example = """{"signal":0.40,"volume":0.20,"atr":0.18,"marketCap":0.08}""")
    val scoreComponents: Map<String, BigDecimal>,
    @Schema(description = "BUY 시그널 reason (DSL evalResult.reason 정합)", example = "RSI(14) < 30 + MACD bullish cross")
    val reason: String,
)
```

### 6-2. GET /api/screener/runs (옵션 영속 ON 시)

```kotlin
@Operation(
    summary = "screening 실행 이력 조회 (옵션 영속 모드 한정)",
    description = "MAGICJAR_SCREENER_PERSIST=true 환경에서만 활성. screening_run 시계열 (페이징, 최신순)."
)
@GetMapping("/runs")
fun listRuns(
    @Parameter(description = "사용자 ID", example = "12345678") @RequestParam userId: Long,
    @Parameter(description = "페이지 (0-based)", example = "0") @RequestParam(defaultValue = "0") page: Int,
    @Parameter(description = "페이지당 row 수", example = "20") @RequestParam(defaultValue = "20") size: Int,
): Page<ScreeningRunDto> = ...
```

### 6-3. GET /api/screener/runs/{id} (옵션 영속 ON 시)

```kotlin
@Operation(
    summary = "screening 단건 상세 (옵션 영속 모드 한정)",
    description = "screening_run + screening_match 배열 상세 (랭킹 50건 default + skip 매트릭스)."
)
@GetMapping("/runs/{id}")
fun findDetail(
    @Parameter(description = "screening 실행 ID", example = "1001") @PathVariable id: Long,
    @Parameter(description = "사용자 ID (격리 검증)", example = "12345678") @RequestParam userId: Long,
): ScreeningRunDetailDto = ...
```

**userId mismatch 시 404 LOCK** (ADR 0010 multi-tenant 격리 정합).

**옵션 영속 OFF 시 6-2·6-3 endpoint**: `MAGICJAR_SCREENER_PERSIST=false` 환경에선 `404 Not Found` 또는 `503 Service Unavailable` 반환 (backend-core 자율 결정 — 일관성을 위해 404 권장).

---

## §7. STOMP 토픽 매트릭스 (신규 0 LOCK)

본 사이클 STOMP fanout 0. A-1 동기 inline 평가 채택 — REST body로 즉시 반환 + 캐시. STOMP push 필요 시점은:
- 비동기 모드 합류(universe 5000+ post-MVP backlog §N)
- DailyScreenerJob cron 결과 push (post-MVP backlog §N)

**1차 LOCK frontend 호출**: POST `/api/screener/evaluate` → response body 즉시 모달 표시. STOMP subscribe 0.

---

## §8. UI 변경 매트릭스 (Phase 3 frontend 합류)

ADR §2-8 H-1 채택 — Strategy Builder 합류.

| UI 영역 | 변경 | 사유 |
|---|---|---|
| `/strategy` Strategy Builder 페이지 | **"이 전략으로 종목 찾기" 버튼 추가** | 핵심 흐름 합류 |
| ScreenerResultModal.tsx (신규) | **결과 표시 모달** | inline 평가 결과 즉시 표시 |
| 모달 내 "Watchlist 일괄 등록" | **체크박스 + 일괄 등록 버튼** | 발굴 → Watchlist 연결고리 |
| `/screener` 단독 페이지 | **변경 0 — backlog** | 1차 LOCK는 Strategy Builder 합류만 |
| /risk 페이지 reconcile timeline (T-RECON 후속) | **변경 0** | T-SC 비범위 |

**Phase 3 frontend 산출물 핵심**:
```
frontend/src/features/screener/
├── components/
│   ├── ScreenerResultModal.tsx
│   ├── ScreenerMatchTable.tsx
│   └── ScreenerScoreBreakdown.tsx
├── hooks/
│   └── useScreenerEvaluate.ts
└── api/
    └── screenerApi.ts
```

Strategy Builder 페이지 수정:
```typescript
// frontend/src/pages/StrategyBuilderPage.tsx (기존)
<Button onClick={() => setScreenerOpen(true)}>이 전략으로 종목 찾기</Button>
{screenerOpen && <ScreenerResultModal strategyId={strategy.id} userId={userId} onClose={...} />}
```

---

## §9. sub-agent 디스패치 매트릭스 (Phase 분리 LOCK)

### 9-1. 직렬 디스패치 매트릭스

**원칙**: Phase 1 (domain port + consumer screening engine — strategy-engine) → Phase 2 (api REST + Redis cache + 옵션 V28 — backend-core) → Phase 3 (frontend Strategy Builder 합류 + ScreenerResultModal — frontend) 직렬.

| 순서 | Phase | sub-agent | 영역 | 의존 |
|---|---|---|---|---|
| 1 | **Phase 1** | **strategy-engine** | domain + consumer | DSL evaluator pool reuse (ADR 0047 baseline) + instrument lookup port (T-IM1 baseline) |
| 2 | **Phase 2** | **backend-core** | api + Redis + 옵션 Flyway V28 | Phase 1 머지 후 |
| 3 | **Phase 3** | **frontend** | Strategy Builder 합류 + 모달 | Phase 2 머지 후 |

**병렬 가능 검토**: Phase 1·2 add-only 영역 분리 → 병렬 가능하나 backend-core REST는 domain port 의존(Phase 1 산출물) — 직렬이 정합. Phase 3은 Phase 2 REST endpoint 의존 — 직렬 LOCK.

**ObjectProvider graceful fallback** (헌법 §6 + ADR 0066·0070 패턴): Phase 2·3 미합류 시 Phase 1 단독 부팅 가능 (domain port + consumer screening engine 단독 부팅, REST endpoint 부재 시 backend 호출 0 — 부팅 의무 정합).

### 9-2. Phase 1 산출물 매트릭스 (strategy-engine)

| 영역 | 파일 | 책임 |
|---|---|---|
| **domain** | `domain/.../screener/ScreenerQuery.kt` | 평가 요청 모델 (userId, strategyId, filters, limit) |
| **domain** | `domain/.../screener/ScreenerResult.kt` | 결과 + 랭킹 + skip 매트릭스 |
| **domain** | `domain/.../screener/ScreenerMatch.kt` | 매칭 종목 + 랭킹 점수 + score components |
| **domain** | `domain/.../screener/ScreenerFilter.kt` | universe 필터 (market·active·type·marketCap·volume) |
| **domain** | `domain/.../screener/ScreenerStatus.kt` | enum (RUNNING·COMPLETED·ERROR) |
| **domain** | `domain/.../screener/Universe.kt` | universe 표현 (instrument list + filter hash) |
| **domain port** | `domain/.../screener/port/ScreenerEngine.kt` | `fun screen(query): ScreenerResult` (헌법 §6 abstract LOCK) |
| **domain port** | `domain/.../screener/port/InstrumentLookupPort.kt` | `fun findActive(filter): List<Instrument>` (T-IM1 baseline reuse 또는 신규 — strategy-engine 자율 결정) |
| **domain port** | `domain/.../screener/port/ScreenerCachePort.kt` | get·put (Redis 추상) |
| **domain port** | `domain/.../screener/port/ScreenerRankerPort.kt` | `fun rank(matches): List<ScreenerMatch>` (E-1 multi-criteria) |
| **consumer** | `consumer/.../screener/ScreenerEngineImpl.kt` | universe iterate + strategy-eval-pool 비동기 평가 + skip 매트릭스 누적 |
| **consumer** | `consumer/.../screener/ScreenerRankerImpl.kt` | E-1 multi-criteria 랭킹 (signal 0.40 + volume 0.30 + atr 0.20 + cap 0.10) |
| **consumer** | `consumer/.../screener/ScreenerStrategyEvalAdapter.kt` | T-DSL StrategyEvaluator + strategy-eval-pool reuse + timeout 500ms |
| **test** | `consumer/src/test/.../ScreenerEngineImplTest.kt` | 5 케이스 (정상·universe empty·timeout 일부·error 일부·캔들 미수집 skip) |
| **test** | `consumer/src/test/.../ScreenerRankerImplTest.kt` | 4 케이스 (4기준 가중치·동점 처리·empty matches·outlier penalty) |

**Phase 1 단위 테스트 ~9건 GREEN 의무**.

### 9-3. Phase 2 산출물 매트릭스 (backend-core)

| 영역 | 파일 | 책임 |
|---|---|---|
| **api/messaging** | `api/.../infrastructure/cache/RedisScreenerCacheAdapter.kt` | ScreenerCachePort 구현 — Jackson serialize + TTL 30m |
| **api/persistence (옵션)** | `api/db/migration/V28__screening_run.sql` | screening_run + screening_match 테이블 IF NOT EXISTS (D-2 옵션) |
| **api/persistence (옵션)** | `api/.../persistence/screener/Screening{Run,Match}{JpaEntity,Repository,Adapter}.kt` | env toggle ON 시 영속 어댑터 (backend-core 자율) |
| **api/service** | `api/.../service/screener/ScreenerService.kt` | 분산락 + 캐시 lookup + ScreenerEngine 호출 + 옵션 영속 |
| **api/controller** | `api/.../controller/screener/ScreenerController.kt` | 3 REST endpoint Swagger 4단 (§6) |
| **api/dto** | `api/.../dto/screener/Screener*Dto.kt` | request·response DTO (§6 매트릭스 정합) |
| **api/infrastructure** | `api/.../infrastructure/instrument/InstrumentLookupAdapter.kt` | T-IM1 baseline reuse — InstrumentRepository 합류 |
| **test** | `api/src/test/.../ScreenerServiceTest.kt` | 4 케이스 (캐시 HIT·MISS+inline eval·분산락 충돌·옵션 영속) |
| **test** | `api/src/test/.../ScreenerControllerTest.kt` | 5 케이스 (POST evaluate 200·404 strategy 부재·409 lock·GET runs list·GET runs detail) |
| **test** | `api/src/test/.../RedisScreenerCacheAdapterTest.kt` | 3 케이스 (put·get HIT·TTL expire) |

**Phase 2 단위 테스트 ~12건 GREEN 의무**.

### 9-4. Phase 3 산출물 매트릭스 (frontend)

| 영역 | 파일 | 책임 |
|---|---|---|
| **pages** | `frontend/src/pages/StrategyBuilderPage.tsx` | "이 전략으로 종목 찾기" 버튼 + 모달 mount |
| **components** | `frontend/src/features/screener/components/ScreenerResultModal.tsx` | 모달 + 결과 표시 + 닫기·등록 액션 |
| **components** | `frontend/src/features/screener/components/ScreenerMatchTable.tsx` | 랭킹 table (rank·name·score·reason) |
| **components** | `frontend/src/features/screener/components/ScreenerScoreBreakdown.tsx` | 점수 구성 tooltip (signal/volume/atr/cap 4 bar) |
| **components** | `frontend/src/features/screener/components/ScreenerSkipReasonsBadge.tsx` | skip 매트릭스 badge (캔들 미수집 N건 등) |
| **hooks** | `frontend/src/features/screener/hooks/useScreenerEvaluate.ts` | React Query mutation `POST /api/screener/evaluate` |
| **hooks** | `frontend/src/features/screener/hooks/useBulkAddWatchlist.ts` | 일괄 Watchlist 등록 — 기존 watchlist API multi 호출 (또는 신규 bulk POST endpoint 합류) |
| **api** | `frontend/src/features/screener/api/screenerApi.ts` | axios endpoint + 타입 정의 |
| **test** | `frontend/src/features/screener/__tests__/ScreenerResultModal.test.tsx` | 4 케이스 (loading·matches render·skip badge·watchlist 등록) |

**Phase 3 단위 테스트 ~4건 GREEN 의무**.

---

## §10. 검증 시나리오 (E2E 의무)

ADR 0070 §3-5 fitness 검증 패턴 정합. dogfooding 사이클로 사용자 메모리 `feedback_verification_strict` 정합 (할루시네이션 금지).

| # | 시나리오 | 검증 | 기대 |
|---|---|---|---|
| 1 | universe 정상 + DSL 평가 | `POST /api/screener/evaluate` (RSI<30 BUY 전략) | universe ~3000 평가 + 매칭 N건 + 랭킹 + cacheHit=false + elapsedMs < 30000 |
| 2 | 캐시 HIT | 1번 반복 호출 | cacheHit=true + elapsedMs < 100 |
| 3 | 동시 호출 분산락 | 1번 동시 2회 호출 | 1회 inline 평가 + 1회 cache fetch 또는 409 |
| 4 | candle 결손 graceful skip | universe 일부 (신규 상장 종목) candle 0건 | skippedReasons["캔들 미수집"] ≥ 1 + 결과에서 제외 |
| 5 | DSL 평가 timeout | strategy-eval-pool timeout 500ms 트리거 | skippedReasons["DSL 평가 timeout"] ≥ 1 + auto-disable 트리거 (ADR 0047 정합) |
| 6 | 옵션 영속 ON | `MAGICJAR_SCREENER_PERSIST=true` 환경 + 1번 호출 | screening_run + screening_match row INSERT + GET /runs 응답 N건 |
| 7 | Strategy Builder 합류 UX | `/strategy` 페이지 → 전략 선택 → "이 전략으로 종목 찾기" 클릭 | ScreenerResultModal 표시 + 결과 + Watchlist 일괄 등록 정상 |
| 8 | AI 호출 0 grep 검증 | `grep -rn "import.*anthropic\|import.*openai\|ChatClient\|springframework\.ai" consumer/screener domain/screener api/.../screener` | 0 lines |
| 9 | KIS endpoint 호출 0 검증 | wireshark 또는 mock KIS server | 본 사이클 KIS API 호출 0 (universe·candle은 영속 reuse) |
| 10 | mock 합성 0 검증 | universe에 instrument 테이블 row 부재 시 | "데이터 없음" 또는 universe size=0 응답 (mock 합성 금지) |

---

## §11. 한계 + backlog 후보

### 11-1. 1차 MVP scope 한계

| 한계 | 사유 | backlog 의제 |
|---|---|---|
| universe 5000+ 비동기 모드 | A-1 동기 inline 채택 | post-MVP backlog §N — `screener.completed.v1` Kafka publish + STOMP fanout |
| `/screener` 단독 페이지 | H-1 Strategy Builder 합류 1차 | post-MVP backlog §N — 단독 페이지 (전략 비선택 상태 진입 가능) |
| 사용자 custom 랭킹 가중치 | E-1 fixed weights 1차 | post-MVP backlog §N — 사용자 DSL meta 또는 env override |
| DailyScreenerJob cron (마스터 §3-1 [3]) | 1차 LOCK은 inline only | post-MVP backlog §N — 다음 영업일 09:00 cron + 후보 Slack push |
| AiCommentProvider 합류 ("이 결과 해석해줘") | AI 호출 0 LOCK 본 사이클 | W4 진입 시 backlog §N |
| Watchlist bulk POST endpoint | 1차는 단건 POST multi 호출 또는 frontend loop | post-MVP backlog §N — backend bulk endpoint 정합 |
| screening_match cron 정합 | screening_run 영속 후 다음 cron까지 lag 가능 | post-MVP backlog §N — screening_match retention 정책 (30일 등) |

### 11-2. 후속 ADR/backlog 후보

- **ADR 0072 (가칭)** — DailyScreenerJob cron 표준 (cron 시각·DSL 선정 정책·결과 분배)
- **ADR 0073 (가칭)** — 사용자 custom 랭킹 가중치 + DSL meta 합류
- **backlog §52 (신규)** — Risk 페이지 reconcile timeline UI (ADR 0070 §2-8 후속, T-SC와 무관 — 이미 backlog §46 등록됨)
- **backlog §52 (신규)** — Screener 비동기 모드 (universe 5000+ + Kafka publish + STOMP push)
- **backlog §53 (신규)** — `/screener` 단독 페이지 (전략 비선택 진입)
- **backlog §54 (신규)** — DailyScreenerJob cron (다음 영업일 09:00 + 후보 Slack push)
- **backlog §55 (신규)** — 사용자 custom 랭킹 가중치 (DSL meta 합류)
- **backlog §56 (신규)** — Watchlist bulk POST endpoint 정합 (단건 multi 호출 → bulk endpoint)
- **backlog §57 (신규)** — Screener 결과 AI 해석 (W4 AiCommentProvider 합류)

---

## §12. Rollback 시나리오

| 시나리오 | rollback 절차 |
|---|---|
| Phase 1 머지 후 DSL 평가 timeout 빈발 | strategy.status=DISABLED 자동 처리 (ADR 0047 baseline) + sub-agent feedback로 timeout 1000ms 상향 검토 |
| Phase 2 머지 후 Redis 캐시 메모리 폭주 | TTL 30m → 10m 또는 LRU 정책 강화 (cache size monitor) |
| Phase 3 머지 후 Strategy Builder UX 회귀 | "이 전략으로 종목 찾기" 버튼 hidden flag 토글 (env `MAGICJAR_SCREENER_UI_ENABLED=false`) — backend는 정상 가동 |
| 옵션 영속 V28 머지 후 DDL 충돌 | IF NOT EXISTS 강제 LOCK으로 hotfix 회피 + Flyway baseline reset 불필요 |
| universe instrument 부재 (T-IM1 미머지 환경) | universe size=0 + "instrument 미수집" 표시 — graceful (mock 합성 금지) |

**Phase 1 단독 rollback 안전 LOCK** (헌법 §6 ObjectProvider): Phase 2·3 미합류 환경에서도 Phase 1 단독 부팅 가능. 모든 Phase 머지 후 dogfooding 실패 시 frontend hidden flag 우선 + Phase 2 backend는 정상 가동 fallback.

---

## §13. 트레이드오프

| 검토안 | 채택 여부 | 사유 |
|---|---|---|
| (a) **A-1 동기 inline + Redis 캐시** | ✓ 채택 | universe ~3000 inline 충분 + UX 즉시성 + 1차 MVP 적정 추상화 |
| (b) A-2 비동기 Kafka publish + STOMP push | 폐기 (1차) | universe 5000+ 도달 전엔 과한 추상화 — 개발 룰 정합 |
| (c) D-1 Redis cache-only + D-2 옵션 영속 V28 | ✓ 채택 (D-1 LOCK · D-2 옵션) | 1차 MVP 단순화 + audit 필요 시 env toggle |
| (d) E-1 multi-criteria 랭킹 (4 기준 fixed weights) | ✓ 채택 | trade 적합성 multi-axis 우위 + 1차 LOC 비용 적정 |
| (e) H-1 Strategy Builder 합류 (vs H-2 별도 `/screener`) | ✓ 채택 (H-1) | 흐름 단절 회복 — Strategy Builder → ? → Watchlist 사이 합류 적합 |
| (f) C-1 strategy-eval-pool reuse | ✓ 채택 | DSL evaluator 단일 출처 LOCK + sandbox 정책 일관성 |
| (g) C-2 별도 screener-eval-pool 신규 | 폐기 (1차) | 격리 우위 있으나 코드 중복 + 분리 cost ↑ — 본 사이클 LOC 적정 |
| (h) G-1 graceful skip (vs G-2 cold-start fetch) | ✓ 채택 (G-1) | mock 합성 0 LOCK + KIS Rate Limit 폭주 회피 |
| (i) 영속 영구 ON (D-2만 채택) | 폐기 | 1차 MVP는 영속 부담 ↓ + Redis cache 충분 — env toggle 정합 |
| (j) F-1 3 endpoint (POST + GET 2) | ✓ 채택 | inline 평가 + 옵션 audit 모두 cover |
| (k) AiCommentProvider 합류 | 폐기 (본 사이클) | 개발 룰 5 W4 한정 — AI 호출 0 LOCK 정합 |

---

## §14. KIS-first raw 인용 (헌법 §3 검증 매트릭스)

**본 사이클 KIS endpoint 신규 호출 0 LOCK** — universe + candle 모두 영속 데이터 reuse. 따라서 raw 인용 의무 발동 0.

**baseline ADR cross-ref**:
- ADR 0017 (Instrument Master 1급 시민) — universe instrument 표 source
- ADR 0019 (Watchlist 캔들 정책) — candle 영속 베이스라인
- ADR 0037 (KIS Rate Limit) — 본 사이클 호출 0이므로 영향 0
- ADR 0047 (DSL Sandbox) — strategy-eval-pool reuse 단일 출처

**예외 발생 시 의무 발동 절차**: cold-start fetch 합류 결정 시(post-MVP backlog) — 그때 raw URL fetch + KDoc inline + 헌법 §3 5단계 절차 정독 의무. 본 ADR 0071 cycle에서는 G-1 graceful skip 채택으로 회피.

---

## §15. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-14 | ADR 0071 신규 발행 — T-SC Screener spec/plan (W3 다섯 번째 트랙 v3.6.3 patch). 8 의제 architect 자율 LOCK (A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1). 3 Phase 직렬 디스패치(strategy-engine domain+consumer → backend-core api+Redis+옵션 V28 → frontend Strategy Builder 합류+ScreenerResultModal). universe = instrument 영속 + candle = 영속 reuse, KIS endpoint 호출 0 LOCK. DSL 평가 = strategy-eval-pool reuse (ADR 0047) + timeout 500ms (universe screening 한정). 결과 = Redis cache 30m + 옵션 영속 Flyway V28(screening_run + screening_match, env toggle). 랭킹 = 4 기준 multi-criteria(signal 0.40 + volume 0.30 + atr 0.20 + cap 0.10). REST 3 endpoint Swagger 4단(POST evaluate + GET runs list + GET runs detail). STOMP 0. Kafka 신규 0. 사용자 비전 정합 = Strategy Builder "이 전략으로 종목 찾기" 흐름 단절 회복 + Watchlist 일괄 등록. fitness = ★★★ "전략으로 종목 찾기" UX 핵심 + v1/v2 잔재 0 + AI 호출 0 + mock 합성 0. 사용자 자율 최종권한 위임 (2026-05-13 "W3 진행해서 마무리까지하자 W4 진입까지 자율"). v3.6.0 T-O 마감 + v3.6.2 T-RECON·T-NOTIFY 마감 후 단일 사이클 v3.6.3 patch. spec 본문: `_workspace/specs/pr-393-t-sc-spec.md`. CHANGELOG slot 3 anchor(architect + strategy-engine + backend-core + frontend — architect anchor 단일 prepend, Phase별 sub-agent 합류 시 in-place edit). (architect) |
