# ADR 0124 — KIS WS 구독 수요 재편 — H0UNCNT0 단일 채널 + watchlist polling 강등 + owner 우선순위 재정렬

- 상태: **Accepted (2026-06-13 새벽, 최고 관리자 직접 결재 — 결정 3건 LOCK · 구현 market-data 병렬 진행)**
- 작성자: architect (opus 4.8, 1M)
- 관계: **ADR 0123 amend** (§2-2 owner priority 재정렬 + §2-3 R3 H0UNCNT0 통합 채택 확정 + watchlist owner WS→polling 강등 — 0123 본문은 수정하지 않고 R3 항목에 "→ 0124 채택" 한 줄만 amend), ADR 0048(WS 한도 — 0123 경유 계승), ADR 0114(HOLDING evict 금지 LOCK 계승), ADR 0091(market-level UN 빈 응답 risk 인용·H0UNCNT0 fallback 가드), ADR 0063(NXT 통합 cover), ADR 0028(watchlist ingestion polling — 본 ADR이 watchlist 시세 공급을 WS→polling 전용으로 환원), 헌법 §9-1(SLO)·§11-2(silent fallback 금지)·§13(롱 온리)
- 진입 트랙: 운영 고도화 (실시간 인입 회계 layer — ADR 0123 후속 채택 확정)
- 자본 결정권: **0** (시세 인입 layer — 주문 발사 X)

---

## 1. 컨텍스트

ADR 0123(KIS WS 슬롯 회계 — Proposed)이 단일 ledger·owner eviction·채널 다이어트를 설계했고, 그 R3(H0UNCNT0 통합 체결가)·owner priority는 **실측 검증 후 채택 확정**을 전제로 두었다. 2026-06-13 새벽 라이브 전환 실측 + 최고 관리자 직접 결재로 3건이 확정되었다. 본 ADR은 0123의 후속 채택을 LOCK으로 고정한다.

**실측 evidence (2026-06-13 00:55 라이브 전환)**:

- 종목당 체결가 2채널(H0STCNT0 + H0NXCNT0) → H0UNCNT0 단일 1채널로 전환 시, 동일 수요에서 **slot 점유 31/40 → 16/40** 실측. 채널 다이어트가 0123 설계대로 slot을 절반화함을 라이브에서 확인.

**KIS-first raw 검증 (헌법 §3 — raw fetch, 2026-06-13)**:

| 항목 | 현행 | KIS 공식 출처 | 결정 |
|---|---|---|---|
| 통합 체결가 tr_id | H0STCNT0 + H0NXCNT0 이중 발사 | **H0UNCNT0 "국내주식 실시간체결가 (통합)"** — `examples_llm/domestic_stock/ccnl_total/ccnl_total.py` (단일 구독으로 KRX+NXT 체결 통합 수신) | **H0UNCNT0 단일 채널 기본** |
| 세션 구분 | tr_id 자체로 KRX/NXT 분기 | 응답 필드 **`NEW_MKOP_CLS_CODE`** (신 장운영 구분 코드 — 단일 스트림 내 세션 구분) | tr_id 1개 + 필드로 세션 구분 |

raw 출처: `examples_llm/domestic_stock/ccnl_total/ccnl_total.py`(H0UNCNT0 "국내주식 실시간체결가 (통합)" + `NEW_MKOP_CLS_CODE`), `domestic_stock_functions_ws.py`(H0UNCNT0·H0UNASP0 tr_id 목록). github.com/koreainvestment/open-trading-api main. ADR 0123 §1 KIS-first 표(`kis_auth.py` 한도 40·`ccnl_total`)와 1:1 정합.

---

## 2. 결정 (2026-06-13 최고 관리자 직접 결재 — 3건 LOCK, 변경 불가)

### 2-A. 체결가 구독 = H0UNCNT0 단일 채널 기본 (ADR 0123 R3 채택 확정)

종목당 체결가 채널을 H0STCNT0(KRX) + H0NXCNT0(NXT) 이중 발사 → **H0UNCNT0 단일 구독**으로 전환한다. H0UNCNT0는 단일 구독으로 KRX+NXT 체결을 통합 수신하며, 스트림 내 `NEW_MKOP_CLS_CODE`(신 장운영 구분 코드)로 세션을 구분한다. 종목당 2채널 → 1채널 (slot 절반).

- **`MAGICJAR_KIS_WS_INTEGRATED_TICK_ENABLED` default `true`로 반전 + deprecated**. 0123 §2-3·§3-1의 `default false + 실측 게이트`를 충족하여 사실상 폐기 처리. ENV 키는 **장중 실증(2026-06-13 09:00 정규장) 완료까지 비상 롤백용으로만 잔존**.
- 장중 실증 완료 후 다음 사이클에서 **H0STCNT0 + H0NXCNT0 이중 발사 코드 경로 제거** 예정 (ENV 분기·이중 구독 fallback 코드 삭제). 본 ADR은 그 제거를 예고하며, 제거 PR 시점에 본 §에 amend.
- **빈 tick fallback 가드 유지** (ADR 0091 정합): H0UNCNT0 빈 응답(market-level UN 빈 데이터) 감지 시 WARN + 해당 종목 한정 이중 구독(H0STCNT0+H0NXCNT0) 복귀. silent 금지(헌법 §11-2). 단, 라이브 실측에서 빈 응답 미관측 — 가드는 안전망.

### 2-B. watchlist WS 구독 폐지 → 5초 REST polling 전용 강등

watchlist owner의 KIS WS 체결가 구독을 폐지하고, watchlist 시세 공급을 **5초 REST polling 전용**으로 강등한다(ADR 0028 ingestion polling 경로 환원).

- **근거**: 자동매매 전 경로(STRATEGY·HOLDING·PENDING_BUY·DETAIL owner)가 WS 수요를 독립 커버한다. watchlist WS 구독은 매매 본체 기여 0 — 사용자가 고정해서 보는 수동 관찰 보드일 뿐이며 5초 polling으로 충분하다. **WS 40 slot은 매매 수요 전용으로 비운다.**
- **watchlist 기능은 유지**(등록/삭제/priority/화면) — **시세 공급 채널만 WS → polling**으로 바뀐다. watchlist 도메인·UI·우선순위는 무변경.
- **eviction 로직 단순화**: 0123 §2-2의 WATCHLIST(priority 20) victim 선정·polling 강등 fallback 분기가 제거된다. watchlist는 더 이상 ledger의 slot owner가 아니므로, eviction 대상 집합에서 빠진다. → eviction 대상 = STRATEGY·DETAIL 2종으로 축소(HOLDING·PENDING_BUY는 evict 금지 LOCK 계승).

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
- 2-A: `MAGICJAR_KIS_WS_INTEGRATED_TICK_ENABLED=false` → 현행 H0STCNT0+H0NXCNT0 이중 구독 복귀 (장중 실증 완료 전까지 비상 롤백 경로 잔존).
- 2-B: watchlist polling 강등은 코드 경로 단순화이므로 ENV 토글보다 PR revert가 정석. 단 watchlist owner를 ledger에서 제거하는 변경은 add-only 역방향(다시 owner 추가)으로 복원 가능.
- 2-C: owner priority는 상수 테이블 — `MAGICJAR_KIS_WS_OWNER_PRIORITY_*` 또는 코드 상수 revert.
- **장중 배포 금지** — 장마감(15:30) 후 + staging dwell(§9-2 canary). 단 2-A 실증은 2026-06-13 09:00 정규장 관측(배포는 그 전 새벽 완료).

**관측 매트릭스**: ADR 0123 §3-1 Prometheus 5종 계승(`magicjar_kis_ws_slots_occupied`·`_slots_available`·`_subscribe_rejected_total`·`_eviction_total`·`_demand_queued`). 추가 관측: H0UNCNT0 전환 후 `slots_occupied` 절반화 실증(31→16), watchlist polling 강등 후 `slots_occupied`에서 watchlist owner tag 소멸. eviction tag = STRATEGY·DETAIL 2종으로 축소.

### 3-2. 자원 영향

신규 Flyway 0 / Kafka 0 / STOMP 0 / REST 0. ENV: `MAGICJAR_KIS_WS_INTEGRATED_TICK_ENABLED` default 반전(true) + deprecated(신규 ENV 추가 0), owner priority 상수 변경. 자원 충돌 0. 구현 = market-data 병렬 진행(`agent/market-data/...` — 본 ADR은 구현 파일 경로를 참조로만 적는다).

### 3-3. 검증 (DoD)

1. H0UNCNT0 단일 채널 전환 후 동일 수요 `slots_occupied` 절반화 실증(31→16 라이브 확인 — 2026-06-13 09:00 정규장 재확인).
2. KRX+NXT 체결이 H0UNCNT0 단일 스트림으로 통합 수신 + `NEW_MKOP_CLS_CODE` 세션 구분 정합(정규장·NXT 시간외 양쪽 tick 수신).
3. watchlist 종목은 WS slot 미점유 + 5초 polling으로 시세 갱신(화면 시세 연속성 유지).
4. owner 경합 시 DETAIL evict + STRATEGY 보호 정합(STRATEGY 16번째는 polling 전환·HOLDING/PENDING_BUY 미evict).
5. H0UNCNT0 빈 tick fallback 가드 동작(인위 빈 응답 시 이중 구독 복귀 + WARN — silent 0).
6. 장중 실증 완료 후 이중 발사 코드 경로 제거 PR(후속 사이클) — 본 §에 amend.

---

## 4. 대안 (rejected)

| 대안 | rejected 사유 |
|---|---|
| **H0STCNT0+H0NXCNT0 이중 구독 유지** | 종목당 2채널 점유로 40 slot 조기 고갈 — 라이브 실측이 H0UNCNT0 단일 채널의 slot 절반화(31→16)를 확인. KIS 공식 통합 채널 우선 |
| **watchlist WS 구독 유지(0123 WATCHLIST owner)** | 매매 본체 기여 0 — 자동매매 전 경로가 WS 수요 독립 커버. watchlist는 수동 관찰 보드, 5초 polling으로 충분. WS 40 slot은 매매 수요 전용 보존이 본질 |
| **DETAIL > STRATEGY 우선순위(ADR 0123 §2-2)** | "사용자 화면 보기"가 "자동매매 평가·진입"보다 우선이면, slot 경합 시 매매 신호 생성에 tick 공백 발생 — 매매 경로 > 관찰 경로가 본질(최고 관리자 명시). DETAIL은 polling fallback이 매매 신호 공백보다 수용 가능 |
| **H0UNCNT0 default false 유지(실측 게이트 미해제)** | 2026-06-13 라이브 실측 GREEN(31→16) + 최고 관리자 결재로 게이트 해제 조건 충족 — default false 유지는 검증 완료된 채택을 보류하는 것 |
| **이중 발사 코드 즉시 삭제(롤백 경로 0)** | 장중 실증(09:00 정규장) 전 코드 삭제는 비상 롤백 경로 소멸 risk — ENV deprecated + 실증 후 제거가 안전 |

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
| 2026-06-13 | ADR 0124 본문 발행 (Accepted) — architect opus 4.8. KIS WS 구독 수요 재편 3건 LOCK(최고 관리자 직접 결재): ①H0UNCNT0 단일 채널 기본(ADR 0123 R3 채택 확정·`INTEGRATED_TICK_ENABLED` default true 반전+deprecated·라이브 31→16 실측·이중 발사 코드 제거 예고) ②watchlist WS 구독 폐지→5초 REST polling 전용 강등(매매 본체 기여 0·WS 40 slot 매매 전용 보존·eviction 단순화) ③owner priority 재정렬 HOLDING(0)>PENDING_BUY(5)>STRATEGY(10)>DETAIL(20)(STRATEGY>DETAIL 역전=매매 경로>관찰 경로·WATCHLIST owner 폐지). ADR 0123 amend(R3 채택 확정·owner 재정렬·watchlist owner 제거). KIS-first raw(`ccnl_total.py` H0UNCNT0+`NEW_MKOP_CLS_CODE`). 구현=market-data 병렬(`agent/market-data/...`). Flyway/Kafka/STOMP/REST 신규 0·ENV default 반전만. 코드 0 touch(md only). 자본 영향 0(시세 인입 layer·주문 발사 X) + KIS 실주문 0 + AI 호출 0 + mock 합성 0. |
