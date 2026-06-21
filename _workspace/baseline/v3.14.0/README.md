# magicJar v3.14.0 Baseline — 분할매수 자본 재설계 마일스톤 (base 1차 박제·부분매수 잔여 추적·floor 알람강등/자동청산 토글·전략 빌더/목록 UX 고도화 — ADR 0124~0128 + Flyway V83~V90)

> **작성 시점**: 2026-06-21 v3.14.0 release commit 준비 (main `cbd5db8e` · 태그·release branch·GitHub Release는 커밋 이후 별도 컷)
> **사이클**: v3.13.0(REAL canary 진입) → v3.13.1~v3.13.7(WS 구독 재편·토픽 명명규칙·전략 성과 원장·전략 시작화면·분할매수 P0 2단 fix) → **v3.14.0 (분할매수 자본 재설계 + floor 운영화 + 전략 UX 고도화)**
> **상태**: 7개 baseline 문서 통합 완료. ADR 0128은 R1~R3 + §6 amend(floor 외부화·자동청산 토글)까지 라이브 반영, 분할매수 리스크 풀 분리(지정 목표 금액 매집)는 base 외부주입 추상화 토대 위 미래 트랙.
> **권위**: 본 baseline 폴더는 v3.14.0 시점 **종합 기능 정의서**. SoT는 `docs/architecture/master-architecture.md` v2 (ADR 0030).

---

## 한 장 요약

magicJar v3.14.0은 **분할매수(scale-in) 자본 로직을 라이브 결함 위에서 재설계한 마일스톤 baseline**이다. v3.13.0(REAL canary 진입)에서 시작한 실계좌 운용이 8일차까지 dwell하며, "하락 시 물타기로 평단을 낮추는" 스윙 전략에서 자본이 의도와 다르게 움직이는 5개 결함을 노출했다. v3.14.0은 이를 **base 1차 박제 + 부분매수 잔여 추적 + floor 알람강등/자동청산 토글 + 회차 한도·0% 엣지 fix**로 add-only 교정하고, 분할계획을 빌더·목록 화면에서 사용자가 검증·발견할 수 있게 UX를 고도화했다.

이 사이클의 본질은 **다회차 진입 전략의 자본 안전선을 "회차 예산 고정"과 "catastrophic 손실 bound"로 정밀화**한 것이다. 직전 분할매수 로직은 ① 회차마다 base를 재계산해 1차 매수가 전체노출을 늘리면 2·3차 회차 예산이 쪼그라들고, ② 부분 매수 시 못 산 잔여를 잊고, ③ catastrophic floor에서 기계적 자동 청산해 스윙 반등을 놓치고, ④ totalSlices 한도를 초과해 마지막 가중으로 계속 매수하고, ⑤ weight 0% 회차가 분할 완료 판정을 막아 손절이 영구 보류되는 위험이 있었다. v3.14.0은 base를 1차 시점에 박제(`scaleInBaseBudget` V90)하고, 잔여를 추적(`scaleInRemainderQty` V90)하며, floor를 "알람만(default) + 전략별 자동청산 opt-in"으로 분리했다.

**v3.14.0 시점 동작하는 핵심 흐름** (v3.13.0 baseline + 분할매수 재설계 + 운영 고도화):

1. **분할매수 자본 path 재설계 (ADR 0128 R1~R3)** — `RiskGatewayEvaluator.resolveBuyQuantity`가 회차마다 buyable을 재계산하던 것을 폐기하고, 1차 진입 시점 buyable을 `StrategyPosition.scaleInBaseBudget`(V90)에 박제 → `회차예산 = floor(박제 base × weight%/100)`. 부분 매수 시 못 산 잔여(`scaleInRemainderQty` V90)를 다음 회차에 더함(buyable clamp). `sliceAt(filledLots)` null → 진입 0(totalSlices 초과 차단). weight 0% 회차 skip(`effectiveSliceCount`). base 외부주입 추상화로 미래 "지정 가격 + 목표 금액" 매집 트랙과 회차 산식 변경 0 호환. 라이브 후성(093370) 1·2·3회차 drift 0 검증.

2. **floor 정책 — 기계적 청산에서 알람 + opt-in 청산으로 (ADR 0128 ⑦ + §6 amend ②)** — catastrophic floor(평단 -손절% 알람선)를 #1058의 **자동 청산 → `FloorBreachAlert`(알람만·SELL 미발행)**로 강등(스윙 변동성 반등 보호). amend로 `ScaleInPlan.floorAutoLiquidate`(default false=알람만·`scale_in_config` JSONB·DDL 0) 토글 신설 — 기계적 손실 bound를 원하는 전략만 ON(자동 전량 청산·STOP_LOSS 슬롯). **자본 경로 변경이므로 계정 전체가 아닌 전략 단위 opt-in**(한 전략 toggle ON이 다른 전략·종목 청산에 영향 0).

3. **floor 운영 default 외부화 (ADR 0128 §6 amend ①)** — 도메인 상수 `DEFAULT_STOP_LOSS_FLOOR_PCT=15`의 운영 default 지위 박탈 → `application.yml` `magicjar.strategy.scale-in.floor-default-pct`(default 15)로 외부화(재컴파일 없이 튜닝 + 화면 가시성). api 저장 경로에서 floor null → yml default 주입 후 영속(저장 전략 항상 명시 floor). 도메인 상수는 last-resort 안전망으로만 잔존. 값은 그대로 15%(외부화 목적은 위치 이동·가시성이지 값 변경 아님).

4. **전략 빌더 고도화 (frontend PR #1070 + ADR 0128 R3)** — 분할계획 프리뷰(회차별 목표수량·진입조건·예상손절가·floor 알람선) + 검증 인라인(저장 전 오류 즉시 표기) + RiskPlan 접기 UX + 모바일 대응 + floor 필드 항상 노출(yml default prefill) + 자동청산 토글(default OFF). 복제(`?duplicate=1`) 시 strategyId 비워 신규 저장 분기(기존 전략 덮어쓰기 방지 #1072).

5. **전략 목록 UX (frontend PR #1069·#1071)** — 검색·정렬(`q`·`sort` 파라미터·api) + 카드 밀도 배지 + 빠른 액션 + 상태 요약. `StrategyController` 목록 검색·정렬 파라미터 add-only.

6. **KIS WS 구독 수요 재편 (ADR 0124 — v3.13.x patch, baseline 통합)** — **H0UNCNT0 단일 체결가 채널**(KRX+NXT 통합·종목당 1채널·`NEW_MKOP_CLS_CODE` 세션 구분 — H0STCNT0+H0NXCNT0 이중 발사 폐기) + watchlist KIS WS 구독 폐지(5초 REST polling 강등) + owner 우선순위 재정렬(HOLDING 0 > PENDING_BUY 5 > STRATEGY 10 > DETAIL 20 — 매매 경로가 관찰 경로 우선). 호가 H0UNASP0 통합 호가 전환.

7. **전략 성과 원장 + TP tick failsafe + 전략 시작화면 (ADR 0126·0127 + V85~V89 — v3.13.x patch)** — 전략×일자 왕복성과 + 레짐/α + 메모 누적(00:30 집계·읽기 전용·KIS 0·ADR 0126·V85~V87). 익절 tick 안전망(`PositionTakeProfitTickWatcher`·SL/TP 비대칭 해소·ADR 0127). 전략 시작화면 + 프리셋/즐겨찾기 DB화(`strategy.owner`·`strategy_favorite` V88 + SYSTEM 템플릿 시드 10건 V89).

**v3.14.0 누적 ADR 124건** (파일 0001~0128 중 0058·0059·0060·0065 의도적 갭 — 자세한 인덱스는 `_workspace/arch/adr/README.md`).

**v3.14.0 시점 자동매매 본체 정합 핵심 충족**:

1. **분할매수 자본 안전선 정밀화** — 회차 예산을 1차에 박제해 흔들리지 않게 하고, catastrophic floor를 알람 + 전략별 opt-in 청산으로 분리. 모든 변경 add-only/하위호환(`scaleInBaseBudget` null=라이브 buyable fallback·`floorAutoLiquidate` 부재 default false=알람만). 회귀 0.
2. **AI 호출 0 LOCK(자본 path) 보존** — 자동매매 본체 AI 호출 0. AI는 종목발굴·차트해석·전략 빌더 옵션 보조(Claude CLI bridge·ADR 0112) 매매 결정권 0.
3. **모듈 매트릭스 보존** — domain · pattern-engine · indicator-impl · persistence-shared · strategy-engine · market-trend-engine · api · batch · consumer. 신규 모듈 0(ScaleInPlanProjector·scale-in 필드는 기존 모듈 add-only).
4. **순수성·우선순위 LOCK 유지** — `ScaleInExitCalculator`·`ScaleInPlanProjector`는 Spring·JPA·IO·AI 의존 0(결정론). 우선순위 LOCK(STOP_LOSS > PARTIAL_TP > TAKE_PROFIT > ENTER_SLICE > HOLD) 불변(ADR 0111·0128).
5. **롱 온리 정합** — 분할매수도 BUY=물타기 진입·floor 청산은 보유분 SELL(헌법 §13·`long-only-trading.md` 정합).
6. **라이브 검증 evidence 의무 충족** — PR 머지·gradle check GREEN만으로 종결하지 않고, 라이브 후성(093370) 분할 1·2·3회차 drift 0 + 전략47 4회차 sliceIndex=3 초과 실증을 인용해 결함을 추적·교정(사용자 메모리 `feedback_verification_strict` 정합).

---

## 인덱스

| 파일 | 작성자 | 내용 |
|------|--------|------|
| [01-architecture.md](01-architecture.md) | architect | Mermaid 9 JVM 모듈 + frontend + docker + 외부 통합 + 분할매수 자본 재설계 흐름(base 박제·잔여·floor 알람/청산 토글) + KIS WS 구독 재편(H0UNCNT0 owner priority) + Flyway V83~V90 시계열 |
| [02-data-flow.md](02-data-flow.md) | backend-core | 모듈별 데이터 흐름 + 분할매수 base 박제/잔여 wiring + scale-in-plan API 흐름 + Kafka 토픽 매트릭스 |
| [03-use-sequences.md](03-use-sequences.md) | market-data | 사용자 유즈 시퀀스 + KIS-first raw 출처(H0UNCNT0/H0UNASP0 통합 채널·40 슬롯 회계) + 분할매수 라이브 시퀀스 |
| [04-feature-spec.md](04-feature-spec.md) | backend-core | 트랙 기능 명세 + 분할매수 9 설계 + floor 외부화/토글 + 전략 검색·정렬 + 성과 원장 |
| [05-ui-inventory.md](05-ui-inventory.md) | frontend | 화면·메뉴·버튼 인벤토리 + 전략 빌더 고도화(분할계획 프리뷰·검증 인라인·floor·토글·모바일) + 전략 목록 UX(검색·정렬·카드 배지) |
| [06-history-traceability.md](06-history-traceability.md) | architect | PR·ADR·기능 매핑 (v3.13.0 → v3.13.x → v3.14.0 시계열 + ADR 0124~0128 + 분할매수 PR #1060~#1073 + Flyway V83~V90 매트릭스) |
| [07-policy-evolution.md](07-policy-evolution.md) | architect | 정책 진화 — 분할매수 자본 정책·floor 알람/청산 분리·floor yml 외부화·KIS WS owner priority·전략 UX·release 정책 개정(feature 묶음=minor) |

---

## v3.13.0 → v3.14.0 통계 매트릭스 (분할매수 자본 재설계 마일스톤 baseline)

| 지표 | v3.13.0 (2026-06-12) | v3.14.0 (2026-06-21) | 증감 |
|------|---------------------|---------------------|------|
| Minor 누적 | 13 (v3.13) | 14 (v3.14) | +1 |
| Patch 누적 (minor 사이) | v3.12.1~v3.12.5 | v3.13.1~v3.13.7 | +7 |
| ADR 수 | 123 (파일 카운트) | **124** (+0124·0125·0126·0127·0128) | **+5(파일), 총건수 124 정합** |
| ADR 신규 (v3.14.0 사이클 + 직전 patch) | — | **5** (0124·0125·0126·0127·0128) | +5 |
| ADR amend (v3.14.0 사이클) | — | **0128 §6**(floor yml 외부화 + 자동청산 토글) · **0111**(scale-in 보강) · **0048/0123**(0124로 H0UNCNT0 채택) | +amend |
| 운영 헌법 § | §1~§13 | §1~§13 (보존 — ADR 0128은 0111 amend·자본 결재로 처리) | 0 |
| JVM 모듈 | 9 | 9 (보존 — scale-in 필드·ScaleInPlanProjector는 기존 모듈 add-only) | 0 |
| sub-agent 수 | 7 | 7 (ADR 0094 hierarchy LOCK) | 0 |
| Flyway 버전 | V82 | **V90** (V83 vi_action·V84 fallback_buy·V85~V87 전략 성과·V88 owner/favorite·V89 템플릿 시드·V90 scale-in base/remainder) | +8 |
| 분할매수 회차 base | 회차마다 재계산(비중 왜곡) | **1차 시점 박제(`scaleInBaseBudget` V90)** | LOCK 전환 |
| 부분 매수 잔여 | 망각 | **`scaleInRemainderQty` 다음 회차 더함 clamp(V90)** | 신규 |
| floor 도달 거동 | 자동 청산(#1058) | **알람만(default) + 자동청산 토글(opt-in)** | 정책 분리 |
| floor 운영 default | 도메인 상수(재컴파일) | **application.yml floor-default-pct(가시성·튜닝)** | 외부화 |
| KIS WS 체결가 채널 | H0STCNT0+H0NXCNT0 이중 | **H0UNCNT0 단일(KRX+NXT 통합)** | 통합 |
| watchlist 시세 | KIS WS 구독(slot 잠식) | **5초 REST polling 강등** | 강등 |
| owner priority | HOLDING·PENDING_BUY·STRATEGY·DETAIL | **재정렬(STRATEGY > DETAIL)** | 매매 우선 |
| 익절(TP) | 시그널 경로 의존 | **tick failsafe(`PositionTakeProfitTickWatcher` ADR 0127)** | failsafe |
| 전략 빌더 | 기본 폼 | **분할계획 프리뷰·검증 인라인·RiskPlan 접기·모바일·floor·토글** | 고도화 |
| 전략 목록 | 기본 목록 | **검색·정렬·카드 배지·빠른 액션·상태 요약** | 고도화 |
| AI 호출 (자동매매 본체) | 0 LOCK | 0 LOCK 보존 | 0 |
| GitHub Actions workflow | 0 | 0 (보존) | 0 |

> **Flyway 매핑 주의**: v3.14.0 사이클의 V83~V89는 직전 v3.13.x patch(VI policy·전략 성과·전략 시작화면)에서 적재됐으나 v3.13.0 baseline 매트릭스(V75~V82) 이후이므로 본 baseline에서 통합 추적한다. 실 파일(`api/src/main/resources/db/migration/`)이 권위 — 06-history §Flyway 매트릭스 참조.

---

## v3.13.0 → v3.14.0 사이 minor·patch 사이클 인덱스 (요약)

| 버전 | 일자 | 마일스톤 | 관련 ADR |
|------|------|----------|----------|
| v3.13.0 | 2026-06-12 | REAL canary 진입 (첫 실전 주문·롱 온리·키 사슬 무결성·WS 슬롯 회계 R1·관리자 가시화) | 0122·0123 + 0109 §7 amend + long-only |
| v3.13.1~v3.13.3 | 2026-06-13 | KIS WS 구독 수요 재편(H0UNCNT0 단일 채널·watchlist polling·owner 재정렬) + 토픽 명명규칙 wire clean-cut | 0124·0125 |
| v3.13.4~v3.13.5 | 2026-06-15~16 | 토픽 명명규칙 Phase 2 + 매수 사이징 계좌 전체 공유 풀 + 전략 성과 히스토리 | 0126 |
| v3.13.6 | 2026-06-18 | 손절 자동화·오프라인 체결 정합·TP tick failsafe 승격 + 전략 시작화면 + 프리셋 DB화 | 0127 |
| v3.13.7 | 2026-06-18 | 분할매수 첫회 전량매수 P0 근본fix(scale_in_config 매핑 누락 drift) + WS self-heal 제거 + EXPIRE 가드 | — |
| **v3.14.0** | **2026-06-21** | **분할매수 자본 재설계 + floor 운영화 + 전략 UX 고도화** (base 박제·잔여 추적·floor 알람강등/자동청산 토글·빌더/목록 UX) | **0128 (+§6 amend)** |

---

## 작성 규약

- 본문 한국어, 코드/명령은 영어 그대로
- Mermaid 다이어그램 의무 (flowchart는 `graph` 명시 — `fix-mermaid-syntax.mjs` 정합)
- raw 출처 인용 의무 (KIS API · DART · 코드 path)
- 추측·관념적 서술 금지 — 실코드·실문서 인용만
- TBD 표시 — 미작성 영역

---

## v3.14.0 사이클 핵심 메시지

**분할매수(scale-in) 자본 로직을 라이브 결함 위에서 재설계한 마일스톤 baseline** — v3.13.0(REAL canary 진입)에서 시작한 실계좌 운용이 8일차까지 dwell하며 다회차 진입 전략의 자본 결함 5개를 노출했다. v3.14.0은 이를 정밀 교정한다: ① **base 1차 박제**(회차마다 재계산 폐기·`scaleInBaseBudget` V90·외부주입 추상화로 미래 매집 트랙 호환) ② **부분 매수 잔여 추적**(`scaleInRemainderQty` V90) ③ **floor 알람강등**(자동 청산 → `FloorBreachAlert` 알람만 default) + **자동청산 토글**(`floorAutoLiquidate` 전략별 opt-in) ④ **floor 운영 default 외부화**(application.yml·가시성·튜닝) ⑤ **회차 한도·weight 0% fix**(sliceAt null 진입 0·effectiveSliceCount). 동반: **KIS WS 구독 재편**(H0UNCNT0 단일 채널·watchlist polling·owner priority — ADR 0124) + **전략 UX 고도화**(빌더 분할계획 프리뷰·검증 인라인·모바일 + 목록 검색·정렬·카드 배지). AI는 옵션 보조(Claude CLI bridge)로 매매 결정권 0 LOCK 지속. 모든 변경 add-only/하위호환 — 회귀 0. **release 정책 개정**: feature 묶음 = minor(v3.14.0 첫 적용). **다음 후보** = 분할매수 리스크 풀 분리(지정 목표 금액 매집·base 외부주입 토대) + floor 자동청산 toggle ON 라이브 실증 + 전략 성과 레짐 임계치 튜닝(ADR 0126) + consumer 엔티티 persistence-shared 추출. 자동매매 본체 AI 호출 0 LOCK 지속(ADR 0023·0066·0067·0068·0080·0094 baseline).
