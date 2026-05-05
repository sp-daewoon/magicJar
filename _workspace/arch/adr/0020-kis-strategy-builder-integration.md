# ADR 0020: KIS Strategy Builder 흡수 (v2 — 명세 port + AI hook 제거)

- 상태: Proposed (v2 — 1차안 `docs/architecture/adr-0036-*.md` 폐기 후 정식 ADR 디렉토리로 이전)
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M) — 사용자 검토 후 정식 발행
- master §위치: `docs/architecture/master-architecture.md` v2 §1·§2.B.5·§2.B.6·§2.B.7·§2.B.8·§4·§7·§10
- 선행 폐기: 1차안 `docs/architecture/adr-0036-kis-strategy-builder-integration.md` (239줄)

본 ADR은 **사용자 명시 요구한 KIS Strategy Builder 통합** 결정의 v2 정식 발행안이다. 1차안의 "AI Decision hook" 가정·"YAML editor 우선" 가정·"30개 지표 1차" 가정 모두 폐기 후 v2 비전 위에서 재작성.

---

## 1. 맥락

### 1-1. 사용자 요구 (2026-05-02 원문 + 2026-05-03 결정 5건)

> "kis mcp도 mcp이지만 기본적으로 이시스템에서는 https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/README.md 이 기능적인건 무조건 탑재하고 차트 기술까지 들어가있는 시스템이되길 바라고 있어"

KIS Strategy Builder의 기능은 magicJar의 **선택 옵션이 아니라 필수 통합 대상**이다. 단 사용자가 2026-05-03 추가 명시한 결정 5건:

1. 비전 정정 + 정리 작업 범위 OK
2. **Strategy Builder UI = GUI 블록 빌더 채택** (yaml editor 아님). 1차 기본 골격(지표 5종 + 진입/청산 1줄) + yaml export/import 동시. 2차 풀 GUI.
3. **80 지표 일괄 + 지표 source 추상화 인터페이스 명세 의무**. KIS 외에서도 받을 수 있게.
4. **MCP 서버는 W4 안에 포함**. 별도 wave 분리 X.
5. **legacy 다 지워도 됨**. GitHub repo도 release/v0 외 싹 지울 예정. 클린 베이스라인.

### 1-2. KIS Strategy Builder 구성 (1차안 fetch 결과 — 동일)

```
strategy_builder/
├── README.md               (16,940 bytes)
├── kis_auth.py             (25,347 bytes — KIS 인증)
├── pyproject.toml          (642 bytes)
├── start.sh                (1,143 bytes)
├── backend/                (FastAPI — main.py + 7 routers)
├── core/
│   ├── data_fetcher.py
│   ├── indicators.py       (80개 함수, ~50KB)
│   ├── signal.py
│   ├── order_executor.py   (흡수 안 함 — 우리 트랙1이 더 견고)
│   ├── position_manager.py
│   └── websocket_manager.py
├── strategy/               (10 preset 전략)
│   ├── strategy_01_golden_cross.py
│   ├── strategy_02_momentum.py
│   ├── strategy_03_week52_high.py
│   ├── strategy_04_consecutive.py
│   ├── strategy_05_disparity.py
│   ├── strategy_06_breakout_fail.py
│   ├── strategy_07_strong_close.py
│   ├── strategy_08_volatility.py
│   ├── strategy_09_mean_reversion.py
│   └── strategy_10_trend_filter.py
├── strategy_core/
│   ├── registry.py
│   ├── executor.py
│   └── dsl/
│       ├── parser.py       (11 클래스)
│       ├── converter.py
│       └── codegen.py
├── frontend/               (Next.js — 흡수 안 함)
└── examples/
```

`indicators.py` 80개 함수 명세 + 10개 프리셋 + DSL 11 클래스 = magicJar의 흡수 대상.

### 1-3. v2 비전 위에서의 결정 압박

v2 master(§1):
- **시스템 본체 = 룰 기반 quant**. 종목 마스터 + 지표 + DSL + 시그널 → 자동 주문. AI 호출 0.
- **AI = 옵션 분석 보조**. 사용자 트리거 시 코멘트만. 매매 결정권 0.
- **지표 source 추상화 plug-in**. KIS 외에서도 plug-in.
- **Strategy Builder UI = GUI 블록 빌더 1차** (yaml export/import 동시).

1차안의 "AI Decision Layer hook"·"YAML editor 1차"·"30개 1차"는 모두 v2 비전 위반.

---

## 2. 검토 대안 (1차안 D 다시 검토 + v2 정합 보강)

### 대안 A — 전체 흡수 (1차안과 동일 — 폐기)

KIS의 Next.js + FastAPI + Python 다 흡수. 컨테이너 +2~3 → 운영 부담 증가 + magicJar React UI 분열 → **거부**.

### 대안 B — 명세·로직만 port (Kotlin/JVM + React로 재구현)

- 80지표·57패턴·DSL·10프리셋 명세만 받아서 Kotlin·React로 재구현
- KIS 코드 직접 import 0
- `.kis.yaml` 포맷 호환 유지
- **v2 정합** — 도메인 일관성 + GUI 블록 빌더 + source 추상화 모두 가능

### 대안 C — 외부 서비스 호출 (1차안과 동일 — 폐기)

KIS Strategy Builder 별도 운영 + magicJar이 HTTP 호출 → 죽으면 magicJar 마비 + 도메인 분열 → **거부**.

### 대안 D — B + 부분 A (v2 보강 — **채택**)

1차안의 D를 v2 비전에 맞게 보강:

| 영역 | 1차 구현 | 2차 옵션 |
|---|---|---|
| **DSL 파서** | Kotlin (`com.mj.consumer.application.strategy.KisYamlDslParser`) — `.kis.yaml` 호환 | magicJar 확장(다중 TF·시간 필터·종목 그룹·**패턴 조건**) Phase R3 |
| **80지표** | Kotlin (`com.mj.consumer.application.indicator.IndicatorEngine`) **80개 일괄 + `IndicatorSource` plug-in 인터페이스** — `KisStrategyBuilderSource` adapter 등록 | post-W2 TA-Lib·KRX adapter 추가 |
| **57패턴** | Kotlin 자체 (`com.mj.consumer.application.pattern.JvmPatternRecognizer`) — KIS는 패턴 명세만 참조 | (없음) |
| **10 프리셋** | Kotlin (`com.mj.consumer.application.strategy.preset.*`) — 각 strategy_NN_*.py 본문 port | 사용자 `.kis.yaml` import 동적 로드 |
| **Strategy Builder UI** | React (`frontend/src/pages/Strategy/Builder.tsx`) — **GUI 블록 빌더 1차 골격** (지표 5종 + 진입/청산 1줄) + **yaml export/import 동시** | 2차 풀 GUI (80지표 picker·패턴·다중 TF·시간 필터) |
| **백테스트** | JVM (`batch/.../BacktestRunJob.kt`) — 현행 확장 + corp_action adjusted | (해당 없음) |
| **Strategy 실행** | JVM (consumer SignalGenerator) — **AI 호출 0** | **AI Comment 옵션은 W4 분리 트랙 (T16) — 매매 흐름과 분리** |
| **Order 실행** | magicJar 기존 KisOrderSender 그대로 | (KIS Strategy Builder의 order_executor.py 흡수 안 함 — 트랙1 견고) |

**v2 변경 핵심**:
1. **AI hook 제거** — 1차안의 "Strategy 실행 → AI Decision Maker 호출" 가정 폐기. 시그널 → 주문은 룰 평가 직접.
2. **80지표 1차 일괄** (사용자 명시) — 30개 우선 폐기.
3. **Source 추상화 의무** — `IndicatorSource` plug-in 인터페이스. KIS는 1차 import, 후속 TA-Lib·자체·KRX 동일 인터페이스로 plug-in.
4. **GUI 블록 빌더 1차** — 사용자 명시. YAML editor 1차 폐기. 단 yaml export/import는 동시 지원.
5. **TradingView Advanced 폐기** — 1차안에선 "후속 트리거"였으나 v2에선 LWC 확장 + 자체 도형/주석/패턴으로 종결 (별도 ADR 0025).

---

## 3. 결정 — 대안 D (v2 보강) 채택

### 3-1. 구체 운용 규칙

(상세는 §2 대안 D 표 참조)

### 3-2. `IndicatorSource` 추상화 인터페이스 명세 (사용자 결정 #3 정합)

```kotlin
// domain/.../indicator/port/IndicatorSource.kt
interface IndicatorSource {
    /** source 식별자 — "kis-strategy-builder" · "ta-lib" · "native" · "krx" */
    val name: String

    /** 우선순위 — 같은 spec을 지원하는 source 중 선택 기준 (낮을수록 우선) */
    val priority: Int

    /** 본 source가 spec(name·params)을 지원하는지 */
    fun supports(spec: IndicatorSpec): Boolean

    /** 계산 — 단일 source 계산. 실패 시 IndicatorException */
    fun calculate(spec: IndicatorSpec, candles: List<Candle>): IndicatorResult
}

// domain/.../indicator/IndicatorEngine.kt
class IndicatorEngine(
    private val sources: List<IndicatorSource>,
) {
    fun calculate(spec: IndicatorSpec, candles: List<Candle>): IndicatorResult {
        val source = sources
            .filter { it.supports(spec) }
            .minByOrNull { it.priority }
            ?: throw IllegalArgumentException("No source supports ${spec.name}")
        return source.calculate(spec, candles)
    }
}

// 1차 구현체 (W2)
class KisStrategyBuilderSource(...) : IndicatorSource {
    override val name = "kis-strategy-builder"
    override val priority = 100
    // 80지표 일괄 — calc_ma · calc_ema · calc_rsi · calc_macd · ... · calc_augen
}

// 2차 구현체 (post-W2)
class NativeIndicatorSource(...) : IndicatorSource {
    override val name = "native"
    override val priority = 50  // KIS보다 우선
    // magicJar 자체 구현 — KIS 외 추가 지표
}

class TaLibSource(...) : IndicatorSource {
    override val name = "ta-lib"
    override val priority = 200  // 백업
    // TA-Lib JNI 또는 HTTP
}

class KrxSource(...) : IndicatorSource {
    override val name = "krx"
    override val priority = 300
    // KRX 공식 발행 지표 (post-MVP)
}
```

### 3-3. 호환성 보장

- `.kis.yaml` 파일을 magicJar `POST /api/strategy/import?format=kis-yaml`로 업로드하면 그대로 등록·실행 가능.
- magicJar이 만든 strategy도 KIS 호환 모드로 export 가능 (`GET /api/strategy/{id}?format=kis-yaml`). 단 magicJar 확장 키워드(`tf:`·`time:`·`market:`·`pattern:`)는 export 시 주석 처리.
- GUI 블록 빌더에서 만든 전략은 **yaml export 의무** — 사용자가 GUI 결과를 .kis.yaml 다운로드 가능.
- yaml import → GUI 블록 자동 재구성 (유효 키워드 한정).

### 3-4. KIS 코드 라이선스·출처 표기

KIS open-trading-api는 MIT 라이선스. magicJar 흡수 시:

- Kotlin port 클래스 KDoc에 출처 표기:
  ```kotlin
  /**
   * 80 indicator port from KIS Strategy Builder.
   * Source: https://github.com/koreainvestment/open-trading-api
   * License: MIT
   */
  class KisStrategyBuilderSource(...) : IndicatorSource
  ```

### 3-5. AI hook 명시 제거 (v2 핵심)

1차안의 "Strategy 실행 → AI Decision Maker hook" 가정은 **폐기**. v2:

- 시그널 → 주문은 **DSL 룰 평가 직접** (consumer SignalGenerator + Risk Guard chain 6단)
- AI 호출 = W4 별도 트랙 (T16) — **사용자 클릭 시** 종목 코멘트만
- 매매 흐름에 AI 0건. 본 ADR로 명시.

### 3-6. TradingView Advanced 폐기 명시 (v2 신규)

- 1차안의 "ADR 0022 §커스텀 지양 원칙 — TradingView Advanced 후속 트리거" 폐기
- TradingView Advanced 라이선스: 결제만으로 안 됨. Charting Library 무료 신청도 검토 거절 가능. 실현성 낮음.
- v2: **LWC 확장 + 자체 도형/주석/패턴 시각화** (별도 ADR 0025) — Canvas 또는 SVG overlay layer over LWC chart.

---

## 4. 결과·영향

### 4-1. 트랙 영향

본 결정은 다음 트랙의 기반 (tracks-roadmap.md v2):

- **T5 (Indicators)** — 80개 일괄 + `IndicatorSource` plug-in 인터페이스
- **T6 (Patterns)** — 57개 Kotlin 자체
- **T7 (DSL)** — `.kis.yaml` 호환 + magicJar 확장(다중 TF·시간 필터·종목·패턴)
- **T8 (Builder UI)** — GUI 블록 빌더 1차 골격 + yaml export/import
- **T9 (Backtest)** — 자체 엔진 강화
- **T11 (Signal)** — DSL 평가 직접 (AI 호출 0)
- **T15 (LWC 확장)** — 자체 도형/주석/패턴 시각화 (TradingView Advanced 폐기)

### 4-2. 컨테이너 영향

- 신규 컨테이너 0
- frontend 책임 확장: +Strategy Builder UI (T8 GUI 블록 빌더)

### 4-3. 운영 영향

- 사용자가 KIS Strategy Builder를 따로 설치·실행할 필요 0 — magicJar 단일 운영
- 사용자가 KIS GUI에서 만든 .kis.yaml을 import 가능 (호환성 보존)
- magicJar 확장 기능(다중 TF·시간 필터·패턴)은 KIS export 시 주석 처리

### 4-4. 검증·정확성

- **80개 지표 1차 구현 시 단위 테스트 fixture 기반 정확성 검증** (T5 R3 게이트). 표준 입력에 대한 expected output 비교, drift 0.001% 이상 0건 운영.
- 백테스트 결과는 **KIS Strategy Builder의 examples/run_all_strategies.py와 동일 시드·기간·데이터로 재현 비교** (T9 R3 게이트).

### 4-5. AI 호출 차단 검증 (v2 핵심)

- T5·T6·T7·T8·T9·T10·T11·T12·T14 트랙의 코드에서 AI provider import·호출 0 검증 (R3 진입 시 architect 1회 grep)
- W4 트랙 진입 시점에만 AI provider 코드 추가 OK

---

## 5. 후속 ADR 후보

본 ADR로 결정 못 한 세부는 후속 ADR로:

- **T-PAT 진입 시 ADR 발행** — 57 패턴 정확 명세 + Kotlin 자체 구현 표준
- **T-BT 진입 시 ADR 발행** — 백테스트 표준 (룩어헤드·세금·수수료·슬리피지)
- **T-SC 진입 시 ADR 발행** — Screener 표준
- **T-PERF 진입 시 ADR 발행** — Performance 표준

---

## 6. 사용자 결정 필요

본 ADR draft 검토 시 사용자 확정:

1. **v2 대안 D (B + 부분 A) 채택 OK?** — 1차안 D와 동일 골격 + AI hook 제거 + 80개 일괄 + GUI 1차 + Source 추상화
2. **`IndicatorSource` 1차 plug-in** — KisStrategyBuilderSource 1차만 / NativeSource 자체 구현 동시?
3. **GUI 블록 빌더 1차 깊이** — 지표 5종 골격 / 풀 80지표 picker?
4. **TradingView Advanced 폐기 OK?** — 사용자 명시 정합. ADR 0025에서 정식

---

## 7. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-03 (v1) | 초안 — `docs/architecture/adr-0036-*.md` 폐기 대상 |
| 2026-05-03 (v2) | **풀 재작성** — AI hook 제거 + 80개 일괄 + GUI 1차 + Source 추상화 + TradingView 폐기 명시. 정식 ADR 디렉토리(`_workspace/arch/adr/`)로 이전 (architect, opus 4.7 1M) |
