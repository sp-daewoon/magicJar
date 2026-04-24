---
name: magicjar-spring-boot-scaffold
description: magicJar의 Spring Boot 3.x + Kotlin 2.0 + JDK 21 + Gradle 멀티모듈 프로젝트 뼈대를 만들거나 재구성한다. 프로젝트 초기 스캐폴딩, 새 모듈 추가, Kotlin 컴파일러 플러그인(`kotlin-spring`/`kotlin-jpa`) 설정, 의존성 개편, 설정 프로파일 수정, 공용 도메인 모델(data class) 정의가 필요할 때 반드시 이 스킬을 사용. "프로젝트 뼈대 만들자", "모듈 추가", "멀티모듈 구조 재편", "application.yml 수정", "기본 설정 보완", "Kotlin 플러그인 붙이자"도 포함.
---

# magicjar-spring-boot-scaffold — Spring Boot 멀티모듈 스캐폴딩

magicJar는 하나의 Gradle 루트 + 역할별 서브모듈로 구성한다. 배포는 `app` 모듈의 단일 실행 가능 JAR로 통합.

## 표준 모듈 구조

```
magic_ch1/                     (디렉토리명 유지, 프로젝트명은 magicJar)
├── settings.gradle.kts        (include "app", "core", "market-data", "strategy", "ai", "order-exec", "web")
├── build.gradle.kts           (공통 플러그인·의존성: Spring Boot BOM, Java 21, Lombok?)
├── gradle/
├── app/                       (메인 애플리케이션 엔트리, 모든 모듈 조립)
├── core/                      (도메인 모델, 포트 인터페이스, 공용 DTO)
├── market-data/               (KIS 클라이언트, 수집·저장)
├── strategy/                  (지표, DSL, 백테스트, 주문 로직)
├── ai/                        (Spring AI 통합, 프롬프트, 스키마)
├── order-exec/                (모의/실계좌 주문 어댑터)
└── web/                       (Thymeleaf 컨트롤러, 정적 자산, WebSocket)
```

## 초기 스캐폴딩 체크리스트

- [ ] Gradle Kotlin DSL 사용 (`build.gradle.kts`, `settings.gradle.kts`)
- [ ] Kotlin 2.0.x + JDK 21 + Spring Boot 3.3+
- [ ] 플러그인: `kotlin("jvm")`, `kotlin("plugin.spring")`, JPA 모듈엔 `kotlin("plugin.jpa")`, `ktlint`
- [ ] `jvmToolchain(21)`, `-Xjsr305=strict`
- [ ] 공통 라이브러리: Resilience4j, Bucket4j, `jackson-module-kotlin`, `kotlin-reflect`
- [ ] 테스트: JUnit 5 + kotest-assertions(또는 AssertJ) + MockK + Testcontainers
- [ ] Lombok/MapStruct 사용 금지 (Kotlin data class + 확장 함수로 대체)
- [ ] 타임존 고정: `@PostConstruct`에서 `TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))`
- [ ] 프로파일: `local`, `mock`, `prod` — 기본은 `mock`
- [ ] `magicjar.mode` 커스텀 프로퍼티: `BACKTEST` / `MOCK` / `REAL`
- [ ] Flyway 마이그레이션(`db/migration/V1__init.sql` 비워서라도 준비)
- [ ] `application.yml` 은 기본값만, 민감정보는 `application-local.yml.example` 템플릿
- [ ] 루트 `.gitignore`: IDE, build/, logs/, `.env`, `application-local.yml`
- [ ] `README.md` 한국어: 실행 방법(./gradlew :app:bootRun), 프로파일 설명

## 공용 도메인 모델(최소) — Kotlin data class

`core/src/main/kotlin/kr/spdaewoon/magicjar/core/domain/` (상세는 `_workspace/backend/domain-model-draft.md`)

- `Instrument(code, name, market)` — KOSPI/KOSDAQ
- `Candle(code, interval, openTime, ohlcv, tradeCount)` — `enum class Interval { D1, M1, M5, … }`
- `Position(code, qty, avgPrice, openedAt)`
- `Order(id, code, side, qty, price, type, status, createdAt)` — `enum class Side { BUY, SELL }`, `OrderType { MARKET, LIMIT }`
- `BacktestResult(strategyId, period, metrics, trades[])`
- `NewsItem(id, source, publishedAt, title, body, instruments[])`
- `AiInsight(topic, payload, reasoning, createdAt)`
- `TradeJournalEntry(...)` — 매매일지 불변 기록(도메인 모델). JPA 영속 엔티티는 별도 `class` + `kotlin-jpa`.

## 공용 포트(인터페이스)

`core/src/main/kotlin/kr/spdaewoon/magicjar/core/port/`

- `CandleRepository`, `OrderSender` (impl: backtest/mock/real), `NewsRepository`, `AiClient`, `StrategyEvaluator`, `TradeJournalRepository`, `HarnessStateReader`

## 재실행/업데이트 지침

- **기존 모듈 구조 유지.** 새 역할이 필요하면 새 서브모듈 추가, 기존 모듈 분해는 ADR 발행 후에만.
- **도메인 모델 변경 시 호환성 주의.** 필드 추가는 기본값 제공, 필드 제거는 deprecate 후 단계적.
- **공용 포트 변경은 모든 구현체에 영향.** 변경 전에 `SendMessage`로 관련 에이전트(market-data, strategy-engine, order-exec, ai-engineer)에 고지.

## 보안 기본

- KIS 앱키·시크릿: `KIS_APP_KEY`, `KIS_APP_SECRET` 환경변수
- DB 접속: `SPRING_DATASOURCE_*`
- 민감 설정은 Jasypt 또는 외부 시크릿 매니저로. 코드·커밋에 평문 금지.
