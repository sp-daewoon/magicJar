# 캔들스틱 패턴 카탈로그 (57개, KIS Strategy Builder 정합)

작성: 2026-05-02 · strategy-engine · T-PAT R1

## 출처

- **KIS Strategy Builder**: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/candlestick.py — 66 패턴 (single 15 + double 20 + triple+ 31)
- **QuantConnect Lean**: https://github.com/QuantConnect/Lean/tree/master/Indicators/CandlestickPatterns — 61 클래스
- 결정 규칙: KIS 66 ∩ Lean 매핑 가능 = 57

## Lean 미지원 9개 (카탈로그 제외)

| KIS 패턴 | 사유 |
|---------|------|
| Opening Marubozu | Lean은 `Marubozu`/`ClosingMarubozu`만 보유, Opening 변형 없음 |
| Tweezer Top | Lean 미보유 |
| Tweezer Bottom | Lean 미보유 |
| Meeting Lines | Lean 미보유 (Counterattack과 유사하나 별개 정의) |
| Matching High | Lean은 `MatchingLow`만 보유 |
| Deliberation | Lean 미보유 (Stalled Pattern·Advance Block과 유사) |
| Rising Three Methods | Lean `RiseFallThreeMethods` 단일 클래스로 통합 → mat_hold만 유지 |
| Falling Three Methods | Lean `RiseFallThreeMethods` 단일 클래스로 통합 → mat_hold만 유지 |
| Side by Side White Lines | Lean `GapSideBySideWhite` 단일 클래스로 통합 → gap_side_by_side_white만 유지 |

`RiseFallThreeMethods`는 KIS의 Mat Hold와 형태 중복, `GapSideBySideWhite`는 Side by Side White Lines와 형태 중복. 두 통합 항목은 KIS 측의 단일 표현(`mat_hold`, `gap_side_by_side_white`)으로 카탈로그에 유지한다.

## 카탈로그 (57)

### Single Candle (14개)

| # | code | 영문 | 한글 | 카테고리 | 기본 방향 |
|---|------|------|------|---------|----------|
| 1 | doji | Doji | 도지 | INDECISION | NEUTRAL |
| 2 | dragonfly_doji | Dragonfly Doji | 잠자리형 도지 | BULLISH_REVERSAL | BULL |
| 3 | gravestone_doji | Gravestone Doji | 비석형 도지 | BEARISH_REVERSAL | BEAR |
| 4 | long_legged_doji | Long Legged Doji | 롱 레그 도지 | INDECISION | NEUTRAL |
| 5 | hammer | Hammer | 해머 | BULLISH_REVERSAL | BULL |
| 6 | hanging_man | Hanging Man | 매달린 사람 | BEARISH_REVERSAL | BEAR |
| 7 | inverted_hammer | Inverted Hammer | 역해머 | BULLISH_REVERSAL | BULL |
| 8 | shooting_star | Shooting Star | 슈팅스타 | BEARISH_REVERSAL | BEAR |
| 9 | marubozu | Marubozu | 마루보주 | REVERSAL_DIRECTIONAL | NEUTRAL |
| 10 | closing_marubozu | Closing Marubozu | 종가 마루보주 | REVERSAL_DIRECTIONAL | NEUTRAL |
| 11 | spinning_top | Spinning Top | 팽이 | INDECISION | NEUTRAL |
| 12 | belt_hold | Belt Hold | 벨트홀드 | REVERSAL_DIRECTIONAL | NEUTRAL |
| 13 | high_wave | High Wave | 높은 파도 | INDECISION | NEUTRAL |
| 14 | rickshaw_man | Rickshaw Man | 인력거꾼 | INDECISION | NEUTRAL |

### Double Candle (16개)

| # | code | 영문 | 한글 | 카테고리 | 기본 방향 |
|---|------|------|------|---------|----------|
| 15 | engulfing | Engulfing | 잉걸핑 | REVERSAL_DIRECTIONAL | NEUTRAL |
| 16 | harami | Harami | 잉태형 | REVERSAL_DIRECTIONAL | NEUTRAL |
| 17 | harami_cross | Harami Cross | 잉태형 도지 | REVERSAL_DIRECTIONAL | NEUTRAL |
| 18 | piercing | Piercing | 관통형 | BULLISH_REVERSAL | BULL |
| 19 | dark_cloud_cover | Dark Cloud Cover | 먹구름형 | BEARISH_REVERSAL | BEAR |
| 20 | counterattack | Counterattack | 반격형 | REVERSAL_DIRECTIONAL | NEUTRAL |
| 21 | on_neck | On Neck | 온넥 | BEARISH_CONTINUATION | BEAR |
| 22 | in_neck | In Neck | 인넥 | BEARISH_CONTINUATION | BEAR |
| 23 | thrusting | Thrusting | 관통(부분)형 | BEARISH_CONTINUATION | BEAR |
| 24 | separating_lines | Separating Lines | 분리선 | REVERSAL_DIRECTIONAL | NEUTRAL |
| 25 | kicking | Kicking | 킥킹 | REVERSAL_DIRECTIONAL | NEUTRAL |
| 26 | kicking_by_length | Kicking by Length | 킥킹 (장신) | REVERSAL_DIRECTIONAL | NEUTRAL |
| 27 | matching_low | Matching Low | 동일 저가 | BULLISH_CONTINUATION | BULL |
| 28 | gap_side_by_side_white | Gap Side by Side White | 갭 나란히 양봉 | BULLISH_REVERSAL | BULL |
| 29 | homing_pigeon | Homing Pigeon | 귀가비둘기 | BULLISH_REVERSAL | BULL |
| 30 | doji_star | Doji Star | 도지스타 | REVERSAL_DIRECTIONAL | NEUTRAL |

### Triple+ Candle (27개)

| # | code | 영문 | 한글 | 카테고리 | 캔들수 | 기본 방향 |
|---|------|------|------|---------|------|----------|
| 31 | morning_star | Morning Star | 샛별 | BULLISH_REVERSAL | 3 | BULL |
| 32 | morning_doji_star | Morning Doji Star | 샛별 도지 | BULLISH_REVERSAL | 3 | BULL |
| 33 | evening_star | Evening Star | 저녁별 | BEARISH_REVERSAL | 3 | BEAR |
| 34 | evening_doji_star | Evening Doji Star | 저녁별 도지 | BEARISH_REVERSAL | 3 | BEAR |
| 35 | three_white_soldiers | Three White Soldiers | 세 흰병사 | BULLISH_REVERSAL | 3 | BULL |
| 36 | three_black_crows | Three Black Crows | 세 까마귀 | BEARISH_REVERSAL | 3 | BEAR |
| 37 | three_inside | Three Inside | 삼중 인사이드 | REVERSAL_DIRECTIONAL | 3 | NEUTRAL |
| 38 | three_outside | Three Outside | 삼중 아웃사이드 | REVERSAL_DIRECTIONAL | 3 | NEUTRAL |
| 39 | abandoned_baby | Abandoned Baby | 버려진 아기 | REVERSAL_DIRECTIONAL | 3 | NEUTRAL |
| 40 | three_stars_in_south | Three Stars in South | 남쪽의 세 별 | BULLISH_REVERSAL | 3 | BULL |
| 41 | advance_block | Advance Block | 어드밴스 블록 | BEARISH_REVERSAL | 3 | BEAR |
| 42 | stalled_pattern | Stalled Pattern | 정체형 | BEARISH_REVERSAL | 3 | BEAR |
| 43 | tasuki_gap | Tasuki Gap | 타스키 갭 | REVERSAL_DIRECTIONAL | 3 | NEUTRAL |
| 44 | upside_gap_two_crows | Upside Gap Two Crows | 갭상승 두 까마귀 | BEARISH_REVERSAL | 3 | BEAR |
| 45 | three_line_strike | Three Line Strike | 삼선 스트라이크 | REVERSAL_DIRECTIONAL | 4 | NEUTRAL |
| 46 | unique_three_river | Unique Three River | 유니크 세 강 | BULLISH_REVERSAL | 3 | BULL |
| 47 | breakaway | Breakaway | 브레이크어웨이 | BULLISH_REVERSAL | 5 | BULL |
| 48 | mat_hold | Mat Hold | 매트홀드 | BULLISH_CONTINUATION | 5 | BULL |
| 49 | ladder_bottom | Ladder Bottom | 사다리 바닥 | BULLISH_REVERSAL | 5 | BULL |
| 50 | concealing_baby_swallow | Concealing Baby Swallow | 은폐된 베이비 스왈로 | BULLISH_REVERSAL | 4 | BULL |
| 51 | stick_sandwich | Stick Sandwich | 스틱 샌드위치 | BULLISH_REVERSAL | 3 | BULL |
| 52 | tristar | Tristar | 트라이스타 | REVERSAL_DIRECTIONAL | 3 | NEUTRAL |
| 53 | identical_three_crows | Identical Three Crows | 동일 세 까마귀 | BEARISH_REVERSAL | 3 | BEAR |
| 54 | two_crows | Two Crows | 두 까마귀 | BEARISH_REVERSAL | 3 | BEAR |
| 55 | up_down_gap_three_methods | Up Down Gap Three Methods | 갭 삼법 | REVERSAL_DIRECTIONAL | 3 | NEUTRAL |
| 56 | downside_tasuki_gap | Downside Tasuki Gap | 하락 타스키 갭 | BEARISH_CONTINUATION | 3 | BEAR |
| 57 | upside_tasuki_gap | Upside Tasuki Gap | 상승 타스키 갭 | BULLISH_CONTINUATION | 3 | BULL |

> 합계 정확히 **57개** (Single 14 + Double 16 + Triple+ 27). PatternCatalog 코드의 `init { check(ALL.size == 57) }`로 빌드 시점에 강제됨.

## R1 인식기 구현 범위 (대표 10)

PatternRecognizer가 R1에서 정확히 매칭하는 패턴:

1. **doji** — |close-open|/range < 10%
2. **hammer** — 양봉, 하단꼬리 ≥ 2*몸통, 상단꼬리 ≤ 0.3*몸통
3. **hanging_man** — hammer 형태 + 직전 5봉 상승추세
4. **inverted_hammer** — 양봉, 상단꼬리 ≥ 2*몸통, 하단꼬리 ≤ 0.3*몸통
5. **shooting_star** — inverted_hammer 형태 + 직전 5봉 상승추세
6. **engulfing** — 두 캔들 색 반대 + 두번째 몸통이 첫번째 몸통을 완전 포함
7. **morning_star** — 큰 음봉 + 갭하락 작은봉 + 갭상승 양봉(첫 몸통 절반 이상 회복)
8. **evening_star** — 큰 양봉 + 갭상승 작은봉 + 갭하락 음봉(첫 몸통 절반 이상 잠식)
9. **three_white_soldiers** — 연속 3양봉, 각 시가가 직전 몸통 안, 종가가 직전 종가보다 높음
10. **three_black_crows** — 연속 3음봉, 각 시가가 직전 몸통 안, 종가가 직전 종가보다 낮음

R2(다음 사이클) 보강 대상: 47 stub. 메소드는 노출되지만 매칭 없이 빈 리스트 반환.

## 검증 픽스처

`_workspace/strategy/pattern-fixtures/` 디렉토리에 단위 테스트가 사용하는 합성 캔들 데이터를 텍스트로 보존(샘플은 R1 테스트 코드 인라인). 실제 KRX 캔들 검증은 R2에서 보강.

## 변경 정책

- 신규 패턴 추가: ALL 리스트 끝에 append, `ALL.size` 가드 갱신
- 기존 `code` rename: ADR 0040 추가 + DSL/DB 마이그레이션 동반 (R1 출시 후 영향 면접하므로 보수적)
- 카테고리 변경: KIS 정합성 우선, 변경 시 사유 ADR 기록
