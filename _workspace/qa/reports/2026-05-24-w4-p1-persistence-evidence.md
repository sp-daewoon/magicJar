# W4 P1 — 영속 작동 (ai_comment_log + mcp_audit + TrendPreference) + KIS Rate Limit 매트릭스

- **dispatch**: orchestrator → qa W4 P1 후속 (5건 중 §4·§5)
- **agent**: qa (magicjar-qa "품질 헌터")
- **수행 시각**: 2026-05-24 17:12 KST
- **HEAD baseline**: `094ab481`

## 1. ai_comment_log INSERT 검증 결과 — **P0 결함 발견**

### 1-1. 호출 evidence

- 5 AI 진입점 호출 (SYMBOL·CHART·STRATEGY·SIGNAL·MARKET 각 1회) — log 확정
- `AiExplainService.handle()` 5건 모두 진입 + `AiKillSwitchGuard.check()` → REFUSED 분기

### 1-2. ai_comment_log 영속 결과

```
postgres# SELECT count(*) FROM ai_comment_log WHERE created_at > now() - interval '5 min';
 total | graceful_refuse
-------+-----------------
     0 |               0
```

**INSERT 0건** — 5 graceful refuse 진입에도 불구하고 영속 0건.

### 1-3. 결함 분석

- `AiExplainService.handle()` 라인 44 `recorder.recordRefusal(...)` 호출 의도
- `AiCommentLogRecorder.recordRefusal()` 라인 59 `repository.save(entity)` 의도
- 실패 시 라인 62 `log.warn("ai_comment_log refuse 기록 실패 ...")` 출력 의도
- **그러나 warn log 0건 + INSERT 0건** — try 블록은 도달했으나 save가 silently swallow됨

### 1-4. 추정 root cause

- `AiCommentLogRecorder`가 `@Component` 단독 (no `@Transactional`)
- `AiCommentLogJpaRepository.save()` 호출이 **implicit transaction 없이 진행** → flush 미발생 → INSERT 영속 안 됨
- PostgreSQL은 auto-commit 모드가 spring data jpa 환경에서 default 비활성 (Hibernate session-per-request)
- WebMvc 처리 끝에서도 transactional 컨텍스트 없으므로 commit 발생 0

### 1-5. 결정 분류

- **P0 cut 차단 결함** — AI 호출 audit 영속 0 = ADR 0026 §2-6 (모든 refuse audit 기록 의무) 위반
- **dispatch 요청**: backend-core 또는 ai-engineer P0 hotfix
- **fix 옵션**:
  1. `AiCommentLogRecorder` 클래스에 `@Transactional` 추가 (가장 작은 변경)
  2. 또는 `AiExplainService.handle()`에 `@Transactional` 추가 (전체 분기 atomic)
  3. 또는 `AiCommentLogJpaRepository.saveAndFlush()` 명시 호출

## 2. mcp_audit INSERT 검증 결과 — **6/6 PASS**

### 2-1. 영속 결과

```
postgres# SELECT count(*) FROM mcp_audit WHERE called_at > now() - interval '30 min';
 recent_count
--------------
            6
```

### 2-2. 6 tool 상세 매트릭스

| tool | user_id | latency_ms | response_size_bytes | error_message |
|------|---------|-----------|---------------------|---------------|
| backtest_strategy | 74683223 | 6 | (null) | `tool 내부 오류 (StrategyNotFoundException)` |
| run_screener | 74683223 | 51 | 118 | (null) |
| get_journal | 74683223 | 16 | 2424 | (null) |
| get_candles | 74683223 | 7 | 1081 | (null) |
| get_market_trend | 74683223 | 14 | 797 | (null) |
| explain_indicator | 74683223 | 2 | 535 | (null) |

- **PASS** — 모든 6 tool 영속 정합 + 500 error 분기도 audit 기록 (`error_message` 한국어)
- latency 측정 적정 (~2~51ms · response_size 적정)
- ADR 0027 §2-7 MCP audit 의무 정합

## 3. TrendPreference lazy INSERT 검증 — **부분 PASS**

### 3-1. 호출 매트릭스

| 단계 | 호출 | 응답 | DB 결과 |
|------|------|------|--------|
| 1 | GET `/api/users/me/preferences/trend` (header X-User-Id: 999) | `{"maPeriods":[5,20,60],"rsPeriod":60,"isUserCustom":false}` | INSERT 0 (default echo) |
| 2 | DB count | — | user_preference 1건 (user_id=74683223, default ADR 0101 seed) |
| 3 | PUT 동일 endpoint `{"maPeriods":[10,30,100],"rsPeriod":120}` | `{"maPeriods":[10,30,100],"rsPeriod":120,"isUserCustom":true}` | UPSERT (user_id=74683223 행 update) |
| 4 | GET 재호출 | `{"maPeriods":[10,30,100],"rsPeriod":120,"isUserCustom":true}` | 저장 값 echo PASS |

### 3-2. DB evidence (PUT 직후)

```
user_id  | trend_ma_periods | trend_rs_period |          updated_at
---------+------------------+-----------------+-----------------------------
74683223 | [10, 30, 100]    |             120 | 2026-05-24 17:12:25.865031+09
```

### 3-3. 발견 사항

- **PASS**: GET default echo + PUT upsert + GET 저장 값 echo (round-trip 정합)
- **부분 검증 불가**: 신규 user 999 lazy INSERT path — backend가 `X-User-Id` 헤더 무시 + default user `74683223` (SystemConfig fallback)로 폴백 → 멀티 user 분기 검증은 ADR 0102 user model 진화 이후로 보류
- 단일 user 모드에서 GET 1회 응답에 INSERT 0 + PUT 1회에 INSERT/UPDATE 1 = ADR 0101 의도 정합

## 4. KIS Rate Limit 26 req/min 측정 — **0 호출 (NoopReader 활성 + cron 미작동)**

### 4-1. Prometheus metric 검증

```
docker exec magicjar-api wget -qO- http://localhost:8080/actuator/prometheus | grep -iE "kis.*ratelimit|kis_rate|ingestion_low"
# 0 lines (Micrometer 미발행)
```

### 4-2. KIS 호출 log 검증 (지난 5분)

```
docker logs --since 5m magicjar-api | grep -iE "KIS.*request|MarketIndex"
# 0 lines
```

### 4-3. market_index_snapshot 영속 검증

```
postgres# SELECT count(*) FROM market_index_snapshot;
 count
-------
     0
```

### 4-4. NoopReader 활성 확인

```
docker logs magicjar-api | grep -i "noop\|MarketIndexSnapshotJpaAdapter"
```

- 소스 코드 확인: `api/src/main/kotlin/.../persistence/trend/NoopMarketIndexSnapshotReader.kt`
- `@ConditionalOnMissingBean(MarketIndexSnapshotReader::class)` — R1 market-data의 실제 Adapter 미머지 시 noop 활성
- 결과: **MarketIndexSyncJob cron 미작동 + KIS 호출 0건**

### 4-5. EGW00201 발생 검증

- `docker logs magicjar-api | grep EGW00201` → 0건
- 한도 점유율: **0%** (호출 자체 0건이므로 안전 자명)

### 4-6. cut 영향 평가

- KIS Rate Limit EGW00201 0건 → cut 차단 X
- 그러나 **MarketIndexSyncJob 미작동 + market_index_snapshot 0건 + dataInsufficient:true 응답**은 사용자 dogfooding 시 빈 화면 노출 risk → **architect 진단 dispatch 권고** (NoopReader fallback 의도인지 / R1 market-data Adapter 미머지 결함인지 결정)

## 5. 영속 작동 요약 매트릭스

| 영역 | INSERT 검증 | 결과 | cut 영향 |
|------|------------|------|---------|
| ai_comment_log | 5 graceful refuse 호출 후 | **0건 (P0 결함)** | **CUT 차단** |
| mcp_audit | 6 tool 호출 후 | 6건 (PASS) | OK |
| TrendPreference (단일 user) | GET 1 → 0 / PUT 1 → upsert | PASS | OK |
| TrendPreference (멀티 user lazy) | 신규 user 999 GET → PUT | header 무시 — 검증 보류 | OK (보류) |
| market_index_snapshot | cron 1분 1회 4 지수 | 0건 (cron 미작동) | OK (P2) |
| KIS Rate Limit 26 req/min | MarketIndexSync 실행 후 통계 | 0 호출 + EGW00201 0건 | OK |

## 6. cut 차단 결정 위임 (orchestrator 보고)

### P0 — cut 차단 의무 (1건)

- **ai_comment_log INSERT 0건** — ADR 0026 §2-6 audit 의무 위반 → backend-core / ai-engineer hotfix

### P1 — spec/code 수정 (3건)

- W4-5 spec path 오타 (`/mcp/tools` → `/api/mcp/tools`) — qa 자체 hotfix
- W4-6 TrendPreference Bean Validation 400 handler 누락 — backend-core hotfix
- backtest_strategy 500 graceful (StrategyNotFoundException 처리) — backend-core 또는 strategy-engine

### P2 — 진단 후 결정 (1건)

- NoopMarketIndexSnapshotReader 단독 활성 (R1 market-data Adapter 미머지인지 fallback 정상인지) — architect

## 7. 부수 보고: GEMINI API key 노출 (사용자 메모리 `feedback_secret_handling`)

- 본 dispatch 진행 중 `.env`에 `MAGICJAR_MCP_API_KEY=qa-test-mcp-key-001` 추가 시 마지막 줄 newline 누락으로 인해 기존 `GEMINI_API_KEY` 라인과 결합되어 채팅 로그에 평문 노출
- 정정 절차 완료 (Python 스크립트로 .env 라인 분리)
- **사용자 행동 권고**: GEMINI API key 즉시 재발급 (Google AI Studio) + 새 키 `.env` 갱신 + IP 화이트리스트 적용 권고
