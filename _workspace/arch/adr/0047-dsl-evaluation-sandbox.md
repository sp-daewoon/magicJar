# ADR 0047 — DSL Evaluation Sandbox (정적 검증 + 런타임 timeout/CPU budget + thread pool 격리 + auto-disable)

- 상태: Accepted
- 작성일: 2026-05-07
- 결정자: architect (사용자 위임 + orchestrator dispatch · 이전 감사 6번 — DSL Evaluation Sandbox)
- 관계: ADR 0021 정합(Indicator Source Abstraction — `indicator()` 호출도 sandbox 내) · ADR 0022 보강(Strategy DSL `.kis.yaml` superset 명세 보강) · ADR 0023 정합(Signal Engine 룰 only — sandbox 적용 위치) · ADR 0044 정합(timeout/disable 통지 트리거) · ADR 0045 정합(eval pool graceful shutdown)

---

## 1. 컨텍스트 (왜 이 결정이 필요한가)

ADR 0022는 Strategy DSL을 `.kis.yaml` 호환 superset으로 정의했고 ADR 0023은 Signal Engine을 룰 매칭 only로 못 박았다. 그러나 두 ADR 모두 **AST 파서·검증·실행 명세만 있고 sandbox(격리·자원 한도·timeout) 정책은 침묵**.

magicJar는 사용자(최고 관리자)가 직접 DSL 식을 작성·등록·import하는 시스템이다 (ADR 0022 §2-3 import/export). 사용자 본인이라도 **악의 없이 부주의한 식**을 등록할 수 있다:

- **무한 평가 시간 식**: 깊은 중첩 `and/or` + 다수 `indicator()` 호출 (예: AST depth 200, indicator 50개 호출) → 단일 strategy 평가에 수 초 소요 → SignalGenerator thread block → 다른 시그널 처리 정지
- **재귀 indicator()**: 사용자가 custom indicator를 등록 가능해질 경우 (post-MVP), 자기 참조로 stack overflow 가능
- **거대 AST**: 노드 수 수만 개 → 파서·평가기 메모리 폭발 → consumer JVM OOM
- **금지 함수 호출**: DSL 키워드 화이트리스트 미정의 시 `system.exit()`·reflection 등 escape route 가능 (현재 구현체 없음 — 선제 차단 필요)

**악성 시나리오 (최고 관리자 본인이라도 발생 가능)**:
1. KIS Strategy Builder GUI에서 만든 복잡한 식이 magicJar로 import된 후 evaluator 버그로 무한 loop
2. 사용자가 백테스트용으로 만든 무거운 식을 실시간 SignalGenerator에 그대로 활성화
3. 외부 import — 다른 사용자/커뮤니티에서 받은 .kis.yaml 검증 없이 등록

**핵심 위험**: SignalGenerator는 **consumer 단일 모듈** 내에서 reconciliation·notification·order dispatch와 동일 JVM. 한 strategy 평가가 hang하면 **자동매매 본체 전체가 정지**한다 (ADR 0023 §2-2 평가 흐름 정합).

**필요한 결정**:
- Static parse-time 검증 한도 (AST depth·노드 수·금지 함수)
- Runtime eval-time 한도 (timeout·CPU budget·메모리)
- Thread pool 격리 정책 (다른 모듈과 분리)
- 한도 위반 시 행동 (auto-disable·통지·재시도)
- 사용자 trace UX (등록 시 estimated complexity·timeout 누적 경고)
- 백테스트 정합 (현장보다 완화된 timeout)

## 2. 결정

### 2-1. Static 검증 (parse-time, 등록·import 시)

DSL 등록 흐름(`POST /api/strategy` · `POST /api/strategy/import?format=kis-yaml`) 진입 직후 `KisYamlDslParser.parse()` 결과 AST에 대해 다음 검증을 의무로 수행한다. 위반 시 `400 Bad Request` + 사유 반환.

| # | 한도 | 임계 | 위반 시 |
|---|---|---|---|
| 1 | **AST depth** | ≤ **32** | 등록 거부 — `DslDepthExceededException(depth, limit=32)` |
| 2 | **AST 노드 수** | ≤ **10,000** | 등록 거부 — `DslNodeCountExceededException(count, limit=10000)` |
| 3 | **`indicator()` 호출 수** | ≤ **50** | 등록 거부 — 동일 strategy 내 indicator 호출 50건 초과 시 거부 |
| 4 | **금지 함수 호출** | 화이트리스트 위반 시 거부 | DSL 화이트리스트(아래 §2-1-A)만 허용 |
| 5 | **재귀 indicator()** | 자기 참조 차단 | indicator A가 indicator B를 호출하고 B가 A를 호출하면 거부 (post-MVP custom indicator 대비 선제 차단) |

#### 2-1-A. DSL 키워드 화이트리스트 (ADR 0022 §2-1·§2-2 정합)

다음 키워드만 허용:
- **조건**: `entry`·`exit`·`conditions`
- **연산자**: `>`·`<`·`>=`·`<=`·`==`·`!=`·`CROSS_UP`·`CROSS_DOWN`
- **논리**: `AND`·`OR`·`NOT`
- **산술**: `+`·`-`·`*`·`/`
- **호출**: `indicator(name, params)` (ADR 0021 IndicatorSource 경유) · `pattern(name)` (T-PAT) · `value(literal)`
- **메타**: `tf`·`time.session`·`time.windowKst`·`universe.market`·`universe.filter`·`sizing`·`trailingStop`·`fixedStopLoss`

위 외 임의 함수·메서드·식별자는 **파서 단계에서 거부** (`UnknownDslSymbolException`). reflection·system call·JNI·spawn·eval은 DSL 문법에 자체 부재 — 어휘 차원에서 차단된다.

#### 2-1-B. Estimated Complexity 계산 (사용자 UX)

등록 성공 시 응답 body에 다음 추정치 포함:

```json
{
  "strategyId": 12,
  "complexity": {
    "astDepth": 14,
    "nodeCount": 247,
    "indicatorCallCount": 8,
    "estimatedEvalTimeMs": 18,
    "complexityClass": "LOW"
  }
}
```

`estimatedEvalTimeMs` = 노드 수 × 평균 노드 처리 시간(0.05ms 가정) + indicator 호출 수 × 평균 indicator 시간(2ms 가정). `complexityClass` 4단:

| Class | 임계 | UI 색상 |
|---|---|---|
| LOW | estimatedEvalTimeMs ≤ 30ms | 녹색 |
| MEDIUM | 30ms < x ≤ 60ms | 노란색 |
| HIGH | 60ms < x ≤ 100ms | 주황색 |
| CRITICAL | x > 100ms (이미 timeout 초과 추정) | 빨간색 + 경고 모달 |

CRITICAL은 등록 자체는 허용 (추정치이므로 false positive 가능) 단 등록 후 strategy 페이지에 빨간 배너 표시.

### 2-2. Runtime 평가 sandbox (eval-time, 시그널 처리 시)

`SignalGenerator.generate()` (ADR 0023 §2-1) 내부 `JvmStrategyEvaluator.evaluate()` 호출 직전·직후에 sandbox 적용.

#### 2-2-A. Timeout — 단일 strategy 평가 ≤ 100ms (실시간)

| 모드 | timeout | 사유 |
|---|---|---|
| **Realtime SignalGenerator** | **100ms** | KIS WS tick 1초 budget — 평가 100ms × 활성 strategy 5개 = 500ms 여유 (ADR 0023 §2-2 정합) |
| **Backtest** | **1000ms** | 현장 매매보다 여유 — 백테스트는 배치성, 한 candle 당 평가 시간이 다소 긴 식도 허용 |

**구현**:
```kotlin
val future: Future<StrategyEvalResult> = strategyEvalPool.submit {
    evaluator.evaluate(strategy, candles, ctx)
}
try {
    val result = future.get(100, TimeUnit.MILLISECONDS)
    // 정상 처리
} catch (e: TimeoutException) {
    future.cancel(true)
    throw StrategyTimeoutException(strategy.id, timeoutMs = 100)
}
```

#### 2-2-B. CPU budget — 평가 thread CPU time ≤ 50ms

JMX `ThreadMXBean.getThreadCpuTime(threadId)` 활용. 단일 평가 종료 후 wall-clock(timeout)과 별개로 CPU time을 측정·비교.

```kotlin
val tmx = ManagementFactory.getThreadMXBean()
val tid = Thread.currentThread().id
val start = tmx.getThreadCpuTime(tid)
evaluator.evaluate(strategy, candles, ctx)
val elapsedNs = tmx.getThreadCpuTime(tid) - start
if (elapsedNs > 50_000_000) {
    log.warn("Strategy {} CPU budget exceeded: {}ms", strategy.id, elapsedNs / 1_000_000)
    // metric 발행 — 누적 시 §2-2-D auto-disable
}
```

CPU budget 초과는 즉시 fail이 아니라 **metric으로 누적** — 평균 CPU time이 50ms를 5회 연속 초과하면 §2-2-D 발동.

#### 2-2-C. 메모리 cap — per-thread heap allocation ≤ 16MB

per-thread heap allocation tracking은 JVM 표준 API 부재(`ThreadMXBean.getThreadAllocatedBytes()`는 OpenJDK 확장 — Sun extension 의존). 1차 결정:

- **MVP**: per-thread allocation tracking 폐기. **timeout(100ms)으로 우회** — 16MB 할당하려면 통상 100ms 이상 소요됨
- **Post-MVP** (가칭 ADR 0090+): `com.sun.management.ThreadMXBean.getThreadAllocatedBytes()` 도입 검토. JVM 옵션 `-XX:+EnableJvmciExperimentalAllocationTracking` 또는 OpenJDK 21 표준 정합 시 재활성화

**현재**: heap OOM은 JVM 전체 차원 모니터링(ADR 0044 §3-3 통지 임계 정합)에 의존.

#### 2-2-D. Auto-disable 정책 — timeout/CPU budget 누적 시

| 위반 종류 | 임계 | 행동 |
|---|---|---|
| **Timeout 1회** | 단 1회 발생 | strategy `disabled_reason='TIMEOUT'`로 **auto-disable** + 사용자 통지 (ADR 0044 정합 — `risk.violation.v1` payload `type='STRATEGY_TIMEOUT'`) |
| **CPU budget 초과** | 5회 연속 (평균 > 50ms) | strategy `disabled_reason='CPU_BUDGET_EXCEEDED'`로 auto-disable + 통지 |
| **재시도 정책** | 사용자 수동 re-enable 필요 | UI strategy 페이지 `[다시 활성화]` 버튼 — 사용자가 식 수정 후 명시 활성화 |

**왜 timeout 1회로 즉시 disable**:
자동매매에서 시그널 1건 누락은 사고가 아니지만, 한 strategy의 평가 hang이 다른 strategy를 막으면 **시스템 전체 마비**. 1회 timeout = 식 자체가 한도 위반 가능성 높음 — 보수적으로 즉시 disable 후 사용자 검토.

### 2-3. 실행 격리 — Strategy Eval Thread Pool

#### 2-3-A. 전용 ThreadPool 분리

SignalGenerator는 **strategy 평가 전용 ThreadPool**을 사용. consumer 모듈 다른 책무(reconciliation·notification·order dispatch·KIS WS handler)와 격리.

| 항목 | 값 | 사유 |
|---|---|---|
| **Pool name** | `strategy-eval-pool` | spring `@Bean` |
| **Type** | fixed thread pool | 동시 평가 한도 명확화 |
| **Core/Max threads** | **4** | 일반 사용자 활성 strategy 평균 5~10개 → 동시 평가 4개로 capacity 충분 (대부분 < 100ms 종료) |
| **Queue size** | **100** | 순간 burst 흡수 (KIS WS 다중 종목 tick) |
| **Rejection policy** | `CallerRunsPolicy` | queue 넘침 시 caller thread에서 직접 평가 → 압력 backpressure |
| **Thread name prefix** | `strategy-eval-` | 로그·metric 분리 |

```kotlin
@Configuration
class StrategyEvalPoolConfig {
    @Bean("strategyEvalPool")
    fun strategyEvalPool(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 4
        maxPoolSize = 4
        queueCapacity = 100
        setThreadNamePrefix("strategy-eval-")
        setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
        initialize()
    }
}
```

#### 2-3-B. 다른 ThreadPool과 비격리 검증

다음은 **별도 pool**로 보존 (혼합 금지):
- Kafka listener container thread (consumer default pool)
- HTTP/REST blocking IO (api 모듈 web container thread)
- KIS WS handler thread (Netty event loop)
- `BalanceReconcileJob` `@Scheduled` thread (Spring TaskScheduler default)
- `NotificationDispatcher` thread (ADR 0044 — Kafka listener)
- `NotificationOutboxRetryWorker` `@Scheduled` thread (ADR 0044)

#### 2-3-C. CPU 사용률 모니터링

`strategy-eval-pool` CPU 사용률을 metric 발행 (Micrometer `executor.cpu.usage` 또는 자체 metric).

| 임계 | 행동 |
|---|---|
| 30분 평균 ≥ 60% | log WARN + metric alert |
| 30분 평균 ≥ 80% | `risk.violation.v1` Kafka publish (`type='EVAL_POOL_OVERLOAD'`) → ADR 0044 통지 |

### 2-4. 사용자 trace UX

#### 2-4-A. Strategy 등록 시 (frontend Strategy Builder)

- 등록 성공 시 모달에 §2-1-B `complexity` 표시
- `complexityClass=CRITICAL` 시 빨간 경고 모달 + "이 식은 평가 시간이 한도(100ms)를 초과할 가능성이 높습니다. 등록은 가능하나 timeout 발생 시 자동 비활성화됩니다."
- 백테스트 우선 권장 메시지

#### 2-4-B. Strategy 페이지 (frontend strategy 상세)

- 마지막 N(=20)회 평가 metric 표시: 평균 평가 시간·timeout 발생 횟수·CPU budget 초과 횟수
- timeout 누적 시 빨간 배너: "⚠ 이 전략은 timeout으로 자동 비활성화되었습니다. 사유: ... 마지막 비활성 시각: 2026-05-07 14:32 KST. [식 수정] [다시 활성화]"
- 식 편집기에서 syntax-highlight + AST depth/노드 수 실시간 표시 (등록 전 미리보기)

#### 2-4-C. Notification (ADR 0044 정합)

`risk.violation.v1` payload 추가 type:

| type | trigger |
|---|---|
| `STRATEGY_TIMEOUT` | §2-2-D timeout auto-disable |
| `STRATEGY_CPU_BUDGET_EXCEEDED` | §2-2-D CPU budget 5회 누적 auto-disable |
| `EVAL_POOL_OVERLOAD` | §2-3-C 30분 평균 80% 초과 |

Slack 메시지 포맷 (ADR 0044 §2-3 정합):
```
[magicJar 알림] (P0)
시각: 2026-05-07 14:32:18 KST
이벤트: STRATEGY_TIMEOUT
사용자: 7468**** (마스킹)
사유: Strategy 'golden_cross_5_20' 평가가 100ms timeout 초과 — 자동 비활성화
즉시 액션:
  1. UI Strategy 페이지에서 식 검토
  2. AST depth/노드 수가 한도 근처인지 확인
  3. 단순화 후 [다시 활성화] 토글
▶ Strategy: https://magicjar.local/strategy/12
```

### 2-5. 백테스트 정합

ADR 0022 §2-4 평가 흐름 + 백테스트(T-BT)에 동일 sandbox 적용. 단 timeout 완화:

| 모드 | timeout | rate limit |
|---|---|---|
| Realtime | 100ms | 1회 위반 → auto-disable |
| Backtest | **1000ms** | 누적 ≥ 50회 / 1 백테스트 → **백테스트 abort** + 결과 페이지 사유 표시 |

백테스트 abort 시 결과 페이지:
```
백테스트 중단 — Strategy 평가 timeout 누적 50회 초과
원인 추정: 식이 너무 무거움 (평균 평가 시간 ~1200ms)
권장 액션: AST depth · indicator 호출 수 축소 후 재시도
```

### 2-6. 데이터 영향

| 테이블 | 변경 | Flyway |
|---|---|---|
| `strategy` (V15 — ADR 0022) | 컬럼 추가: `disabled_reason VARCHAR(32)` (TIMEOUT·CPU_BUDGET_EXCEEDED·MANUAL·NULL) · `last_disabled_at TIMESTAMPTZ` · `eval_metric_summary JSONB` (마지막 N회 metric) | **V26** (T-DSL 진입 시) |
| `strategy_eval_metric` (신규) | `id, strategy_id, evaluated_at, eval_time_ms, cpu_time_ms, outcome (OK·TIMEOUT·CPU_EXCEEDED), error_msg` — 마지막 N=100건만 보관 (ring buffer 또는 retention cron) | **V27** |

**Kafka topic**:
- `risk.violation.v1` (ADR 0044 정합 — payload `type` 확장) — 신규 topic 추가 없음

### 2-7. 환경변수

| 변수 | default | 용도 |
|---|---|---|
| `MAGICJAR_DSL_AST_DEPTH_LIMIT` | `32` | §2-1-1 |
| `MAGICJAR_DSL_NODE_COUNT_LIMIT` | `10000` | §2-1-2 |
| `MAGICJAR_DSL_INDICATOR_CALL_LIMIT` | `50` | §2-1-3 |
| `MAGICJAR_DSL_EVAL_TIMEOUT_MS` | `100` | §2-2-A realtime |
| `MAGICJAR_DSL_BACKTEST_TIMEOUT_MS` | `1000` | §2-2-A backtest |
| `MAGICJAR_DSL_CPU_BUDGET_MS` | `50` | §2-2-B |
| `MAGICJAR_DSL_CPU_BUDGET_VIOLATION_THRESHOLD` | `5` | §2-2-D 5회 연속 |
| `MAGICJAR_DSL_EVAL_POOL_SIZE` | `4` | §2-3-A |
| `MAGICJAR_DSL_EVAL_POOL_QUEUE` | `100` | §2-3-A |
| `MAGICJAR_DSL_EVAL_POOL_OVERLOAD_THRESHOLD` | `0.80` | §2-3-C 80% |

`.env.example`에 위 10건 추가. 모두 시크릿 아니므로 환경변수 OK (ADR 0046 §2-5 secret 정책 비위반).

### 2-8. Graceful Shutdown 정합 (ADR 0045)

`strategy-eval-pool`은 SmartLifecycle stop 단계에서 graceful shutdown:

| Phase | 동작 | timeout |
|---|---|---|
| 1 | `pool.shutdown()` — 신규 평가 거부, 진행 중은 완료 대기 | - |
| 2 | `pool.awaitTermination(timeoutMs, MILLISECONDS)` | **2000ms** (max 100ms × queue 100 = 10초 가정의 5분의 1 — 진행 중 평가만 대기) |
| 3 | timeout 초과 시 `pool.shutdownNow()` — interrupt | - |

ADR 0045 §2-1 consumer phase 4(KIS WS 정리) 직전에 합류.

## 3. 결과·영향

### 3-1. 트랙 영향

- **T-DSL (W2)** — KisYamlDslParser에 §2-1 static 검증 의무. AST visitor 패턴으로 depth·노드 수·indicator 호출 수 카운트. complexity 응답 추가.
- **T-SIG (W3)** — JvmStrategyEvaluator를 strategyEvalPool 경유로 호출. timeout/CPU budget 측정. auto-disable 로직 + risk.violation.v1 publish.
- **T-BT (W3)** — 백테스트 timeout 1000ms 적용 + abort 정책.
- **T-RISK (W3)** — `risk.violation.v1` payload type 확장 (`STRATEGY_TIMEOUT`·`STRATEGY_CPU_BUDGET_EXCEEDED`·`EVAL_POOL_OVERLOAD`).
- **T-NOTIFY (W3, ADR 0044)** — `risk.violation.v1` consumer는 type 분기 처리 (메시지 포맷 §2-4-C).
- **T-SB (W2)** — 등록 모달에 complexity 표시. CRITICAL 경고. AST 실시간 미리보기.
- **신규 트랙 X** — DSL Sandbox는 T-DSL·T-SIG·T-BT 합류, 별도 트랙 신설 불필요.

### 3-2. 컨테이너 영향

| 컨테이너 | 영향 |
|---|---|
| `domain` | `DslDepthExceededException`·`DslNodeCountExceededException`·`UnknownDslSymbolException`·`StrategyTimeoutException` sealed class. `StrategyComplexity` data class. `OrderStatus`처럼 `StrategyDisabledReason` enum |
| `api` | `POST /api/strategy` 등록 검증 + complexity 응답. `POST /api/strategy/import` 동일. `GET /api/strategy/{id}/eval-metric` 조회 |
| `consumer` | `strategyEvalPool` Bean. JvmStrategyEvaluator.evaluate를 pool 경유. timeout/CPU 측정. auto-disable + `risk.violation.v1` publish. eval_metric_summary 갱신 |
| `batch` | T-BT 백테스트 동일 pool 사용 (선택) 또는 별도 backtest-eval-pool. 1차는 동일 pool 공유 + 백테스트 timeout만 1000ms로 분기 |
| `frontend` | Strategy 페이지 metric 표시. CRITICAL 경고 모달. 식 편집기 AST 실시간 미리보기. 빨간 배너 + [다시 활성화] |

### 3-3. 운영 영향

| 모니터링 | 임계 |
|---|---|
| `strategy-eval-pool` 평균 CPU | 30분 평균 ≥ 80% → `risk.violation.v1` (§2-3-C) |
| Strategy timeout 발생 빈도 | 1일 ≥ 5회 → 시스템 점검 (식 품질 vs pool 용량) |
| `strategy_eval_metric` retention | N=100건 ring buffer — 별도 cron 폐기 권고 |
| 백테스트 abort 빈도 | 1주 ≥ 3회 → 사용자 식 품질 가이드 검토 |

### 3-4. 보안·격리

- DSL 어휘는 §2-1-A 화이트리스트 only — reflection·system call·JNI·spawn·eval **어휘 차원 차단**
- `strategy-eval-pool` thread는 다른 모듈(notification·order·reconcile)과 격리 — 한 strategy hang이 자동매매 본체 전체를 마비시키지 못함
- timeout 1회 = auto-disable — 사용자가 식 수정 전에는 다시 동작 안 함 (보수적 fail-safe)
- 사용자 본인이라도 잘못 import한 외부 .kis.yaml로 시스템 다운 방지

### 3-5. 트레이드오프

| 검토안 | 채택 여부 | 사유 |
|---|---|---|
| (a) **sandbox 부재 (현재 ADR 0022·0023)** | 폐기 | 한 strategy hang이 시스템 전체 마비 — 자동매매 본체 fitness 미달 |
| (b) **OS 프로세스 격리 (별도 JVM)** | 폐기 | 매 평가마다 IPC 비용 vs 100ms 한도 — overhead 너무 큼. consumer 단일 JVM 가정(ADR 0005) 위반 |
| (c) **SecurityManager 기반 격리** | 폐기 | JDK 17+ deprecated · JDK 21에서 제거 예정 — 미래 호환성 0 |
| (d) **GraalVM polyglot sandbox** | 폐기 | 의존성 추가 + 학습 곡선. magicJar 1차 가치 대비 과다 |
| (e) **Static 검증 + ThreadPool timeout + Whitelist DSL (채택)** | ✅ | 표준 JDK API + Spring `@Bean` 표준. 어휘 차원 차단으로 SecurityManager 불필요. JVM 21 호환. 운영 단순 |
| (f) **Timeout 무제한 (사용자 자율)** | 폐기 | 실수 1회로 자동매매 정지 — 사용자 신뢰 훼손 |

### 3-6. 후속 ADR 후보

- **0090+ (가칭)** — per-thread heap allocation tracking (§2-2-C) — JVM 21 표준 API 정합 시 재활성화
- **0091+ (가칭)** — Custom indicator 등록 (사용자 정의 indicator) — 재귀 차단 (§2-1-5) 구체화 + 별도 sandbox 강화
- **0092+ (가칭)** — DSL syntax v2 (post-MVP) — 자연어 → DSL 번역 (W4 후속) 합류 시 sandbox 정책 재검토

## 4. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | ADR 0047 신규 발행 — 이전 감사 6번 DSL Evaluation Sandbox. Static 검증(AST depth 32·노드 10000·indicator 호출 50·키워드 화이트리스트) + Runtime sandbox(timeout realtime 100ms·backtest 1000ms·CPU budget 50ms·auto-disable on 1회) + ThreadPool 격리(strategy-eval-pool 4 fixed) + 사용자 trace UX(complexity 4단·strategy 페이지 metric·통지) + 백테스트 abort 정책 + ADR 0045 graceful shutdown 정합. (architect)
