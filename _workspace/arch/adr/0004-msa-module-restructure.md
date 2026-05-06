# ADR 0004: MSA 모듈 재구성 (4 JVM 모듈 + frontend)

- 상태: Accepted
- 작성일: 2026-04-27
- 결정자: architect (사용자 sp-daewoon 합의)
- 관계: ADR 0002(Gradle 멀티모듈)를 **재배치**(supersede 아님 — 멀티모듈 정신은 유지, 분할 축만 전환)

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
├─ domain/      # Kotlin java-library, Spring Boot 미부착 — 도메인 모델·포트·DSL·지표·정책
├─ api/         # Spring Boot 3.x — REST + @Scheduled + STOMP/WebSocket
├─ batch/       # Spring Boot 3.x + Spring Batch — DailyCandleBackfill·EodReport·BacktestRun
├─ consumer/    # Spring Boot 3.x + Spring Kafka — KIS WS 브리지·집계·StrategyRunner·OrderDispatcher·JournalRecorder
└─ frontend/    # React + TS + Vite — SPA, TradingView Lightweight Charts
```

폐기 디렉토리: `app/`, `core/`, `market-data/`, `strategy/`, `ai/`, `order-exec/`, `web/` (7개 일괄 `git rm`).

### 의존 방향 (단방향)

- `domain` ← `api`, `batch`, `consumer` 모두 의존. domain은 어떤 앱 모듈도 참조 안 함.
- `api`, `batch`, `consumer`는 **서로 직접 의존하지 않는다**. 상호작용은 Kafka(ADR 0020) 또는 HTTP.
- `frontend`는 Gradle 그래프 밖. JVM 모듈과는 HTTP로만 결합.

### 패키지 루트

- JVM: `com.mj.domain`, `com.mj.api`, `com.mj.batch`, `com.mj.consumer`
- 도메인 영역: `com.mj.domain.{common, market, strategy, ai, order, journal}`

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
