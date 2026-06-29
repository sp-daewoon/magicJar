---
name: magicjar-autopilot
description: magicJar autopilot(완전 자율 REAL 자기증대 루프) 운영·디버그·증대 매뉴얼. autopilot 가동/점검/개선/사고대응, "오토파일럿 자율증대", 전략 universe·계획기반진입·백테스트 검증·kill-recovery 작업 시 사용. PO=Claude 직접 운영(Codex 미사용).
---

# magicjar-autopilot — 자율 자기증대 운영 매뉴얼

> **정체성**: Claude(나)가 직접 짓고 굴리는 완전 자율 REAL 트레이딩 자기증대 루프. 2026-06-22 사용자 PO 위임. **1원칙: 코드 강제 > 프롬프트**(LLM 판단 비신뢰 — 한도는 코드 회로차단기가 책임). 권위 이력 = 메모리 `project_autopilot_initiative` + `project_session_2026_06_23_overnight` + ADR 0129~0133.

## 0. 검증 규율 (매 작업 의무 — 가장 자주 어기는 부분)

### §0-A 행위 검증 (할루시네이션 금지)
PR 머지·`gradlew check` GREEN만으로 "완료" 종결 **금지**. **라이브 evidence 인용 의무** — 로그·DB·Redis·STOMP·실주문. "단위테스트 ≠ 라이브"(분할매수 첫회 전량매수·Guard 우회 3회 헛발질이 산 교훈). 게이트 켰으면 **실제 발화/거동을 로그·DB로 확인**하고 보고.

### §0-B KIS 크로스체크 (진실원)
실현손익·잔고·체결은 **우리 DB만 보지 말고 KIS(브로커=진실원)와 대조**. `GET consumer:8082/admin/reconcile/realized-pnl?userId=<id>` → `matched=true`·`kisPeriodTotalRealizedPnl` 권위. 우리 gross는 과대(거래비용·net 미반영) 상습 — **autopilot_state.daily_realized_loss는 손실만 누적값**이라 net 아님(승리 상계 안 됨). "흑자/적자"는 KIS net로만 확정.

### §0-C 포지션 정합 self-detect (능동 감지 — 2026-06-29 강한 교훈)
**autopilot의 본질은 사용자가 지적하기 전에 스스로 결함을 찾아 고치는 것이다.** 사용자가 "왜 안 팔려/안 사"라고 지적해야 발견되면 스킬 실패다. ledger(strategy_position) ↔ KIS 실잔고는 **체결 정합 race(WS_PREINSERT — 재진입 시 ACK 전 WS 체결통보 도달 → strategyId=0 stub → strategy_position early-return → replay skip → 체결 영구 드롭)로 전면 drift날 수 있다.** 2026-06-29 실증: 095500 KIS 26주인데 ledger 0 → 익절 SELL이 `SELL_WITHOUT_POSITION`으로 차단(못 팜) / 역방향 유령(ledger 보유·KIS 0)이 `POSITION_LIMIT` 점유 → 신규 BUY 전면 차단(매매 멈춤). §0-B(손익)만으론 못 잡는다 — **보유(포지션) 크로스체크가 핵심.**
- **자동(필수)**: `KisLedgerSelfHealWorker`(ADR 0136, ENV `MAGICJAR_RECONCILE_KIS_LEDGER_SELF_HEAL_ENABLED`) — 주기 10분 KIS↔ledger 크로스체크·drift 자동 정정(KIS 권위)·미해결 시 P0 알림. **반드시 ON 유지** + 가동 로그 확인.
- **수동(세션/이상 시)**: `KIS inquire-balance(9443) 실보유 vs strategy_position` 종목별 대조. 불일치 = drift.
- **증상 트리거 → 즉시 ledger↔KIS 대조**: SELL_WITHOUT_POSITION·POSITION_LIMIT 차단 급증 / 매매 멈춤(BUY·SELL 0인데 차단도 0) / "익절선 넘었는데 미체결" / 보유 수와 체결 이력 불일치.
- 정정은 KIS 실측 일치 동기화만(임의 wipe 금지·전체 wipe는 auto-mode 차단됨 → 종목별 단일 정합). 근본은 체결 정합 fix(ADR 0136).

## 1. 8단계 자율 루프 (Phase 1)
`관찰 → 발굴 → 생성/튜닝 → 검증(백테스트+페이퍼) → 승격(Guard 통과 REAL ACTIVE) → 강등/킬 → 저널 → 반복`
1. **관찰**: owner=AUTOPILOT 성과(KIS net·전략별)·리스크 사용량·kill 상태 + **§0-C 포지션 정합(ledger↔KIS) self-detect**. ops-monitor 감시.
2. **발굴**: `discovery_scorecard`(매일 08:30)·`DaytradeDiscoverService` TOP N.
3. **생성/튜닝**: 빌더 DSL·직전 저널 복습 후 부진 전략 파라미터 튜닝.
4. **검증**: `JvmBacktestEngine` **INTRADAY_REALISTIC 모드**(ADR 0132 — 라이브 parity, TradePlanEvaluator 재사용)로 백테스트 + 페이퍼. ⚠️ LEGACY 모드는 다일 스윙이라 라이브 초단타와 무관(검증 무효).
5. **승격**: 합격 기준(ADR 0133: trades≥20·승률≥40%·net기대값>0·MDD≤15%·정합성 OK) 통과만 ACTIVE.
6. **강등/킬**: 부진 전략 PAUSED. 누적손실 → kill.
7. **저널**: 사이클 결과 `_workspace/autopilot/journal/` + 일일보고 `_workspace/ops/daily/`.
8. **반복**.

## 2. 안전 스택 (3층 — 계획기반진입 ADR 0130)
- **L1 전략권한 + lot 소유권**: 산 전략만 자기 lot 청산(교차청산 차단). SELL 라우팅 strategy_id 일치 lot.
- **L2 거래계획 TradePlan**(`strategy_position.trade_plan` JSONB): 진입 시 박제(TP/SL/trailing/min_hold/contingency). `PlanExecutor`가 계획대로 청산(threshold 휩쏘 아님). 게이트 `MAGICJAR_AUTOPILOT_PLANNED_ENTRY_ENABLED`.
- **L3 항상-on 안전망**: VI 시장가청산(`PositionViAutoExitWatcher`)·EOD 청산(allow_overnight=false·`AutopilotEodLiquidationWorker` 15:15)·계좌 dll(RiskConfig)·종목 floor. **계획 틀려도 손실 bound**.
- **AutonomousTradingGuard / AutopilotBuyGuard**: owner=AUTOPILOT BUY 전수 통과 의무(fail-closed). kill switch: 누적손실 도달 시 자동 ON.
- **거버넌스 = 실 RiskConfig**(5% 풀 칭 폐기·"실거래처럼"): dll_pct·max_concurrent·max_position_abs·trade_freq. auto-pause 거버넌스는 owner=AUTOPILOT 공유풀 과민 회귀 주의(POSITION_LIMIT·자기억제 룰 benign 제외 — ADR 0129/0130).

## 3. 운영 게이트 (ENV — 켤 때 §0-A 검증 의무)
| ENV | 기능 |
|---|---|
| `MAGICJAR_AUTOPILOT_ENABLED` | autopilot 전역 |
| `MAGICJAR_AUTOPILOT_PLANNED_ENTRY_ENABLED` | 계획기반진입(L1+L2) |
| `MAGICJAR_AUTOPILOT_UNIVERSE_REFRESH_ENABLED` | 일일 universe 자동재생성(08:35·ADR 0131) |
| `MAGICJAR_BACKTEST_INTRADAY_REALISTIC_ENABLED` | 초단타 백테스트(읽기전용) |
| `dll_pct`(RiskConfig DB) | 일일손실 한도(%) |
**전부 default 보수적(OFF/false)**. 검증 후 ON. owner=AUTOPILOT 격리·USER zero-regression 불변.

## 4. 7개 함정 (반드시 인지)
1. **stale jar**: 코드 변경 후 `bootJar` 미실행 → 컨테이너 옛 jar. `scripts/build-and-deploy.sh` 정석.
2. **gradle UP-TO-DATE**: compileKotlin 캐시로 재컴파일 skip → `:<mod>:clean :<mod>:bootJar` 강제(#1096 교훈).
3. **엔티티 컬럼 매핑 누락**: `ConsumerStrategyJpaEntity`/Position에 신규 컬럼 매핑 빠지면 SQL은 읽어도 도메인 default로 덮임(owner·scale_in_config·trade_plan drift — ADR 0128/0130). 도메인 필드 추가 시 엔티티 매핑 **동반 의무**.
4. **config 캐시 = 재시작 필요**: `@Value` ENV는 부팅 시 1회 주입. .env 변경 후 `docker restart`로는 안 됨 → `build-and-deploy --no-gradle <svc>`(컨테이너 recreate)로 .env 재로드.
5. **cross-process bean**: api 엔진이 consumer 빈 못 부름(@Autowired null). backfill/엔진 co-locate 확인(ADR 0133 = api 자급).
6. **신호생성↔Guard 식별 경로 불일치**: 다른 쿼리 쓰면 게이트 조용히 우회. owner는 signal이 운반(#1096).
7. **cwd-drift**: worktree 에이전트 bash가 main 체크아웃에서 실행돼 local main 오염. git은 worktree 절대경로(`git -C`) 기준. 자기 cwd 든 worktree는 remove 금지.

## 5. 배포 규율
- **정지 상태(전략 BUY_BLOCKED·kill 등)면 장중에도 배포 OK**(BUY_BLOCKED DB 영속 → 자동재개 안 됨). 라이브 매매 중이면 보수적(api-only는 consumer/매매 무중단 / consumer 재배포는 장마감 후 or 정지 시).
- 순서: **clean 빌드 → api(Flyway 먼저) → consumer**. V번호 충돌 확인. 배포 후 4모듈 docker logs 에러 0 확인(EGW00201 캔들시드 rate limit은 transient).

## 6. kill-recovery 루프 (kill = 종료 아닌 학습 체크포인트)
누적손실 kill 발동 시: ①근본 분석(`autopilot_kill_journal` post-mortem evidence) ②시스템/전략 보정 ③`POST /resume`(보정 사유 동반·envelope 리셋·lifetime 보존) ④재도전. **lifetime 하드플로어**(풀 100% 소진) 도달 시 kill 영구·사용자만 해제.

## 7. 제어 API (api:8080 `/api/autopilot`)
`GET /status`(killActive·pool·손실·전략수) · `POST /stop`(수동 kill) · `POST /resume` · `POST /rollback`(owner=AUTOPILOT만 PAUSE→삭제+청산) · `GET /performance`(owner-scoped net). 롤백 앵커 = **v3.14.0**.

## 8. 일일보고 의무 (장종료)
`_workspace/ops/daily/YYYY-MM-DD.html` + index prepend. 포함: ①KIS net 손익(§0-B) ②**universe 전일 대비 변경(➕추가/➖제외/＝유지)** ③**전략별 평가(거래수·승률·net·기여손익)** ④배포 개선 ⑤발견 결함 ⑥내일 인계. (메모리 `feedback_journal_universe_diff`·`feedback_journal_per_strategy_eval`)

## 9. 디스패치 매트릭스 (자기증대 작업)
- 자본경로 결함 → backend-core(주문/청산) · strategy-engine(전략/계획/리스크)
- 캔들/분봉/KIS → market-data · 설계/ADR → architect · UI/화면 → frontend · 검증/캡처 → qa
- 설계 변경(매매 흐름·캔들·자본) 전 **magicjar-direction-guard** 적용 의무.
