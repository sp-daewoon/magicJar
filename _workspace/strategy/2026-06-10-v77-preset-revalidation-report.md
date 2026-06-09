# V77 단타 프리셋 — 재검증 리포트 (클린 MDD 게이트 + donchian 돌파 — 실측 only)

> **지위**: 재검증·승격/정정 판정 문서. 엔진 결함 2건 fix(#862 MDD mark-to-market · #861 donchian 현재봉 제외) 배포 후, V76 검증을 **풀 게이트(G3 MDD≤8% 실제 적용)**로 다시 돌린 결과.
> **철학 LOCK**: "확률 높다"는 주장 0. OOS + **클린 MDD** 실측으로만 판정. 통과 못 하면 폐기/비활성. **H1이 클린 MDD 게이트에서 떨어지면 정직하게 비활성**(이전 승격 고수 금지).
> 작성자: strategy-engine · 일자: 2026-06-10 · task: T-20260610-0344 · 브랜치: agent/strategy-engine/v77-preset-revalidation

---

## §0. 재검증 결론 (요약 — 실측)

- **클린 MDD 게이트(G3 ≤8%)를 실제 적용한 결과, 6개 재검증 대상 전부 G3 탈락.** OOS 클린 MDD가 **21.4%~41.7%** 범위 — 8% 한도를 전부 큰 폭 초과.
- **H1 `DAYTRADE_GAP_AND_GO` 재판정 = G3 미달 → V77에서 `is_active=FALSE` 비활성**(정직 LOCK 적용). H1은 OOS PF 1.33·양 레짐 PF>1·민감도 plateau는 모두 통과하나, **클린 MDD 35.07%**로 하드 게이트 G3 실패. 이전 승격(V76)은 MDD가 측정 불가(≈100% 아티팩트)였기에 G3를 proxy로 우회한 판정이었고, 클린 MDD가 복원된 지금 **승격 근거가 사라졌다.**
- **H2 VOLUME_BREAKOUT·H6 RANGE_BREAKOUT** — donchian fix로 **처음 매칭 성공**(OOS 798·790 거래, 이전 0). 그러나 둘 다 **레짐-A PF<1(0.94·0.945) 손실 + 클린 MDD 21~26%**로 G8·G3 동시 탈락.
- **V75 기존 3종(V75-1·2·3)** 클린 MDD 재확인 — 전부 G3 탈락(MDD 26%~42%). V75-2는 V76에서 이미 비활성. V75-1·V75-3은 기존 시드라 본 task에서 변경하지 않되 G3 미달 사실을 기록.
- **V77 신규 승격: 0건.** V77 시드 = H1 비활성 정정 1건(UPDATE)만.

---

## §1. 재검증 환경·표본 (V76과 동일 방법론 — 비교 가능성 보존)

| 항목 | 값 | 비고 |
|---|---|---|
| 엔진 | #862 MTM equity + #861 donchian shift **배포 확인**(api 컨테이너 재기동·코드 verify) | `JvmBacktestEngine.markToMarketEquity`(L832) · `IndicatorVolatility.donchianUpper` shift(L195~205) |
| R3 엔드포인트 | `POST /api/backtest/overfit` · `/sensitivity` · `/run` | 앱 가동(docker healthy), 실 candle |
| universe | **80종목** (V76과 동일 필터 재현 — 거래대금 상위 보통주, 연속 적재) | mn≤2021-01-15 · mx≥2026-06-01 · IS≥700봉 · OOS≥400봉 |
| IS | 2021-01-01 ~ 2024-05-31 | V76 정합 |
| OOS | 2024-06-01 ~ 2026-06-09 | V76 정합(최근 레짐) |
| 레짐 분할 | OOS-A 2024-06~2025-06 · OOS-B 2025-06~2026-06 | G8 robustness |
| exit | target 2.0% / stop 1.2% (단일 진입) · V75-2만 1.8/1.0(원 시드값) | V76과 동일 — 진입 edge 격리 |
| exit 활성 경로 | `scale_in_config={"totalSlices":1}` + `take_profit_pct`/`fixed_stop_loss_pct` | 엔진 TP/SL은 scale-in/exit 경로에서만 발화(`ScaleInExitSimulation.isActive`). totalSlices=1 = AllCapital 단일 진입 등가 |
| 비용 | korean_default tax + kis_direct commission + fixed_0.05 slippage | ADR 0056 엔진 기본 무변경 |
| 초기자본 | 10,000,000 KRW | |

### 임시 Strategy row (검증 후 정리 의무)
- userId 74683223 · name prefix `V77REVAL_` 6종(id 63~68) 생성 → **검증 종료 후 DELETE**(자본 path 0 · 실주문 0).

---

## §2. ★ 엔진 fix 2건 검증 (V76 §2 제약이 해소됐는지 실측)

### 2-1. MDD(G3) — 클린 측정 복원됨 ✅
- `markToMarketEquity(cash, openPositions, evalPrice)` = `cash + Σ(보유 수량 × 평가가)`. 보유 구간에도 cash≈0이 아닌 실제 평가자본을 매 t 기록(L325~328).
- **결과**: MDD가 더 이상 ≈99.97% 구조적 고정이 아니다. 6종 OOS MDD = **21.39%~41.74%**, 민감도 grid 전반에서도 33~36%로 안정(H1) — **전략 품질이 반영되는 실측값**. G3를 proxy 없이 실제 적용 가능.
- **단, MDD 의미론(정직 명시)**: 엔진은 `for(code in universe){ for(t in candles) }` — universe 종목을 **단일 cash 풀로 순차** 시뮬하고 equity curve를 **연속 concat**한다(`SizingPolicy.AllCapital` = 보유현금 전액 1종목). 따라서 클린 MDD는 "80종목을 순차로 단일 풀 풀투자로 돌린 누적 곡선의 최대 낙폭"이다. 분산 포트폴리오 MDD가 아니라 **풀집중(1포지션=100%) 누적 MDD** — 단타 프리셋이 이 사이징에서 구조적으로 30%대 낙폭을 만든다는 뜻. (per-trade 손실 상한은 stop 1.2%로 별도 보호됨.)

### 2-2. `donchian_upper(n)` 돌파 — 매칭 복원됨 ✅
- fix: `rollingMax(highs, period)`를 1봉 shift → `donchian_upper(n)[i] = max(high[i-n..i-1])`(현재 봉 제외). `close[i] > donchian_upper(n)` 비교 성립.
- **실측**: H2(`close > donchian_upper(20)` 기반) OOS 거래 **798건**(이전 0), H6 **790건**(이전 0). **돌파 가설 처음으로 정상 검증 가능.** V76 §2-2 "DSL 표현 불가" 제약 해소.

---

## §3. 가설별 클린 실측 + 풀 게이트 판정 (재검증 6종)

> 하드 게이트: **G1** OOS총수익>0 · **G2** 거래≥100 · **G3 MDD≤8%(이제 실측)** · **G4** degradation≤0.50 · **G5** sensitivity plateau · **G8** 양 레짐 PF>1.
> 절대 수익률 magnitude는 풀집중 누적 산식으로 여전히 부풀려짐(신뢰 X) — PF·MDD·win·레짐만 판정에 사용.

| # | 가설 | OOS ret | OOS 거래 | OOS win | OOS PF | **OOS MDD(클린)** | degr. | plateau | 레짐A PF | 레짐B PF | 판정 |
|---|---|---:|---:|---:|---:|---:|---:|:---:|---:|---:|:---:|
| H1 | GAP_AND_GO | +3.373 | 398 | .493 | **1.331** | **0.3507** | n/a(OOS>IS) | ✅True(TP·SL 양축) | **1.189** | **1.428** | **❌ G3 탈락** |
| H2 | VOLUME_BREAKOUT | +4.989 | 798 | .466 | 1.273 | **0.2139** | n/a | — | **0.939** | 1.434 | **❌ G3·G8 탈락** |
| H6 | RANGE_BREAKOUT | +4.716 | 790 | .463 | 1.259 | **0.2577** | n/a | — | **0.945** | 1.430 | **❌ G3·G8 탈락** |
| V75-1 | GAP_RVOL_TURNOVER | +4.084 | 544 | .476 | 1.301 | **0.2619** | n/a | — | 1.088 | 1.446 | **❌ G3 탈락**(기존 시드 유지) |
| V75-2 | VOLATILITY_EXPANSION | +1.887 | 1204 | .440 | 1.091 | **0.4174** | n/a | — | 1.035 | 1.131 | **❌ G3 탈락**(이미 비활성) |
| V75-3 | MOMENTUM_BREAKOUT | +7.167 | 1559 | .456 | 1.209 | **0.3608** | n/a | — | 1.029 | 1.337 | **❌ G3 탈락**(기존 시드 유지) |

- degradation = n/a 사유: H1은 OOS ret(+3.37) > IS ret(+0.44)이라 (IS−OOS)/IS<0(저하 없음, 과최적화 아님). V75-1/2/3·H6는 IS 총수익 ≤0(`IS_RETURN_NON_POSITIVE`)으로 degradation 정의 불가(엔진 명시) — G4는 그 경우 판정 보류.
- **레짐 분할 MDD**(참고): H1 A/B = 0.297/0.310 · H2 A/B = 0.608/0.198 · H6 0.582/0.216 · V75-1 0.444/0.255 · V75-2 0.521/0.426 · V75-3 **0.788**/0.186. 어느 구간에서도 8% 한참 초과.

### §3-1. H1 클린 게이트 재판정 (★ 핵심)

| 게이트 | 기준 | H1 실측 | 통과? |
|---|---|---|:---:|
| G1 OOS 양(+) | totalReturn>0 | +3.373 | ✅ |
| G2 거래≥100 | totalTrades≥100 | 398 | ✅ |
| **G3 MDD≤8%** | maxDrawdown≤0.08 | **0.3507** | **❌** |
| G4 degradation≤0.50 | (IS−OOS)/IS≤0.50 | n/a(OOS>IS, 저하 없음) | ✅(과최적화 무징후) |
| G5 plateau | isPlateau==true | TP grid plateau(mav 0.049) · SL grid plateau(mav 0.044) 둘 다 True | ✅ |
| G8 양 레짐 PF>1 | A·B PF>1 | A 1.189 · B 1.428 | ✅ |
| G6 PF≥1.2(소프트) | OOS PF | 1.331 | ✅ |

- **판정: G3 단독 실패 → 하드 게이트 미통과 → V77에서 비활성.** H1은 G3를 제외한 모든 하드·소프트 게이트를 통과하는 "가장 좋은 후보"였으나, 클린 MDD 35.07%가 단타 자본보호 한도(8%)를 4배 이상 초과한다. 민감도 grid 전 점에서 MDD 33~36%로 안정 — 임계 튜닝으로 줄일 수 없는 **구조적 낙폭**(풀집중 사이징).
- V76에서 H1을 승격했던 근거(G3 proxy 우회 + OOS PF + 레짐)는 클린 MDD가 복원된 지금 **무효**. 이전 승격을 고수하지 않고 정직하게 비활성화한다.

### §3-2. H1 민감도 plateau 실측 (G5 — 클린)

**take_profit_pct grid** [1.5·1.75·2.0·2.25·2.5] (OOS 2024-06~2026-06): isPlateau=**True**, returnSpread 0.244, meanAdjVar **0.049**.
| TP% | ret | MDD | PF | trades |
|---|---:|---:|---:|---:|
| 1.5 | 3.279 | 0.344 | 1.331 | 400 |
| 1.75 | 3.509 | 0.326 | 1.354 | 399 |
| 2.0 | 3.373 | 0.351 | 1.331 | 398 |
| 2.25 | 3.523 | 0.343 | 1.344 | 397 |
| 2.5 | 3.373 | 0.360 | 1.324 | 397 |

**fixed_stop_loss_pct grid** [0.8·1.0·1.2·1.4·1.6]: isPlateau=**True**, returnSpread 0.545, meanAdjVar **0.044**.
| SL% | ret | MDD | PF | trades |
|---|---:|---:|---:|---:|
| 0.8 | 2.933 | 0.367 | 1.291 | 399 |
| 1.0 | 3.079 | 0.358 | 1.306 | 398 |
| 1.2 | 3.373 | 0.351 | 1.331 | 398 |
| 1.4 | 3.442 | 0.353 | 1.336 | 397 |
| 1.6 | 3.478 | 0.332 | 1.337 | 397 |

→ H1의 edge는 robust(plateau)지만 **MDD가 모든 점에서 33~37%**로 8% 게이트와 무관하게 높다. G5는 통과, G3는 어떤 튜닝으로도 통과 불가.

---

## §4. V77 승격/정정 (검증 기반)

### 4-1. V번호 audit
`ls api/src/main/resources/db/migration/` 최신 = **V76**. V77 미점유 확인(옆 세션 충돌 0). → **V77 확정.**

### 4-2. V77 시드 내용 = 0 신규 + 1 정정(UPDATE)

- **신규 INSERT: 0건.** 6종 전부 G3(클린 MDD≤8%) 하드 게이트 탈락. 억지 승격 0 LOCK.
- **정정 UPDATE 1건**: `DAYTRADE_GAP_AND_GO`(H1) → `is_active=FALSE`. 클린 MDD 35.07%로 G3 미달. V76에서 MDD 측정 불가(proxy 우회)로 승격됐던 것을 fix 후 정직하게 비활성. market_fit에 재검증 사유·수치 명기. builtin row는 삭제 않고 비활성만(역사 evidence + ON CONFLICT 재배포 안정성 — V76 V75-2 비활성 선례 정합).

### 4-3. V75 3종 재판정
- V75-2: V76에서 이미 `is_active=FALSE`. 클린 MDD 41.74%로 비활성 유지 정당성 재확인(추가 변경 0).
- V75-1·V75-3: 기존 V75 시드 active. 클린 MDD 26.19%·36.08%로 G3 미달이나 **본 task는 신규 승격/정정 범위** — 이 둘의 강등은 데이터 근거(G3 전면 미달)가 명확하므로 **별도 결정 권고**(§6). 본 task에서는 H1과의 일관성 위해 변경하지 않고 사실만 기록(과도 변경 회피).

---

## §5. 정직성 기록 (실패 보존)

1. **clean MDD가 모든 단타 프리셋을 탈락시킨다 (구조적)**: MDD 결함 fix 전(V76)에는 G3가 ≈100% 아티팩트라 proxy로 우회됐고, 그 틈에 H1이 승격됐다. fix 후 실측 MDD는 21~42%로, **현 엔진 사이징(풀집중 AllCapital 단일 풀)에서 단타 프리셋은 G3≤8%를 구조적으로 만족할 수 없다.** 이건 프리셋 식의 문제가 아니라 **백테스트 포트폴리오 모델(1포지션=자본 100%, 80종목 순차 concat) ↔ 게이트 기준(per-한도 8%)의 정합 문제**다. 둘 중 하나를 손봐야 한다(§6).
2. **donchian fix 효과 확인 + 그래도 탈락**: H2·H6 돌파가 처음 매칭(0→798·790거래)됐지만 **레짐-A PF<1(0.94)** + MDD 21~26%로 G8·G3 동시 탈락. 돌파 자체는 표현 가능해졌으나, 이 표본·기간에서 OOS edge가 robust하지 않음(상승장 B구간 luck 의존).
3. **H1 비활성 = 정직 LOCK 실행**: 이전 사이클 승격분을 클린 게이트가 부정할 때 고수하지 않고 비활성화. 검증 게이트가 강화되면 과거 판정도 재집행한다는 원칙의 실제 적용 사례.
4. **절대 수익률 신뢰 금지 유지**: §2-1 풀집중 누적 산식으로 magnitude(+3~+7)는 부풀려짐. PF·MDD·레짐만 인용.

---

## §6. 후속 권고 (본 task 범위 밖 — architect/backend-core 협의)

- **G3 게이트 ↔ 백테스트 사이징 정합 결정 필요(★ 1순위)**: 현 `SizingPolicy.AllCapital`(1포지션=100%) 풀집중 누적 MDD는 단타 프리셋 평가에 부적합할 수 있다. 선택지:
  - (a) 백테스트에 **per-position 한도 사이징**(예: 자본의 N%만 1종목) 도입 → 분산 포트폴리오 MDD로 G3 재측정.
  - (b) G3 기준을 **per-trade 손실(stop 1.2%)** 또는 **trade-level MDD**로 재정의(현 portfolio-MDD 대신).
  - (c) 단타 프리셋 게이트에서 G3 한도를 현실적 값(예 단타 ≤15~20%)으로 ADR 재설정.
  - 결정 없이 단타 프리셋을 G3≤8%로 평가하면 **영원히 0건 승격**이 구조적으로 강제된다.
- **V75-1·V75-3 강등 검토**: 클린 MDD 26%·36%로 G3 전면 미달 + V75-3 레짐-A PF 1.03(near-noise)·레짐 MDD 78.8%. 단타 프리셋 노출 적합성 재판정 권고(별도 결정·ADR).
- **분봉 재검증**: 일봉 단일 진입 + TP/SL은 사실상 swing 근사. 분봉 단타 + intrabar TP/SL이면 MDD·거래수 프로파일이 다를 수 있음 — 전종목 분봉 적재 후보.
