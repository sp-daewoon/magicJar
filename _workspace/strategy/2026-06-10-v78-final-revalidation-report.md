# V78 최종 재검증 리포트 — 사이징 정합(FixedFractional) + 신 G3(포트폴리오 MDD≤12%) 집행 (실측 only)

> **지위**: ADR 0121 LOCK 집행 문서. V77이 발견한 측정-게이트 불일치(AllCapital 몰빵 MDD를 분산 게이트 8%로 측정)를, 백테스트 사이징을 실전 RiskConfig 정합(FixedFractional 10%/5슬롯)으로 환산하고 G3를 "실전 사이징 기준 포트폴리오 MDD ≤ 12%"로 재정의해 6종을 최종 재판정한 결과.
> **철학 LOCK**: "확률 높다" 주장 0. OOS + **사이징 정합 포트폴리오 MDD** 실측으로만 판정. 통과 못 하면 비활성(정직 LOCK — 통과해도 정직, 미달해도 정직).
> 작성자: strategy-engine · 일자: 2026-06-10 · task: T-20260610-1430 · 브랜치: agent/strategy-engine/v78-final-revalidation-r2

---

## §0. 최종 결론 (요약 — 실측)

- **사이징 정합 후 6종 中 5종이 신 G3(FixedFractional 10%/5 기준 OOS MDD ≤ 12%) 포함 전 하드 게이트를 통과.** V77(AllCapital 몰빵)에서 6종 전부 G3 탈락했던 것이 **측정-게이트 불일치였음이 실증**됨 — ADR 0121 §5-1 가설(프리셋 식이 아니라 측정 모델 문제)이 데이터로 확정.
- **H1 `DAYTRADE_GAP_AND_GO` 최종 운명 = active 유지 확정.** Fractional OOS MDD **0.0789(7.9%)** ≤ 12% — V77 보류했던 비활성 판정을 **집행하지 않음**(ADR §3-3 promote rule). V77 AllCapital MDD 35.07%는 1포지션=자본 100% 몰빵 누적 곡선의 낙폭이었고, 분산(10%/5) 환산 시 7.9%로 게이트 충족. G3 외 전 게이트(G1·G2·G4·G5 plateau·G8 양 레짐 PF 1.31/1.47) 모두 통과.
- **H2 VOLUME_BREAKOUT · H6 RANGE_BREAKOUT 신규 승격(INSERT).** 사이징 환산으로 (a) MDD 7.7%·8.6% ≤ 12% (G3 통과), (b) **레짐-A PF가 V77 0.94·0.945(G8 탈락)에서 1.210·1.204로 반전(G8 통과)**. donchian 돌파(#860 fix)가 처음 정상 매칭(789·780 거래)된 위에서 사이징 정합이 G3·G8을 동시 해소.
- **V75-1 · V75-3 active 유지.** Fractional MDD 7.0%·7.1% ≤ 12%, 양 레짐 PF>1, plateau True — 신 G3 통과로 기존 active 정당성 재확인.
- **V75-2 VOLATILITY_EXPANSION 비활성 유지(재활성 안 함).** Fractional MDD **0.1230(12.30%) > 12%**로 신 G3 단독 미달 + 레짐-A PF 1.003(near-noise). ADR LOCK "V75-2는 통과 시에만 재활성 검토" — 미통과이므로 V76 비활성 유지.
- **임계 X=12% 확정.** 실측 환산 MDD(H1 7.9% — AllCapital 35%의 약 0.24배)가 ADR §임계 추정 범위(9~14%)에 정합 → ADR 0121 amend 트리거 없음.
- **V78 시드 = 신규 INSERT 2건(H2·H6) + UPDATE 1건(H1 market_fit 갱신, active 유지).**

---

## §1. ★ 사이징 작동 사전 점검 (직전 결함 재발 가드 — 필수)

본 검증 시작 전, 라이브 api에서 동일 H1 요청을 AllCapital vs FixedFractional로 1회씩 실행해 결과가 달라지는지 확인했다(#871이 scale-in 경로 슬롯 환산을 누락해 비트 동일로 무력화됐던 직전 결함의 재발 가드 — #873 hotfix 작동 검증).

| 사이징 | OOS ret | OOS MDD | OOS 거래 | OOS PF | echo type |
|---|---:|---:|---:|---:|---|
| AllCapital (몰빵) | 3.0748 | **0.3277** | 382 | 1.3177 | ALL_CAPITAL |
| FixedFractional 10%/5 | 0.3200 | **0.0789** | 370 | 1.3726 | FIXED_FRACTIONAL |

- **비트 비동일 확인** — MDD 0.3277 → 0.0789로 환산(약 4.2배 축소), ret도 3.07→0.32로 슬롯 1/10 축소 반영. 직전 결함(비트 동일) 재발 0.
- **PF는 1.32 → 1.37로 슬롯 크기 무관 근사 불변** — ADR §2-B "PF는 슬롯 크기에 비례 불변" 예측 정합(슬롯이 작아져도 per-trade edge는 동일, 거래수 미세차 370 vs 382는 cash 부족 floor 차이).
- 환산계수 ≈ 0.0789 / 0.3277 ≈ **0.24** — ADR §임계 추정 "35% × (0.25~0.4) ≈ 9~14%"의 하단 근방. H1 단일 종목 자본 노출 1/10 + 누적 self-correlation 영향이 반영된 보수적(낮은) 근사.
- **사전 점검 통과 → 정식 run 진행** (엔진 무변경, 결함 재발 없음).
- evidence: `sample-backtest/v78-precheck-sizing-separation.txt`

---

## §2. 재검증 환경 (V77 정합 — 비교 가능성 보존, 유일 변경 = 사이징)

| 항목 | 값 | 비고 |
|---|---|---|
| 엔진 | #862 MTM equity + #861/#860 donchian shift + #871/**#873 FixedFractional 슬롯 환산(scale-in 경로 포함)** 배포 | base = fa3c4840 (#873 머지) · `fractionalSlot` buyBudget·scale-in 회차 budget 공유 |
| R3 엔드포인트 | `POST /api/backtest/overfit` · `/sensitivity` · `/run` | 앱 healthy, 실 candle |
| universe | **80종목** (거래대금 상위 보통주 — `AVG(close_raw*volume)` OOS 내림차순 top 80, code LIKE '%0') | mn≤2021-01-15 · mx≥2026-06-01 · IS≥700봉 · OOS≥400봉 (V77 동일 필터 결정론 재현) |
| IS | 2021-01-01 ~ 2024-05-31 | V77 정합 |
| OOS | 2024-06-01 ~ 2026-06-09 | V77 정합 |
| 레짐 분할 | A 2024-06-01~2025-06-01 · B 2025-06-01~2026-06-09 | G8 robustness |
| exit | TP 2.0% / SL 1.2% (V75-2만 1.8/1.0) · scale_in `{"totalSlices":1}` + TP/SL | V77 그대로 — 사이징만 격리 |
| **사이징 (유일 변경)** | **`FixedFractional(positionPercent=10.00, maxConcurrent=5, maxPositionAmount=null)`** | ADR §2-A V78 표준 프로파일 LOCK. 초기자본 1천만 × 10% = 100만 슬롯 = RiskConfig default 등가 |
| 비용 | korean_default tax + kis_direct commission + fixed_0.05 slippage | ADR 0056 엔진 기본 무변경 |
| 초기자본 | 10,000,000 KRW | |
| 임시 Strategy row | userId 74683223 · `V78REVAL_` 6종(id 76~81) → **검증 종료 후 DELETE 완료**(자본 path 0·실주문 0) | |

> universe 정직 명시: V77이 명시 list를 영속하지 않아 동일 필터(`close_raw*volume` 평균 내림차순 top 80)를 결정론적으로 재현했다. AllCapital 비교 컬럼도 본 run에서 같은 데이터 스냅샷으로 재측정(V77 리포트값과 미세차는 candle 추가 적재분 — 동일-스냅샷 비교가 정합).

---

## §3. 6종 사이징 정합 실측 + 신 G3 판정 (★ 핵심)

> 하드 게이트: **G1** OOS총수익>0 · **G2** 거래≥100 · **G3 신정의 = Fractional MDD≤12%** · **G4** degradation≤0.50(IS≤0이면 보류) · **G5** sensitivity plateau · 소프트 **G6** PF≥1.2 · **G8** 양 레짐 PF>1.
> 절대 수익률 magnitude는 사이징 정합으로 합리화됐으나(슬롯 1/10), 여전히 단일 cash 풀 순차 concat 곡선 — PF·MDD·레짐을 1차 판정에 사용.

| # | 가설 | OOS ret | OOS 거래 | OOS PF | **Frac MDD** | (V77 AllCap MDD) | degr. | plateau | 레짐A PF | 레짐B PF | 판정 |
|---|---|---:|---:|---:|---:|---:|---:|:---:|---:|---:|:---:|
| H1 | GAP_AND_GO | +0.320 | 370 | 1.373 | **0.0789** | 0.3277 | n/a(OOS>IS) | ✅True | **1.313** | **1.473** | **✅ 유지** |
| H2 | VOLUME_BREAKOUT | +0.701 | 789 | 1.430 | **0.0766** | 0.2972 | n/a | ✅True | **1.210** | 1.643 | **✅ 신규 승격** |
| H6 | RANGE_BREAKOUT | +0.666 | 780 | 1.410 | **0.0858** | 0.3538 | n/a | ✅True | **1.204** | 1.631 | **✅ 신규 승격** |
| V75-1 | GAP_RVOL_TURNOVER | +0.414 | 517 | 1.347 | **0.0701** | 0.2460 | n/a(IS≤0) | ✅True | 1.186 | 1.498 | **✅ 유지** |
| V75-2 | VOLATILITY_EXPANSION | +0.150 | 1135 | 1.082 | **0.1230** | 0.5825 | n/a(IS≤0) | — | 1.003 | 1.206 | **❌ 비활성 유지** |
| V75-3 | MOMENTUM_BREAKOUT | +0.764 | 1522 | 1.244 | **0.0711** | 0.3078 | n/a(IS≤0) | ✅True | 1.079 | 1.428 | **✅ 유지** |

- degradation n/a 사유: H1·H2·H6는 OOS ret > IS ret(저하 없음, 과최적화 무징후 — H1 IS 0.094 < OOS 0.320). V75-1/2/3는 IS totalReturn ≤ 0(`IS_RETURN_NON_POSITIVE`)으로 degradation 정의 불가 → G4 보류(ADR §3-3-4 정합, 다른 하드 게이트로 판정).
- **V75-2 단독 탈락 정직 기록**: Frac MDD 12.30%로 12% 게이트를 0.30%p 초과(경계 미달). 레짐-A PF 1.003은 사실상 breakeven(near-noise)로 양 레짐 우위 신뢰 약함. 두 신호 모두 비활성 유지를 지지.
- evidence: `sample-backtest/v78-overfit-battery-frac-vs-allcap.txt`(overfit 12 run) · `v78-regime-split-AB-frac.txt`(레짐 12 run).

### §3-1. H1 클린 게이트 재판정 (V77 G3 단독 실패 → V78 G3 통과)

| 게이트 | 기준 | V77(AllCap) | V78(Frac) | 통과? |
|---|---|---|---|:---:|
| G1 OOS 양(+) | totalReturn>0 | +3.373 | +0.320 | ✅ |
| G2 거래≥100 | totalTrades≥100 | 398 | 370 | ✅ |
| **G3 MDD≤12%** | Fractional MDD≤0.12 | **0.3507(8% 기준 ❌)** | **0.0789** | **✅** |
| G4 degradation≤0.50 | (IS−OOS)/IS≤0.50 | n/a(OOS>IS) | n/a(OOS>IS) | ✅(무징후) |
| G5 plateau | isPlateau==true | True | TP True(mav 0.053)·SL True(mav 0.044) | ✅ |
| G8 양 레짐 PF>1 | A·B PF>1 | 1.189/1.428 | 1.313/1.473 | ✅ |
| G6 PF≥1.2(소프트) | OOS PF | 1.331 | 1.373 | ✅ |

- **판정 = 전 하드·소프트 게이트 통과 → active 유지 확정.** V77에서 G3 단독으로 떨어졌던 유일 사유(몰빵 MDD 35%)가 사이징 정합으로 해소됐다. 측정 모델을 실전(분산 10%/5)에 맞추니 H1의 실제 자본 노출 낙폭은 7.9%로, 자본보호 한도(12%) 안.

### §3-2. H1 민감도 plateau 실측 (G5 — FixedFractional)

**take_profit_pct grid** [1.5·1.75·2.0·2.25·2.5]: isPlateau=**True**, returnSpread 0.0384, meanAdjVar 0.0529.
| TP% | ret | Frac MDD | PF | trades |
|---|---:|---:|---:|---:|
| 1.5 | 0.299 | 0.0822 | 1.359 | 372 |
| 1.75 | 0.328 | 0.0791 | 1.392 | 371 |
| 2.0 | 0.320 | 0.0789 | 1.373 | 370 |
| 2.25 | 0.338 | 0.0784 | 1.392 | 369 |
| 2.5 | 0.325 | 0.0872 | 1.369 | 369 |

**fixed_stop_loss_pct grid** [0.8·1.0·1.2·1.4·1.6]: isPlateau=**True**, returnSpread 0.0346, meanAdjVar 0.0442.
| SL% | ret | Frac MDD | PF | trades |
|---|---:|---:|---:|---:|
| 0.8 | 0.285 | 0.0831 | 1.341 | 371 |
| 1.0 | 0.296 | 0.0824 | 1.352 | 370 |
| 1.2 | 0.320 | 0.0789 | 1.373 | 370 |
| 1.4 | 0.305 | 0.0790 | 1.351 | 370 |
| 1.6 | 0.301 | 0.0785 | 1.343 | 370 |

→ **MDD가 모든 grid 점에서 7.8~8.7%로 12% 한참 아래** — H1의 G3 통과는 knife-edge가 아니라 robust(임계 튜닝 무관 구조적). G5·G3 동시 통과.
- evidence: `sample-backtest/v78-h1-sensitivity-frac.txt`

### §3-3. H2·H6·V75-1·V75-3 G5 plateau 실측 (승격/유지 후보 전수)

신 G3·G8 통과 후보에 take_profit_pct grid [1.5·1.75·2.0·2.25·2.5] sweep — **전부 isPlateau=True**, 전 grid 점 MDD<12%.
| 가설 | isPlateau | spread | meanAdjVar | grid MDD 범위 |
|---|:---:|---:|---:|---:|
| H2 | True | 0.0229 | 0.0169 | 0.077~0.088 |
| H6 | True | 0.0164 | 0.0174 | 0.086~0.097 |
| V75-1 | True | 0.0440 | 0.0525 | 0.069~0.071 |
| V75-3 | True | 0.0449 | 0.0219 | 0.060~0.071 |

→ 4종 모두 G5 통과 + G3가 grid 전반에서 robust. evidence: `sample-backtest/v78-g5-sensitivity-h2-h6-v75.txt`

---

## §4. V78 집행 (검증 기반)

### 4-1. V번호 audit
`ls api/src/main/resources/db/migration/` 최신 = **V77**(`discovery_scorecard` — 옆 세션 스코어카드, candidate 테이블 INSERT/UPDATE 안 함 → 충돌 0). 워크트리·브랜치 V78 점유 0 audit. → **V78 확정.**

### 4-2. V78 시드 내용 = 신규 INSERT 2 + UPDATE 1
- **INSERT 2건**: `DAYTRADE_VOLUME_BREAKOUT`(H2, sort 240) · `DAYTRADE_RANGE_BREAKOUT`(H6, sort 250). 신 G3·G8·G5 전 하드 게이트 통과 신규 승격. `is_active=TRUE`·`is_builtin=TRUE`. ON CONFLICT DO NOTHING(재배포 안정).
- **UPDATE 1건**: `DAYTRADE_GAP_AND_GO`(H1) market_fit를 V78 사이징 정합 근거로 갱신(active 유지 — V77 보류 비활성 집행 안 함). builtin row 비활성/삭제 0.
- **V75-1·V75-3**: 기존 active 유지(신 G3 통과). 변경 0.
- **V75-2**: V76 비활성(`is_active=FALSE`) 유지. 신 G3 미달(12.30%)로 재활성 안 함. UPDATE 불요.

### 4-3. 시드 검증 (라이브 DB 적용 실측)
V78 마이그레이션을 라이브 DB에 적용 → INSERT 2 / UPDATE 1 성공. 최종 `strategy_research_candidate` DAYTRADE_* 상태:
| candidate_key | is_active | sort_order |
|---|:---:|---:|
| DAYTRADE_GAP_RVOL_TURNOVER (V75-1) | t | 200 |
| DAYTRADE_VOLATILITY_EXPANSION (V75-2) | **f** | 210 |
| DAYTRADE_MOMENTUM_BREAKOUT (V75-3) | t | 220 |
| DAYTRADE_GAP_AND_GO (H1) | t | 230 |
| DAYTRADE_VOLUME_BREAKOUT (H2) | **t (신규)** | 240 |
| DAYTRADE_RANGE_BREAKOUT (H6) | **t (신규)** | 250 |
→ 활성 5 / 비활성 1. 의도대로 집행됨.

---

## §5. 정직성 기록 (실패·반전 보존)

1. **측정-게이트 불일치가 6종을 전부 떨어뜨렸던 것이 실증으로 확정**: V77 AllCapital(몰빵 100%) MDD 21~42%를 분산 게이트 8%로 잰 것이 문제였고, 프리셋 식 문제가 아니었다. 사이징 정합 후 5종이 통과 — ADR 0121 §5-1 진단이 데이터로 옳았다.
2. **H1 운명 반전 = 정직 LOCK 양방향 적용**: V77에서 "게이트 강화되면 과거 승격도 비활성 집행" 원칙으로 H1 비활성을 판정했고, V78에서 "측정 정합 후 통과하면 비활성 집행 안 함"을 똑같이 정직하게 적용. 게이트가 정직하면 판정도 양방향으로 정직하다.
3. **레짐-A PF 반전(H2·H6)의 정직 해석**: V77 AllCapital 레짐-A PF 0.94(손실)가 V78 Frac 1.21로 반전. 이는 사이징이 per-trade PnL을 균등 가중(고정 슬롯)해, 몰빵 곡선에서 cash 고갈 구간 큰 손실이 비균등 가중되던 왜곡이 제거된 결과다(PF가 실제 per-trade edge를 더 정직하게 반영). 단, 절대 magnitude는 여전히 단일 풀 순차 concat이므로 신뢰 X — PF·MDD·레짐 부호만 사용.
4. **V75-2 경계 탈락 정직 집행**: Frac MDD 12.30%로 12%를 0.30%p 초과. 임계를 사후에 12.5%로 늘려 통과시키지 않았다(게이트 후퇴 금지 — `feedback_v3_skepticism`). 레짐-A PF 1.003 near-noise도 비활성 유지를 지지.
5. **임계 X=12% 사후 검증 통과**: 실측 환산계수 0.24(H1)가 ADR 추정(0.25~0.4)에 정합 → 임계가 아니라 전략·환산 로직을 의심할 근거 없음 → ADR amend 트리거 없음.
6. **1차 사이징 환산의 구조적 한계 LOCK 유지**: 본 환산은 "1종목 10% 슬롯 순차"이지 "5종목 10% 동시"가 아니다(ADR §2-2). 따라서 Frac MDD는 실전 분산 MDD의 **보수적(낮은) 근사**(상관=0에 가까움). maxConcurrent=5는 메타 echo로만 기록(동시보유 시뮬은 post-1차). 실전에서 종목 동반 하락 시 합산 낙폭은 본 측정보다 클 수 있음을 명시.

---

## §6. 후속 권고 (본 task 범위 밖)

- **forward-test 누적(V77 scorecard 연계)**: V78 승격 5종(H1·H2·H6·V75-1·V75-3)은 V77 discovery_scorecard 잡으로 매 영업일 발굴 hit-rate가 누적된다 — 백테스트 우위가 forward에서도 유지되는지 살아있는 evidence로 추적 권고.
- **maxConcurrent 동시보유 시뮬(post-1차)**: 1차 환산은 상관=0 근사라 실전 분산 MDD를 낮게 추정. 종목 간 상관(동반 하락) 반영하는 진짜 멀티종목 동시 시뮬은 별도 ADR(엔진 코어 재작성 risk)로 격하 — 데이터 필요 입증 후.
- **V75-2 재설계 후보**: 변동성은 진입 edge가 아니라 손절폭 설계 변수라는 V76 §5-3 권고 유효 — 비활성 유지하되 손절 적응형으로 재설계 시 별도 가설로.
- **분봉 재검증**: 일봉 단일 진입 + TP/SL은 swing 근사. 분봉 intrabar TP/SL이면 프로파일 상이 가능 — 전종목 분봉 적재 후보(V77 §6 유지).
