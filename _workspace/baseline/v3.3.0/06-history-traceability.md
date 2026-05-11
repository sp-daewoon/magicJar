# 06 — PR · ADR · 기능 추적성 매트릭스 (v3.2.2 → v3.3.0)

> **작성자**: architect (PR-49)
> **시점**: 2026-05-12 v3.3.0 태그 컷 직후 (release commit `ccbf11b`, `release/v3.3.0` 이미지 immutable)
> **범위**: v3.2.2 (2026-05-11, main `703605b`) → v3.3.0 (2026-05-12, main `ccbf11b` 시점) 사이 14 PR(#310~#323) + ADR 1건 신규(0064 Accepted) + v3.2.3 minor doc cut(ADR 0019·0028·0029 §2 매트릭스 NXT/합산 5종 보강) + 실시간 boundary fanout 미러스톤 + 1 hotfix(§33 KIS 500 retry 3회).

본 문서는 **PR·ADR·기능 3축 추적성 매트릭스**다. raw 출처:
- `git log v3.2.2..v3.3.0 --oneline` (worktree 직접 실행)
- `gh pr list --state merged --search "merged:>=2026-05-11"` 결과 인용
- `CHANGELOG.md` 시계열 row
- `_workspace/arch/adr/README.md` 인덱스 (60 → 61)
- `_workspace/backlog.md` § 단위 (§33·§34·§35 처리 매트릭스)

---

## §1. v3.3.0 사이클 트랙별 PR 매핑 (핵심)

`docs/architecture/tracks-roadmap.md` §2 트랙 정의 + v3.3.0 release row(CHANGELOG.md) 풀 정합.

| 트랙 ID | ★ 등급 | 명칭 | 주요 PR | 부수 PR | 결과물 |
|---------|-------|------|---------|----------------|--------|
| **v3.2.2 baseline 디스패치 마감** | — | PR-48 sub-agent 4 PR + meta task archive | #310 (frontend) · #311 (backend-core) · #312 (market-data) · #313 (architect 4 파일) · #314 (meta html + task archive) | — | v3.2.2 baseline 7 파일 풀세트 본격 작성 완료. orchestrator html 변환(`scripts/baseline-render-html.sh`) + task archive 4건. baseline immutable LOCK. |
| **v3.2.3 minor doc cut (§35 + §33)** | — | architect 단독 ADR 매트릭스 본문 LOCK + market-data §33 KIS 500 retry | #316 (architect §35 doc cut — ADR 0019·0028·0029 §2 NXT/합산 5종 row 보강 + external-api-policy + candle-data-policy-v2 정합) + #315 (market-data §33 KIS 500 retry 3회) → #317 (release v3.2.3) | — | ADR 0019 §2-1 매트릭스 NXT 시각 행 + 합산 5종 row 추가 + §2-2 UI 매트릭스 5종 활성화 + §2-3 cold-start KOSPI=UN·KOSDAQ=J 분기 + halt/liquidation skip · §2-5 "합성 폐기" revoke 정정. ADR 0028 §2-1·§2-3 cold-start AggregateBackfillRunner 호출 명시. ADR 0029 §2-1 Redis 키 매트릭스 5m·15m·30m·90m·1h boundary 캐시 키 5건 add (v3.3.0 §34 작업 가이드). external-api-policy §2-5 KIS 단일 출처 매트릭스 + NXT 매트릭스 신설. candle-data-policy-v2 §1·§3 매트릭스 5m~1h 백엔드 precomputed + §6·§7 cold-start NXT 통합 + §8 신규 컴포넌트 6건 추가. 코드 변경 0건 (.md 5 파일). modify-shared 0 (market-data §33 KIS 500 retry sibling 영역 분리). KIS 500 retry 1회 → 3회 exponential (500ms→1s→2s) — `KisRestCandleAdapter` `fetchOneMinCandles` + `fetchDailyCandles` 양쪽 적용. backlog §33·§35 처리 완료. |
| **v3.3.0 §34 미러스톤 — 실시간 boundary fanout (ADR 0064)** | ★★ | KIS WS in-process boundary 검사 + 5종 별도 Redis + Kafka 5 토픽 + STOMP 5종 fanout | #318 (architect plan ADR 0064 Proposed) → #319 (architect ADR 0064 Accepted — 사용자 A-1·B-1·C-1 채택) → #320 (market-data 본격 — domain BoundaryDetector + consumer BoundaryAggregator·RedisCandleCache·TickToMinuteAggregator 확장·KafkaTopicConfig 5 토픽 + api CandleClosedStompListener) → #321 (frontend STOMP 5종 dynamic subscribe + LWC series.update) | — | **ADR 0064 신규** (사용자 결정 의제 3건 — A Redis 키 매트릭스 / B boundary 마감 트리거 / C STOMP fanout 토픽 — 모두 architect 권장안 A-1·B-1·C-1 채택). `domain/.../candle/BoundaryDetector.kt` Pure Kotlin object (Spring·JPA 의존 0): `isBoundaryClosed(ts, target)` 5m·15m·30m·90m·1h NXT cover 08:00~20:00 boundary 마감 분 검사 + `boundaryStartTs(ts, target)` boundary 시작 ts + `parseHhmmssToInstant(hhmmss, midnight)` KIS STCK_CNTG_HOUR 파싱 — **44 unit test GREEN** (5m 마감/비마감·15m·30m·90m 8 anchor 전부·1h·NXT 범위 밖·parseHhmmssToInstant·boundaryStartTs cover). `consumer/.../cache/RedisCandleCache.kt` 신규: A-1 5종 별도 hash 키 `candle:current:{code}:5m~1h` · TTL 25h · `putBoundaryCandle/getBoundaryCandle/evictBoundaryCandle`. `consumer/.../aggregation/BoundaryAggregator.kt` 신규 @Component: B-1 — 1m closing 마감 시 `onTickClosed(code, tickTs)` 5 interval 순차 처리 → DB 1m fetch(boundary 내 N건) → CandleAggregator.aggregate → Redis put → insertIgnore → Kafka publish. 멱등 가드 in-process `lastProcessedBoundaryMin` ConcurrentHashMap + DB ON CONFLICT DO NOTHING 이중 보장. `consumer/.../config/KafkaTopicConfig.kt` 5 토픽 신규 빈: `market.candle.5m/15m/30m/90m/1h.closed.v1` retention 1d. `TickToMinuteAggregator` 확장: 1m closing 완료 직후 `boundaryAggregator.onTickClosed(code, closingTs)` 호출 (B-1 trigger). `api/.../messaging/CandleClosedStompListener.kt` 신규 @Component: 5 KafkaListener → STOMP `/topic/market.candle.{interval}.{code}` fanout. KIS-first raw 출처 inline 주석 의무 (헌법 §3): H0STCNT0 ccnl_krx cols[1]=STCK_CNTG_HOUR raw URL 인용. frontend (PR #321): `useSymbolCandles.ts` STOMP 5종 토픽 dynamic subscribe/unsubscribe (IntervalToggle 선택값 정합) + LWC v4 `series.update()` boundary 메시지 수신 시 마지막 bar 실시간 갱신 + dogfooding evidence E2E 검증. **end-to-end latency <50ms 측정** (사용자 dogfooding evidence). KIS REST 추가 호출 0건 (Rate Limit 영향 0). Redis 메모리 +11 KB · STOMP +0.06 msg/s · DB +2,596 rows/일 (cold-start와 ts 일치 멱등). |
| **v3.3.0 cut + baseline init** | — | release + baseline 폴더 신설 | #322 (release v3.3.0 — orchestrator commit `ccbf11b`) + #323 (chore(baseline): v3.3.0 init — 폴더 + CHANGELOG 5 slot prepend PR-49) | — | v3.2.2 → v3.2.3 → v3.3.0 minor 진행. orchestrator release commit + CHANGELOG row + master §10 ADR 인덱스 0064 갱신. baseline init: `_workspace/baseline/v3.3.0/` 폴더 신설 + README skeleton + 7 파일 인덱스 + CHANGELOG sub-agent 슬롯 5 anchor (architect/backend-core/market-data/frontend + orchestrator). |

---

## §2. PR 시계열 (#310 ~ #323, 14 PR)

`gh pr list --state merged --search "merged:>=2026-05-11"` raw 결과 + `git log v3.2.2..main --oneline` 정합.

> **PR Prefix 분류**: `docs(arch/baseline/architect/adr)` = architect 단독 · `feat(...)` = sub-agent 트랙 본격 구현 · `fix(...)` = hotfix · `chore(meta/adr)` = orchestrator·meta 정리 · `release:` = 릴리즈 컷 · `chore(baseline)` = baseline init.

### 2-1. v3.2.2 baseline 디스패치 마감 (2026-05-11)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #310 | 2026-05-11 | `agent/frontend/pr-48-baseline-fe` | frontend baseline | docs(baseline): PR-48 frontend — v3.2.2 05-ui-inventory |
| #311 | 2026-05-11 | `agent/backend-core/pr-48-baseline-be` | backend-core baseline | chore(baseline): v3.2.2 baseline 02-data-flow + 04-feature-spec |
| #312 | 2026-05-11 | `agent/market-data/pr-48-baseline-md` | market-data baseline | docs(baseline): v3.2.2 03-use-sequences — KIS-first raw 출처 11건 LOCK (market-data) |
| #313 | 2026-05-11 | `agent/architect/pr-48-baseline-arch` | architect baseline | docs(baseline): PR-48 architect — v3.2.2 baseline 4 파일 (README + 01 + 06 + 07) |
| #314 | 2026-05-11 | `chore/baseline-v3.2.2-html` | meta | chore(baseline): v3.2.2 html 변환 + task archive 4건 |

### 2-2. v3.2.3 minor doc cut (2026-05-12, §35 + §33)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #315 | 2026-05-12 | `agent/market-data/kis-500-retry-3x` | market-data hotfix · §33 | fix(consumer): KIS HTTP 500 retry 3회 exponential backoff 보강 (backlog §33) — `KisRestCandleAdapter` HTTP 500 재시도 1회→3회 exponential (500ms→1s→2s) · `RETRY_BACKOFF_MS` + `RETRY_MAX_ATTEMPTS=3` 상수 발행 · 503/502/기타 즉시 실패 유지 · 5종목 dogfooding `KIS_COLD_START_PARTIAL_FAIL` 패턴 해소 |
| #316 | 2026-05-12 | `agent/architect/adr-matrix-nxt-aggregate` | architect doc cut · §35 | docs(arch): backlog §35 — ADR 0019·0028·0029 §2 매트릭스 NXT/합산 5종 보강 (v3.2.3 doc cut) — ADR 0019 §2-1·§2-2·§2-3·§2-5 (NXT 행·합산 5종 row·UI 활성화·cold-start 흐름·"합성 폐기" revoke) · ADR 0028 §2-1·§2-3 cold-start AggregateBackfillRunner 호출 · ADR 0029 §2-1 Redis 키 매트릭스 5m~1h 5건 add (v3.3.0 §34 작업 가이드) · external-api-policy §2-5 + NXT 매트릭스 신설 · candle-data-policy-v2 §1·§3·§6·§7·§8 신규 컴포넌트 6건 — 코드 변경 0건 (.md 5 파일) |
| #317 | 2026-05-12 | `chore/v3.2.3-cut` | release | release: v3.2.3 — ADR 매트릭스 NXT/합산 보강 + KIS 500 retry 3회 (orchestrator commit `f21a074`) |

### 2-3. v3.3.0 §34 미러스톤 — 실시간 boundary fanout (2026-05-12, ADR 0064)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #318 | 2026-05-12 | `agent/architect/0064-realtime-aggregation-fanout` | architect plan · ADR 0064 Proposed | docs(architect): **ADR 0064 plan — Redis boundary fanout + STOMP 실시간 합산 갱신 (v3.3.0 §34, Proposed)** — 컨텍스트: ADR 0062 합산 가공 채택 후 cold-start + DailyCloseFinalizer 20:00 KST에서만 5m~1h 갱신 → 장중 boundary 마감(09:05·09:10 등) 실시간 갱신 결함 → T-SIG·T-DSL·MCP 외부 client 모두 stale data. 결정 의제 3건: A Redis 키 매트릭스 (5종 별도 hash vs 통합 hash) — architect 권장 **A-1 5종 별도** / B boundary 마감 트리거 (KIS WS H0STCNT0 in-process vs scheduled vs 하이브리드) — architect 권장 **B-1 in-process** / C STOMP fanout 토픽 (5종 별도 vs 단일 + payload field) — architect 권장 **C-1 5종 별도**. fitness gap 검증: 자동매매 본체 정합(T-SIG 5m·15m 본질 입력)·v1/v2 잔재 0·KIS-first raw 출처 인용·회귀 risk 0. sub-agent 디스패치 매트릭스(채택 후) + 운영 영향: Redis +11KB·STOMP +0.06 msg/s·KIS REST 추가 호출 0·DB +2,596 rows/일·latency <50ms. Rollback 3 시나리오 + 절차. backlog §34 갱신. ADR README 0064 row 추가 + master-architecture §10 0064 row. 코드 변경 0건(.md add-only) |
| #319 | 2026-05-12 | `chore/adr-0064-accepted-v2` | architect ADR 0064 Accepted | chore(adr): **ADR 0064 Proposed → Accepted (사용자 A-1·B-1·C-1 채택)** — 사용자 결정 의제 3건 모두 architect 권장안 채택 → status `Proposed` → `Accepted (2026-05-12 — 사용자 결정 A-1·B-1·C-1 채택)` 헤더 갱신 |
| #320 | 2026-05-12 | `agent/market-data/0064-boundary-fanout` | market-data 본격 · ADR 0064 §34 | feat(market-data): **ADR 0064 §34 boundary fanout + STOMP 실시간 합산 갱신 구현 — BoundaryDetector domain + BoundaryAggregator consumer + RedisCandleCache 5 키 + KisWebSocketBridge 확장 + CandleClosedStompListener api + Kafka 5 토픽** — `domain/.../candle/BoundaryDetector.kt` Pure Kotlin object 신규 (Spring 0): `isBoundaryClosed(ts, target)` 5m·15m·30m·90m·1h NXT cover 08:00~20:00 boundary 마감 분 검사 + `boundaryStartTs(ts, target)` boundary 시작 ts + `parseHhmmssToInstant(hhmmss, midnight)` KIS STCK_CNTG_HOUR 파싱. `domain/BoundaryDetectorTest.kt` **44 tests GREEN** (5m 마감/비마감·15m·30m·90m 8 anchor 전부·1h·NXT 범위 밖·parseHhmmssToInstant·boundaryStartTs). `consumer/.../cache/RedisCandleCache.kt` 신규: ADR 0064 A-1 5종 별도 Redis hash 키 `candle:current:{code}:5m~1h` · TTL 25h. `consumer/.../aggregation/BoundaryAggregator.kt` 신규 @Component: ADR 0064 B-1 — 1m closing 마감 시 `onTickClosed(code, tickTs)` 5 interval 순차 처리 → DB 1m fetch(boundary 내 N건) → CandleAggregator.aggregate → Redis put → insertIgnore → Kafka publish. 멱등 가드 in-process `lastProcessedBoundaryMin` ConcurrentHashMap + DB ON CONFLICT DO NOTHING 이중 보장. `consumer/.../config/KafkaTopicConfig.kt` 5 토픽 신규 빈: `market.candle.5m/15m/30m/90m/1h.closed.v1` retention 1d. `TickToMinuteAggregator` 확장: 1m closing 완료 직후 `boundaryAggregator.onTickClosed(code, closingTs)` 호출 (B-1 trigger). `api/.../messaging/CandleClosedStompListener.kt` 신규 @Component: 5 KafkaListener → STOMP `/topic/market.candle.{interval}.{code}` fanout. KIS-first raw 출처 inline 주석 의무 (헌법 §3): H0STCNT0 ccnl_krx cols[1]=STCK_CNTG_HOUR raw URL + `_workspace/baseline/v3.2.2/03-use-sequences.md §시나리오 3 R5` 인용. 헌법 §6 JPA default fun 함정 회피 확인 — insertIgnore @Query (abstract body 없음). `:domain:check` + `:consumer:check` + `:api:check` GREEN |
| #321 | 2026-05-12 | `agent/frontend/0064-stomp-subscribe` | frontend 본격 · ADR 0064 §2-C | feat(frontend): **ADR 0064 §2-C STOMP 5종 boundary subscribe + LWC series.update 실시간 갱신** — `useSymbolCandles.ts` STOMP 5종 토픽 dynamic subscribe/unsubscribe (IntervalToggle 선택값 정합) + LWC v4 `series.update()` boundary 메시지 수신 시 마지막 bar 실시간 갱신. dogfooding evidence E2E 검증(09:05 boundary 도달 → KIS WS tick → BoundaryAggregator → STOMP fanout → LWC update latency <50ms 측정) |

### 2-4. v3.3.0 cut + baseline init (2026-05-12)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #322 | 2026-05-12 | `chore/v3.3.0-cut` | release | **release: v3.3.0 — 실시간 boundary fanout 미러스톤 (Redis + STOMP + KIS WS)** — orchestrator commit `ccbf11b` (CHANGELOG row + master §10 ADR 인덱스 갱신 + 릴리즈 노트). v3.2.3 → v3.3.0 minor 미러스톤. 4 PR 묶음(#318·#319·#320·#321) ADR 0064 채택(A-1·B-1·C-1) + architect plan(§34) + Accepted + market-data 구현 + frontend STOMP 5종 dynamic subscribe + LWC series.update 실시간 갱신. 5m·15m·30m·90m·1h boundary 마감 시점 자동 차트 갱신 — latency <50ms 측정 |
| #323 | 2026-05-12 | `chore/baseline-v3.3.0-init` | baseline init | **chore(baseline): v3.3.0 init — 폴더 + CHANGELOG 5 slot prepend (PR-49)** — orchestrator init (README skeleton + 5 sub-agent slot anchor 사전 발행 — architect·backend-core·market-data·frontend + orchestrator) |

---

## §3. ADR 시계열 매트릭스 (60 → 61, 신규 1건)

`_workspace/arch/adr/README.md` 인덱스 정합.

| ADR | 상태 | 발행 PR | 결정자 | 종류 | 핵심 결정 |
|-----|------|---------|--------|------|----------|
| **0064** | Accepted (2026-05-12 — 사용자 결정 A-1·B-1·C-1 채택) | #318 plan / #319 Accepted / #320 본격 / #321 frontend | 사용자 + architect (의제 3건 검토 위임) | 실시간 데이터 흐름 baseline | **Redis boundary fanout + STOMP 실시간 합산 갱신 (5m·15m·30m·90m·1h)** — A-1 5종 별도 Redis hash 키 / B-1 KIS WS H0STCNT0 in-process boundary 검사 (1m closing trigger 경유) / C-1 5종 별도 STOMP 토픽 + Kafka 5 토픽 + 멱등 가드 이중(in-process map + DB ON CONFLICT DO NOTHING) + end-to-end latency <50ms 측정 + Redis 메모리 +11 KB + STOMP +0.06 msg/s + KIS REST 추가 호출 0건 |

**v3.3.0 신규 ADR 1건**: ADR 0064. ADR 번호 시퀀스 보존 룰(`_workspace/arch/adr/README.md`)에 따라 0058·0059·0060·0061 번호는 v3.2.2 사이클부터 충돌 회피용 슬롯으로 비어있음 (v3.3.0에서도 보존).

**ADR 0019 부분 supersede 갱신 의무 (지속)**:
- v3.2.3 doc cut(PR #316)에서 §2-1 매트릭스 NXT 시각 행 + 합산 5종 row 보강 + §2-2·§2-3·§2-5 본문 정합 갱신 완료
- ADR 0064 채택(boundary fanout 트랙 진입)으로 §2-1 매트릭스 5m~1h "boundary closing trigger (ADR 0064)" 추가 후속 (별도 사이클)
- v3.4.x minor 진입 시 architect 단독 PR로 §2 본문 재통합 (현재는 v3.2.3 doc cut으로 매트릭스 row만 보강된 상태)

**ADR 0029 supersede 후속 (지속)**:
- v3.2.3 doc cut(PR #316)에서 §2-1 Redis 키 매트릭스 5m·15m·30m·90m·1h boundary 캐시 키 5건 add (v3.3.0 §34 작업 가이드 표기)
- ADR 0064 채택으로 §2-1·§2-3·§2-5 매트릭스 본문 LOCK 후속 (현재 "v3.3.0 §34 작업 가이드" 표기 → "Accepted, ADR 0064 본문 권위") — 별도 사이클

**ADR 0062 supersede 후속**:
- §4-3 트리거 흐름 본문 — ADR 0064 채택으로 boundary 마감 흐름 LOCK (별도 사이클)

---

## §4. hotfix 패턴 — v3.3.0 사이클 1건 (§33 KIS 500 retry 3회 — dogfooding root cause 정정)

v3.3.0 사이클의 hotfix는 1건뿐. 운영 헌법 §7(sub-agent fail 분기) + 사용자 메모리 `feedback_conflict_resolution_autonomy.md`(자율 처리) 정합.

| hotfix # | PR | 결함 root cause | fix | spec/plan 발행 여부 |
|---|----|----------------|-----|--------------------|
| 1 | #315 | dogfooding `KIS_COLD_START_PARTIAL_FAIL` (success=3~4 / fail=1~2) 5종목 패턴 — `KisRestCandleAdapter` 1회 retry(500ms backoff) 불충분 → 5종목(004090·064260·001040·035760·005930) HTTP 500 응답 누적 PARTIAL_FAIL | consumer hotfix — `RETRY_BACKOFF_MS` + `RETRY_MAX_ATTEMPTS=3` companion object 상수 발행 · 1회 retry → 3회 exponential backoff (500ms→1s→2s) · `fetchOneMinCandles` (FHKST03010230) + `fetchDailyCandles` (FHKST03010100) 양쪽 적용 · 503/502/기타 즉시 실패 유지 (서비스 점검 가능성, 재시도 무의미) · Rate Limit (ADR 0037): retry는 500 발생 시에만 — 20 req/s 한도 영향 미미 | spec 0건 (사용자 메모리 `feedback_spec_plan_scope.md` 정합 — hotfix는 spec 면제) |

**hotfix 사이클 정합**: dogfooding 채팅 evidence 기반 → root cause 직접 진단 → 1 PR fix → main 머지. spec/plan 발행 0건. 검증 의무는 `feedback_verification_strict.md` 정합 — `:consumer:check` GREEN + ktlintCheck GREEN.

> **v3.2.2 → v3.3.0 사이클 hotfix는 1건으로 매우 안정** — 데이터 정책 묶음 결정(v3.2.2 ADR 0057·0062·0063)이 안정화되었고 + boundary fanout 트랙은 architect plan PR(ADR 0064) → 사용자 결정 → 본격 구현 PR(#320·#321) 표준 cycle 진행 + 결함 0건 머지.

---

## §5. backlog 처리 매트릭스 (v3.3.0 사이클)

`_workspace/backlog.md` § 단위 추적.

### 5-1. 처리 완료 §

| § | 제목 | 처리 PR | 후속 |
|---|------|---------|------|
| **§33** | KIS API 500 retry 강화 (1회 → 3회 exponential backoff) — v3.3.0 미러스톤 | hotfix PR #315 (market-data) | 처리 완료. 다음 minor cleanup에서 archive |
| **§34** | Redis boundary fanout + STOMP 실시간 합산 갱신 (ADR 0029) — v3.3.0 미러스톤 | architect plan PR #318 → ADR 0064 Accepted PR #319 → market-data 본격 PR #320 → frontend PR #321 | 처리 완료. **v3.3.0 §34 미러스톤 본 ADR 0064 권위.** 다음 minor cleanup에서 archive |
| **§35** | architect ADR 0019·0028·0029 §2 매트릭스 NXT/합산 행 보강 — v3.2.3 minor | architect doc cut PR #316 (single PR — 5 .md 파일) | 처리 완료. v3.2.3 minor doc cut. 다음 minor cleanup에서 archive |

### 5-2. 신규 등록 §

| § | 제목 | 등록 PR | 트리거 |
|---|------|--------|--------|
| **§36** | (TBD — v3.3.0 baseline 작성 중 사용자 의제 발생 시 등록 예정) | — | — |

> §33·§34·§35는 v3.3.0 사이클에서 모두 처리 완료. 다음 minor 사이클에서 `_workspace/backlog.md` archive 매트릭스(§29·§30·§31·§32·§33·§34·§35)로 일괄 처리 권장.

### 5-3. v3.3.0 사이클 신규 보강 매트릭스 (별도 사이클 후속 LOCK)

| 영향 ADR/문서 | 갱신 내용 | 갱신 시점 |
|---|---|---|
| ADR 0029 §2-1·§2-3·§2-5 | 본문 LOCK (현재 "v3.3.0 §34 작업 가이드" 표기 → "Accepted, ADR 0064 본문 권위") | architect 단독 PR — 별도 사이클 |
| ADR 0048 §3-2 | 흐름 본문 — KIS WS tick 수신 1m closing trigger boundary aggregator 호출 단계 추가 | architect 단독 PR — 별도 사이클 |
| ADR 0062 §4-3 | 트리거 흐름 본문 — boundary 마감 흐름 LOCK (ADR 0064 권위) | architect 단독 PR — 별도 사이클 |
| ADR 0019 §2-1 | 5m~1h INSERT 트리거에 "boundary closing (ADR 0064)" 추가 | architect 단독 PR — 별도 사이클 |
| `docs/architecture/candle-data-policy-v2.md` §3·§6·§7 | boundary 마감 trigger 단락 추가 | architect 단독 PR — 별도 사이클 |

> 본 v3.3.0 baseline 컷 시점에는 ADR 0064 채택 직후 사이클이라 위 매트릭스 본문 갱신 미반영 — **v3.4.x minor 진입 시 architect 단독 PR로 일괄 정정** (modify-shared 충돌 회피).

---

## §6. 결론 — v3.3.0 사이클 핵심 통계

| 지표 | v3.2.2 (2026-05-11) | v3.3.0 (2026-05-12) | 증감 |
|------|---------------------|---------------------|------|
| PR # 누적 | #309 | #323 | +14 |
| ADR 수 | 60 (실제 신규 4건 + 슬롯 4건 0058~0061) | 61 (0064 Accepted 추가) | +1 |
| Flyway 버전 | V11 | V11 | 0 (BoundaryDetector·BoundaryAggregator는 기존 candle 테이블 활용) |
| JVM 모듈 | 5 (domain·api·batch·consumer·pattern-engine) | 5 (보존) | 0 |
| 외부 통합 (활성) | KIS + DART + Anthropic (3) — Yahoo @Deprecated | KIS + DART + Anthropic (3) — Yahoo @Deprecated 보존 (v3.4.x 일괄 삭제 예정) | 0 |
| W3 ★ 트랙 코드 진입 | 1 (T-BT — `domain/backtest/*` 5 sealed class) | 1 (T-BT — v3.3.0에서 본격 진행 0 · ADR 0064 baseline 활용 T-SIG 후속 사이클) | 0 |
| **실시간 boundary fanout 흐름** | **미구현 (장중 5m~1h boundary 마감 갱신 0건 — dogfooding 결함)** | **구현 완료 (end-to-end latency <50ms — ADR 0064 채택)** | **+ADR 0064** |
| Kafka 토픽 (활성) | 7 + 도메인 이벤트 | 7 + **5종 boundary fanout 추가** = 12 | +5 |
| Redis 키 (활성) | quote·session·instrument·position·kis token + indicator/pattern cache + candle:current:{code}:1m | + **candle:current:{code}:{5m·15m·30m·90m·1h} 5종 별도 hash 키 add** | +5 |
| STOMP 토픽 (활성) | tick · candle/1m · orderbook | + **/topic/market.candle.{5m·15m·30m·90m·1h}.{code} 5종 add** | +5 |
| `BoundaryDetector` unit test | 0 | **44 GREEN** | +44 |
| KIS REST retry policy | 1회 retry (500ms backoff) | **3회 exponential backoff (500ms→1s→2s)** — v3.2.3 §33 | +200% |
| end-to-end latency (5m boundary → STOMP fanout) | — (장중 fanout 0건) | **<50ms 측정** | -∞ → <50ms |
| dogfooding hotfix 건수 | 6 묶음 (v3.2.0 → v3.2.2 사이클) | 1 (v3.2.2 → v3.3.0 사이클, §33 KIS 500 retry) | -5 |
| 운영 헌법 § 수 | 7 | 7 (보존) | 0 |
| Living Policy 문서 | 6 | 6 (보존) | 0 |

**v3.3.0 사이클 핵심 메시지**: **장중 boundary 마감 시점 실시간 합산 fanout (ADR 0064 채택)** — 사용자 dogfooding 결함(장중 5m bar missing → 20:00 flush까지 stale)이 root cause 해결되어 자동매매 본체 정합의 결정적 결함이 해소. T-SIG (Signal Engine) 5m·15m 실시간 시그널 본질 입력 fitness 충족 + T-DSL 5m·30m 인터벌 조건식 fresh + MCP 외부 client 장중 5m~1h 정합 baseline 확립. + v3.2.3 doc cut(ADR 0019·0028·0029 §2 매트릭스 NXT/합산 5종 row 보강 + KIS 500 retry 3회)으로 cold-start PARTIAL_FAIL 패턴 해소 + 운영 자료 정합 정착.

**다음 minor 사이클(v3.4.x 또는 v3.5.0) 의제**:
1. ADR 0019·0028·0029·0048·0062 본문 갱신 (boundary fanout 후속 정합 — ADR 0064 권위 본문 LOCK) — architect 단독 PR
2. Yahoo `YahooCandleFetcherAdapter`·Yahoo 관련 yaml 환경 완전 삭제 — market-data 단일 PR (v3.4.x minor 일괄)
3. W3 T-BT 후속 사이클 — API REST `BacktestController` + 영속 Flyway V12 `backtest_run` + frontend `/backtest` 라우트
4. **W3 T-SIG (Signal Engine) 본격 진입 — ADR 0064 boundary fanout baseline 활용** — 5m·15m 실시간 시그널 evaluation + signal_event 영속 + STOMP fanout
5. backlog §29·§30·§31·§32·§33·§34·§35 archive 매트릭스 정리
