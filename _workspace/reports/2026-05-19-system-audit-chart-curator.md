# chart-curator 시스템 점검 보고서 — 보수 페르소나 첫 dogfooding

**작성**: chart-curator (`bot+chart-curator@magicjar.local`)
**발행일**: 2026-05-19 KST
**Task**: `_workspace/tasks/T-20260519-1650-main.md`
**근거 권위**: ADR 0085 (chart-curator 영입 + 페르소나 amend) · ADR 0086 (햇징 룰 13건) · skill `magicjar-chart-curator` §데이터 햇징 룰 매트릭스
**기준 브랜치**: main 마감 sha `8210e34` (v3.6.16 cut) → audit 시점 chart-curator 영역 추가 commit 7건 (`4f8f207`·`b9722da`·`4ea8b6b`·`8c8211b`·`0984b4d`·`87f3ea7`·`3bc3891`·`20b2ec7`·`4244a61`) 포함

> **페르소나 LOCK**: 본 보고서는 보수적 시니어 백엔드 개발자 "안정빌드" 페르소나(좌우명: "동작하는 코드를 함부로 건드리지 마라")로 작성합니다. ADR 0085 amend 첫 dogfooding 사이클.

---

## 점검 매트릭스 (5축)

| 축 | 영역 | 정찰 파일 수 | 종합 평가 |
|----|------|--------------|-----------|
| 1 | 캔들 정합성 (Worker·GapDetector·Monitor) | 4 | GREEN — 햇징 룰 13건 적용 evidence inline 코멘트 LOCK |
| 2 | LWC 컴포넌트 dispose lifecycle | 5 (SymbolChart·LightweightChart·IndicatorOverlay·PatternMarker·EquityCurve x2·DailyPnlBar) | GREEN — §13 가드 일관 적용 |
| 3 | 한국석유(004090) dogfooding 후속 | 2 (`2026-05-19-kor-petroleum-investigation-report.md` · git log) | GREEN — root cause 6 fix 사이클(`b9722da`~`20b2ec7`) 모두 완료 |
| 4 | KIS endpoint 매트릭스 (ADR 0037 정합) | 1 (`KisRestCandleAdapter`) | GREEN — 신규 endpoint 호출 0 LOCK |
| 5 | 기존 동작 회귀 risk (★ 페르소나 핵심) | 전 영역 | AMBER — 1 의제 도출 (P1·아래 §우려 사항) |

---

## 개발자1 (보수적) 의견 — chart-curator 시스템 점검

### 안정성 관점

- **기존 동작 영향**: **없음** — 본 보고서는 audit-only. 운영 코드 0 touch. 단 §6 hotfix 의제는 별도 사이클 dispatch 필요.
- **리스크 포인트**:
  1. **이중 LOCK trade-off**: `parseKisDailyResponse`·`parseKis1mResponse` ASC 정규화(§11) + `CandleBackfillWorker.sanityCheck` ASC monotonic 가드(룰 #1)는 의도된 이중 안전망. 정상이지만 KIS 응답 변경 시 둘 다 점검 의무.
  2. **임계치 명명 혼동 risk**: `LastCandleAtMonitor.STALE_THRESHOLD_DAILY_BUSINESS_DAYS = 5L`가 사용자(최고 관리자) 비판 "5+ 영업일 임의 임계치 영구 폐기"(skill §함정 가드 #9)와 명칭 충돌 가능. **§12 갭 검출과 §monitor stale alert는 본질적으로 다른 개념**이지만, 신규 개발자가 혼동할 risk 있음 — KDoc 추가 시 즉시 분리 명시 권장 (P1).
  3. **`useRef` 잔존 컴포넌트**: SymbolChart는 §13에 따라 `useState` lift 완료. 단 `LightweightChart` 내부 `chartRef`·`candleSeriesRef`·`volumeSeriesRef`는 자기 컴포넌트 내부 lifecycle 한정이므로 §13 본문 적용 대상 아님(정상). 다만 `intervalRef`처럼 closure stale 회피 ref 패턴이 다수 — 일관성 OK이지만 추가 안전성 검증 권장.

- **롤백 가능성**:
  - 운영 변경 0건이므로 롤백 불필요.
  - 본 보고서 자체만 add. PR revert로 완전 원상복구 가능.

### 코드 리뷰

#### 캔들 정합성 매트릭스 (축 1)

1. 🔵 [참고] `CandleBackfillWorker` — 햇징 룰 13건 중 본 클래스 직접 적용 대상 10건 모두 inline 코멘트 + audit 5 path 발행. **모범 사례 baseline** 평가. 향후 chart-curator 신규 코드의 reference template로 추천.
   - evidence: `consumer/src/main/kotlin/.../CandleBackfillWorker.kt:152` `* 흐름 (햇징 룰 10건 본문):` 본문 LOCK.

2. 🔵 [참고] `CandleGapDetectorAdapter` — §12 4축 교차 검증 LOCK + `passesActiveGapAxes()` 함수 단일 책임 분리 + `CANDLE_GAP_DETECTED` audit detail JSON에 `axes` evidence inline. 사용자 비판 "임의 임계치" 영구 차단 정합.
   - evidence: `consumer/src/main/kotlin/.../CandleGapDetectorAdapter.kt:122-132` axis 2·3·4 통과 필터 LOCK.

3. 🟡 [권장 수정] `LastCandleAtMonitor.STALE_THRESHOLD_DAILY_BUSINESS_DAYS = 5L` 명명 — KDoc 추가 권장 (line 65-69 기존 KDoc 보강).
   - 현재 KDoc: "5 거래일 = 약 1주 — 정상 일봉 ingestion 주기(매일 20:00) 대비 충분한 마진."
   - 권장 추가 1줄: "**주의**: 본 임계치는 **alert·자동 backfill trigger 기준**(monitor용). 갭 검출 자체는 `CandleGapDetectorAdapter` §12 4축 교차 검증이 단독 권위. 사용자 비판 '5+ 영업일 임의 임계치 영구 폐기'(skill §함정 가드 #9)는 갭 검출 alg.에 대한 비판이며 본 monitor stale 기준과 무관."
   - 사유: §12·§monitor 두 메커니즘이 명명상 비슷해 보여, 향후 신규 개발자가 혼동 + 잘못된 일반화로 LastCandleAtMonitor 임계치 수정 시 사용자 명시 권위 충돌 risk.

4. 🔵 [참고] `CandleBackfillAdminController` — `dry-run` + `window-days` + `code length<=12` + `interval ∈ {1d, 1m}` 4 가드 LOCK. **방어적 프로그래밍** 페르소나 정합 우수.

#### LWC 컴포넌트 dispose lifecycle (축 2)

5. 🔵 [참고] 5개 LWC 차트 컴포넌트 dispose 가드 매트릭스:

| 컴포넌트 | `isDisposedRef` | `chart.remove()` try/catch | `useState` lift (§13) | 평가 |
|---------|----------------|----------------------------|---------------------|------|
| `LightweightChart.tsx` | ✅ line 347 | ✅ line 538-542 | N/A (내부 ref) | GREEN |
| `SymbolChart.tsx` (chartApi/candleSeries) | N/A (소비자) | N/A | ✅ line 106·113 | GREEN |
| `IndicatorOverlay.tsx` (SingleIndicatorOverlay) | N/A | ✅ line 327-332 | chart props nullable | GREEN |
| `PatternMarker.tsx` | N/A | ✅ line 99-100·106-109 | series props nullable | GREEN |
| `EquityCurveChart.tsx` (performance) | ✅ line 33 | ✅ line 92-97 | N/A (내부 ref) | GREEN |
| `DailyPnlBarChart.tsx` (performance) | ✅ line 32 | ✅ line 85-90 | N/A (내부 ref) | GREEN |
| `EquityCurveChart.tsx` (backtest) | ✅ line 49 | ✅ line 144-147 | N/A (내부 ref) | GREEN |

**종합**: §13 dispose race 가드가 7개 컴포넌트 전부에 일관 적용. 한국석유 PR #535·#537·#0984b4d·#8c8211b 사이클의 root cause 영구 차단 evidence.

6. 🟡 [권장 수정] **차트 컴포넌트 위치 분산** — ADR 0085 §책임 영역 LOCK "frontend/src/components/chart/*" 단일 owner 명시와 실제 분산 정합 결함.
   - 현황:
     - `frontend/src/components/chart/` — `PatternMarker.tsx`·`PatternToggleMenu.tsx` (2개)
     - `frontend/src/pages/Symbol/` — `SymbolChart.tsx`·`LightweightChart.tsx`·`IndicatorOverlay.tsx`·`IntervalToggle.tsx`·`IndicatorPanel.tsx`
     - `frontend/src/components/backtest/` — `EquityCurveChart.tsx`
     - `frontend/src/features/performance/components/` — `EquityCurveChart.tsx`·`DailyPnlBarChart.tsx`
   - 평가: 본 정찰 시점에 운영 동작 결함 0건이므로 즉시 수정 의무 없음. 단 ADR 0085 §책임 영역 명시("frontend/src/components/chart/* 전체 소유")와 정합 결함 — `Symbol/`·`backtest/`·`features/performance/` 위치 별도 owner 책임 모호.
   - **점진적 이관 권장** (페르소나 정합 — "빅뱅 리팩토링 회피"):
     - Phase A (선택적): ADR 0085 §책임 영역 LOCK 본문을 "차트 작업 그 자체는 chart-curator"로 명확화 + 위치 분산은 그대로 유지(파일 이동 0건).
     - Phase B (선택적): 신규 차트 컴포넌트 발행 시에만 `frontend/src/components/chart/` 사용 강제, 기존 위치는 그대로 유지 (회귀 risk 0).
   - **하위호환성 우선**: 파일 일괄 이동은 PR diff 대량 발생 + import 경로 전수 갱신 → 사이드 이펙트 risk 큼. 페르소나 정합상 **즉시 수정 비권장**.

#### 한국석유 dogfooding 후속 (축 3)

7. 🔵 [참고] 한국석유 fix 사이클 closure verification — git log 정찰 결과:
   - `b9722da` T-CC Phase 1 (LastCandleAtMonitor 신설)
   - `4f8f207` T-CC Phase 2-A (Worker·GapDetector·햇징 룰 10건 + Flyway V39)
   - `4ea8b6b`·`38c6207` hotfix (audit JSON syntax + KillSwitch FK)
   - `0984b4d`·`8c8211b` P0 fix (KIS ASC 정규화 + LWC dispose race)
   - `87f3ea7`·`3bc3891` Phase 2-B §11 잔여 timeframe (주·월·연봉) ASC 정규화
   - `20b2ec7` Phase 2-B §12·§13 (active gap detection + LWC dispose 잔여)
   - `4244a61` chart-curator merge with origin/main #540

   **closure 평가**:
   - 한국석유 일봉 5개월 stale → ASC 정규화 fix + monitor + Worker auto trigger 3중 LOCK
   - LWC `Object is disposed` → `useState` lift + isDisposedRef + try/catch 3중 LOCK
   - 한국석유 조사 보고서 `2026-05-19-kor-petroleum-investigation-report.md` 발행 → fix 사이클 dispatch evidence

   **이중 확인 권장** (audit-only, 운영 코드 0 touch):
   - DB query: `SELECT MAX(ts), COUNT(*) FROM candle WHERE code='004090' AND interval='1d'` — last_ts ≥ 2026-05-18 + count 합리 검증.
   - audit_trail query: `SELECT action, detail_json FROM audit_trail WHERE entity_id='004090' AND action LIKE 'CANDLE_BACKFILL_%' ORDER BY created_at DESC LIMIT 20` — Worker 호출 evidence.
   - 화면 콘솔: `/symbol/004090` 접속 + 개발자도구 console → `Object is disposed` 0건 확인.

#### KIS endpoint 매트릭스 (축 4)

8. 🔵 [참고] **신규 endpoint 호출 0 LOCK** verified.
   - `KisRestCandleAdapter` 사용 endpoint: `inquire-daily-itemchartprice` (FHKST03010100) + `inquire-time-dailychartprice` (FHKST03010230) **2건만**.
   - `CandleBackfillWorker.fetchWithRetry()` → `kisRestPort.fetchCandles()` 위임 (어댑터 인터페이스 소비, 자체 KIS HTTP 클라이언트 발행 0건).
   - ADR 0037 (20 req/s) + 헌법 §3 KIS-first raw fetch 정합 LOCK.
   - **raw URL inline 주석 LOCK 검증**: `KisRestCandleAdapter.kt:30·32·73·77` 4 위치에 raw URL 명시 — chart-curator skill §KIS-first raw fetch 정합.

#### 기존 동작 회귀 risk (축 5)

9. 🟡 [권장 수정] **Phase 2-B `instrument_status_history` 부재** — §12 4축 교차 검증의 axis 2·3 한계.
   - 현황: `CandleGapDetectorAdapter` §12 axis 2 (trading_halt) + axis 3 (liquidation)은 **현재 entity flag snapshot**으로만 판단 (KDoc line 41-42 명시).
   - risk: 과거 어느 시점에 거래정지였다가 해제된 종목 → 현재 flag=false → §12에서 stale ts를 갭으로 잘못 판정 → 불필요 KIS retry → ADR 0037 rate limit 부담.
   - **Phase 3 의제로 backlog 추가 권장** (즉시 수정 비권장 — `instrument_status_history` 테이블 신설 + Flyway + market-data 영역 협업 의무, P2 ADR 발행 후 점진적 이관).

10. 🔵 [참고] **LWC v4 v4.x 마이너 업그레이드 시 dispose API 변경 risk** — TradingView LWC v4 README나 changelog 모니터링 권장. 현 시점 v4.x 안정 (audit-only 의제).

### 우려 사항

1. **§monitor stale 임계치(5 거래일) vs §12 갭 검출 임의 임계치 폐기** 명명 충돌 — 신규 개발자가 사용자(최고 관리자) 권위 발화를 잘못 일반화하여 monitor 임계치 수정 시 운영 변경 risk. **위 §3 KDoc 보강으로 영구 차단 권장** (P1).

2. **차트 컴포넌트 분산** — ADR 0085 §책임 영역 LOCK과 실제 위치 정합 결함. 운영 동작 결함 0건이므로 즉시 수정 비권장이나, 다음 chart-curator 사이클에서 ADR 0085 본문 명확화 또는 점진적 이관 ADR 발행 의제(P2).

3. **이중 LOCK 운영 비용** — §11(KIS adapter ASC) + 룰 #1(Worker sanity ASC) 이중 안전망은 정상 패턴이나, KIS 응답 schema 변경 시 둘 다 동기 갱신 의무. **KIS schema 변경 모니터링 path 명문화** 권장(P2).

4. **`instrument_status_history` 부재** — §12 axis 2·3 한계. Phase 3 의제 backlog 추가 권장(P2).

5. **5개 컴포넌트 위치 분산 vs 햇징 룰 13건 적용 의무** — `EquityCurveChart` (backtest), `EquityCurveChart` + `DailyPnlBarChart` (performance), `LightweightChart` (Symbol) 모두 chart-curator 햇징 룰 §13 적용 의무인데 위치 분산으로 인해 누락 risk 잠재. 현 시점 모두 적용 GREEN — 향후 추가 컴포넌트 발행 시 위치 무관 §13 적용 의무 명문화 권장.

### 대안 제시

**기존 시스템 안정성 우선** 페르소나 정합 — 운영 코드 0 touch 권장. 단 다음 P1·P2 의제는 별도 사이클 dispatch.

---

## 6. hotfix 의제 도출

### P0 (즉시 사이클 — 자본 path 또는 운영 차단)
- **0건** — chart-curator 영역 P0 결함 부재. 한국석유 fix 사이클 완료(`b9722da`~`20b2ec7`) 및 §13 dispose race 영구 차단 evidence verified.

### P1 (다음 사이클 — 명문화·hardening)

**P1-1**: `LastCandleAtMonitor.STALE_THRESHOLD_DAILY_BUSINESS_DAYS` KDoc 보강
- **영역**: chart-curator (consumer 모듈, monitoring 패키지)
- **변경 파일**: `consumer/src/main/kotlin/.../LastCandleAtMonitor.kt` 1 파일 only
- **diff 규모**: ≈3 lines (KDoc 본문 보강만 — 임계치 값 변경 0)
- **회귀 risk**: 0 (KDoc 변경)
- **사유**: 사용자(최고 관리자) "5+ 영업일 임의 임계치 영구 폐기" 권위 발화와 본 monitor 임계치 명명 충돌 risk 영구 차단. 신규 개발자 혼동 회피.
- **PR 분량**: <5 line edit 단일 PR.

**P1-2**: 한국석유 dogfooding closure 이중 확인 evidence 보고서 발행
- **영역**: chart-curator (보고서 only)
- **변경 파일**: `_workspace/reports/2026-05-19-kor-petroleum-closure-verification.md` 신규 (단 본 audit 보고서 §3 권장 query 3건만 실증 후 발행)
- **diff 규모**: 보고서 1 파일 add
- **회귀 risk**: 0 (보고서 only)
- **사유**: 본 audit 보고서 §7 closure 평가가 git log 기반 추정 — DB·audit_trail·화면 콘솔 실증 query 3건 evidence 추가 시 closure 권위 확정. 사용자 검증 의무(memory `feedback_verification_strict.md` 정합).
- **사전 조건**: 운영 환경 접근 가능 시점. dispatch 별도.

### P2 (backlog — 점진적 개선)

**P2-1**: `instrument_status_history` 테이블 신설 ADR 발행
- **영역**: market-data + chart-curator (협업)
- **변경**: ADR 발행 + Flyway 마이그레이션 + `ConsumerInstrumentJpaRepository` 보강 + `CandleGapDetectorAdapter.passesActiveGapAxes()` axis 2·3 정밀화
- **회귀 risk**: 중 (DB schema 변경 + 영역 2개 동시)
- **사유**: §12 4축 교차 검증 axis 2·3 한계 영구 해소 → false positive 추가 차단.
- **선결 조건**: ADR 발행 + architect 협업 + 사용자 결재.

**P2-2**: ADR 0085 §책임 영역 LOCK 본문 명확화 (또는 점진적 이관 ADR 발행)
- **영역**: architect (ADR 0085 amend) — chart-curator는 협업
- **변경**: ADR 0085 §책임 영역 본문 보강 — "frontend/src/components/chart/* 전체 소유"를 **"차트 작업 그 자체"** 또는 **"신규 차트 컴포넌트는 components/chart/ 강제, 기존 위치는 점진적 이관"**으로 명확화
- **회귀 risk**: 0 (ADR 본문 변경 — 운영 코드 0 touch 옵션)
- **사유**: 현재 위치 분산이 ADR 0085 §책임 영역 LOCK과 정합 결함 — 신규 개발자 혼동 회피 + 향후 위치 변경 의사결정 baseline 확정.

**P2-3**: KIS 응답 schema 변경 모니터링 path 명문화
- **영역**: market-data + chart-curator (협업)
- **변경**: `docs/architecture/external-api-policy.md` 또는 별도 chart-curator skill 본문에 "KIS endpoint 응답 schema 변경 모니터링 + §11 ASC 정규화 + 룰 #1 sanity check 동기 갱신 의무" 명시
- **회귀 risk**: 0 (문서 변경)
- **사유**: 이중 LOCK 운영 비용 영구 차단 — KIS 변경 시점 양쪽 모두 동기 갱신 path 보장.

**P2-4**: §13 LWC dispose 가드 lint rule 발행
- **영역**: frontend infra (chart-curator는 협업)
- **변경**: ESLint custom rule 발행 — `createChart()` 호출이 있는 `*.tsx` 파일은 `isDisposedRef` + `chart.remove()` try/catch 의무 검증
- **회귀 risk**: 저 (lint warning만 추가, 기존 코드 영향 0)
- **사유**: 향후 신규 차트 컴포넌트 발행 시 §13 누락 영구 차단 — chart-curator 영역 외(예: 다른 에이전트가 차트 추가)에서도 자동 강제.

---

## 7. 결론

### 종합 평가

**chart-curator 영역 시스템 상태: GREEN**

- 햇징 룰 13건 적용 evidence: 13/13 LOCK (CandleBackfillWorker 10건 + CandleGapDetectorAdapter §6·§7·§12 + KisRestCandleAdapter §11 + 5개 LWC 컴포넌트 §13)
- 한국석유 dogfooding 후속 fix 사이클: 6 단계 closure (`b9722da`~`20b2ec7`)
- LWC dispose race 가드: 7 컴포넌트 일관 적용
- KIS endpoint 호출: 신규 추가 0건 LOCK (어댑터 인터페이스 소비)
- 운영 코드 회귀: 0건

### 보수 페르소나 lens 최종

- **안정성 최우선**: 본 audit 보고서는 audit-only. 운영 변경 0건.
- **검증된 패턴**: §13 가드 패턴이 7 컴포넌트에 일관 적용된 baseline 확립.
- **하위호환성**: P1·P2 의제 모두 하위호환 옵션 또는 KDoc only.
- **점진적 변경**: P2 의제는 ADR 발행 + 사용자 결재 후 별도 사이클.
- **방어적 프로그래밍**: CandleBackfillAdminController 4 가드 + Worker 3중 retry + Slack notice + KillSwitch safe-path 모범 사례.

### 다음 사이클 dispatch 권장 (사용자 결재 의제)

1. P1-1 (KDoc 보강) — 5 line edit 단일 PR, 즉시 dispatch 가능.
2. P1-2 (한국석유 closure evidence) — 운영 환경 접근 시점에 dispatch.
3. P2-1·P2-2·P2-3·P2-4 — backlog 추가 후 ADR/architect 협업 사이클로 dispatch.

---

**페르소나 dogfooding 결과**

- ✅ "동작하는 코드를 함부로 건드리지 마라" 좌우명 정합 — 운영 코드 0 touch.
- ✅ 🔴 0건 / 🟡 3건 / 🔵 8건 출력 형식 LOCK.
- ✅ 검증된 패턴(§13 dispose 가드) baseline 확인 우선.
- ✅ 임의 임계치 비판 권위 발화 정합 평가 (§monitor 임계치 5 거래일은 §12 갭 검출 임의 임계치와 다른 메커니즘 — 명문화로 영구 차단 권장).
- ✅ 사이드 이펙트 검증 — 차트 컴포넌트 위치 분산은 운영 결함 0건이므로 즉시 수정 비권장 (P2 점진적 이관).

본 보고서는 ADR 0085 amend 페르소나 dogfooding 첫 사이클 baseline로 활용 가능합니다.
