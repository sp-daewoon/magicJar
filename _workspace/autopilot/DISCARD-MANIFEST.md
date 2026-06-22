# autopilot 폐기 매니페스트 (DISCARD-MANIFEST)

> **권위 문서** — `/autopilot rollback`("v3.14.0 복귀") 시 **무엇을 버리고 무엇을 살리는가**의 단일 진실원.
> 스펙 §3-bis(2-tier 선택적 폐기 철학). 롤백 앵커: **v3.14.0** (tag·release immutable).
>
> **갱신 규율**: 매 검증 사이클마다 갱신한다. autopilot 패키지 밖에서 N회(권장 ≥3) 검증된 개선이 생기면
> Tier B 로 승격(코드에 `// [AUTOPILOT-PROVEN <date>] <근거·검증횟수>` 주석 + 본 표 추가).

---

## 폐기 철학 (스펙 §3-bis)

"v3.14.0 복귀"는 **전량 리셋이 아니다.** autopilot 시도는 쉽게 버리되, 수행 중 **수 회 검증된 진짜 좋은
개선은 살린다.** 핵심 안전 보장: `MAGICJAR_AUTOPILOT_ENABLED=false`(default)면 autopilot 전 기능이
비활성 = **기존 매매 동작은 v3.14.0 그대로**(zero-regression). 폐기는 본질적으로 "이미 꺼져 있는 격리물 제거"다.

---

## Tier A — autopilot 전용 (쉽게 폐기)

폐기 = 플래그 off + 아래 자원 제거 + `owner=AUTOPILOT` 데이터 정리. 기존 매매 동작 무영향.

### 코드 / 패키지
| 자원 | 위치 | 폐기 방법 |
|------|------|-----------|
| 도메인 autopilot 패키지 | `domain/.../domain/autopilot/**` (예: `AutopilotState.kt`) | 패키지 디렉터리 삭제 |
| `StrategyOwner.AUTOPILOT` 상수 | `domain/.../strategy/StrategyOwner.kt` | enum 에서 `AUTOPILOT` 제거(코드 revert) |
| strategy-engine autopilot 패키지 | `strategy-engine/.../strategy/autopilot/**` (`AutonomousTradingGuard.kt`) | 패키지 디렉터리 삭제 |
| **consumer autopilot 패키지 (Task 3)** | `consumer/.../consumer/autopilot/**` (`MagicjarAutopilotProperties`·`AutopilotStateStore`·`AutopilotStateJpaEntity/Repository`·`AutopilotBuyGuard`·`AutopilotKillSwitch`·`AutopilotLossAccumulator`·`AutopilotConfiguration`) | 패키지 디렉터리 삭제 |
| consumer JPA scan 등록 (Task 3) | `consumer/.../config/JpaScanConfiguration.kt` | `consumer.autopilot` basePackage 2줄 제거 |
| `ConsumerStrategyJpaRepository` autopilot 쿼리 (Task 3) | `pauseAllAutopilotStrategies`·`countAutopilotStrategies`·`countAutopilotStrategyById` | 3 fun 제거(owner='AUTOPILOT' 격리 쿼리) |
| `StrategyPositionJpaRepository` autopilot 쿼리 (Task 3) | `sumAutopilotExposureByUser`·`countAutopilotHeldCodesByUser` | 2 fun 제거 |
| `SignalGeneratedKafkaListener` Guard wiring (Task 3) | `autopilotBuyGuardProvider`(nullable default)·`applyAutopilotBuyGuard`·`AutopilotGateResult` | ctor 파라미터·helper·sealed 제거(게이트 분기 — owner≠AUTOPILOT/off 면 NotApplicable→기존 경로 byte 동일) |
| `ConsumerExecutionStrategyPositionAdapter` 손실누적 hook (Task 3) | `autopilotLossAccumulatorProvider`(nullable default)·applyExecution SELL delta hook | ctor 파라미터·hook 블록 제거 |
| **strategy-engine 페이퍼 섀도 스코어러 (개선 G)** | `strategy-engine/.../strategy/autopilot/AutopilotPaperShadowScorer.kt`·`ShadowOutcome` | autopilot 패키지 삭제에 포함(Task 2 행이 패키지 전체) |
| **consumer 페이퍼 섀도 (개선 G)** | `consumer/.../consumer/autopilot/AutopilotPaperShadow{JpaEntity,JpaRepository,Recorder,ScoringWorker}.kt` | consumer autopilot 패키지 삭제에 포함 |
| `SignalGeneratedKafkaListener` 섀도 hook (개선 G) | `autopilotPaperShadowRecorderProvider`(nullable default)·`recordPaperShadowIfStarved` | ctor 파라미터·helper 제거(BLOCKED hook — non-AUTOPILOT/off 면 no-op→byte 동일) |
| `ConsumerCandleJpaRepository.findByCodeAndIntervalAfter` (개선 G) | candle 후속봉 조회(섀도 스코어링용) | 1 fun 제거(다른 경로 미사용 — autopilot 전용) |

### 설정 / ENV
| 자원 | 위치 | 폐기 방법 |
|------|------|-----------|
| `magicjar.autopilot.*` 게이트 블록 | `api/src/main/resources/application.yml`, `consumer/.../application.yml` | 블록 제거 |
| `MAGICJAR_AUTOPILOT_ENABLED` (default false) | ENV / `.env` | 미설정(=false)로 즉시 비활성. 제거. |
| `MAGICJAR_AUTOPILOT_POOL_PCT` (default 5) | ENV | 제거 |
| `MAGICJAR_AUTOPILOT_PER_TRADE_PCT` (default 20) | ENV | 제거 |
| `MAGICJAR_AUTOPILOT_MAX_POSITIONS` (default 3) | ENV | 제거 |
| `MAGICJAR_AUTOPILOT_DAILY_LOSS_PCT` (default 30) | ENV | 제거 |
| `MAGICJAR_AUTOPILOT_CUM_LOSS_PCT` (default 50) | ENV | 제거 |

### 데이터 / 스키마
| 자원 | 위치 | 폐기 방법 |
|------|------|-----------|
| `autopilot_state` 테이블 | Flyway `V91__autopilot_state.sql` | `DROP TABLE IF EXISTS autopilot_state;` (V91 하단 ROLLBACK 노트) |
| `owner='AUTOPILOT'` 전략 데이터 | `strategy` 테이블(컬럼 공유, 스키마 무변경) | `DELETE FROM strategy WHERE owner = 'AUTOPILOT';` (사용자 전략·후성 보존) |
| `autopilot_paper_shadow` 테이블 (개선 G) | Flyway `V93__autopilot_paper_shadow.sql` | `DROP TABLE IF EXISTS autopilot_paper_shadow;` (V93 하단 ROLLBACK 노트) |

> `strategy.owner` 컬럼은 VARCHAR(16)(V88)이라 `AUTOPILOT` 값 추가에 DDL 변경이 없다 — 스키마 폐기 대상은
> `autopilot_state` 테이블 하나뿐이다.

### 추적 / revert
- 모든 autopilot 커밋은 `feat(autopilot):`/`fix(autopilot):` prefix → `git log --grep autopilot` 로 일괄 식별·revert.

---

## Tier B — 검증된 개선 (폐기에서 생존)

autopilot 작업 중 일반적으로도 가치 있다고 **반복 검증된** 개선(버그 fix·리팩터·관측 향상)은 autopilot
패키지 **밖**에 두고 `// [AUTOPILOT-PROVEN <date>] <근거·검증횟수>` 주석 + 본 표에 기록. 폐기 시 보존·promote.

| # | 개선 | 위치(패키지 밖) | 검증 근거·횟수 | 승격일 |
|---|------|-----------------|----------------|--------|
| — | _아직 없음 (Phase 0 기반 단계)_ | — | — | — |

---

## 검증 사이클 로그

| 사이클 | 일자 | Tier A 변동 | Tier B 승격 | 비고 |
|--------|------|-------------|-------------|------|
| Phase 0 Task 1 | 2026-06-22 | autopilot_state(V91)·StrategyOwner.AUTOPILOT·게이트 ENV 5건·domain 패키지 추가 | 없음 | 안전 하니스 기반 scaffold |
| Phase 0 Task 2 | 2026-06-22 | strategy-engine autopilot 패키지(AutonomousTradingGuard 순수 회로차단기) 추가 | 없음 | Guard 순수 로직 |
| Phase 0 Task 3 | 2026-06-22 | consumer autopilot 패키지(properties·state store·JPA·BuyGuard·KillSwitch·LossAccumulator)·JPA scan 등록·repo autopilot 쿼리 5건·listener Guard wiring·체결 adapter 손실누적 hook 추가 | 없음 | kill switch + Guard 매수경로 wiring + 손실누적. 전부 enabled=false 게이트 뒤(zero-regression). DRY: @ConfigurationProperties 한도 단일화 |
| 개선 G (슬롯 공정성) | 2026-06-22 | autopilot_paper_shadow(V93)·strategy-engine `AutopilotPaperShadowScorer`(순수)·consumer 페이퍼 섀도 4파일(Entity/Repository/Recorder/ScoringWorker)·listener BLOCKED hook·candle 후속봉 쿼리 1건 추가 | 없음 | paper-parallel — 실슬롯 starve 된 owner=AUTOPILOT BUY 를 페이퍼 섀도로 스코어링(실자본 0). 실보유 cap 3 불변. 전부 enabled=false 게이트 뒤(zero-regression) |
