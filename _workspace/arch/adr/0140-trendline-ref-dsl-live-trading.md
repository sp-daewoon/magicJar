# ADR 0140 — 기울어진 추세선 라이브 매매 연동 (TrendlineRef DSL 노드)

- 상태: **Accepted (2026-07-06, 사용자(최고 관리자) 결재)**
- 작성일: 2026-07-06
- 결정자: 사용자(최고 관리자) — 2026-07-06 "Phase 2도 ADR 발행해서 진행해줘" 직접 결재
- 협의: orchestrator(오케스트라 사전 설계 초안) · strategy-engine(DSL 평가 타당성) · frontend(차트 UX) · backend-core(영속·참조 무결성)
- 입력 권위:
  - Phase 1 계약: `_workspace/specs/2026-05-25-symbol-trendline-tool-spec.md` (§1-2 "strategy DSL 연결은 아직 비목표")
  - Phase 1 구현(v3.16.0, PR #1149): `frontend/src/lib/trendlineStrategy/trendlineDraft.ts` — 수평선만 빌더 프리셋 연결, 기울선은 "Phase 2 예정" 비활성
  - 현행 DSL AST: `domain/.../strategy/dsl/` (Operator 9종·LiteralValue·ArithmeticExpr·IndicatorRef·Condition·CompositeCondition)
  - 평가기: `strategy-engine/.../JvmStrategyEvaluator.kt` (AST visitor walk → BoolEval/NumEval)
  - trendline 저장: `api/.../TrendlineController.kt` + `TrendlineService.kt` + `trendline` 테이블(V53·V54)
- 관계:
  - **ADR 0025**(LWC v4 + 자체 overlay — 추세선 렌더링 토대) · **ADR 0092**(strategy-engine 모듈 — 평가기 공유) · **ADR 0088**(persistence-shared — port/adapter 모듈 합류 패턴, 헌법 §11-1 정합)
  - **ADR 0021**(도메인 Indicator 단일 진입점 — TrendlineRef는 Indicator 경로를 우회하는 별도 노드로 분리해 혼선 회피)
  - ADR 0130(TradePlan 박제) · ADR 0127(tick watcher) — 손절 경로 재사용 근거
  - **Phase 1 계약 amend** — `2026-05-25-symbol-trendline-tool-spec.md` §1-2 "DSL 연결 비목표"를 본 ADR로 해제(기울선 한정)
- 마이그레이션: **Flyway V99** — `strategy_trendline_ref` 참조 무결성 테이블 신설(전략↔trendline). trendline 테이블·기존 5 REST는 재사용(스키마 무변경).
- 구현 spec: `_workspace/specs/2026-07-06-trendline-phase2-spec.md` (R1·R2·R3 라운드 분할)

---

## §1. 컨텍스트

Phase 1(v3.16.0)에서 **수평 추세선**은 전략 빌더 프리셋으로 연결됐다 — `close crosses_above {linePrice}`처럼 **상수(LiteralValue)** 하나로 표현된다. 그러나 실제 지지/저항선은 대부분 **기울어진 선**(추세선·채널)이고, 기울선은 시간에 따라 기준 가격이 변하므로 상수 하나로 표현할 수 없다. Phase 1은 기울선을 비활성 처리하고 "Phase 2 예정"으로 남겼다.

핵심 난제: **평가 시점 ts마다 기준 가격이 달라진다.** 사용자가 두 점(anchor1·anchor2)으로 그은 선은 `(anchor1Ts, anchor1Price)`·`(anchor2Ts, anchor2Price)`를 지나는 직선이며, 임의 시점 `ts`의 선 가격은 선형보간으로 구한다:

```
linePrice(ts) = anchor1Price + (anchor2Price − anchor1Price) × (ts − anchor1Ts) / (anchor2Ts − anchor1Ts)
```

이 값을 DSL 비교 대상으로 넣으려면, 상수 자리에 "선을 참조하는 노드"가 필요하다. 동시에 다음 4개 라이프사이클·정합 문제를 함께 닫아야 한다:

1. **선-전략 결합** — 선을 참조하는 전략이 있는데 선이 지워지면 전략의 기준이 증발한다(무결성 붕괴).
2. **선 수정** — 사용자가 선을 다시 그으면 라이브 매매 기준이 어떻게 바뀌어야 하는가.
3. **손절 기준** — 선 자체를 손절선으로 쓸 때, tick마다 변하는 선 가격을 어떻게 tick watcher에 태우는가.
4. **백테스트** — anchor 기반이라 과거 시점 보간은 가능하지만, 사용자가 **미래를 보고 그은 선**을 과거에 적용하면 lookahead bias(미래정보 편향)가 생긴다.

현행 자원은 대부분 재사용 가능하다: trendline 테이블·REST 5종은 완비, DSL 파서/평가기는 IndicatorRef를 함수 호출(`ma(5)`)로 파싱하는 구조라 `trendline(123)` 토큰을 새 노드로 분기하기 쉽다. domain 모듈은 Spring·JPA 의존 0(port 패턴)이라 선 조회는 port로 추상화한다.

## §2. 대안 비교

| 축 | (A) DSL 신규 노드 `TrendlineRef(id)` ★ | (B) indicator 함수 `trendline_price(id)` | (C) 워커가 THRESHOLD 상수 주기 재작성 |
|----|----------------------------------------|------------------------------------------|--------------------------------------|
| 평가 방식 | 새 DslNode. 평가 시점 ts에서 anchor 2점 선형보간 → NumEval | 기존 IndicatorRef 경로에 `trendline_price` 함수 등록, IndicatorEngine이 계산 | 별도 스케줄러가 선 가격을 계산해 전략 DSL의 상수를 주기적으로 UPDATE |
| 타입 안전성 | ★ 전용 노드 — out-of-range·null 분기를 evaluator에서 명시 처리 | IndicatorEngine 계약(캔들 입력→시계열 출력)과 맞지 않음(선은 캔들 무관 anchor 기반). 억지 매핑 | DSL은 그대로 상수 — 안전하나 값이 stale |
| 참조 무결성 | ★ 노드 id로 파싱 시 참조 추출 용이 → 참조 테이블 자동 유지 | 함수 인자에서 id 추출 가능하나 Indicator 식별자 오염 | 상수화되어 선-전략 연결 흔적 소실(어떤 선에서 왔는지 추적 불가) |
| 정합 drift | ★ 참조 기반 — 선 수정 시 다음 평가부터 자동 반영(drift 0) | 참조 기반(A와 동일) | **재작성 주기 사이 stale**·워커 실패 시 옛 값으로 매매(위험) |
| IndicatorEngine 영향 | ★ 0 (별도 walk 분기) | IndicatorSpec/DslIdentifierMapper 확장 필요(ADR 0021 단일 진입점 오염) | 0 |
| 구현 복잡도 | 노드 1개 + port 1개 + parser 1분기 + evaluator 1분기 | 함수 등록 + 캔들 무관 특수 케이스로 IndicatorEngine 예외 처리 다수 | 스케줄러 + 상수 재작성 트랜잭션 + 실패 복구 + 경합 |
| 결론 | **채택** — 타입 안전·참조 무결성·drift 0·IndicatorEngine 무오염 | 기각 — IndicatorEngine 계약(캔들→시계열)과 근본 불일치 | 기각 — stale/워커 실패 = 옛 기준으로 실매매(자본 위험), 추적성 상실 |

## §3. 결정 (LOCK)

### 3-1. 평가 방식 — DSL 신규 노드 `TrendlineRef(trendlineId)`

- domain에 `TrendlineRef(val trendlineId: Long) : DslNode()` 신설. DSL 텍스트 `trendline(123)`을 파서가 `TrendlineRef(123)`으로 분기(기존 IndicatorRef 함수 파싱 재사용, name=="trendline"만 전환).
- 평가기(`JvmStrategyEvaluator.walk`)에 `TrendlineRef` 분기 추가: 평가 시점 = **마지막 캔들의 `ts`**. 선 조회 port로 `(anchor1·anchor2·extendLeft·extendRight)`를 얻고 **domain 순수함수** `TrendlineGeometry.priceAt(anchors, ts)`로 선형보간 → `NumEval(linePrice)`.
- **선형보간·범위 판정은 domain 순수함수(`TrendlineGeometry`)로 격리** — 테스트 용이 + Spring/JPA 무의존. DB 접근은 anchor 조회 port(`TrendlinePricePort`) 한 곳뿐.
- **DSL 예**: 진입 `close crosses_above trendline(123)`, 청산 `close crosses_below trendline(123)`. Phase 1의 상수 자리를 `trendline(id)`가 대체한다.

### 3-2. TrendlinePricePort — 조회 port + 캐시

- `domain`에 `TrendlinePricePort` 인터페이스(port 패턴, ADR 0088 정합):
  ```kotlin
  interface TrendlinePricePort {
      /** trendlineId의 anchor 스냅샷(없으면 null). 구현은 캐시로 sandbox 예산 보호. */
      fun anchorsOf(trendlineId: Long): TrendlineAnchors?
  }
  ```
  `TrendlineAnchors`(domain 불변 VO): anchor1Ts/Price·anchor2Ts/Price·extendLeft·extendRight.
- **캐시 의무**: 평가는 sandbox 100ms + CPU 50ms 예산(ADR 0047) 안에서 tick마다 반복 호출된다. anchor는 선 수정 전까지 불변이므로, 구현(R2 어댑터)은 **선 id별 anchor를 캐시**(in-memory/Redis)하고 **선 수정·삭제 시 무효화**한다. 선형보간 자체는 순수 산술이라 예산 무영향.
- **timestamp 타입**: port는 domain Candle과 정합하도록 `kotlinx.datetime.Instant`를 쓴다(어댑터가 JPA `java.time.Instant` ↔ 변환).

### 3-3. 선-전략 라이프사이클

| 이벤트 | 거동 |
|--------|------|
| **선 수정**(PUT) | 다음 평가부터 자동 반영(참조 기반의 자연 결과). 별도 전략 갱신 없음. 어댑터 캐시 무효화 의무. |
| **선 삭제**(DELETE) — 참조 전략 존재 | **409 차단** — 사유 "이 추세선을 참조하는 전략 N개 존재. 전략을 먼저 보관/정리하세요"(자본 기준 고아화 방지, 전략 아카이브 가드 선례 정합) |
| **관리자 강제 삭제**(별도 경로) | 참조 전략 **자동 PAUSED**(즉시 자동매매 중단) + Slack 알림(P2). 기준 증발 상태로 매매 지속 금지. |
| 선-전략 참조 무결성 | **DB 레벨 참조 테이블 `strategy_trendline_ref`**(strategy_id·trendline_id) — 전략 저장 시 DSL 파싱으로 TrendlineRef id 추출·upsert. 삭제 가드·강제 삭제 PAUSED가 이 테이블을 조회. |

### 3-4. 손절 경로 (R1 보수안)

- 선을 **손절선**으로 지정한 전략은, **봉 경계(BoundaryClosed)마다 선형보간 가격을 slPrice로 재박제**한다. 기존 `TradePlan`·`PositionStopLossTickWatcher` 구조를 그대로 재사용 — **tick watcher는 박제된 slPrice(상수)만 본다.** 매 봉 close에서 slPrice를 최신 선 가격으로 갱신·재박제하는 것만 신설.
- **tick 수준 선형보간(tick마다 선 가격 재계산해 즉시 손절)은 후속 과제**로 기록(§5). 봉 경계 재박제로도 기울기 완만한 선은 충분히 근사(봉 내 오차 = 봉당 기울기 변화분).

### 3-5. 백테스트 — lookahead bias 경고 명시

- anchor 기반이라 과거 시점 보간은 계산적으로 가능하다. 그러나 사용자가 **미래 pivot을 보고 그은 선**(anchor2Ts가 백테스트 시뮬 시점보다 미래)이면 그 선은 미래정보를 담는다 → 백테스트 성과가 낙관 편향된다.
- **결정**: 백테스트 실행 시 시뮬 시점 `ts < max(anchor1Ts, anchor2Ts)`인 구간이 존재하면 **결과에 lookahead bias 경고를 명시 표기**한다("이 추세선은 백테스트 구간 이후 시점의 anchor를 포함 — 성과가 미래정보로 낙관 편향될 수 있습니다"). frontend가 경고 배지로 노출(R3). 백테스트를 막지는 않되 해석 주의를 강제 노출.

### 3-6. 선 범위 밖 — 비활성(조건 false)

- `extendRight=false`인 선의 **anchor2Ts 이후** 평가는 선 밖 → 조건 **false(비활성)**. `extendLeft=false`인 선의 **anchor1Ts 이전**도 동일.
- 구현: `TrendlineGeometry.priceAt`가 범위 밖이면 `null` 반환 → 평가기는 해당 Condition을 `BoolEval(false, reason=TRENDLINE_OUT_OF_RANGE)`로 처리(operator 무관). extend=true면 선 slope를 연장해 값 산출.
- **port bean 부재/선 조회 실패**(§11-2 silent fallback 금지): null 분기는 **ERROR 로그 가시화** + 조건 false. REAL 모드에서 자본 port는 fail-fast 대상이나, TrendlineRef는 "조건 미충족(false)"이 자본 안전 방향(BUY 안 함)이므로 **degraded=false 진행 + ERROR 로그**로 명시 처리(silent 0).

## §4. 비결정 (후속 검토 — 본 ADR 범위 밖)

- tick 수준 선형보간 손절(§3-4 후속) — post-MVP.
- 채널(평행선 2개)·다중 선 조합 조건 — Phase 3 후보.
- 자동 추세선(`/auto` pivot) → 전략 자동 연결 — 여전히 비목표(자동 추세선은 시각 보조선, Phase 2도 사용자 수동 선만 매매 연결).
- 선 기울기/각도 기반 필터(추세 강도) — 별도 트랙.

## §5. 검증

- domain `TrendlineGeometry` 순수함수 단위 테스트 — 보간(중간)·extendRight 연장·extendLeft 연장·범위 밖 null·anchor 역순·수평선(기울기 0) 경계.
- 평가기 `TrendlineRef` walk — NumEval 산출·out-of-range false·port null graceful.
- 참조 무결성 — 전략 저장 시 참조 upsert·선 삭제 409·강제 삭제 PAUSED (R2 통합 테스트).
- **모듈 합류 검증(헌법 §11-1)**: `TrendlinePricePort` 구현 빈이 이를 소비하는 **consumer**(실시간 평가) ApplicationContext에 합류하는지 정적 검증(ADR 0088 패턴). 백테스트가 api에서 도는 경우 api 합류도 동반.
- 백테스트 lookahead 경고 표기 — 시뮬 ts < anchor ts 구간에서 경고 flag set 검증.

## §6. 운영 슬롯 (헌법 §9-5)

- **SLO 영향**: 유지. TrendlineRef 평가는 캐시된 anchor + 순수 산술이라 시그널→주문 P95<5초 예산 무영향. 캐시 미스 시 DB 1회 조회(선 id별 최초 1회)만 추가.
- **rollback plan**: (1) V99 down(`strategy_trendline_ref` DROP — 참조 테이블은 add-only, 기존 전략 무영향) 또는 잔류 무해. (2) 평가기 `TrendlineRef` 분기·파서 분기 revert 시 기존 상수 DSL은 byte-동일 동작(zero-regression). (3) frontend 기울선 활성화 toggle off → Phase 1 상태 복귀. (4) 이미 저장된 `trendline(id)` DSL 전략은 파서 revert 시 파싱 실패 → 저장 시점 검증 게이트로 활성화 전 차단(자본 진입 0).
- **관측 매트릭스**: (a) TrendlineRef 평가 reason 분포(`TRENDLINE_OUT_OF_RANGE` 비율·port null ERROR 로그 counter) (b) 선 삭제 409 거부 건수 (c) 강제 삭제 → 전략 PAUSED Slack 알림(P2) (d) **Visual 회귀(frontend)**: 차트 기울선 "전략 만들기" 활성화·빌더 TrendlineRef 표현·백테스트 lookahead 경고 배지 (qa Visual R3).

## §7. 결과

- **긍정**: 기울어진 지지/저항선을 룰 기반 전략의 살아있는 기준으로 승격(Phase 1 수평 한계 해제). 참조 기반이라 선 수정이 매매 기준에 drift 0으로 반영. IndicatorEngine 무오염(별도 노드). domain 순수함수로 보간 로직 격리(테스트·이식 용이).
- **부정/비용**: DSL 노드·port·참조 테이블·삭제 가드 신설 = 라운드 3개(R1 strategy-engine·R2 backend-core·R3 frontend+qa). 선 삭제 UX에 409 마찰 추가(전략 정리 유도 — 의도된 안전 가드). 손절 tick 정밀도는 봉 경계 근사(tick 정밀은 후속).
- **자본 영향**: TrendlineRef 조건은 진입/청산 트리거만 바꾼다(주문 발사는 기존 marketable limit·TradePlan 경로 그대로). 자동 저장·자동 활성화 0 — 사용자가 백테스트 검증 후 저장·활성화(Phase 1 계약 계승).
- KIS 호출 0(선은 사용자 입력·DB) · AI 호출 0 · mock 합성 0 · 롱온리(§13) 정합(선 돌파 BUY / 이탈 SELL=청산).
