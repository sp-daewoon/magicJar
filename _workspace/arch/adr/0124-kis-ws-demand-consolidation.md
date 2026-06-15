# ADR 0124 — KIS WS 구독 수요 재편 — H0UNCNT0 단일 채널 + watchlist polling 강등 + owner 우선순위 재정렬

- 상태: **Accepted (2026-06-13, 최고 관리자 직접 결재 — 결정 3건 LOCK · 이중 발사 경로·flag 완전 제거)**
- 작성자: architect (opus 4.8, 1M)
- 관계: **ADR 0123 amend** (§2-2 owner priority 재정렬 + §2-3 R3 H0UNCNT0 통합 채택 확정 + watchlist owner WS→polling 강등 — 0123 본문은 수정하지 않고 R3 항목에 "→ 0124 채택" 한 줄만 amend), ADR 0048(WS 한도 — 0123 경유 계승), ADR 0114(HOLDING evict 금지 LOCK 계승), ADR 0091(market-level UN 빈 응답 risk 인용·H0UNCNT0 fallback 가드), ADR 0063(NXT 통합 cover), ADR 0028(watchlist ingestion polling — 본 ADR이 watchlist 시세 공급을 WS→polling 전용으로 환원), 헌법 §9-1(SLO)·§11-2(silent fallback 금지)·§13(롱 온리)
- 진입 트랙: 운영 고도화 (실시간 인입 회계 layer — ADR 0123 후속 채택 확정)
- 자본 결정권: **0** (시세 인입 layer — 주문 발사 X)

---

## 1. 컨텍스트

ADR 0123(KIS WS 슬롯 회계 — Proposed)이 단일 ledger·owner eviction·채널 다이어트를 설계했고, 그 R3(H0UNCNT0 통합 체결가)·owner priority는 **실측 검증 후 채택 확정**을 전제로 두었다. 2026-06-13 새벽 라이브 전환 실측 + 최고 관리자 직접 결재로 3건이 확정되었다. 본 ADR은 0123의 후속 채택을 LOCK으로 고정한다.

**실측 evidence (2026-06-13 00:55 라이브 전환)**:

- 종목당 체결가를 H0UNCNT0 단일 1채널로 전환 시, 동일 수요에서 **slot 점유 31/40 → 16/40** 실측. 채널 다이어트가 0123 설계대로 slot을 절반화함을 라이브에서 확인.

**KIS-first raw 검증 (헌법 §3 — raw fetch, 2026-06-13)**:

| 항목 | KIS 공식 출처 | 결정 |
|---|---|---|
| 통합 체결가 tr_id | **H0UNCNT0 "국내주식 실시간체결가 (통합)"** — `examples_llm/domestic_stock/ccnl_total/ccnl_total.py` (단일 구독으로 KRX+NXT 체결 통합 수신) | **H0UNCNT0 단일 채널** |
| 세션 구분 | 응답 필드 **`NEW_MKOP_CLS_CODE`** (신 장운영 구분 코드 — 단일 스트림 내 세션 구분) | tr_id 1개 + 필드로 세션 구분 |

raw 출처: `examples_llm/domestic_stock/ccnl_total/ccnl_total.py`(H0UNCNT0 "국내주식 실시간체결가 (통합)" + `NEW_MKOP_CLS_CODE`), `domestic_stock_functions_ws.py`(H0UNCNT0·H0UNASP0 tr_id 목록). github.com/koreainvestment/open-trading-api main. ADR 0123 §1 KIS-first 표(`kis_auth.py` 한도 40·`ccnl_total`)와 1:1 정합.

---

## 2. 결정 (2026-06-13 최고 관리자 직접 결재 — 3건 LOCK, 변경 불가)

### 2-A. 체결가 구독 = H0UNCNT0 단일 채널 기본 (ADR 0123 R3 채택 확정)

종목당 체결가 채널을 **H0UNCNT0 단일 구독**으로 확정한다. H0UNCNT0는 단일 구독으로 KRX+NXT 체결을 통합 수신하며, 스트림 내 `NEW_MKOP_CLS_CODE`(신 장운영 구분 코드)로 세션을 구분한다. 종목당 1채널 (slot 절반 — 31→16 라이브 실측).

- **이중 발사 경로·flag 완전 제거 (2026-06-13 결재)**: 옛 H0STCNT0+H0NXCNT0 이중 구독 경로와 `MAGICJAR_KIS_WS_INTEGRATED_TICK_ENABLED` ENV 분기를 코드·문서에서 모두 삭제했다. H0UNCNT0 단일 채널이 유일한 경로다. 롤백이 필요하면 CHANGELOG에서 v3.13.2 시점 코드를 참조한다.
- **빈 tick fallback 가드 유지** (ADR 0091 정합): H0UNCNT0 빈 응답(market-level UN 빈 데이터) 감지 시 WARN. silent 금지(헌법 §11-2). 라이브 실측에서 빈 응답 미관측 — 가드는 안전망.

### 2-B. watchlist WS 구독 폐지 → 5초 REST polling 전용 강등

watchlist owner의 KIS WS 체결가 구독을 폐지하고, watchlist 시세 공급을 **5초 REST polling 전용**으로 강등한다(ADR 0028 ingestion polling 경로 환원).

- **근거**: 자동매매 전 경로(STRATEGY·HOLDING·PENDING_BUY·DETAIL owner)가 WS 수요를 독립 커버한다. watchlist WS 구독은 매매 본체 기여 0 — 사용자가 고정해서 보는 수동 관찰 보드일 뿐이며 5초 polling으로 충분하다. **WS 40 slot은 매매 수요 전용으로 비운다.**
- **watchlist 기능은 유지**(등록/삭제/priority/화면) — **시세 공급 채널만 WS → polling**으로 바뀐다. watchlist 도메인·UI·우선순위는 무변경.
- **eviction 로직 단순화**: 0123 §2-2의 WATCHLIST(priority 20) victim 선정·polling 강등 fallback 분기가 제거된다. watchlist는 더 이상 ledger의 slot owner가 아니므로, eviction 대상 집합에서 빠진다. → eviction 대상 = STRATEGY·DETAIL 2종으로 축소(HOLDING·PENDING_BUY는 evict 금지 LOCK 계승).

#### ★ amend (2026-06-15, 최고 관리자 직접 결재) — watchlist 시세 5초 REST polling **폐지**

§2-B의 "watchlist 시세 = 5초 REST polling 전용" 결정을 **폐지**한다. **watchlist 시세 자동 polling 자체를 제거**하고, `quote:last` Redis 캐시는 WS owner(STRATEGY/HOLDING/PENDING_BUY/DETAIL) 경로가 채운다(혹은 화면이 STOMP WS tick·KIS PriceMetrics 폴백으로 직접 조달).

- **운영 evidence**: `WatchlistPollingWorker.tick()`이 5초마다 watchlist 등록 종목 현재가를 KIS REST(`FHKST01010100` inquire-price)로 조회했고, 이 polling이 KIS **`EGW00201`(초당 거래건수 초과)**를 지속 유발하며 USER bucket(주문·잔고 hot path)과 경합해 잔고조회 `EGW00215`까지 밀었다(2026-06-15 REAL 세션 실측).
- **근거**: 이 polling이 채우던 `quote:last`는 watchlist 전용 종목(STRATEGY/HOLDING/PENDING_BUY/DETAIL owner에 안 걸린 종목)의 화면 시세만 추가로 채웠다. **최고 관리자가 watchlist 시세 화면을 사용하지 않으므로 순수 KIS quota 낭비**다.
- **영향도 audit (무회귀 결재)**:
  - STRATEGY/DETAIL 종목 상세 화면 — frontend는 STOMP `topic.market.tick`으로 WS tick을 실시간 수신하므로 `quote:last` 부재의 영향이 없다. REST 첫 조회(`SymbolMetaService`)는 `quote:last` 1순위가 비면 KIS PriceMetrics(2순위)로 폴백한다(폴백 설계됨).
  - `CandleQueryService`·`CandleHealthService`의 `quote:last` 소비는 WS/PriceMetrics 경로로 충족된다.
  - watchlist 전용 종목의 화면 시세만 안 나옴 — **결재된 trade-off**.
- **watchlist 기능은 유지**(등록/삭제/priority/DB·pool). 시세 자동 조회 책임만 폐지된다. `WatchlistPollingWorker`는 pool 추적/`start`/`stop` API만 보존(향후 재활성화 hook). `application.yml`의 `watchlist-polling.*` throttle 설정과 worker의 KIS REST·credential 의존성을 제거했다.
- **구현**: `agent/backend-core/watchlist-polling-and-strategy-drift` (consumer 모듈).

### 2-C. owner 우선순위 재정렬: HOLDING > PENDING_BUY > STRATEGY > DETAIL

WS slot owner priority를 재정렬한다(낮을수록 상위):

| owner | 기존 (ADR 0123 §2-2) | **본 ADR 확정** |
|---|---|---|
| HOLDING | 0 | **0** |
| PENDING_BUY | 5 | **5** |
| DETAIL | 10 | **20** |
| STRATEGY | 40 (상한 15) | **10** (상한 15 계승) |
| WATCHLIST | 20 (신규) | **폐지** (§2-B — owner 아님) |

- **HOLDING 최우선 유지**(0123 계승): 보유 종목 스탑로스/청산 처리에 tick 공급이 끊기면 청산 재료 공백 = 자본 risk. HOLDING·PENDING_BUY는 eviction 절대 금지(ADR 0114 LOCK 계승).
- **STRATEGY(10) > DETAIL(20) 역전 — 본 ADR의 핵심 변경**: 기존은 DETAIL(10) > STRATEGY(40)으로 "사용자 화면 보기"가 "자동매매 평가·진입"보다 우선이었다. 최고 관리자 명시 — **매매 경로(STRATEGY: 자동매매 평가·진입)가 관찰 경로(DETAIL: 사용자 화면 보기)보다 우선해야 한다.** STRATEGY는 진입 신호 생성·marketable limit 진입가 산정에 tick이 직결되므로, slot 경합 시 DETAIL을 evict하고 STRATEGY를 보호한다.
- STRATEGY는 가변 수 owner — `MAGICJAR_KIS_WS_STRATEGY_MAX_CODES`(default 15) 상한 계승, discovery score 상위 우선(ADR 0117).

---

## 3. 결과

### 3-1. 운영 양식 3 슬롯 (헌법 §9-5)

**SLO 영향**: KIS WS 연결률 SLO(>99%, §9-1) 영향 없음. 체결가 채널 절반화로 slot 여유 증가(31→16 실측) → OPSP0008 거부 위험 추가 감소. watchlist polling 강등으로 WS 수요가 매매 경로로 집중 — slot 충족률 개선.

**rollback plan**:
- 2-A: 이중 발사 경로·flag는 완전 제거됐다. 롤백이 필요하면 CHANGELOG의 v3.13.2 시점 코드(H0STCNT0+H0NXCNT0 이중 구독·`INTEGRATED_TICK` 분기)를 참조해 PR revert한다.
- 2-B: watchlist polling 강등은 코드 경로 단순화이므로 PR revert가 정석. watchlist owner를 ledger에서 제거하는 변경은 add-only 역방향(다시 owner 추가)으로 복원 가능.
- 2-C: owner priority는 상수 테이블 — 코드 상수 revert.
- **장중 배포 금지** — 장마감(15:30) 후 + staging dwell(§9-2 canary). 단 2-A 실증은 2026-06-13 09:00 정규장 관측.

**관측 매트릭스**: ADR 0123 §3-1 Prometheus 5종 계승(`magicjar_kis_ws_slots_occupied`·`_slots_available`·`_subscribe_rejected_total`·`_eviction_total`·`_demand_queued`). 추가 관측: H0UNCNT0 전환 후 `slots_occupied` 절반화 실증(31→16), watchlist polling 강등 후 `slots_occupied`에서 watchlist owner tag 소멸. eviction tag = STRATEGY·DETAIL 2종으로 축소.

### 3-2. 자원 영향

신규 Flyway 0 / Kafka 0 / STOMP 0 / REST 0. ENV: `MAGICJAR_KIS_WS_INTEGRATED_TICK_ENABLED` 제거(이중 발사 경로와 함께 삭제 — 신규 ENV 추가 0), owner priority 상수 변경. 자원 충돌 0. 구현 = market-data 진행(`agent/market-data/...`).

### 3-3. 검증 (DoD)

1. H0UNCNT0 단일 채널 전환 후 동일 수요 `slots_occupied` 절반화 실증(31→16 라이브 확인 — 2026-06-13 09:00 정규장 재확인).
2. KRX+NXT 체결이 H0UNCNT0 단일 스트림으로 통합 수신 + `NEW_MKOP_CLS_CODE` 세션 구분 정합(정규장·NXT 시간외 양쪽 tick 수신).
3. watchlist 종목은 WS slot 미점유 + 5초 polling으로 시세 갱신(화면 시세 연속성 유지).
4. owner 경합 시 DETAIL evict + STRATEGY 보호 정합(STRATEGY 16번째는 polling 전환·HOLDING/PENDING_BUY 미evict).
5. H0UNCNT0 빈 tick fallback 가드 동작(인위 빈 응답 시 WARN — silent 0).
6. 이중 발사 코드 경로·`INTEGRATED_TICK` flag 완전 제거(2026-06-13 완료 — market-data 병렬 PR).

---

## 4. 대안 (rejected)

| 대안 | rejected 사유 |
|---|---|
| **H0STCNT0+H0NXCNT0 이중 구독 유지** | 종목당 2채널 점유로 40 slot 조기 고갈 — 라이브 실측이 H0UNCNT0 단일 채널의 slot 절반화(31→16)를 확인. KIS 공식 통합 채널 우선 |
| **watchlist WS 구독 유지(0123 WATCHLIST owner)** | 매매 본체 기여 0 — 자동매매 전 경로가 WS 수요 독립 커버. watchlist는 수동 관찰 보드, 5초 polling으로 충분. WS 40 slot은 매매 수요 전용 보존이 본질 |
| **DETAIL > STRATEGY 우선순위(ADR 0123 §2-2)** | "사용자 화면 보기"가 "자동매매 평가·진입"보다 우선이면, slot 경합 시 매매 신호 생성에 tick 공백 발생 — 매매 경로 > 관찰 경로가 본질(최고 관리자 명시). DETAIL은 polling fallback이 매매 신호 공백보다 수용 가능 |
| **이중 발사 경로·flag 보존(deprecated 잔존)** | 라이브 실측 GREEN(31→16) + 최고 관리자 결재로 채택 확정 — flag·이중 구독 코드를 deprecated로 길게 잔존시키면 죽은 분기 누적 + 문서 혼선. 완전 제거 후 롤백은 CHANGELOG v3.13.2 코드 참조로 충분 |

---

## 5. 참고

- ADR 0123 — KIS WS 슬롯 회계 + owner eviction + 채널 다이어트 (본 ADR이 R3 채택 확정 + owner priority 재정렬 amend)
- ADR 0048 — KIS WS 한도 (0123 경유 40건 계승)
- ADR 0114 — HOLDING evict 금지 LOCK (계승)
- ADR 0091 — market-level UN 빈 응답 결함 (H0UNCNT0 빈 tick fallback 가드 인용)
- ADR 0063 — NXT/통합거래 매트릭스 (H0UNCNT0 KRX+NXT cover 정합)
- ADR 0028 — watchlist ingestion polling (본 ADR이 watchlist 시세 공급을 polling 전용으로 환원)
- ADR 0117 — daytrade discovery (STRATEGY score 상위 우선)
- 헌법 §9-1·§9-5(운영 양식)·§11-2(silent fallback 금지)·§13(롱 온리)
- spec: `_workspace/specs/2026-06-11-kis-ws-slot-budget.md` (0123 SoT — 본 ADR이 R3 채택 확정으로 정합)
- KIS-first raw: `examples_llm/domestic_stock/ccnl_total/ccnl_total.py`(H0UNCNT0 "국내주식 실시간체결가 (통합)" + `NEW_MKOP_CLS_CODE`)·`domestic_stock_functions_ws.py`(H0UNCNT0 tr_id)

## 6. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-06-13 | ADR 0124 본문 발행 (Accepted) — architect opus 4.8. KIS WS 구독 수요 재편 3건 LOCK(최고 관리자 직접 결재): ①H0UNCNT0 단일 채널 기본(ADR 0123 R3 채택 확정·라이브 31→16 실측) ②watchlist WS 구독 폐지→5초 REST polling 전용 강등(매매 본체 기여 0·WS 40 slot 매매 전용 보존·eviction 단순화) ③owner priority 재정렬 HOLDING(0)>PENDING_BUY(5)>STRATEGY(10)>DETAIL(20)(STRATEGY>DETAIL 역전=매매 경로>관찰 경로·WATCHLIST owner 폐지). ADR 0123 amend(R3 채택 확정·owner 재정렬·watchlist owner 제거). KIS-first raw(`ccnl_total.py` H0UNCNT0+`NEW_MKOP_CLS_CODE`). 구현=market-data 병렬(`agent/market-data/...`). |
| 2026-06-13 | 이중 발사 경로·`INTEGRATED_TICK` flag 완전 제거 현행화 (최고 관리자 결재 "문서에서 다 지워라") — deprecated·비상 롤백 잔존·제거 예정 서술을 H0UNCNT0 단일 채널 확정으로 압축. 롤백은 CHANGELOG v3.13.2 코드 참조. md only. |
| 2026-06-15 | **§2-B amend — watchlist 시세 5초 REST polling 폐지** (최고 관리자 직접 결재 · backend-core opus 4.8). `WatchlistPollingWorker.tick()`의 5초 KIS REST(`FHKST01010100`) 시세 조회가 `EGW00201`(초당 거래건수 초과) 지속 유발 + USER bucket(주문·잔고) 경합 → 잔고조회 `EGW00215`까지 밀어 결재. polling이 채우던 `quote:last`는 watchlist 전용 종목 화면 시세만 추가 공급했고 최고 관리자 미사용 → 순수 quota 낭비. watchlist 등록/삭제/priority 기능은 유지, 시세 자동 조회 책임만 폐지. STRATEGY/DETAIL 종목은 STOMP WS tick·KIS PriceMetrics 폴백으로 무회귀. worker KIS REST·credential 의존성 + `application.yml watchlist-polling.*` throttle 설정 제거. 구현=`agent/backend-core/watchlist-polling-and-strategy-drift`. |
