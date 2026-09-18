# 차트/가격 데이터 정합성 수정 플랜 — 현행 권위

> **권위**: 본 문서. 조치 머지마다 상태 갱신.
> **결정 이력**: 감사 [`_workspace/audit/2026-09-17-chart-data-integrity-audit.md`](../../../_workspace/audit/2026-09-17-chart-data-integrity-audit.md) · 선행 [audit-remediation-tracker.md](audit-remediation-tracker.md) P1-④ · ADR 0105·0106·0111·0118·0140
> **마지막 갱신**: 2026-09-17 (신설 — 조치 플랜 LOCK, 구현 착수 전)

상태 값: **미착수** / **진행 중** / **완료** / **이연**(사유 명기).

---

## 현행 규약 (조치 항목별)

### P0 — 데이터 오염 근원 (자본 안전 직결, 최우선)

| # | 항목 | 대상 파일 | 조치 | 검증 | 상태 |
|---|------|-----------|------|------|------|
| **P0-A** | 캔들 writer 8경로 난립 + DO NOTHING/UPDATE 혼재 | `ConsumerCandleRepositoryAdapter`·`CandleRefreshService`·`BoundaryAggregator`·`MarketDaily*`·`*GapFill*` | 모든 캔들 쓰기를 단일 `CandleWriteArbiter` 진입점으로 통합. 8경로는 arbiter 호출로 교체. 설계: [`2026-09-18-candle-write-arbiter-design.md`](../../../_workspace/specs/2026-09-18-candle-write-arbiter-design.md) (5-Phase strangler) | arbiter 미경유 직접 UPSERT 0건 + 통합 테스트 | **Phase A-1 완료** (domain `CandleWriteArbiter`+`CandleSource` 순수 판정 + 테스트, 자본영향0). A-2~B 결재 대기 |
| **P0-B** | 무가드 DO UPDATE가 확정봉을 덮음 | `ConsumerCandleRepositoryAdapter.kt:86-88` | `source` → enum + `source_priority` 컬럼 도입. DO UPDATE에 `WHERE EXCLUDED.source_priority >= candle.source_priority` 가드 | 낮은 신뢰(WS-live)가 높은 신뢰(daily-close-finalized) 덮기 시도 → 무시되는 테스트 | **판정 로직 완료** (CandleWriteArbiter.decide). DB 가드 배선은 Phase A-2 |
| **P0-C** | date repair DELETE가 KIS 일시 미보유를 영구삭제 오판 | `MarketDailyDateRepairService.kt:405-420` | DELETE → tombstone/quarantine 상태 전환. KIS 확정 재조회 성공 시에만 실제 교체 | rt_cd=0 + 빈 응답 시 DELETE 0건 + quarantine row 생성 테스트 | 미착수 |
| **P0-D** | source 필드 12종 표준 불일치 (하이픈/언더스코어) | 12개 writer 전체 + `candle.source` 컬럼 | source 값 enum 정규화(대문자·언더스코어 통일) + Flyway migration | enum 외 값 INSERT 시 CHECK 위반 + 기존 12종 매핑 백필 검증 | **enum 정의 완료** (`CandleSource` + fromLabel 12종 매핑 테스트). DB 마이그레이션은 Phase A-2 |

### P1 — 차트 신뢰성 (사용자 화면 = 자동매매 데이터 일치)

| # | 항목 | 대상 파일 | 조치 | 검증 | 상태 |
|---|------|-----------|------|------|------|
| **P1-A** | 조회 시점 3중 병합 재조립 | `CandleQueryService.getCandles` | 확정봉=DB 단일 권위, 진행봉=Redis current 1개만 오버레이. quote:last는 표시 메타(등락률)로 분리, OHLC 재계산 배제 | 같은 확정 ts 봉을 2회 조회 시 OHLC 동일(멱등) 테스트 | 미착수 |
| **P1-B** | 프론트/백 KST 경계 이중 계산 | `LightweightChart.tsx` `barBoundaryTimestamp` ↔ `LiveCandleUpdater.liveCandleStart` | 백엔드가 `barStartTs`를 API/STOMP payload에 포함. 프론트는 재계산 없이 사용 | 90m/4h/12h·NXT 세션에서 프론트 계산 boundary == 백엔드 ts 스냅샷 테스트 | 미착수 |

### P2 — 차트 기반 매매 복원 (원래 목표 · 데이터 신뢰 확보 후)

| # | 항목 | 대상 파일 | 조치 | 검증 | 상태 |
|---|------|-----------|------|------|------|
| **P2-A** | 가격 소스 혼합(P1-④ 이연분 상환) | `JvmStrategyEvaluator.directCandleAccess`·`IndicatorEngine` 계약 | RAW/ADJUSTED 모드를 지표+DSL 양측이 단일 플래그로 공유(threading). `close > ma(20)` 혼합비교 원천 차단 | 분할/배당 종목에서 DSL close와 ma가 같은 모드 값 사용하는 테스트 + 선행 audit P1-④ 상태 완료 전환 | 미착수 |
| **P2-B** | 추세선 매매(ADR 0140) 미작동 | `PlanExecutor`(`planned-entry.enabled=false`)·`TrendlineStopRestamp` | 통제 환경 canary로 게이트 활성. "박제 상수 slPrice" → tick 수준 선형보간(ADR 0140 후속과제) 구현 | canary 계정에서 지지선 이탈 시 실제 손절 발화 + 회귀 0 테스트 | 미착수 |
| **P2-C** | 거대 파일 분해 | `KisWebSocketBridge`(2741)·`CandleRefreshService`(2237)·`SignalGeneratedKafkaListener`(2092) | 책임별 클래스 분리(연결/구독/파싱/체결통보 등). ADR 주석은 분리된 클래스로 이관 | 분해 후 각 파일 <800 LOC + 기존 테스트 전부 green | 미착수 |
| **P2-D** | ADR 상태 불일치 정리 | `_workspace/arch/adr/0063`·`0091` 상태줄 | 0118에 의해 deprecated된 실체 반영 — 상태줄 `Accepted` → `Superseded by ADR 0118`. 폐기 아님(이력 보존) | adr/README 인덱스 상태 == 각 ADR 헤더 상태 일치 검증 | 미착수 |

---

## 실행 순서 원칙

1. **P0가 절대 선행** — 데이터 신뢰 없이 P2(차트 매매 복원)를 켜면 ADR 0137이 실증한 휩쏘 손실 재발. %기반은 데이터 불신 상태에서는 정직한 선택.
2. **P0-A(writer 단일화)가 P0-B/C/D의 전제** — 단일 진입점이 있어야 precedence 가드·enum·quarantine을 한 곳에서 강제.
3. **P1은 P0 이후 병렬 가능** — 조회/렌더 계층은 쓰기 계층과 독립.
4. **P2는 P0+P1 완료 후** — 신뢰 가능한 데이터 위에서만 차트 조건 매매 재개.

## 적용 위치 (착수 시 갱신)

- (미착수 — 구현 PR 머지 시 파일:라인 등재)

## 변경 이력

- 2026-09-17: 신설 — P0 4항목·P1 2항목·P2 4항목 조치 플랜 LOCK. 감사 2026-09-17 §3 개선 포인트 Top 3(P0-A·P2-A·P1-A) 우선.
