# V76 단타 프리셋 — IS/OOS 검증 리포트 (실측 only)

> **지위**: 검증·승격 판정 문서. #858 연구 문서(8가설 + V75 3종 재평가)를 R3 ④검증(IS/OOS overfit + 파라미터 sensitivity + 레짐 분할)으로 실데이터에 돌린 결과.
> **철학 LOCK**: "확률 높다"는 주장 0. OOS 실측 + 레짐 분할로만 판정. 통과 못 하면 폐기. 0개 통과면 0개 시드.
> 작성자: strategy-engine · 일자: 2026-06-10 · task: T-20260610-0222 · 브랜치: agent/strategy-engine/v76-preset-validation

---

## §0. 검증 결론 (요약)

- **V76 신규 승격: 1종** — `DAYTRADE_GAP_AND_GO`(H1). 유일하게 OOS 양(+) · 거래≥100 · plateau · **양 레짐 분할 모두 PF>1** 통과.
- **V75-2 `DAYTRADE_VOLATILITY_EXPANSION`: 비활성화(is_active=FALSE)** — 현행·제안 수정안 **둘 다 양 레짐에서 손실**(PF 0.90/0.98 · 0.89/1.07). 연구 문서의 "하한 상향 수정"이 데이터로 **뒷받침되지 않음**. 손실 프리셋을 사용자에게 노출할 수 없어 비활성화(폐기 보존).
- **나머지 9가설 전부 탈락** — 사유 각각 §3 표에 실측 인용.

---

## §1. 검증 환경·표본 (silent 축소 0 — 범위·한계 명시)

| 항목 | 값 | 비고 |
|---|---|---|
| R3 엔드포인트 | `POST /api/backtest/overfit` · `/sensitivity` · `/run` | 앱 가동 중(docker), 실 candle |
| universe | **80종목** (대표 표본 — 풀스케일 2517종목 비현실) | 거래대금 상위 보통주(코드 끝 0) ∩ **연속 적재**(2021-01~2026-06 mn≤2021-01-15·mx≥2026-06-01·IS≥700봉·OOS≥400봉) |
| IS | 2021-01-01 ~ 2024-05-31 | #858 계획 정합 |
| OOS | 2024-06-01 ~ 2026-06-09 | 분포 측정 구간과 동일(최근 레짐) |
| 레짐 분할 | OOS-A 2024-06~2025-06 · OOS-B 2025-06~2026-06 | G8 robustness |
| exit | target 2.0% / stop 1.2% (R:R≈1.67) · 단일 진입 | 진입 edge 격리 비교 위해 exit 고정. V75-2만 1.8/1.0(원 시드값) |
| 비용 | korean_default tax + kis_direct commission + fixed_0.05 slippage | ADR 0056 엔진 기본 무변경 |
| 초기자본 | 10,000,000 KRW | |

### 표본 축소 사유 (정직 LOCK)
- **전종목×다년×12전략 풀스케일 비현실**: 파일럿(10종목×5.4년) = 6.4초. 80종목×12전략 overfit ≈ 4분(허용), 2517종목이면 종목당 ~3.8초 × 12 ≈ 3시간 + 타임아웃 risk.
- **연속 적재 필터 필수(엔진 제약)**: overfit 엔진은 universe 中 **한 종목이라도** IS 또는 OOS sub-range에 candle 0건이면 **run 전체를 Aborted(NO_CANDLE_DATA)** 처리(`OverfitDetector.runOrThrow` + `JvmBacktestEngine` line 171). 거래대금 상위 100종목 그대로 쓰면 최근 IPO(489790·450080·454910·402340 등)가 2021 IS에 없어 **전 12전략 503**. → mn≤2021-01-15 필터로 80종목 확정.
- **한계**: 표본은 "대형 유동주 80종". 소형·신규상장은 제외(편향 인지). 결론은 이 표본·기간 한정.

---

## §2. ★ 엔진 제약 2건 (검증 중 발견 — 별도 보고, 코드 무변경)

### 2-1. MDD(G3) 측정 불가 — equity curve = cash-only 아티팩트
- `JvmBacktestEngine`은 `SizingPolicy.AllCapital`(보유현금 전액 1종목 매수)로 **universe 종목을 단일 cash 풀로 순차** 시뮬레이션하고, equity curve에 **cash만** 적재(`equityCurve.add(cash)` — 보유 포지션 mark-to-market 미반영, line 271·290·315·342).
- 결과: 포지션 보유 중 cash≈0 → equity curve가 매 진입마다 ~0으로 떨어졌다 청산 시 복귀 → **MDD가 전 전략 ≈99.97~99.99%로 구조적 고정**(전략 품질과 무관).
- **판정 영향**: 하드 게이트 **G3(MDD≤8%) 현 엔진으로 측정 불가**. → G3는 "엔진 한계/proxy"로 처리. 자본 보호는 **per-trade 고정 손절 1.2%**(각 거래 손실 상한)로 대체 평가하고, 진짜 edge는 **OOS profitFactor**(per-trade 산식 — 아티팩트 무관)로 판정.
- 절대 수익률 magnitude(예: V75-3 OOS +302%)도 동일 아티팩트로 **부풀려짐** → 절대값 신뢰 X, **PF·win·avgWin/avgLoss(per-trade)만** 교차비교 신뢰.

### 2-2. `donchian_upper(n)` breakout = 구조적 0 매칭 (H2·H6 평가 불가)
- `IndicatorVolatility.donchianUpper` = `rollingMax(highs, period)` — **현재 봉 포함** 채널 상단(정상 Donchian 의미).
- 따라서 `close > donchian_upper(n)`은 수학적으로 **항상 false**(채널 상단이 자기 봉 high를 포함, high≥close). 실측: 005930·000660·035420 2024+ 1770봉 中 `close>dch(현재봉포함)` = **0건**, `close>dch(현재봉제외)` = 171건.
- **결과**: H2·H6 모두 IS·OOS **거래 0건**(graceful, abort 아님). 신고가 돌파를 현 엔진 식별자로 표현하려면 shifted donchian 또는 `high>prev_dch`가 필요하나 **현 DSL 미지원**.
- **판정**: H2·H6 = **DSL 표현 불가**로 탈락(시장 edge 실패 아님). 연구 문서 §2-3이 경고한 함정의 변형(highest 미등록 회피로 donchian 채택했으나 inclusive-bar로 동일 무력화).

---

## §3. 가설별 실측 + 게이트 판정 (전 12종)

> 결정 지표: **OOS PF**(per-trade, 아티팩트 무관) + **plateau** + **양 레짐 PF>1**. G3는 §2-1로 proxy. G4는 IS≤0인 경우 n/a.

| # | 가설 | OOS ret | OOS 거래 | OOS win | **OOS PF** | plateau | 레짐A PF | 레짐B PF | 판정 | 사유(실측) |
|---|---|---:|---:|---:|---:|:---:|---:|---:|:---:|---|
| H1 | GAP_AND_GO | +1.117 | 333 | .469 | **1.13** | ✅True | **1.19** | **1.11** | **✅ 승격** | 양 레짐 PF>1 · plateau(PF 1.13~1.16) · expectancy +35.6k/trade |
| H2 | VOLUME_BREAKOUT | 0.000 | **0** | — | null | — | — | — | ❌ 폐기 | **donchian inclusive-bar → 0매칭**(§2-2) DSL 표현불가 |
| H3 | MOMENTUM_CONT | +0.419 | 851 | .444 | 1.03 | — | — | — | ❌ 폐기 | PF 1.03 = 비용 후 near-noise(edge 없음) |
| H4 | MA_PULLBACK | −0.780 | 305 | .393 | 0.72 | — | — | — | ❌ 폐기 | G1 실패(OOS 음) · PF 0.72 손실 |
| H5 | SQUEEZE_EXPANSION | −0.119 | **6** | .333 | 0.34 | — | — | — | ❌ 폐기 | G2 실패(6거래 판정불가) + 손실 |
| H6 | RANGE_BREAKOUT | 0.000 | **0** | — | null | — | — | — | ❌ 폐기 | donchian inclusive-bar(§2-2) DSL 표현불가 |
| H7 | TURNOVER_SURGE | +0.655 | 105 | .410 | 1.26 | ❌**False** | — | — | ❌ 폐기 | G5 실패(meanAdjVar 0.46 · PF가 tp에 단조의존 1.08→1.28) + 표본 105(취약) |
| H8 | STRONG_CLOSE | +1.897 | 565 | .450 | 1.15 | ✅True | **0.81** | 1.36 | ❌ 폐기 | **G8 실패** — 레짐A PF 0.81(손실). aggregate edge=2025 단일구간 luck |
| V75-1 | GAP_RVOL_TURNOVER | +1.032 | 486 | .442 | 1.09 | ✅True | 1.05 | 1.15 | ⏸ **유지** | 양 레짐>1이나 PF<1.10(thin). 기존 V75 시드 — 무변경 유지 |
| V75-2c | VOLEXP (현행) | −0.871 | 1245 | .428 | **0.96** | — | 0.90 | 0.98 | ❌ **비활성** | **양 레짐 손실**. 연구 문서대로 ATR밴드 필터 부재 확정 |
| V75-2f | VOLEXP (수정안 atr>4%) | −0.151 | 1201 | .441 | 1.00 | ❌**False** | 0.89 | 1.07 | ❌ **수정 기각** | 하한 상향해도 breakeven·non-plateau·레짐A 손실. **fix가 데이터로 미입증** |
| V75-3 | MOMENTUM_BREAKOUT | +3.027 | 1457 | .439 | 1.10 | ✅True | **0.78** | 1.26 | ⏸ **유지** | **G8 약점**(레짐A PF 0.78 손실)이나 기존 V75 시드 — 본 task 범위는 신규 승격. 강등은 별도 결정 권고 |

### 통과 게이트 요약 (하드 G1·G2·G5 + 레짐 G8 결합)
- **G1·G2·G5·G8 전부 통과**: **H1 단 1종**.
- V75-1·V75-3은 기존 V75 시드 — 본 task는 신규 승격 범위. V75-3는 G8 약점(레짐A 손실) 관찰됨(별도 강등 결정 권고, 본 task 변경 0).

---

## §4. V76 승격 + V75-2 조치 (검증 기반)

### 4-1. V번호 audit
`ls api/src/main/resources/db/migration/` 최신 = **V75**. V76 미점유 확인(옆 세션 충돌 0). → **V76 확정**.

### 4-2. V76 시드 = 1 INSERT + 1 UPDATE
- **INSERT** `DAYTRADE_GAP_AND_GO` (sort_order 230) — H1. entry_dsl 등록 식별자만(open·prev_close·volume·volume_ma·close — directCandle/catalog). target 2.0 / stop 1.2 / hold 8. `ON CONFLICT DO NOTHING`.
- **UPDATE** `DAYTRADE_VOLATILITY_EXPANSION` → `is_active=FALSE` — 양 레짐 손실 실측. market_fit에 비활성 사유 명기.

### 4-3. 승격 식 (DAYTRADE_GAP_AND_GO)
```
open >= prev_close * 1.025 and volume > volume_ma(20) * 2.5 and close * volume > 16000000000 and close > 2000
```
- V75-1(갭 1.5%/RVOL 2.5)의 **공격형 형제**(갭 2.5%=p95/RVOL 2.5). 두 프리셋 차별 노출 정합(#858 §4-C 권고).
- market_fit에 "과거 검증 기준 · 미래 보장 없음 · 후보 출발점(자동매매 결정권 0)" 명기(정직 LOCK).

---

## §5. 정직성 기록 (실패 보존 — 같은 실수 반복 방지)

1. **donchian breakout 함정 (H2·H6)**: 연구 문서가 `highest()` 미등록을 피해 `donchian_upper`를 택했으나, inclusive-bar 의미로 `close>donchian_upper`는 항상 false. 신고가 돌파는 현 DSL로 표현 불가 — 엔진 식별자 보강(shifted donchian) 없이는 재시도 무의미.
2. **aggregate ≠ robust (H8·V75-3)**: 합산 OOS는 화려(+190%·+302%)하나 레짐 분할 시 전반기 손실. 분할 없으면 2025 단일 상승 leg luck을 edge로 오인. **레짐 분할이 핵심 필터**였음.
3. **V75-2 fix 기각**: 연구 문서의 "ATR 하한 상향" 가설은 분포 근거(밴드 72% 통과=필터 부재)는 맞았으나, **하한을 올려도 edge가 생기지 않음**(breakeven). 변동성 자체가 단타 진입 edge가 아님을 시사 — 변동성은 종목 선별이 아니라 exit(손절폭) 설계 변수로 쓰는 게 맞을 수 있음(후속 가설).
4. **절대 수익률 신뢰 금지**: §2-1 아티팩트로 magnitude는 무의미. PF·win·avgWin/avgLoss만 인용.

---

## §6. 후속 권고 (본 task 범위 밖)
- **엔진 결함 2건**(§2): (a) backtest equity curve를 portfolio mark-to-market로 보강(MDD 실측 복원), (b) `donchian_upper_shifted` 또는 `prior_high(n)` 식별자 추가(신고가 돌파 표현) — architect/backend-core 협의.
- **V75-3 강등 검토**: 레짐A PF 0.78(손실) — 단타 프리셋으로 노출 적합성 재판정 권고(별도 결정·ADR).
- **분봉 재검증**: 일봉 8봉 보유는 사실상 8거래일(분봉 단타와 괴리). 전종목 분봉 적재 시 H1 재검증 후보.
</content>
