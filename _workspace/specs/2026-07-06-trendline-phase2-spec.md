# spec: 추세매매 Phase 2 — 기울어진 추세선 라이브 매매 연동 (TrendlineRef DSL 노드)

- 작성일: 2026-07-06
- 작성자: architect (오케스트라 위임)
- 결재: 사용자(최고 관리자) — 2026-07-06 "Phase 2도 ADR 발행해서 진행해줘"
- 권위 ADR: **ADR 0140** (`_workspace/arch/adr/0140-trendline-ref-dsl-live-trading.md`) — 결정 본문·대안 비교는 ADR이 권위, 본 spec은 구현 라운드 분할·자원 audit
- 선행: Phase 1 (v3.16.0, PR #1149) — `frontend/src/lib/trendlineStrategy/trendlineDraft.ts` 수평선 프리셋
- 관련 정책: 롱온리(`docs/architecture/policies/long-only-trading.md`) · 개발 룰 3(라운드 분할)·4(코드 먼저 테스트 마지막) · 헌법 §4(CHANGELOG 슬롯)·§11(모듈 합류·silent fallback)

> 목표: 사용자가 종목 상세 차트에 그은 **기울어진 지지/저항선**을 룰 기반 전략의 살아있는 진입·청산 기준으로 연결한다. Phase 1이 남긴 "기울선 = Phase 2" 비활성을 해제한다. **자동 저장·자동 활성화 0** — 사용자가 백테스트 검증 후 저장·활성화하는 Phase 1 계약을 그대로 계승한다.

---

## §1. main 자원 audit (헌법 spec 발행 전 5 자원 점유 검증 — `feedback_spec_audit_existing_resources`)

| 자원 | main 현황 | Phase 2 영향 |
|------|-----------|--------------|
| **Flyway** | 최신 적용 **V98**(`strategy.archived_at` + status CHECK 갱신). V95=strategy_position.trade_plan JSONB·V96=trailing_stop_pct·V97=dsl_exit/min_hold 소급 | **다음 = V99** — `strategy_trendline_ref` 참조 테이블 신설(add-only). 충돌 없음(audit: V99 미점유 확인). |
| **trendline 테이블(V53·V54)** | userId·code·source_interval·line_type·source·anchor1Ts/Price·anchor2Ts/Price·extend_left/right·label·color | **재사용(스키마 무변경)** — anchor 조회만. |
| **trendline REST 5종** | `GET/POST/PUT/DELETE /api/symbols/{code}/trendlines` + `POST /auto` (`TrendlineController`) | DELETE에 **409 참조 가드 추가**(계약 add-only·기존 성공 경로 무변경). 신규 endpoint 0. |
| **strategy 테이블 DSL** | `dsl_entry`(TEXT NOT NULL)·`dsl_exit`(TEXT) — DSL 텍스트 저장 | **재사용** — `trendline(id)` 토큰이 텍스트에 포함. 저장 시 파싱으로 참조 추출. |
| **Kafka topic** | BoundaryClosed → SignalGenerated 경로 (ADR 0125 명명규칙) | **신규 0** — 평가 경로에 노드 분기만 추가. |
| **Redis 키** | kis:ws:approval·kis:oauth:token·reconcile lock 등 | **신규 1(선택)** — `trendline:anchors:{id}` 캐시(어댑터 구현 선택·in-memory 대체 가능). 무효화=선 수정/삭제. |
| **STOMP** | /topic/orders·/topic/positions 등 | **신규 0**. |
| **REST(신규)** | — | **신규 0** — 기존 trendline·strategy REST 재사용. |
| **ENV** | magicjar.dsl.eval.* 등 | **신규 0~2(선택)** — `magicjar.trendline.cache-ttl`(선택)·`magicjar.trendline.eval.enabled`(기능 토글, default true). |

**결론**: 신규 자원은 **Flyway V99 참조 테이블 1건**이 유일한 필수. 나머지는 기존 자원 재사용 + add-only 계약 확장. 병렬 sub-agent 충돌 표면 최소.

---

## §2. 데이터 흐름 (변경 후)

```
[차트] 사용자 기울선 그림 → trendline 테이블 저장(기존 REST)
  → [빌더] "이 선으로 전략 만들기"(R3) → DSL: close crosses_above trendline(123)
  → [저장] StrategySaveModal 사용자 확인 → strategy.dsl_entry/dsl_exit 저장
       ↳ (R2) 저장 훅이 DSL 파싱 → TrendlineRef id 추출 → strategy_trendline_ref upsert
  → [평가] BoundaryClosed → RealtimeSignalGenerator → JvmStrategyEvaluator.evaluate
       ↳ (R1) walk(TrendlineRef) → TrendlinePricePort.anchorsOf(123)[캐시]
              → TrendlineGeometry.priceAt(anchors, 마지막캔들.ts) → NumEval(선가격)
              → Condition 비교(close vs 선가격) → BUY/SELL/HOLD
  → signal → TradePlanFactory(slPrice/tpPrice 박제) → PlanExecutor(기존 경로)

[선 삭제] DELETE → strategy_trendline_ref 조회 → 참조 전략 존재 시 409
[선 강제삭제(관리자)] → 참조 전략 자동 PAUSED + Slack P2
[선 수정] PUT → 캐시 무효화 → 다음 평가부터 자동 반영
```

---

## §3. 라운드 분할 (개발 룰 3 — 한 PR 4 layer 금지)

### R1 — strategy-engine (domain DSL 노드 + parser + evaluator 선형보간)

- **담당**: strategy-engine
- **의존성**: 없음(선두 라운드). domain·strategy-engine 모듈만 touch.
- **범위**:
  1. `domain/.../strategy/dsl/TrendlineRef.kt` — `data class TrendlineRef(val trendlineId: Long) : DslNode()`.
  2. `domain/.../strategy/dsl/TrendlineAnchors.kt` — 불변 VO(anchor1Ts/Price·anchor2Ts/Price·extendLeft·extendRight, `kotlinx.datetime.Instant`).
  3. `domain/.../strategy/dsl/TrendlineGeometry.kt` — 순수함수 `priceAt(anchors, ts): BigDecimal?`(선형보간 + extendRight/Left 연장 + 범위 밖 null + anchor 역순 정규화 + 기울기 0 수평선 경계).
  4. `domain/.../strategy/port/TrendlinePricePort.kt` — `fun anchorsOf(trendlineId: Long): TrendlineAnchors?`(port 인터페이스만·구현 X).
  5. `KisYamlDslParser` — `parseFactor` IDENTIFIER 분기에서 name=="trendline"이면 `TrendlineRef(첫 인자 Long)` 반환(기존 IndicatorRef 함수 파싱 재사용, 다른 식별자는 무영향).
  6. `JvmStrategyEvaluator` — 생성자에 `TrendlinePricePort` 주입 + `walk`에 `is TrendlineRef` 분기: port로 anchors 조회 → null(부재)이면 ERROR 로그(§11-2) + 해당 Condition false 유도(`TRENDLINE_OUT_OF_RANGE`/`TRENDLINE_NOT_FOUND` reason), 있으면 `TrendlineGeometry.priceAt(anchors, candles.last().ts)` → NumEval. `evalCondition`/`evalCrossover`에서 operand가 out-of-range(null)면 BoolEval(false).
  7. **requiredDays** — `Condition.requiredDays`의 extract에 TrendlineRef=0 추가(선은 캔들 lookback 무관).
- **테스트**(R1은 단위 테스트 동반 — DSL 파싱·순수함수는 코드-테스트 결합 자연스러움):
  - `TrendlineGeometry` — 중간 보간·extendRight 연장·extendLeft 연장·범위 밖 null·anchor 역순·수평(기울기 0).
  - parser — `close crosses_above trendline(123)` → Condition(left=close IndicatorRef, CROSSES_ABOVE, right=TrendlineRef(123), CROSSOVER).
  - evaluator — fake TrendlinePricePort로 NumEval 산출·out-of-range false·port null graceful(ERROR 로그 + false).
- **완료 기준**: `:domain:check :strategy-engine:check` GREEN. TrendlinePricePort 구현 없음(다음 라운드) — strategy-engine은 라이브러리 모듈이라 Spring context boot 없음(테스트는 fake 주입).

### R2 — backend-core (V99 참조 테이블 + JPA 어댑터 + 삭제 가드 + consumer 배선)

- **담당**: backend-core
- **의존성**: **R1 머지 후**(TrendlinePricePort 인터페이스·TrendlineRef 노드 필요).
- **범위**:
  1. **Flyway V99** `strategy_trendline_ref`(id·strategy_id·trendline_id·created_at·`UNIQUE(strategy_id, trendline_id)` + FK 또는 논리 참조). add-only.
  2. `TrendlinePricePort` **JPA 어댑터** — trendline 테이블에서 anchors 조회 + **캐시**(in-memory Caffeine 또는 Redis `trendline:anchors:{id}`) + 선 수정/삭제 시 무효화. **@Component로 api·consumer 양쪽 합류**(ADR 0088 패턴·헌법 §11-1) — 실시간 평가는 consumer, 백테스트가 api면 api도.
  3. **참조 무결성 유지** — 전략 저장(생성·수정) 훅에서 `dsl_entry`/`dsl_exit` 파싱 → TrendlineRef id 추출 → `strategy_trendline_ref` 재계산 upsert(전략당 참조 집합 동기화).
  4. **선 삭제 가드** — `TrendlineService.delete`가 `strategy_trendline_ref`로 참조 전략 조회 → 존재 시 **409**(사유 "이 추세선을 참조하는 전략 N개 존재 — 전략을 먼저 보관/정리하세요"). 소유권 검증 유지.
  5. **관리자 강제 삭제 경로** — 참조 전략 자동 PAUSED(StrategyService 재사용) + Slack P2 알림. (강제 삭제 endpoint 또는 flag — 최소 구현: force 파라미터).
  6. **consumer 평가 배선** — `JvmStrategyEvaluator`가 consumer context에서 TrendlinePricePort 빈을 실제 주입받는지 확인.
  7. **손절 경로(§3-4 ADR)** — 선을 손절 기준으로 지정한 전략의 slPrice를 봉 경계(BoundaryClosed 처리 지점)마다 선형보간 값으로 재박제. 기존 `TradePlanFactory`·`PositionStopLossTickWatcher` 재사용(tick watcher는 박제 상수만 봄). **범위 판단**: MVP는 진입/청산 조건 TrendlineRef를 우선 완성하고, 선-기반 동적 SL 재박제는 R2 내 별도 커밋으로 분리(복잡도 격리·필요 시 R2b로 후행).
- **테스트**(R2 통합 — 코드 먼저 원칙이나 참조 무결성·409는 자본 안전이라 동반):
  - 전략 저장 → 참조 upsert / 참조 없는 선 삭제 성공 / 참조 있는 선 삭제 409 / 강제 삭제 → 전략 PAUSED.
  - **모듈 합류 정적 검증(헌법 §11-1)** — consumer(및 api) ApplicationContext에 TrendlinePricePort 빈 존재 단언(PR #888 선례 패턴).
  - 캐시 무효화 — 선 수정 후 다음 조회가 새 anchor.
- **완료 기준**: `./gradlew check` GREEN. V99 로컬 적용 성공. 헌법 §6(Repository body fun 함정)·§11(빈 합류·silent fallback) 정합.

### R3 — frontend + qa (차트 기울선 활성화 + 빌더 표현 + 백테스트 경고 + 검증)

- **담당**: frontend(구현) + qa(Visual/E2E 검증)
- **의존성**: **R1·R2 머지 후**(DSL 노드·참조 API 필요).
- **범위(frontend)**:
  1. `TrendlineOverlay`/차트 — 기울선 선택 시 "이 선으로 전략 만들기" **활성화**(Phase 1 수평 제한 `HORIZONTAL_TREND_TOLERANCE` 게이트 해제·기울선도 허용). tooltip "Phase 2 예정" 문구 제거.
  2. `trendlineDraft.ts` — draft에 **trendlineId 포함**(상수 linePrice 대신 `trendline(id)` DSL 생성). 진입 `close crosses_above trendline({id})`·청산 `close crosses_below trendline({id})`.
  3. `StrategyBuilderPage` — TrendlineRef를 빌더 트리에서 표현(선 라벨·id 표시, "추세선 참조" 노드 UI). 손절 프리필(선 가격 기준 2% — Phase 1 계승·편집 강조).
  4. **백테스트 lookahead 경고** — 백테스트 결과에서 시뮬 구간이 anchor ts보다 과거인 선 참조 시 경고 배지("이 추세선은 백테스트 구간 이후 anchor를 포함 — 성과가 미래정보로 낙관 편향될 수 있습니다"). 백엔드 백테스트 응답 flag 또는 프론트 판정.
  5. 상단 배너(Phase 1 계승) — "차트 추세선에서 생성된 프리셋 — 자동매매 결정을 대신하지 않으며 백테스트 검증 후 저장·활성화" 유지.
- **범위(qa)**: Visual 시나리오 — 기울선 "전략 만들기" 활성·빌더 TrendlineRef 표현·백테스트 경고 배지·선 삭제 409 토스트. E2E — 선 그림 → 전략 draft → 빌더 → (검증 유도) 흐름. 스크린샷 evidence + 모바일 375px.
- **테스트**: frontend vitest(수평/기울 판정·DSL 출력·draft 라운드트립·경고 표시) + `pnpm typecheck`/`pnpm build`. qa Playwright.
- **완료 기준**: `pnpm typecheck`+`pnpm build`+vitest GREEN. qa Visual PASS.

---

## §4. 라운드 매트릭스 요약

| 라운드 | 담당 | 의존 | 핵심 산출물 | 신규 자원 | 완료 게이트 |
|--------|------|------|-------------|-----------|-------------|
| R1 | strategy-engine | 없음 | TrendlineRef 노드·TrendlineGeometry·TrendlinePricePort(IF)·parser·evaluator | 0(코드만) | `:domain:check :strategy-engine:check` |
| R2 | backend-core | R1 | V99 참조 테이블·JPA 어댑터(캐시)·삭제 409 가드·강제삭제 PAUSED·consumer 배선·(선택 동적 SL 재박제) | Flyway V99·Redis 키(선택)·ENV(선택) | `./gradlew check` + 모듈 합류 검증 |
| R3 | frontend+qa | R1·R2 | 기울선 활성화·빌더 표현·백테스트 lookahead 경고·Visual/E2E | 0 | `pnpm typecheck/build`+vitest+qa Visual |

---

## §5. 위험·롤백 (헌법 §9-2·§9-5)

- **위험**: (1) TrendlineRef 평가가 sandbox 예산(100ms/CPU 50ms) 잠식 → 캐시 의무(R2)로 완화. (2) 선 삭제 UX 마찰(409) — 의도된 자본 안전 가드. (3) 백테스트 lookahead 편향 — 경고 표기로 해석 주의 강제. (4) 참조 테이블 누락 시 삭제 가드 우회 → R2 저장 훅이 참조 동기화 보장.
- **롤백**: (1) V99 down(참조 테이블 DROP·add-only 무영향). (2) 평가기/파서 TrendlineRef 분기 revert → 기존 상수 DSL byte-동일(zero-regression). (3) frontend 기능 토글 off → Phase 1 상태 복귀. (4) 이미 저장된 `trendline(id)` 전략은 파서 revert 시 파싱 실패 → 저장 시점 검증으로 활성화 전 차단(자본 진입 0).
- **관측**: TrendlineRef reason 분포(out-of-range·not-found·port null ERROR counter)·선 삭제 409 건수·강제삭제 PAUSED Slack P2·Visual 회귀.

---

## §6. 비범위 (post-MVP)

- tick 수준 선형보간 손절(봉 경계 재박제로 근사) — 후속.
- 채널(평행선 2개)·다중 선 조합 조건 — Phase 3.
- 자동 추세선(`/auto`) → 전략 자동 연결 — 여전히 비목표(수동 선만 매매 연결).
- 선 기울기/각도 기반 추세 강도 필터 — 별도 트랙.
