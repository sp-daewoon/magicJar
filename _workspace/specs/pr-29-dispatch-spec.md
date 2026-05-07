# PR-29 Dispatch Spec — 4 sub-agent PR 분배 (W1 마감)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-08
- 정합 SoT: `pr-29-w1-close-design.md` v1 + `pr-29-conflict-matrix.md` v1 + ADR 0013·0014·0015·0016·0018·0050
- 헌법 정합: `_workspace/arch/agent-charter.md` §1·§3·§4·§5·§6

---

## §1. 결론 (한 줄)

> **PR-29a (backend-core, T-MS1) + PR-29b (market-data, T-CA1) + PR-29c (frontend, T-S1 잔재) 3 PR 적극 병렬 디스패치 권장. 영역이 batch+api+consumer(MS) vs batch+api(CA) vs frontend(S1)로 격리. modify-shared 충돌 점은 CHANGELOG (anchor 5건 발행) + Flyway V 번호 분배(V6 PR-29a / V7+V8 PR-29b)로 0%. PR-29d (R3 일괄 테스트)는 a·b·c 머지 후 직렬.**

---

## §2. PR-29a — backend-core: T-MS1 + REJECTED Audit

### §2-1. 작업 범위 LOCK

| 산출물 | 모듈 | 파일 |
|---|---|---|
| MarketSessionResolver port | domain | `domain/.../market/session/MarketSessionResolver.kt` (신규) |
| MarketSession sealed class | domain | `domain/.../market/session/MarketSession.kt` (신규) |
| BusinessDayCalendar port | domain | `domain/.../market/session/BusinessDayCalendar.kt` (신규) |
| MarketCalendarEntry data class | domain | `domain/.../market/session/MarketCalendarEntry.kt` (신규) |
| MarketCalendarRepository port | domain | `domain/.../market/session/port/MarketCalendarRepository.kt` (신규) |
| RejectReason sealed class | domain | `domain/.../market/order/RejectReason.kt` (신규) |
| KrxMarketSessionResolver | api or consumer | `api/.../market/session/KrxMarketSessionResolver.kt` (신규 — 위치 LOCK = api 권장, Redis + DB 통합 진입점) |
| MarketCalendarJpaEntity | api | `api/.../persistence/MarketCalendarJpaEntity.kt` (신규) |
| MarketCalendarJpaRepository | api | `api/.../persistence/MarketCalendarJpaRepository.kt` (신규) |
| MarketCalendarRepositoryAdapter | api | `api/.../persistence/MarketCalendarRepositoryAdapter.kt` (신규) |
| **Flyway V6** | api | `api/src/main/resources/db/migration/V6__market_calendar_and_reject_audit.sql` (신규) |
| KisChkHolidayAdapter | batch | `batch/.../infrastructure/kis/KisChkHolidayAdapter.kt` (신규) |
| MarketCalendarSyncJob | batch | `batch/.../job/market_calendar/MarketCalendarSyncJob.kt` (신규) |
| MarketCalendarSeedRunner | batch | `batch/.../bootstrap/MarketCalendarSeedRunner.kt` (신규) |
| `market-calendar-seed.json` | batch | `batch/src/main/resources/market-calendar-seed.json` (신규 — 2026~2027 KRX 30+ 휴일) |
| DailyCloseFinalizer | consumer | `consumer/.../application/market/DailyCloseFinalizer.kt` (신규 @Scheduled 16:00 KST) |
| TickFallbackEmitter | consumer | `consumer/.../application/market/TickFallbackEmitter.kt` (신규 — 10초 주기) |
| SimpleMarketSessionResolver 폐기 | consumer | 기존 PR-9b `SimpleMarketSessionResolver` → 본격 KrxMarketSessionResolver 사용으로 교체 (consumer 모듈에 KrxMarketSessionResolver 사본 또는 import) |
| order_reject_audit (V6 동일 파일) | api | (V6 SQL 내) |
| OrderRejectAuditJpaEntity (선택) | api | post-W3 — 본 PR-29a는 테이블만 신설 |
| application.yml | api or consumer | `MAGICJAR_MARKET_SESSION_ENFORCE`·`MAGICJAR_MARKET_CALENDAR_SYNC_AT_BOOT` 환경변수 추가 (단일 sub-agent 단독 — backend-core LOCK) |
| `.env.example` | root | 위 2 환경변수 add |
| smoke test | api/batch | `KrxMarketSessionResolverTest`·`KisChkHolidayAdapterTest` 1~2건 (R3 미만 — 룰 4 정합 spec lock 검증) |

### §2-2. KIS-first raw 출처 인용 의무 (헌법 §3)

PR-29a sub-agent는 PR 본문에 다음 인용 의무:

```
출처: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/chk_holiday/chk_holiday.py
- API_URL: /uapi/domestic-stock/v1/quotations/chk-holiday
- tr_id: CTCA0903R
- params: BASS_DT (YYYYMMDD), CTX_AREA_FK, CTX_AREA_NK
- 응답: bass_dt, wday_dvsn_cd, bzdy_yn, tr_day_yn, opnd_yn, sttl_day_yn
- 호출 한도: 1일 1회 권장 (KIS 명시)
```

### §2-3. 비범위

- DSL 평가 adjusted 의무 적용 (W2 사이클)
- session=Closed 시 KIS WS subscribe 보류 (PR-9b 정합 — 본격 추가 0)
- order_reject_audit retention 90일 cleanup job (post-W3)

---

## §3. PR-29b — market-data: T-CA1 (Corp Action + Adjusted Price)

### §3-1. 작업 범위 LOCK

| 산출물 | 모듈 | 파일 |
|---|---|---|
| CorpAction data class | domain | `domain/.../instrument/corp_action/CorpAction.kt` (신규) |
| CorpActionType enum | domain | `domain/.../instrument/corp_action/CorpActionType.kt` (신규) |
| CorpActionRepository port | domain | `domain/.../instrument/corp_action/port/CorpActionRepository.kt` (신규) |
| DartDisclosureFetcher port | domain | `domain/.../instrument/corp_action/port/DartDisclosureFetcher.kt` (신규) |
| KisKsdInfoFetcher port | domain | `domain/.../instrument/corp_action/port/KisKsdInfoFetcher.kt` (신규) |
| **Flyway V7** | batch | `batch/src/main/resources/db/migration/V7__corp_action.sql` (신규) |
| **Flyway V8** | batch | `batch/src/main/resources/db/migration/V8__candle_adjusted.sql` (신규) |
| CorpActionJpaEntity | batch | `batch/.../persistence/CorpActionJpaEntity.kt` (신규) |
| CorpActionJpaRepository | batch | `batch/.../persistence/CorpActionJpaRepository.kt` (신규) |
| CorpActionRepositoryAdapter | batch | `batch/.../persistence/CorpActionRepositoryAdapter.kt` (신규) |
| KisKsdInfoAdapter | batch | `batch/.../infrastructure/kis/KisKsdInfoAdapter.kt` (신규 — 6 endpoint 단일 어댑터) |
| DartDisclosureAdapter | api | `api/.../infrastructure/dart/DartDisclosureAdapter.kt` (신규) |
| DartCorpCodeRepository | api | `api/.../infrastructure/dart/DartCorpCodeRepository.kt` (신규 — CORPCODE.xml in-memory cache) |
| CorpActionSyncJob | batch | `batch/.../job/corp_action/CorpActionSyncJob.kt` (신규 — cron 18:30) |
| CandleAdjustmentJob | batch | `batch/.../job/corp_action/CandleAdjustmentJob.kt` (신규 — Kafka listener `corp_action.applied.v1`) |
| Kafka topic config | api or batch | `corp_action.applied.v1` 1d retention auto-create (KafkaTopicConfig 보강 — backend-core 본인 영역 1차 단독 분배 권장 → PR-29b 단독 add) |
| application.yml | batch | `MAGICJAR_CORP_ACTION_SYNC_*` 환경변수 (단일 sub-agent 단독 — market-data LOCK) |
| `.env.example` | root | DART/KIS ksdinfo 환경변수 (이미 OPENDART_API_KEY 존재 — 본 PR 변경 0 권장) |
| smoke test | batch | `KisKsdInfoAdapterTest`·`CandleAdjustmentJobTest` 1~2건 |

### §3-2. KIS-first raw 출처 인용 의무 (헌법 §3)

PR-29b sub-agent는 PR 본문에 다음 6 endpoint + DART endpoint raw 인용 의무:

```
KIS ksdinfo 6 endpoint 출처:
1. https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_dividend/ksdinfo_dividend.py
   - API_URL: /uapi/domestic-stock/v1/ksdinfo/dividend, tr_id: HHKDB669102C0
2. https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ksdinfo_rev_split/ksdinfo_rev_split.py
   - API_URL: /uapi/domestic-stock/v1/ksdinfo/rev-split, tr_id: HHKDB669105C0
3. ksdinfo_merger_split: HHKDB669104C0 / merger-split
4. ksdinfo_bonus_issue: HHKDB669101C0 / bonus-issue
5. ksdinfo_paidin_capin: HHKDB669100C0 / paidin-capin
6. ksdinfo_cap_dcrs: HHKDB669106C0 / cap-dcrs
공통 params: cts (연속조회), gb1 (시장구분), f_dt/t_dt (조회기간), sht_cd (종목코드)

DART OPENDART 출처:
- https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001
- endpoint: https://opendart.fss.or.kr/api/list.json
- params: crtfc_key (필수), corp_code, bgn_de, end_de, pblntf_ty (B=주요사항·D=지분), pblntf_detail_ty
- 응답: corp_name, corp_code, stock_code, report_nm, rcept_no, rcept_dt
- 한도: 1일 10,000건 (external-api-policy.md §4-1)

별도 raw 검증 보고서 작성 권고 (PR-21처럼) — `_workspace/specs/pr-29b-corp-action-raw-verification.md`
```

### §3-3. 비범위

- DART 본문 fetch (rcept_no → document.xml)
- adjustment_factor 다단계 누적 풀 backfill (1 종목당 1 corp_action 처리만)
- DSL adjusted 의무 적용 (W2)
- corp_action retention cleanup (post-W3)

---

## §4. PR-29c — frontend: T-S1 잔재 검증 + dogfooding §6.5 회귀

### §4-1. 작업 범위 LOCK (코드 신규 0 권장 — 동작 검증 + minor 정정만)

| 항목 | 작업 |
|---|---|
| LWC v4 차트 interval 토글 (5/15/30/60/D/W/M) | PR-10a SymbolChart 동작 검증 |
| 무한 스크롤 (before cursor) | useSymbolCandles InfiniteQuery 동작 검증 |
| 호가창 STOMP 구독 | mount POST subscribe + unmount sendBeacon (ADR 0048 §5) |
| dogfooding §6.5 한글 검색 회귀 | InstrumentSearchInput 자동완성 7b6b904 hotfix 후 검증 |
| delisted 라벨 검증 | WatchlistRow + SymbolDetailPage active=false 흐름 |
| 일부 종목 노출 검증 (004090 한국석유) | scrt_grp_cls_code='ST' 필터 검증 |
| stale 배지 (T2/T3 fallback) 표시 검증 | T-MS1 본격 동작 후 (PR-29a 머지 후 의존) — 본 PR-29c에서 stub 표시 검증만, 실 fallback은 PR-29a 머지 후 dogfooding 시 검증 |

### §4-2. 결함 발견 시 처리

- 1줄~10줄 minor 정정만 본 PR-29c 흡수
- 신규 컴포넌트 add 또는 1 파일 50줄+ 변경은 backlog §21 후속 PR-30+ 이연
- dogfooding §6.5 5건 모두 처리 완료 마킹은 backlog 갱신 (PR-29 architect 본 PR에서 사전 갱신)

### §4-3. 비범위

- 모바일 반응형 (backlog §9 — post-MVP)
- LWC overlay layer 자체 (W5 T-LWC)
- 신규 페이지·컴포넌트 (룰 3 R1 금지)

---

## §5. PR-29d — backend-core (R3 일괄 테스트)

### §5-1. 작업 범위 LOCK

| 항목 | 출처 backlog § |
|---|---|
| `SymbolControllerTest` OnboardingGuardFilter mock 보강 | §11 |
| `KisInstrumentMasterAdapterTest` @Disabled 해제 + assertion 정정 | §18 |
| `InstrumentSyncJobTest` @Disabled 해제 + assertion 정정 | §18 |

### §5-2. 머지 순서

PR-29a + PR-29b + PR-29c 모두 머지 **후** 진입 (테스트가 R1·R2 산출물 검증).

### §5-3. 비범위

- 신규 R3 시나리오 (T-MS1·T-CA1 R3 시나리오는 W2 진입 직전 별도 PR 또는 dogfooding 사이클)

---

## §6. 영역 분배 LOCK ★ (add-add 0% 사전 검증)

### §6-1. 모듈별 책임 분배

| 모듈 | PR-29a (backend-core, T-MS1) | PR-29b (market-data, T-CA1) | PR-29c (frontend) | PR-29d (backend-core R3) |
|---|---|---|---|---|
| `domain/.../market/session/` | **신규 add (책임)** | (변경 0) | (변경 0) | (변경 0) |
| `domain/.../market/order/` (RejectReason) | **신규 add (책임)** | (변경 0) | (변경 0) | (변경 0) |
| `domain/.../instrument/corp_action/` | (변경 0) | **신규 add (책임)** | (변경 0) | (변경 0) |
| `api/.../market/session/` | **신규 add (책임)** | (변경 0) | (변경 0) | (변경 0) |
| `api/.../persistence/MarketCalendar*` | **신규 add (책임)** | (변경 0) | (변경 0) | (변경 0) |
| `api/.../infrastructure/dart/` | (변경 0) | **신규 add (책임)** | (변경 0) | (변경 0) |
| `api/src/main/resources/db/migration/V6__*` | **신규 add (책임)** | (변경 0) | (변경 0) | (변경 0) |
| `batch/src/main/resources/db/migration/V7__*` | (변경 0) | **신규 add (책임)** | (변경 0) | (변경 0) |
| `batch/src/main/resources/db/migration/V8__*` | (변경 0) | **신규 add (책임)** | (변경 0) | (변경 0) |
| `batch/.../infrastructure/kis/KisChkHolidayAdapter` | **신규 add (책임)** | (변경 0) | (변경 0) | (변경 0) |
| `batch/.../infrastructure/kis/KisKsdInfoAdapter` | (변경 0) | **신규 add (책임)** | (변경 0) | (변경 0) |
| `batch/.../job/market_calendar/` | **신규 add (책임)** | (변경 0) | (변경 0) | (변경 0) |
| `batch/.../job/corp_action/` | (변경 0) | **신규 add (책임)** | (변경 0) | (변경 0) |
| `batch/.../persistence/CorpAction*` | (변경 0) | **신규 add (책임)** | (변경 0) | (변경 0) |
| `consumer/.../application/market/` (DailyCloseFinalizer + TickFallbackEmitter) | **신규 add (책임)** | (변경 0) | (변경 0) | (변경 0) |
| `consumer/.../application/market/SimpleMarketSessionResolver.kt` (PR-9b) | **modify or delete (책임)** | (변경 0) | (변경 0) | (변경 0) |
| `frontend/**` | (변경 0) | (변경 0) | **modify (책임)** | (변경 0) |
| `api/src/test/.../SymbolControllerTest.kt` | (변경 0) | (변경 0) | (변경 0) | **modify (책임)** |
| `batch/src/test/.../KisInstrumentMasterAdapterTest.kt` | (변경 0) | (변경 0) | (변경 0) | **modify (책임)** |
| `batch/src/test/.../InstrumentSyncJobTest.kt` | (변경 0) | (변경 0) | (변경 0) | **modify (책임)** |

### §6-2. modify-shared 분배 (충돌 risk 사전 LOCK)

| 파일 | PR-29a | PR-29b | PR-29c | PR-29d | 분배 결정 |
|---|---|---|---|---|---|
| `CHANGELOG.md` | row add | row add | row add | row add | ADR 0050 anchor 5건 분리 (PR-29 architect 본 PR에서 사전 발행) |
| `_workspace/backlog.md` | (변경 0) | (변경 0) | (변경 0) | (변경 0) | **PR-29 architect 본 PR에서 §21 신설 + §6.5·§11·§18 처리 마킹** — sub-agent 변경 0 |
| `application.yml` (api) | env add | (변경 0) | (변경 0) | (변경 0) | **PR-29a 단독** |
| `application.yml` (batch) | (변경 0) | env add | (변경 0) | (변경 0) | **PR-29b 단독** |
| `application.yml` (consumer) | env add (DailyCloseFinalizer + TickFallbackEmitter cron) | (변경 0) | (변경 0) | (변경 0) | **PR-29a 단독** |
| `.env.example` | env add (MAGICJAR_MARKET_*) | (변경 0 권고 — OPENDART_API_KEY 이미 존재) | (변경 0) | (변경 0) | **PR-29a 단독** |
| `domain/.../instrument/Instrument.kt` (이미 PR-21에서 InstrumentType enum + 'DR' 처리됨) | (변경 0) | (변경 0) | (변경 0) | (변경 0) | **변경 0** |
| `domain/.../instrument/port/InstrumentRepository.kt` | (변경 0) | (변경 0 권고 — corp_action은 별도 port) | (변경 0) | (변경 0) | **변경 0** |
| `domain/.../market/MarketSessionPort.kt` (구) | **modify or delete** (PR-9b SimpleMarketSessionResolver port → MarketSessionResolver로 통합) | (변경 0) | (변경 0) | (변경 0) | **PR-29a 단독** |
| `consumer/.../KafkaTopicConfig.kt` | session 토픽 add | corp_action.applied.v1 add | (변경 0) | (변경 0) | **modify-shared risk** ★ — §6-3 처리 |

### §6-3. KafkaTopicConfig modify-shared 처리

**문제**: PR-29a (`market.session.v1` 추가 가능 — ADR 0013 §3) + PR-29b (`corp_action.applied.v1` 추가 — ADR 0018 §2-3) 양쪽이 KafkaTopicConfig modify.

**해결**:
- PR-29a는 `market.session.v1` 토픽 add를 본 사이클 비범위 처리 (session 변경 publish는 W3 진입 시 — TickFallbackEmitter가 STOMP fanout으로 충분)
- PR-29b 단독으로 `corp_action.applied.v1` 추가 → KafkaTopicConfig 단일 modify

**LOCK**: PR-29a는 KafkaTopicConfig 변경 0, PR-29b 단독 modify.

### §6-4. consumer 모듈 add-add risk 점검 (backlog §5 정합)

PR-29a가 consumer 모듈에 add (DailyCloseFinalizer + TickFallbackEmitter + KrxMarketSessionResolver 사본).
PR-29b는 consumer 모듈 변경 **0** — 본 PR-29b의 corp_action 흐름은 batch + api 단독, consumer 영향 없음.

→ **add-add 0% 검증 PASS**.

---

## §7. CHANGELOG 슬롯 채번룰 (헌법 §4 + ADR 0050)

### §7-1. anchor 5건 + row 5건 사전 발행 (본 PR-29 architect)

본 PR-29 머지 시 CHANGELOG.md head에 사전 prepend (이미 §7 architect row + 4 anchor + 4 TBD row):

```markdown
| 2026-05-08 | **docs(specs): PR-29 W1 마감 spec 4건 + backlog §21 신설** — ... (architect 본인 row) | docs/spec/backlog | PR-29 W1 마감 spec |
<!-- pr-29-slot-architect -->
| 2026-05-?? | **TBD — PR-29a backend-core: T-MS1 ...** | api/batch/consumer/domain | PR-29a T-MS1 |
<!-- pr-29-slot-backend-core -->
| 2026-05-?? | **TBD — PR-29b market-data: T-CA1 ...** | api/batch/domain | PR-29b T-CA1 |
<!-- pr-29-slot-market-data -->
| 2026-05-?? | **TBD — PR-29c frontend: T-S1 잔재 검증 ...** | frontend | PR-29c T-S1 잔재 |
<!-- pr-29-slot-frontend -->
| 2026-05-?? | **TBD — PR-29d backend-core (R3): SymbolControllerTest mock + batch test 정정 ...** | api/batch | PR-29d R3 일괄 |
<!-- pr-29-slot-end -->
```

### §7-2. sub-agent in-place edit 의무

각 sub-agent는 자기 anchor 바로 위 row만 in-place edit (TBD → 실제 내용). anchor line 자체는 변경 금지.

---

## §8. 머지 순서 + 머지 정책

### §8-1. 순서 LOCK

1. **PR-29 (architect)** — 본 PR (docs only) **선행 머지**
2. **PR-29a + PR-29b + PR-29c 병렬** (영역 격리 LOCK + V 번호 분배 LOCK + KafkaTopicConfig PR-29b 단독)
3. **PR-29d** — R3 일괄 테스트, a·b·c 머지 후 직렬 진입

### §8-2. 머지 방식

`--rebase` 의무 (헌법 §5 + ADR 0034). squash 금지 (author 화이트리스트 충돌 회피).

### §8-3. author 분리

| sub-agent | author email |
|---|---|
| PR-29 (architect) | bot+architect@magicjar.local |
| PR-29a (backend-core) | bot+backend-core@magicjar.local |
| PR-29b (market-data) | bot+market-data@magicjar.local |
| PR-29c (frontend) | bot+frontend@magicjar.local |
| PR-29d (backend-core) | bot+backend-core@magicjar.local |

---

## §9. 운영 체크리스트 (헌법 §1·§2)

각 sub-agent 진입 시:
- ✅ `node scripts/task.mjs start --agent <name> --goal "..." --deliverables "..."`
- ✅ branch `agent/<agent>/<task>`
- ✅ author `bot+<agent>@magicjar.local`
- ✅ KIS-first raw fetch 출처 인용 (PR 본문 의무 — PR-29a 1건, PR-29b 7건)
- ✅ CHANGELOG slot row in-place edit (TBD → 실제)
- ✅ 머지 후 4단계 정리 (헌법 §2): main pull → task.mjs archive → worktree remove → branch -d

---

## §10. 변경 이력

- v1 (2026-05-08, architect) — 초안. PR-29a/b/c/d 4 PR 분배 + 영역 LOCK + add-add 0% 검증 + KafkaTopicConfig 단일 분배 결정.
