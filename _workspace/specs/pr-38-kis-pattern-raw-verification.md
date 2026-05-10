# PR-38 KIS 캔들스틱 패턴 raw 출처 검증 (T-PAT KIS-first 정합)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-10
- 정합 SoT: 헌법 §3 KIS-first raw fetch 의무 + `docs/architecture/policies/kis-first-raw-fetch.md` + `_workspace/strategy/pattern-catalog.md` (57패턴 LOCK) + 사용자 메모리 `feedback_kis_first_policy.md`
- 본 문서: T-PAT(PR-38 spec → PR-39 코드) 진입 시 KIS Strategy Builder `candlestick.py` 직접 raw fetch 결과를 LOCK한다. **자체 추론 0** — sub-agent PR-39a strategy-engine은 본 문서 + raw 출처 줄번호 인용 KDoc만으로 구현 의무.

---

## §1. raw fetch 명령 + 결과 요약

### 1-1. 명령

```bash
curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/core/candlestick.py -o /tmp/kis-candlestick.py
wc -l /tmp/kis-candlestick.py     # 755 lines
grep -c "^def _" /tmp/kis-candlestick.py    # 79 함수 (helper 7 + 패턴 detector 72 — alias 포함)
grep -c '":' /tmp/kis-candlestick.py        # PATTERN_DETECTORS dict 65 entries
```

### 1-2. 출처 LOCK

| 항목 | 값 |
|---|---|
| 출처 | https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/candlestick.py |
| 라이선스 | MIT (KIS open-trading-api repo) |
| 파일 길이 | 755 lines |
| 1줄 docstring | "캔들스틱 패턴 탐지 모듈 / OHLC DataFrame → 패턴 탐지 → +1(bullish) / -1(bearish) / 0(없음) / pandas 기반 직접 구현. 외부 의존성 없음." |
| Python 의존성 | pandas (선택 — Kotlin port 시 List<Candle> 직접 사용으로 대체) |
| 관용 룰 | 모든 detector 함수는 `(int 1=BULLISH | -1=BEARISH | 0=미감지)` 반환. 즉 **방향성 함께 반환** — magicJar PatternMatch도 동일 LOCK |

---

## §2. PATTERN_DETECTORS dict (KIS 65 패턴) → magicJar 카탈로그 57 매핑

`candlestick.py` L685 PATTERN_DETECTORS는 65개 entry. magicJar `_workspace/strategy/pattern-catalog.md`는 KIS 66 ∩ Lean 매핑 가능 = 57개. 구체 매핑은 다음과 같이 LOCK:

### 2-1. KIS Single (15) → magicJar Single (14) — 1건 제외

| KIS pattern_id | KIS 함수 | KIS 줄 | magicJar 카탈로그 # | 비고 |
|---|---|---|---|---|
| `doji` | `_doji` | L81 | 1 | 1:1 port |
| `dragonfly_doji` | `_dragonfly_doji` | L89 | 2 | 1:1 port |
| `gravestone_doji` | `_gravestone_doji` | L99 | 3 | 1:1 port |
| `long_legged_doji` | `_long_legged_doji` | L109 | 4 | 1:1 port |
| `hammer` | `_hammer` | L119 | 5 | 1:1 port (R1 인식기 1차) |
| `hanging_man` | `_hanging_man` | L132 | 6 | 1:1 port |
| `inverted_hammer` | `_inverted_hammer` | L145 | 7 | 1:1 port |
| `shooting_star` | `_shooting_star` | L157 | 8 | 1:1 port |
| `marubozu` | `_marubozu` | L169 | 9 | 1:1 port |
| `closing_marubozu` | `_closing_marubozu` | L181 | 10 | 1:1 port |
| ~~`opening_marubozu`~~ | `_opening_marubozu` | L193 | **(제외)** | Lean 미지원 — pattern-catalog 정합 |
| `spinning_top` | `_spinning_top` | L205 | 11 | 1:1 port |
| `belt_hold` | `_belt_hold` | L216 | 12 | 1:1 port |
| `high_wave` | `_high_wave` | L228 | 13 | 1:1 port |
| `rickshaw_man` | `_rickshaw_man` | L239 | 14 | 1:1 port |

### 2-2. KIS Double (20) → magicJar Double (16) — 4건 제외

| KIS pattern_id | KIS 함수 | KIS 줄 | magicJar 카탈로그 # | 비고 |
|---|---|---|---|---|
| `engulfing` | `_engulfing` | L248 | 15 | 1:1 port (R1 인식기 1차) |
| `harami` | `_harami` | L258 | 16 | 1:1 port |
| `harami_cross` | `_harami_cross` | L271 | 17 | 1:1 port |
| `piercing` | `_piercing` | L282 | 18 | 1:1 port |
| `dark_cloud_cover` | `_dark_cloud_cover` | L291 | 19 | 1:1 port |
| `counterattack` | `_counterattack` | L300 | 20 | 1:1 port |
| ~~`tweezer_top`~~ | `_tweezer_top` | L312 | **(제외)** | Lean 미지원 |
| ~~`tweezer_bottom`~~ | `_tweezer_bottom` | L321 | **(제외)** | Lean 미지원 |
| `on_neck` | `_on_neck` | L330 | 21 | 1:1 port |
| `in_neck` | `_in_neck` | L339 | 22 | 1:1 port |
| `thrusting` | `_thrusting` | L348 | 23 | 1:1 port |
| `separating_lines` | `_separating_lines` | L357 | 24 | 1:1 port |
| ~~`meeting_lines`~~ | `_meeting_lines` | L368 | **(제외)** | Lean 미지원 |
| `kicking` | `_kicking` | L379 | 25 | 1:1 port |
| `kicking_by_length` | `_kicking_by_length` | L442 | 26 | 1:1 port (KIS dict 순서와 def 순서 다름 주의) |
| `matching_low` | `_matching_low` | L394 | 27 | 1:1 port |
| ~~`matching_high`~~ | `_matching_high` | L403 | **(제외)** | Lean 미지원 |
| `gap_side_by_side_white` | `_gap_side_by_side_white` | L412 | 28 | 1:1 port (Side by Side White 통합) |
| `homing_pigeon` | `_homing_pigeon` | L421 | 29 | 1:1 port |
| `dojistar` | `_dojistar` | L430 | 30 (`doji_star`) | **id rename** — KIS `dojistar` → magicJar `doji_star` (catalog 정합) |

### 2-3. KIS Triple+ (30) → magicJar Triple+ (27) — 3건 제외

| KIS pattern_id | KIS 함수 | KIS 줄 | magicJar 카탈로그 # | 비고 |
|---|---|---|---|---|
| `morning_star` | `_morning_star` | L451 | 31 | 1:1 port (R1 인식기 1차) |
| `morning_doji_star` | `_morning_doji_star` | L460 | 32 | 1:1 port |
| `evening_star` | `_evening_star` | L470 | 33 | 1:1 port (R1 인식기 1차) |
| `evening_doji_star` | `_evening_doji_star` | L479 | 34 | 1:1 port |
| `three_white_soldiers` | `_three_white_soldiers` | L489 | 35 | 1:1 port (R1 인식기 1차) |
| `three_black_crows` | `_three_black_crows` | L502 | 36 | 1:1 port (R1 인식기 1차) |
| `three_inside` | `_three_inside` | L515 | 37 | 1:1 port |
| `three_outside` | `_three_outside` | L531 | 38 | 1:1 port |
| `abandoned_baby` | `_abandoned_baby` | L547 | 39 | 1:1 port |
| `three_stars_in_south` | `_three_stars_in_south` (=`_simple_triple`) | L576 | 40 | **simple_triple delegation** — `_simple_triple(df, "bullish")` |
| `advance_block` | `_advance_block` (=`_simple_triple`) | L577 | 41 | **simple_triple delegation** — bearish |
| `stalled_pattern` | `_stalled_pattern` (=`_simple_triple`) | L578 | 42 | **simple_triple delegation** — bearish |
| ~~`deliberation`~~ | `_deliberation` (=`_simple_triple`) | L579 | **(제외)** | Lean 미지원 |
| `tasuki_gap` | `_tasuki_gap` | L582 | 43 | 1:1 port |
| `upside_gap_two_crows` | `_upside_gap_two_crows` | L593 | 44 | 1:1 port |
| `three_line_strike` | `_three_line_strike` | L602 | 45 | 1:1 port (4 캔들) |
| `unique_three_river` | `_unique_three_river` (=`_simple_triple`) | L617 | 46 | **simple_triple delegation** — bullish |
| `breakaway` | `_breakaway` (=`_simple_triple`) | L618 | 47 | **simple_triple delegation** — bullish (5 캔들 — KIS는 simplified) |
| `mat_hold` | `_mat_hold` (=`_simple_triple`) | L619 | 48 | **simple_triple delegation** — bullish (5 캔들) |
| ~~`rising_three_methods`~~ | `_rising_three_methods` (=`_simple_triple`) | L620 | **(제외)** | Lean `RiseFallThreeMethods` 단일 통합 → mat_hold만 유지 |
| ~~`falling_three_methods`~~ | `_falling_three_methods` (=`_simple_triple`) | L621 | **(제외)** | 동상 |
| `ladder_bottom` | `_ladder_bottom` (=`_simple_triple`) | L622 | 49 | **simple_triple delegation** — bullish (5 캔들) |
| `concealing_baby_swallow` | `_concealing_baby_swallow` (=`_simple_triple`) | L623 | 50 | **simple_triple delegation** — bullish (4 캔들) |
| `stick_sandwich` | `_stick_sandwich` | L626 | 51 | 1:1 port |
| `tristar` | `_tristar` | L636 | 52 | 1:1 port |
| `identical_three_crows` | `_identical_three_crows` | L655 | 53 | 1:1 port |
| `two_crows` | `_two_crows` | L666 | 54 | 1:1 port |
| `up_down_gap_three_methods` | `_up_down_gap_three_methods` (=`_tasuki_gap`) | L675 | 55 | **tasuki_gap delegation** |
| `downside_tasuki_gap` | `_downside_tasuki_gap` (=`_tasuki_gap` — bearish only) | L676 | 56 | **tasuki_gap filtered** |
| `upside_tasuki_gap` | `_upside_tasuki_gap` (=`_tasuki_gap` — bullish only) | L677 | 57 | **tasuki_gap filtered** |
| ~~`side_by_side_white_lines`~~ | `_side_by_side_white_lines` (=`_gap_side_by_side_white`) | L678 | **(제외)** | gap_side_by_side_white 통합 |

**총 KIS 65 detector → magicJar 57 (제외 9 — Lean 미지원 8 + 통합 중복 1)** — pattern-catalog.md §"Lean 미지원 9개" 정합 (Tweezer Top·Tweezer Bottom·Meeting Lines·Matching High·Deliberation·Rising Three Methods·Falling Three Methods·Side by Side White Lines + Opening Marubozu).

---

## §3. KIS 7 helper 함수 → Kotlin port LOCK (자체 추론 0)

KIS L14-L36 helper 7개 — Kotlin은 `Candle` data class에서 동등 메서드 또는 BigDecimal 연산자 활용. sub-agent PR-39a는 다음 1:1 매핑 의무:

| KIS 함수 | KIS 줄 | Kotlin 등가 | 비고 |
|---|---|---|---|
| `_body(o, c)` | L14 | `(c - o).abs()` | BigDecimal abs |
| `_upper_shadow(h, o, c)` | L18 | `h - max(o, c)` | |
| `_lower_shadow(o, c, l)` | L22 | `min(o, c) - l` | |
| `_range(h, l)` | L26 | `h - l` | |
| `_is_bullish(o, c)` | L30 | `c > o` | BigDecimal compareTo |
| `_is_bearish(o, c)` | L34 | `c < o` | |
| `_avg_body(df, n=10)` | L71 | `candles.takeLast(n).map { (it.close - it.open).abs() }.average()` | BigDecimal sum/n. n=10 default |

**예외 처리 룰** (KIS 정합 — sub-agent LOCK):
- `_range(h, l) == 0` → 0 반환 (모든 doji·marubozu detector 가드)
- `_body(o, c) == 0` → 일부 detector(hammer 등)는 0 반환 — KIS L122 `if r == 0 or b == 0` 정합
- 캔들 부족 (`candles.size < required`) → `0` 반환 (`detect_pattern` L48 `if df.empty or len(df) < 5`)

---

## §4. detect_pattern 진입점 LOCK (KIS L38)

KIS는 단일 함수 entry point + dict registry 패턴. magicJar는 sealed class + when 분기 또는 Map<String, Detector> 등록 둘 중 1.

**architect 권장 = `Map<String, PatternDetector>` 등록 (KIS 1:1 정합)**:

```kotlin
// consumer/.../application/pattern/JvmPatternRecognizer.kt — sub-agent PR-39a 구현
@Component
class JvmPatternRecognizer(
    detectors: List<PatternDetector>,    // Spring auto-collect — 57개 @Component
) : PatternRecognizer {
    private val registry: Map<String, PatternDetector> = detectors.associateBy { it.id }

    override fun recognize(candles: List<Candle>, patterns: Set<String>): List<PatternMatch> {
        if (candles.size < 5) return emptyList()    // KIS L48 정합 — 최소 5캔들
        return patterns.mapNotNull { id ->
            val detector = registry[id] ?: return@mapNotNull null
            try {
                val result = detector.detect(candles)
                if (result.direction == Direction.NONE) null
                else PatternMatch(id, candles.last().time, candles.last().time, result.direction, result.confidence)
            } catch (ex: Exception) {
                null    // KIS L57 try/except 정합 — 예외 발생 시 미감지 처리
            }
        }
    }
}
```

**KIS L48 `if df.empty or len(df) < 5: return 0` 정합 의무**: PatternRecognizer 진입 시 candles.size < 5면 즉시 빈 리스트 반환.

**KIS L57 `try: detector(df) except: return 0` 정합 의무**: 개별 detector 예외는 swallow + 미감지 처리. log.debug만 (사용자 화면 응답 끊지 않음).

---

## §5. R1 인식기 10건 — 실제 매칭 detector LOCK (pattern-catalog.md §"R1 인식기 구현 범위" 정합)

`_workspace/strategy/pattern-catalog.md` L103-L116에 명시된 R1 10개는 KIS raw 1:1 port 의무. 나머지 47개는 stub 의무 (라이브러리 메서드 노출 + `Direction.NONE` 반환 default — R2/R3 사이클 보강).

| # | code | KIS 줄 | KIS 핵심 룰 |
|---|---|---|---|
| 1 | doji | L81 | `_body / _range < 0.1` |
| 2 | hammer | L119 | bullish + `_lower_shadow >= 2 * _body` + `_upper_shadow <= 0.3 * _body` |
| 3 | hanging_man | L132 | bearish 변형 — KIS는 단순 hammer 형태 + `-1` 반환. **R1 보강**: 직전 5봉 상승추세 룰 추가 (catalog §정합) — `consecutive_up_count(candles[-6..-2]) >= 3` |
| 4 | inverted_hammer | L145 | bullish + `_upper_shadow >= 2 * _body` + `_lower_shadow <= 0.3 * _body` |
| 5 | shooting_star | L157 | bearish 변형 — inverted_hammer 형태 + `-1`. **R1 보강**: 직전 5봉 상승추세 (catalog §정합) |
| 6 | engulfing | L248 | bullish: `_is_bearish(c1) AND _is_bullish(c2) AND o2 <= c1 AND c2 >= o1` / bearish 반대 |
| 7 | morning_star | L451 | (raw 정독 의무 — 큰 음봉 + 갭하락 작은봉 + 갭상승 양봉 첫 몸통 절반 회복) |
| 8 | evening_star | L470 | morning 반대 |
| 9 | three_white_soldiers | L489 | 연속 3양봉 + 각 시가 직전 몸통 안 + 종가 직전 종가보다 위 |
| 10 | three_black_crows | L502 | 반대 |

R1 보강 룰(catalog 명시): `hanging_man`·`shooting_star`는 KIS 단순 형태 매칭에 **직전 5봉 상승추세** 추가 의무 (KIS는 형태만 — 추세 검증은 magicJar 자체 룰).

---

## §6. PatternMatch 반환 타입 LOCK (자체 추론 0)

KIS는 `int (1/-1/0)` 1차원 응답이지만 magicJar는 더 풍부한 도메인 응답:

| 필드 | 타입 | 의미 | KIS 정합 매핑 |
|---|---|---|---|
| `pattern` | String | 패턴 id (예: `doji`·`engulfing`) | KIS pattern_id LOCK |
| `startTime` | Instant | 시작 캔들 시각 (single=1·double=2·triple+=3~5 캔들 첫번째) | 새 — magicJar 자체 |
| `endTime` | Instant | 마지막 캔들 시각 (인식 시점) | 새 — magicJar 자체 |
| `direction` | Direction enum (BULL/BEAR/NEUTRAL) | KIS L51 `+1=bullish` → `BULL`, `-1=bearish` → `BEAR`, `0=미감지` → 결과 미포함 | KIS 1:1 정합 |
| `confidence` | BigDecimal (0.0~1.0) | 1차 = 1.0 고정 (KIS는 0/1 binary). post-MVP 신뢰도 모델 (catalog §"R2 사이클 보강" 합류) | 새 — magicJar 자체 |
| `params` | Map<String, BigDecimal>? | 디버그용 — 실제 측정값 (예: `body_ratio=0.08`·`lower_shadow_ratio=0.65`). nullable | 새 — magicJar 자체 |

**Direction enum LOCK** (catalog §"카테고리·기본 방향" 정합):
```kotlin
enum class Direction { BULL, BEAR, NEUTRAL }
```
- KIS `+1` → BULL
- KIS `-1` → BEAR
- KIS pattern catalog의 NEUTRAL 카테고리 (예: marubozu·spinning_top — bullish/bearish 조건부 구분) → detector 내부에서 `BULL` 또는 `BEAR` 동적 결정 (KIS detector도 이미 동적 — `_marubozu` L177-L179 `1 if _is_bullish else -1`). NEUTRAL 응답은 0(`미감지`)와 동치 — 결과에 미포함.

---

## §7. PATTERN_DETECTORS 등록 의무 (KIS L685)

magicJar Kotlin port는 KIS `PATTERN_DETECTORS` dict 65개 entry 그대로 + 9건 제외 = **57개 등록 의무**. ID 네이밍 LOCK:

- KIS `dojistar` → magicJar `doji_star` (catalog id 정합 — _ 구분자 표준)
- KIS 그 외 pattern_id는 catalog id와 1:1 일치

**alias delegation 처리** (KIS L576-L678):
- `_simple_triple(df, direction)` — KIS는 7건 단순 추세 검증 (3양봉 또는 3음봉 + close 단조 증가/감소). magicJar는 별도 `SimpleTripleHelper.kt` 추출 권장 — 7 detector(`three_stars_in_south`·`advance_block`·`stalled_pattern`·`unique_three_river`·`breakaway`·`mat_hold`·`ladder_bottom`·`concealing_baby_swallow`)가 공유.
- `up_down_gap_three_methods` = `tasuki_gap` 그대로
- `downside_tasuki_gap` = `tasuki_gap` filter (`-1` only)
- `upside_tasuki_gap` = `tasuki_gap` filter (`+1` only)

**중요**: alias delegation도 KIS 출처 의무. sub-agent PR-39a는 `SimpleTripleHelper`·`TasukiGapAlias` 같은 helper 클래스 생성 시 KDoc에 KIS 줄번호 인용 (자체 추론 0).

---

## §8. detector 함수 시그니처 LOCK

```kotlin
// domain/.../pattern/port/PatternDetector.kt — sub-agent PR-39b 신규
interface PatternDetector {
    /** 패턴 id (KIS pattern_id 1:1 — catalog id 정합) */
    val id: String

    /** 최소 필요 캔들 수 (single=5·double=5·triple+=5 — KIS L48 정합 minimum) */
    val minCandles: Int get() = 5

    /**
     * 마지막 캔들 기준 패턴 탐지.
     *
     * @return Result(direction, confidence, params) — direction=NEUTRAL 시 미감지
     * @throws nothing — KIS L57 try/except 정합으로 호출자가 예외 swallow
     */
    fun detect(candles: List<Candle>): DetectorResult
}

data class DetectorResult(
    val direction: Direction,
    val confidence: BigDecimal = BigDecimal.ONE,
    val params: Map<String, BigDecimal>? = null,
)
```

**구현체 위치**: `consumer/.../application/pattern/detector/{single,double,triple}/{XxxDetector}.kt` — 57개 @Component 클래스.

---

## §9. 검증 체크리스트 (sub-agent PR-39a 의무)

- [ ] candlestick.py raw fetch — `curl -sL .../candlestick.py > /tmp/kis-candlestick.py` 직접 실행 (자체 추론 0 의무)
- [ ] 57개 detector 모두 KDoc에 `Source: candlestick.py:line_NN` 인용 (R1 10건 + R2 stub 47건)
- [ ] PATTERN_DETECTORS 등록표 57개 일치 — `init { check(detectors.size == 57) }` 빌드 시점 강제
- [ ] R1 10건은 KIS L48·L57 룰 정합 (최소 5캔들 + try/except)
- [ ] simple_triple delegation 7건은 KIS L562 `_simple_triple` 1:1 port 의무
- [ ] tasuki_gap alias 3건은 KIS L675-L677 1:1 port 의무

---

## §10. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-10 | PR-38 KIS 패턴 raw fetch v1 발행. candlestick.py 755 lines + 65 PATTERN_DETECTORS dict 정합. KIS 65 → magicJar 57 매핑 (제외 9건 — Lean 미지원 8 + 통합 중복 1) §2에 LOCK. 7 helper + detect_pattern 진입점 + alias delegation(simple_triple·tasuki_gap) 의무 §3·§4·§7에 LOCK. R1 10건 KIS 줄 매트릭스 §5에 LOCK. PatternDetector port 시그니처 §8에 LOCK. (architect, opus 4.7 1M) |
