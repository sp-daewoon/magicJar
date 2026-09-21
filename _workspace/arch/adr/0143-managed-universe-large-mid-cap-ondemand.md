# ADR 0143 — 관리 유니버스 축소: 대·중형 + 온디맨드 편입 + 보유 종목

- 상태: **Accepted** (사용자 승인 2026-09-20 — 결재 3건 모두 승인: §4 온보딩 필터+managed_manual, §6 clean-reset, §5 온디맨드 편입 포함)
- 날짜: 2026-09-20
- 결정자: architect (협의: 최고 관리자)
- 관련: 감사 [`2026-09-17-chart-data-integrity-audit.md`](../../audit/2026-09-17-chart-data-integrity-audit.md) · ADR 0017(종목 삭제 금지) · ADR 0039(마스터 sync cron) · ADR 0071(T-SC screener universe) · ADR 0105(managed universe priority)

---

## 1. 배경 (문제)

- 관리 종목 수 = **3,610개** (KOSPI 1,810 + KOSDAQ 1,800). candle **1,133만 행**.
- 전종목 수집·발굴·백테스트·차트 갱신은 (1) 오염 갱신 부담, (2) KIS TPS 경합, (3) 백테스트/발굴 계산량을 모두 키운다.
- 소형주·SPAC·저유동성은 조작·상폐·유동성 위험이 크고, 룰 기반 발굴의 신호 대비 잡음이 높다.
- 현행 온보딩(`InstrumentSyncJob.doSync`)은 `fetchAllRaw()` 전종목을 그대로 upsert하고 `avls_scale`은 저장만 한다 — **유니버스 필터가 없다.**

## 2. 결정

**관리 유니버스 = 대·중형(KIS `avls_scal_cls_code IN {1,2}`) + 사용자 온디맨드 편입 종목 + 보유 종목(자동).**

- **대·중형 (기준선 689)**: KRX 공식 시총규모 등급을 KIS가 전달하는 `avls_scale`. KOSPI 대(1)+중(2), KOSDAQ KOSDAQ100(1)+mid300(2). 파싱·stale 위험 없는 KRX 공식 분류라 시총 raw 값보다 신뢰 높음(감사에서 market_cap_prev stale 확인).
- **온디맨드 편입**: 관리 유니버스 밖 종목을 사용자가 검색 → 로컬 미스 → KIS 단일 조회로 존재 확인 → 사용자 승인 시 편입. 소형주라도 사용자가 원하면 그때 관리 대상 합류.
- **보유 종목(자동)**: 미청산 수량(`strategy_position.quantity > 0`)이 있는 종목은 자동 편입 — 청산 로직이 가격 참조를 잃지 않도록. (행 존재가 아니라 quantity>0로 판정 — 청산 완료 이력 행 제외.)

## 3. 관리 유니버스 판정 (domain 순수)

```
managed(code) :=
     avls_scale(code) ∈ {"1","2"}          // 대·중형
  ∨  admitted(code)                          // 사용자 온디맨드 편입 (instrument.managed_manual = true)
  ∨  held(code)                              // strategy_position.quantity > 0
```

- `instrument`에 `managed_manual BOOLEAN DEFAULT false` 컬럼 추가(P0-D 스타일 add-only, 하위호환).
- 수집·발굴·백테스트·차트 배경 갱신은 이 판정을 통과한 종목으로 스코프.

## 4. 온보딩 필터 (batch)

`InstrumentSyncJob.doSync`에서 fetch 직후 필터:

```kotlin
val kisRaws = kisAdapter.fetchAllRaw()
    .filter { it.avlsScale in ADMITTED_SCALES || manualAdmitted.contains(it.code) }
```

- 대·중형 아님 + 미편입 종목은 애초에 INSERT 안 함.
- ADR 0017(삭제 금지)와 충돌 회피: 기존 유니버스 밖 종목의 물리 삭제는 본 ADR의 clean-reset(§6)에서 1회 예외로만 허용. 정기 sync는 여전히 삭제 안 함(안 들어올 뿐).

## 5. 온디맨드 편입 흐름 (api)

```
검색 → 로컬 InstrumentSearchIndex 미스
     → KIS 단일 종목 조회(InstrumentLookupAdapter 확장 or KisInstrumentMasterAdapter 단건)
     → "존재함 + 미편입" 응답
     → 사용자 "추가할까요?" 확인
     → POST /api/instrument/{code}/admit → instrument upsert(managed_manual=true) + 단일 종목 candle backfill 트리거
```

- 재료 기존재: `InstrumentSearchIndexAdapter`(검색), `InstrumentLookupAdapter`(조회), `WatchlistService.add`(편입 패턴). **빠진 것 = 검색 미스 시 KIS 폴백 + admit endpoint.**

## 6. Clean-Reset 절차 (1회, 되돌리기 어려움)

1. **백업** — candle 제외 slim dump (`_backups/db/`, gitignore). ✅ 완료 2026-09-20.
2. **삭제** (자식 → 부모, 트랜잭션):
   - strategy 참조: signal · strategy_position · strategy_eval_metric · strategy_favorite · strategy_trendline_ref → strategy
   - instrument 참조: instrument_alias · position · trendline · watchlist · instrument_status_history · candle → instrument
   - ※ DSL '전략 빌더'는 코드 — 무변경. 지우는 것은 저장된 strategy 인스턴스뿐.
3. **온보딩 재실행** — §4 필터로 대·중형만 재수집.
4. **candle backfill** — 대·중형 689종목 전 timeframe. circuit-breaker(ADR 0037 Phase 3) 보호 하에.

## 7. 자본 안전

- 보유 종목 자동 편입으로 "보유 종목 데이터 삭제 → 청산 불능" 원천 차단.
- 현재 미청산 보유 0건(2026-09-20 실측 quantity>0 = 0) — clean-reset 시 청산 대상 없음.
- 온디맨드 편입은 데이터 편입만 — 주문·자본 이동 0.

## 8. 대안 (기각)

- **전종목 유지**: 오염·TPS·계산 부담 지속. 기각.
- **시총 raw 절대금액 컷(1000억)**: market_cap_prev stale/파싱 불명확(감사 확인). 기각 — KRX 등급이 신뢰 높음.
- **삭제 없이 플래그만**: candle 1,133만 유지 + 조회 시 필터 → 오염 봉이 남아 재갱신 유도 지속. 사용자는 clean-reset 선택.

## 9. 결정 필요 사항 (사용자 결재)

1. §4 온보딩 필터 배선 + `managed_manual` 컬럼 추가 승인?
2. §6 clean-reset 실행 승인? (instrument 3,610 + candle 1,133만 + strategy 51 삭제 — 되돌리기 어려움, 백업 있음)
3. §5 온디맨드 편입 endpoint 이번 사이클 포함 vs 후속 분리?

## 10. 변경 이력

- 2026-09-20: 신설(Proposed). 관리 유니버스 = 대·중형 + 온디맨드 + 보유. 온보딩 필터 + 온디맨드 편입 + clean-reset 절차 LOCK. 착수 전 결재 대기.
- 2026-09-20: **Accepted** (사용자 결재 3건 승인). PR #1232 — V103 `managed_manual` + InstrumentSyncJob 대·중형 필터 + admit endpoint 머지.
- 2026-09-20: **clean-reset 실행 완료** (§6). instrument·strategy·candle wipe(백업 `_backups/db/`) → consumer 재적재. 실측 미청산 보유 0건이라 청산 대상 없음.
- 2026-09-21: **재온보딩 + 완주 자동화 구현** (PR #1233).
  - `InstrumentSyncEndpoint`(batch actuator `POST /actuator/instrumentSync`) — 영업일 게이트 우회 재온보딩. 대·중형 694 적재.
  - **자동 완주 오퍼레이션** `MarketDailyBackfillAutoRunner`(@Scheduled 5분) — cap(6000콜) 절대값 종료의 3대 공백 보강: (1) 자동 재시도 트리거 (2) 완주 판정(remaining=0 → MARKET_DATA INFO) (3) 정체 알람(3회 진행0 → WARN + 자동 재시도 중단). "cap으로 자르고 마는 건 정합성 서비스가 아니다"(사용자) 해소.
  - `KisRestCandleAdapter` BACKFILL acquire timeout 5s→60s (4곳) — 토큰 경합 시 실패(중간 gap 유발) 대신 대기.
  - `SystemEventCategory.MARKET_DATA` 신규.
  - **결과: 대·중형 687종목 1d 일봉 100% 완주** (auto-runner가 remaining=0까지 자동 이어받기 실증). BG버킷·동시성·cap은 env 튜닝(매매 0 구간 BG8+concurrency8).
- 2026-09-21: **온디맨드 편입 KIS 폴백 구현** — admit(code)이 로컬 DB에 없는 code(clean-reset로 빠진 소형주, 예: 한국석유 004090)를 404 대신 처리한다. KIS 종목 마스터는 전종목 .mst 파일 일괄 fetch 방식이라 api 단건 조회가 없으므로, admit이 `managed_manual=true + active=false` **stub row를 선등록**(`InstrumentJpaRepository.insertManagedManualStub`, native INSERT … ON CONFLICT, §6 준수). 다음 `InstrumentSyncJob`이 `admittedCodes`(=managed_manual) 필터로 이 code를 KIS 마스터에서 찾아 실데이터로 INSERT(active=true)한다. stub은 active=false라 편입 완료 전까지 조회·수집·검색에 노출 안 됨. 응답에 `pendingSync` 플래그 + 즉시 반영 안내(`POST /actuator/instrumentSync`). 새 테이블 0(기존 admittedCodes 로직 재사용).

## 11. 후속 (backlog)
- 중간 gap 정합성 체크 — `isSymbolComplete`는 recency+rows 기반이라 중간 결측은 못 잡을 수 있음. 봉 연속성 검증 후속.
- auto-runner 정체 중단(`stalledStop`) 수동 재개 admin endpoint 노출(현재 `resumeAfterStall()` 메서드만).
- admit KIS 폴백 stub의 **편입 완료 자동 트리거** — 현재 stub 선등록 후 다음 sync(스케줄러/수동)를 기다림. admit 직후 batch sync를 자동 호출해 즉시 편입하는 흐름은 모듈 간 결합(api→batch)이라 후속 검토.
- admit stub이 KIS 마스터에도 없는 무효 code(오타·상폐)면 active=false stub이 남음 — sync 후 미채움 stub 정리(GC) 후속.
