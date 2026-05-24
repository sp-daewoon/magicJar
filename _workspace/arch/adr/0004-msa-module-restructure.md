# ADR 0004: MSA 모듈 재구성 (7 JVM 모듈 + frontend)

- 상태: Accepted
- 작성일: 2026-04-27
- 갱신일: **2026-05-24 (7 → 8 JVM 모듈 — market-trend-engine 추가, ADR 0100·0101 정합).** 2026-05-22 (6 → 7 JVM 모듈 — persistence-shared 추가, ADR 0088 정합). 2026-05-20 (6 → 7 JVM 모듈 — strategy-engine 추가, ADR 0092 정합). 2026-05-18 (5 → 6 JVM 모듈 — indicator-impl 추가, ADR 0083 정합). 2026-05-10 (4 → 5 JVM 모듈 — pattern-engine 추가, ADR 0054 정합).
- 결정자: architect (사용자 sp-daewoon 합의)
- 관계: ADR 0002(Gradle 멀티모듈)를 **재배치**(supersede 아님 — 멀티모듈 정신은 유지, 분할 축만 전환). ADR 0054(pattern-engine 라이브러리 모듈) + ADR 0083(indicator-impl 라이브러리 모듈) + ADR 0088(persistence-shared) + ADR 0092(strategy-engine) + **ADR 0100·0101(market-trend-engine — 2026-05-24)**이 본 ADR을 갱신.

## 맥락

ADR 0002의 7 모듈(`app, core, market-data, strategy, ai, order-exec, web`)은 **수직 도메인 분할**이었다. Phase B 운영 결과 다음 한계가 드러났다.

- 한 도메인 모듈이 REST·스케줄·WS·Kafka 등 **여러 런타임 책임**을 동시에 짊어져 빌드·배포 단위가 모호.
- `app/`이 7 모듈을 모두 의존하는 단일 부트 jar 구조라, 백테스트·실시간 신호·뉴스 분석을 같은 프로세스에 묶어 야간/주간 부하가 충돌.
- Thymeleaf 기반 `web/`은 프런트엔드 진화(차트·실시간 push·SPA UX)를 가로막음.
- 한국 주식 도메인 특성상 **뉴스·DART 스크래핑 + 헤드리스 캡처**는 Python 생태계가 압도적으로 유리한데, JVM 모듈에 무리하게 욱여넣고 있었음.

## 검토한 대안

| 대안 | 장점 | 단점 |
|------|------|------|
| A. ADR 0002 유지 + 어댑터 보강 | 변경량 최소 | 런타임 책임 혼재 해소 불가, 프런트 SPA 진화 차단 |
| B. 본격 마이크로서비스 (서비스 ≥ 8) + 서비스별 DB + Service Mesh | 완전 격리 | 단일 운영자에 과도, MVP 범위 초과 |
| C. **수평 분할(5 JVM 모듈) + Python 모듈 + React SPA** (본 결정) | 런타임 단위 명확, 도메인 라이브러리 재사용, 프런트·스크래핑 최적 도구 채택 | 빅뱅 마이그레이션 1회 비용 |

## 결정

**모듈을 런타임 책임 축으로 재배치하고, Python·프런트를 1급 모듈로 승격한다.**

```
.
├─ domain/                 # Kotlin java-library, Spring Boot 미부착 — 도메인 모델·포트·DSL·지표·정책 (Spring 의존 0)
├─ pattern-engine/         # Kotlin + Spring Context (Boot main 0) — 57 패턴 detector + Redis 캐시 (api·consumer 공유)  ★ ADR 0054
├─ indicator-impl/         # Kotlin + Spring Context — Indicator 계산 lib (api·consumer 공유)  ★ ADR 0083
├─ strategy-engine/        # Kotlin + Spring Context — DSL parser/evaluator (api·consumer 공유)  ★ ADR 0092
├─ persistence-shared/     # Kotlin + Spring Context + JPA — Outbox Repository + JPA 어댑터 공유  ★ ADR 0088
├─ market-trend-engine/    # Kotlin + Spring Context — Trend calculator + RS rating + SectorRanking pure math (api·batch·consumer 공유)  ★ ADR 0100·0101 (2026-05-24)
├─ api/                    # Spring Boot 3.x — REST + @Scheduled + STOMP/WebSocket
├─ batch/                  # Spring Boot 3.x + Spring Batch — DailyCandleBackfill·EodReport·BacktestRun·MarketIndexSync·MarketIndexDailyClose
├─ consumer/               # Spring Boot 3.x + Spring Kafka — KIS WS 브리지·집계·StrategyRunner·OrderDispatcher·JournalRecorder
└─ frontend/               # React + TS + Vite — SPA, TradingView Lightweight Charts
```

**JVM 모듈 8 (2026-05-24 amend)**: domain · pattern-engine · indicator-impl · strategy-engine · persistence-shared · market-trend-engine · api · batch · consumer.

(컨테이너는 그대로 7 — api · batch · consumer · frontend + postgres · redis · kafka. 라이브러리 모듈은 Boot main 0 컨테이너 0.)

폐기 디렉토리: `app/`, `core/`, `market-data/`, `strategy/`, `ai/`, `order-exec/`, `web/` (7개 일괄 `git rm`).

### 의존 방향 (단방향 — 2026-05-24 amend market-trend-engine 합류)

- `domain` ← `pattern-engine`, `indicator-impl`, `strategy-engine`, `persistence-shared`, `market-trend-engine`, `api`, `batch`, `consumer` 모두 의존. domain은 어떤 응용 모듈도 참조 안 함 (Spring·JPA 의존 0 정책 LOCK).
- 라이브러리 모듈 (pattern-engine · indicator-impl · strategy-engine · persistence-shared · market-trend-engine) → `domain`만 의존. 다른 라이브러리·응용 모듈 미의존.
- `pattern-engine` ← `api`, `consumer` 의존. (batch는 본 ADR 시점 미의존)
- `indicator-impl` ← `api`, `consumer` 의존. (ADR 0083 정합)
- `strategy-engine` ← `api`, `consumer` 의존. (ADR 0092 정합)
- `persistence-shared` ← `api`, `consumer` 의존. (ADR 0088 정합)
- **`market-trend-engine` ← `api`, `batch`, `consumer` 의존** (2026-05-24 amend — batch가 cron Job에서 TrendCalculator reuse · ADR 0100 정합)
- `api`, `batch`, `consumer`는 **서로 직접 의존하지 않는다**. 상호작용은 Kafka(ADR 0007) 또는 HTTP. (라이브러리 모듈 신설은 이 정책 보존)
- `frontend`는 Gradle 그래프 밖. JVM 모듈과는 HTTP/STOMP로만 결합.

### 패키지 루트

- JVM: `kr.spdaewoon.magicjar.domain`, `kr.spdaewoon.magicjar.pattern`, `kr.spdaewoon.magicjar.api`, `kr.spdaewoon.magicjar.batch`, `kr.spdaewoon.magicjar.consumer`
- 도메인 영역: `kr.spdaewoon.magicjar.domain.{common, market, strategy, pattern, ai, order, journal}`

### 모듈 매핑

| 구 | 신규 |
|---|---|
| `app/` | 폐기 (각 앱이 자체 main) |
| `core/common`, `core/security` | `domain/common` + `api/config` |
| `core/persistence` | 도메인 영역별 분산 또는 각 앱 `infrastructure/` |
| `market-data/kis-rest` | `domain/market` (port + 구현) |
| `market-data/kis-ws` | `consumer/kis_ws_bridge` |
| `strategy/` | `domain/strategy` (라이브러리). 백테스트=batch, 실시간=consumer |
| `ai/` | `domain/ai` (포트·프롬프트) + 호출은 api·consumer |
| `order-exec/` | `domain/order` (port) + `consumer/order_dispatcher` |
| `web/` | 폐기 → `frontend/` (React) |

## 결과

- 긍정: 런타임 단위 명확 → 빌드·배포·스케일링 분리 자연스러움. 도메인은 Boot 미부착 라이브러리라 단위 테스트 빠름. 프런트·스크래핑이 각자 최적 생태계로.
- 부정: 빅뱅 마이그레이션 1회 비용. Phase B 누적 변경분 7개 디렉토리 일괄 제거. settings.gradle.kts·CI 전면 재작성.
- 후속:
  - ADR 0007 (Kafka 백본), 0005 (3 JVM 앱), 0009 (프런트 SPA), 0008 (Docker), 0006 (DB)
  - 마이그레이션 절차: spec §14 단계별(domain 빌드 통과 → 각 앱 bootRun → 구 디렉토리 일괄 git rm)

## Links

- master-architecture.md §5 (모듈 매트릭스), settings.gradle.kts (모듈 권위)
- supersedes 부분: ADR 0002 (모듈 분할 축만 전환, 멀티모듈 자체는 유지)
- 갱신 동반: ADR 0054 (pattern-engine 라이브러리 모듈) · ADR 0083 (indicator-impl) · ADR 0088 (persistence-shared) · ADR 0092 (strategy-engine) · **ADR 0100·0101 (market-trend-engine — 2026-05-24)** — 본 ADR §의존 방향 정합 보존

---

## 변경 이력

| 날짜 | 변경 | 결정자 |
|------|------|--------|
| 2026-04-27 | ADR 0004 신설 — 7 도메인 모듈 → 5 런타임 모듈 + frontend 재배치 | architect |
| 2026-05-10 | amend — 4 → 5 JVM 모듈 (pattern-engine 추가 · ADR 0054 정합) | architect |
| 2026-05-18 | amend — 5 → 6 JVM 모듈 (indicator-impl 추가 · ADR 0083 정합) | architect |
| 2026-05-20 | amend — 6 → 7 JVM 모듈 (strategy-engine 추가 · ADR 0092 정합) | architect |
| 2026-05-22 | amend — persistence-shared 추가 (ADR 0088 정합) | architect |
| 2026-05-24 | **amend — 7 → 8 JVM 모듈 (market-trend-engine 추가 · indicator-impl 패턴 1:1 reuse · api·batch·consumer 모두 의존 · ADR 0100·0101 정합) + diagram 보강 + 의존 방향 매트릭스 amend** — 사용자 brainstorming 결재 cec6a4d6 정합. orchestrator → architect R0 dispatch. ADR 0026·0027·0037·0094·0095 amend 동반. 운영 코드 0 touch. (architect, opus 4.7 1M) |
