# ADR 0083: indicator-impl 라이브러리 모듈 신설 — IndicatorSource 모듈 경계 결함 fix

- 상태: Proposed
- 작성일: 2026-05-18
- 결정자: 사용자 sp-daewoon (최고 관리자, 옵션 B 명시) + architect 합의
- 관계: ADR 0004(MSA 모듈 재구성)를 **갱신**(supersede 아님 — 5 모듈 → 6 모듈, 의존 방향·도메인 순수성 정책 보존). ADR 0054(pattern-engine 모듈) 패턴 1:1 정합. ADR 0021(IndicatorSource 추상화) source 구현 위치만 변동.

## 맥락

사용자 dogfooding evidence (최고 관리자, 2026-05-18 KST):

```
GET http://localhost:8080/api/indicators?code=066570&name=ema&interval=1d&params=period=20&adjusted=false
→ HTTP/1.1 400 Bad Request
```

ADR 0021(2026-05-03 Accepted)의 5 native + 80 KIS 합계 81지표가 api 모듈 사용자 노출 endpoint(`GET /api/indicators`)에서 전부 400 응답. 근본 원인:

- `api/.../api/config/IndicatorEngineConfig`는 Spring autowire `List<IndicatorSource>`로 IndicatorSource 빈을 모두 수집.
- 그러나 `NativeIndicatorSource`(44 line, priority=50)·`KisStrategyBuilderSource`(140 line, priority=100) 두 `@Component` 구현체는 **consumer 모듈** 단독 거주(`consumer/src/main/kotlin/.../consumer/application/indicator/source/`).
- ADR 0004 §의존 방향 정합 — api 모듈은 consumer 의존 **0** LOCK (KafkaListener 이중 등록·KIS WS 이중 connect risk 차단).
- 결과: api 컨테이너 부팅 시 `sources=emptyList()` → `IndicatorEngine.calculate(...)` 모든 호출이 `IllegalArgumentException("No source supports ...")` → `IndicatorService` catch에서 `IndicatorException.UnsupportedSpecException` 매핑 → 400.

또한 두 source 구현 모두 `@Component` Spring 의존 보유 → ADR 0004 §domain 순수성 정책 LOCK 정합으로 domain 모듈 직접 흡수 0 (domain은 Spring·JPA 의존 0).

본 결함은 W2 T-IND 트랙 PR-33a/PR-33b 머지 시점(2026-05-04~05) source 구현체 거주 모듈 결정의 결함. **ADR 0054 §후속 "향후 indicator-engine 동일 패턴 적용 검토 — backlog 추가, 별도 ADR 필요 시점에 발급"**(2026-05-10 명시)가 운영 부채로 누적되어 본 dogfooding evidence로 표면화.

## 검토한 대안

| 대안 | 장점 | 단점 |
|---|---|---|
| A. `api → consumer` 의존 추가 | build.gradle 1줄 | **ADR 0004 §의존 방향 위반** + `KisWebSocketBridge` 같은 KafkaListener 빈이 api 컨테이너에도 등록 → Kafka 이중 소비 + KIS WS 클라이언트 2개 동시 connect → ADR 0048 41건 한도 위반 risk |
| **B. `indicator-impl` 라이브러리 모듈 신설** (본 결정, 사용자 명시) | ADR 0004 §의존 방향 정합 보존 + api·consumer 동일 빈 공유 + 컨테이너 수 불변 (api·batch·consumer 3개 유지) + Hexagonal port·adapter 분리 정석 + ADR 0054 pattern-engine 패턴 1:1 재사용 (운영 인지 비용 0) | 모듈 1개 신설 비용 + 15 파일 git mv + 패키지 rename |
| C. domain에 source 구현 흡수 + `@Component` 의존 추가 | 모듈 추가 0 | **ADR 0004 "domain Spring 의존 0" 정책 위반** + Hexagonal port 순수성 위반 (port·adapter 동일 모듈 혼재) |
| D. api·consumer 양쪽에 source 구현 별도 발행 (중복) | 컨테이너 격리 강함 | **80지표 코드 중복** (~3,433 line × 2) + 알고리즘 drift risk + 정합성 유지 비용 폭증 |

## 결정

**`indicator-impl` 라이브러리 모듈을 신설한다.**

- 위치: `indicator-impl/` (root 직속, `domain`·`pattern-engine`과 동일 레벨)
- 종류: Kotlin + Spring Context (Spring Boot main 0, 라이브러리 jar)
- 패키지 루트: `kr.spdaewoon.magicjar.indicator.impl.*`
- 의존: `indicator-impl` → `domain`만. `api`·`consumer` → `indicator-impl` 신규 의존 추가
- 컨테이너: 0 (라이브러리 jar — api·batch·consumer 컨테이너 수 불변)

### 모듈 매트릭스 (6 모듈 = ADR 0004 갱신)

```
.
├─ domain/         # Kotlin java-library (Spring 0 — 도메인 모델·port·DSL·정책)
├─ pattern-engine/ # Kotlin + Spring Context (Boot main 0) — 57 pattern detector + Redis (ADR 0054)
├─ indicator-impl/ # ← 신설 — Kotlin + Spring Context (Boot main 0) — 80 KIS + 5 Native source 구현
├─ api/            # Spring Boot — implementation(":indicator-impl") + implementation(":pattern-engine")
├─ batch/          # Spring Boot — indicator 미사용 (의존 추가 0)
└─ consumer/       # Spring Boot — implementation(":indicator-impl") + implementation(":pattern-engine")
```

### 의존 방향 (단방향, ADR 0004 정합 보존)

- `domain` ← 모든 모듈 의존 (이전과 동일).
- `indicator-impl` → `domain`만 의존. 다른 응용 모듈 미의존 LOCK.
- `pattern-engine` → `domain`만 의존 (이전과 동일).
- `api`, `consumer` → `indicator-impl`·`pattern-engine` 의존 (라이브러리 jar).
- `api`, `batch`, `consumer`는 **여전히 서로 직접 의존하지 않는다** (ADR 0004 §의존 방향 LOCK 보존).
- `frontend`는 Gradle 그래프 밖.

cycle 검증: domain(0) → indicator-impl(domain) → api/consumer(domain + indicator-impl + pattern-engine) — cycle 0건.

### 책임

`indicator-impl`은 다음 자원을 보유:

- `NativeIndicatorSource` (`@Component`, priority=50, 5종: ma·ema·rsi·macd·bb)
- `KisStrategyBuilderSource` (`@Component`, priority=100, 80지표 — TREND 20 + MOMENTUM 20 + VOLUME 12 + VOLATILITY 12 + OSCILLATOR 8 + OTHER 8)
- `Native*` helper (5 파일 — NativeMa·NativeEma·NativeRsi·NativeMacd·NativeBollingerBands·IndicatorMath)
- `Indicator*` helper (7 파일 — IndicatorTrend·IndicatorMomentum·IndicatorVolume·IndicatorVolatility·IndicatorOscillator·IndicatorOther·KisIndicatorMath)

근거 출처:
- KIS Strategy Builder `strategy_builder/core/indicators.py` 1:1 port (ADR 0020 + ADR 0021 §2-3 raw 출처 LOCK)
- magicJar 자체 5지표 cross-check 검증 정합 (drift < 0.001%)

### 잔류 자원 (consumer 모듈)

- `LiveCandleMerger` (`@Component`, Redis StringRedisTemplate + Redis key `candle:today:{code}:1d` 정합)
- `LiveCandleMergingSource` decorator (LiveCandleMerger 의존)
- `IndicatorEngineConfig` (consumer ApplicationContext, LiveCandleMergingSource wrap — 자본 보호 critical hotfix 2026-05-15 정합 보존)

이유: LiveCandleMerger의 Redis key는 `KisWebSocketBridge.updateCandleToday` 정합 — consumer 모듈 컨텍스트에서 update + read 정합. api 모듈은 cache-aside read-only endpoint 한정 사용 — 본 ADR 0083 진입 후 api 측 wiring 검토는 **결함 3-B**로 분리 (backlog §66 후속).

### 마이그레이션 매트릭스 (15 파일)

- 패키지 rename: `kr.spdaewoon.magicjar.consumer.application.indicator.source(.{native,kis})` → `kr.spdaewoon.magicjar.indicator.impl(.{native,kis})`
- 파일 매트릭스 풀세트: `_workspace/specs/2026-05-18-indicator-impl-module-spec.md` §5-1

## 결과

긍정:
- 사용자 dogfooding evidence (066570 ema 400) 해소 — api 컨테이너 IndicatorEngine bean이 native + kis-strategy-builder 2 source 자동 collect.
- ADR 0004 §의존 방향 정합 보존 (api·consumer 직접 의존 0).
- domain 순수성 정책 보존 (Spring 의존 0).
- 컨테이너 수 불변 (api·batch·consumer 3개).
- KafkaListener·KIS WS 격리 보존 (consumer 단독 등록).
- Hexagonal port·adapter 분리 정석 정합 — domain.indicator port + indicator-impl 구현 분리.
- ADR 0054 pattern-engine 패턴 정합 재사용 (운영 인지 비용 0).

부정:
- 모듈 1개 신설 비용 (settings.gradle.kts + build.gradle.kts + 디렉토리).
- 15 파일 패키지 리네이밍 마이그레이션 (~3,433 line + git mv).
- 결함 3-B(api 측 LiveCandleMerger wiring 부재) 별도 검토 분리.

## 후속

- ADR 0004 §의존 방향 매트릭스 본문 6 모듈 갱신 (architect 후속 PR 또는 본 ADR 0083 머지 후 일괄).
- master-architecture.md §5·§6·§10 갱신 (architect 단독 본 spec PR 포함).
- sub-agent dispatch plan: backend-core 단일 PR (Gradle 4 파일 + 15 파일 git mv + 패키지 rename + 외부 import 갱신) — `_workspace/specs/2026-05-18-indicator-impl-module-spec.md` §8.
- 검증 의무: `./gradlew :indicator-impl:check` + `./gradlew check` GREEN + 사용자 dogfooding curl evidence 200 응답 + source=native 시계열 데이터 포함.
- v3.6.14 patch cut 합류 권장 (T-ACCT + T-MAN과 함께 — `feedback_release_policy_patch_first` 정합).
- 결함 3-B (api 측 LiveCandleMerger wiring 부재) → backlog §66 후속.

## Links

- ADR 0004 (MSA 모듈 재구성) — 본 ADR이 갱신 대상 (5 → 6 모듈)
- ADR 0021 (IndicatorSource 추상화) — source 구현 위치 변동, 추상화 결정 보존
- ADR 0054 (pattern-engine 모듈) — 본 ADR 1:1 정합 패턴, §후속 indicator-engine 검토 완수
- ADR 0030 (master-architecture SoT) — §5·§10 갱신 정합
- ADR 0050 (CHANGELOG 슬롯 채번) — 본 ADR PR은 architect 단독 (slot row 사전 발행 면제, `feedback_spec_plan_scope` 정합)
- 헌법 §3 KIS-first — 본 사이클 raw fetch 발동 0 (모듈 경계 fix만)
- 사용자 메모리 `feedback_v3_skepticism_no_regression` — fitness gap 정합 (자동매매 필수 + v1/v2 잔재 아님)
- 사용자 메모리 `feedback_release_policy_patch_first` — v3.6.14 patch 권장
- 사용자 dogfooding evidence — `GET /api/indicators?code=066570&name=ema&interval=1d&params=period=20&adjusted=false` → 400 (2026-05-18 KST)
- spec 본문: `_workspace/specs/2026-05-18-indicator-impl-module-spec.md`
