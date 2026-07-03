# magicJar 전 영역 시스템 감사 보고서

- **일자**: 2026-07-03
- **트리거**: 최고 관리자 — "시스템이 점점 바보가 되어가는 것 같다. 문제점·개선점을 낱낱이 찾아라"
- **방법**: 5개 전문 감사 트랙 병렬 수행 (아키텍처·빌드 / 매매 코어 / 시세 데이터 / 거버넌스·문서 / 프론트·AI·운영). 모든 지적은 실제 코드·문서 판독으로 검증됨 — 추정 0건.
- **총 발견**: P0 1건 · P1 12건 · P2 16건 · P3 12건

---

## 0. 종합 진단 — "왜 점점 바보가 되는가"

코드의 뼈대(domain 순수성, fail-closed 게이트, 멱등, outbox)는 건강하다. "멍청해진다"는 체감은 아래 **4개 독립 메커니즘의 합성**으로 설명된다.

| # | 메커니즘 | 성격 | 대표 결함 |
|---|---------|------|----------|
| 1 | **시간 비례 성능 부채** — 운영일수가 늘수록 실제로 느려짐 | 단조 악화 | 1m 캔들 풀스캔 (§2-①) |
| 2 | **규모 비례 시세 기아** — 전략·보유 종목이 늘수록 매매 경로 tick 품질 저하 | 규모 악화 | slot priority eviction 미구현 (§2-③), WS 재구독 누락 (§1) |
| 3 | **백테스트 낙관 편향** — 라이브가 "상대적으로" 멍청해 보이는 착시 + 실제 튜닝 오류 | 구조 편향 | LEGACY SL/TP 미평가 (§2-②) |
| 4 | **문서 계층 부패** — 에이전트에게 구식 정보가 매 작업마다 강제 주입 | AI 품질 저하 | CLAUDE.md/INDEX 3주 동결, chart-curator 유령 (§4) |
| + | **무알림(silent rot)** — 위 전부가 조용히 진행됨 | 증폭기 | 알림 스택 부재 (§3) |

---

## 1. P0 — 즉시 조치

### P0-1. KIS WS 재연결 시 호가·VI·시간외 채널 재구독 누락 → VI 자동 청산 보호 silent 상실
- **근거**: `consumer/.../ws/KisWebSocketBridge.kt:962-984` — `onConnected` 재구독은 체결가(H0UNCNT0)+체결통보만 재발사. `orderbookSubscribedCodes`·`marketStatusSubscribedCodes`·`overtimeSinglePriceCodes`는 재발사 0. 로컬 Set이 유지되므로 이후 구독 시도가 "이미 구독됨"으로 영구 no-op. 같은 파일 :205-212에 "KIS는 매시 정각 연결을 끊는다"는 운영 evidence 명기 — **매 거래일 첫 정각 재연결 후 호가·VI 스트림이 KIS측에서만 사라짐**.
- **영향**: ① STRATEGY marketable-limit 진입용 `orderbook:top`(TTL 10s) 고갈 → 진입 fail-closed로 전략이 조용히 정지 ② **VI 정책(EXIT_MARKET) 보유 종목의 VI 이벤트가 무검출 단절 — 자본 리스크 직결**.
- **개선**: `onConnected`에서 3개 Set도 동일 재발사, 또는 재연결 시 로컬 Set clear 후 라우터 resync.

---

## 2. P1 — 매매 성과 저하 직접 원인 (Trading Core)

### ① BoundaryAggregator가 boundary마다 1m 캔들 **전체 이력 풀스캔** — 유일한 단조 악화 기제
- **근거**: `consumer/.../aggregation/BoundaryAggregator.kt:231-246` — `findByCodeAndIntervalOrderByTsAsc(code, "1m")` 전량 조회 후 메모리 필터 (주석 자인). 1m/3m 캔들 **시간 기반 retention 삭제 없음** (repo 확인).
- **영향**: 종목당 1m 캔들 하루 ~720행 무한 누적. 정각엔 8개 interval 동시 fire × 종목 수만큼 풀스캔 → boundary close 발행 지연 → 시그널 평가·진입가 열화 + GC 압박. **"운영할수록 나빠진다"와 정확히 일치**.
- **개선**: `findByCodeAndIntervalAndTsBetween` 범위 쿼리로 교체(유사 쿼리 기존재) + 1m/3m retention 잡 신설.

### ② LEGACY 백테스트가 비-scale-in 전략의 SL/TP/트레일을 평가하지 않음 — 백테스트로 튜닝한 전략이 라이브에서 딴판
- **근거**: `api/.../backtest/JvmBacktestEngine.kt:303-373` — scale-in 아니면 청산은 DSL sellCondition뿐. 라이브는 `RealtimeSignalGenerator.kt:742-836`에서 fixedStopLoss/takeProfit 상시 평가 + tick 손절 watcher 별도. 민감도 sweep(`:1378-1388`)도 이 경우 **silent no-op**. 정합 모드 INTRADAY_REALISTIC은 잘 구현돼 있으나 default OFF(`IntradayRealisticBacktestProperties.kt:32`)라 요청해도 LEGACY 강등(`:130-138`).
- **영향**: 백테스트 성과 기준 채택·튜닝된 전략이 라이브에서 전혀 다른 청산 분포 → 성과 괴리의 구조적 원인 + 잘못된 파라미터 선택 유도.
- **개선**: 단기 — LEGACY에 `evaluatePlainPositionExit` 동일 산식 삽입. 중기 — INTRADAY_REALISTIC 검증 후 default 승격.

### ③ WS slot 경합 시 priority 기반 eviction **미구현** — 정책(ADR 0114/0124) 반쪽 이행
- **근거**: `consumer/.../ingestion/RealtimeSubscriptionRouter.kt:278-329` — victim은 "어느 demand에도 없는 잔여 구독"만. `priority`는 로그에만 사용. 후보 없으면 신규 요청 `queued`(구독 안 함). "DETAIL evict·STRATEGY 보호" 선점 조항 미구현 (HOLDING/PENDING_BUY evict 금지 조항만 구현됨). 통합 ledger admission(:303-311)은 40 초과 시 신규 HOLDING까지 보류.
- **영향**: PRICE_TICK_MAX=30이 차면 신규 ACTIVE 전략·신규 보유 종목이 tick을 못 받고 5초 REST degraded feed로 강등 — **전략 수·화면 사용이 늘수록 매매 경로가 멍청해짐**. DISABLED 전략 보유 종목은 degraded poller 대상에서도 누락 가능.
- **개선**: victim 선정을 priority 최댓값(DETAIL=20) 순 확장 + HOLDING/PENDING_BUY 제외 LOCK 유지. queued 발생 시 WARN+Slack.

### ④ DSL `close` = 수정주가 우선, 지표(ma·rsi 등) = 원주가 고정 — 혼합 비교
- **근거**: `JvmStrategyEvaluator.kt:562-588`(closeAdj 우선) vs `IndicatorMath.kt:104-110`(adjusted=false default, Native 5종·KIS 80종 전부 원주가). `close > ma(20)`이 수정주가 vs 원주가 비교가 됨. 백테스트 `PriceAdjustmentMode.RAW` 요청도 DSL close는 adj를 읽어 무력화(`JvmBacktestEngine.kt:1334-1353`).
- **영향**: 액면분할·배당락 종목에서 조건 체계적 왜곡 (일봉 전략·스크리너 특히).
- **개선**: 가격 소스 정책 단일화 — directCandleAccess와 IndicatorMath가 동일 모드 플래그 공유.

## 2′. P1 — 데이터 품질 (Market Data)

### ⑤ tick 가격/OHLC sanity 검증 0 — 공백 가격이 **0원 캔들로 영속**되고 readiness는 READY로 통과
- **근거**: `KisWsMessageParser.kt:177` — `BigDecimal(priceStr.ifBlank { "0" })`. `LiveCandleUpdater.kt:56-121`·`TickToMinuteAggregator.kt:140-166` — price>0 게이트 없이 OHLC 반영·DB upsert. DataReadinessService는 **freshness만 검사, validity 미검사**.
- **영향**: 글리치 1건이 low=0 캔들로 영속 → 합산봉(3m~12h)·지표(FVG/오더블록/저가 스탑) 전파 오염. 무검출.
- **개선**: 파서에서 blank/≤0 reject + metric, aggregator upsert 전 `low>0 && high≥low && open,close∈[low,high]` 가드.

### ⑥ ADR 0124 수용 기준 위반 — `NEW_MKOP_CLS_CODE` 세션 구분이 주석에만 존재
- **근거**: `KisWsMessageParser.kt:259-265` — `parseH0Uncnt0`이 KRX용 파서 재사용, 세션 필드 미판독. grep 결과 comment 3곳뿐. ADR 0124 수용 기준 #2 명시 위반.
- **영향**: KRX 정규장/NXT/애프터마켓/예상체결이 구분 없이 한 캔들 스트림으로 합성 — 특히 동시호가 예상체결 tick 오염 가능, 감지 수단 0.
- **개선**: 세션 필드 인덱스 파싱 추가 + TickEvent session 태그 + 캔들 반영 정책(예상체결 제외 등) 명시.

### ⑦ "WS 30초 단절 → 전 종목 5초 polling" 폴백이 실제로는 **완전 no-op** — 문서는 반대로 기술
- **근거**: `WatchlistPollingWorker.kt:33-67` — 2026-06-15 amend로 KIS 호출 전부 제거된 stub. `KisWebSocketBridge.kt:441-444`의 `onPollingFallbackStart/Stop` 훅 등록처 **레포 전체 0건**. `candle-data-policy-v2.md:220-223`·CLAUDE.md는 폴백 존재를 전제.
- **영향**: WS 장기 단절 시 ACTIVE 전략 명시 종목 외(전략에 안 걸린 보유 종목·watchlist)는 시세 공백. SoT 문서가 오정보 주입.
- **개선**: 문서를 현실로 정정 + WS 장기 단절 시 HOLDING 한정 REST 폴백을 degraded poller에 편입할지 명시 결정(ADR).

## 3. P1 — 관측성: "알림이 전부 앱 생존에 의존" (2개 트랙 독립 검증)

### ⑧ Prometheus alert rule 0건 · Alertmanager 부재 — 시스템이 죽으면 알림도 같이 죽음
- **근거**: `docker/observability/prometheus/prometheus.yml` — `rule_files`/`alerting` 섹션 자체 없음. `docker-compose.observability.yml` — Alertmanager 컨테이너 없음. Grafana 대시보드 3종 alert 0건. 유일한 자동 알림 = consumer 프로세스의 Kafka SystemEvent → Slack (`SystemEventNotificationDispatcher.kt:27-46`).
- **영향**: consumer·Kafka·호스트가 죽으면 시세 수집·전략·주문 전부 정지하는데 **어느 채널로도 알림이 안 감**. ADR 0095 routing matrix는 문서로만 존재 — 임계치 평가 엔진이 없음.
- **누락 알림 (전수 검색 확인)**: process down(`up==0`) / 장중 시그널 N시간 부재 / WS 단절 지속시간 / 주문 거절률 / DLQ depth(메트릭 자체 없음) / outbox drain lag>30s SLO / 1m·tick staleness(1m 감시는 ADR 0119로 제거, 일봉만 잔존 + 무tick watchdog은 WARN 로그뿐).
- **개선**: Alertmanager(또는 Grafana unified alerting) + 최소 규칙 4개: `up==0`, 장중 시그널 0건 2h, WS_DISCONNECTED 10분 지속, outbox lag>30s. S-1 P95>5s 포함.

### ⑨ 프론트 STOMP 재연결 후 topic 재구독 부재 — "CONNECTED"인데 실시간 데이터 영구 정지
- **근거**: `frontend/src/lib/stomp.ts:107-137` — mount 시 1회 subscribe, `onConnect` 재구독 없음 (@stomp/stompjs는 자동 복원 안 함). 재구독 로직 레포 전체 부재 (backfill·배지 훅만 존재).
- **영향**: WS 1회 단절 후 자동 재연결되면 tick·캔들·호가·주문/시그널 toast 구독 전부 사망, 배지는 CONNECTED — 사용자 오인.
- **개선**: `useStompTopic`에 connection generation counter deps 추가 또는 `onConnect` 시 재구독 — 5줄 내외 패치.

## 4. P1 — 거버넌스·문서 부패 (AI 에이전트를 멍청하게 만드는 축)

### ⑩ 최상위 진입점 3종이 2026-06-13 이후 동결 — 이후 ADR 12건(0125~0136, 자본 경로 핵심)·릴리스 3번 미반영
- **근거**: git log — CLAUDE.md·INDEX.md 최종 2026-06-13. CLAUDE.md/INDEX "총 120 ADR" vs `adr/README.md` "총 132건". INDEX "최신 v3.11.0" vs 실제 baseline v3.14.0. `magicjar-direction-guard`가 이 stale 문서를 "먼저 읽어라" 의무화 → **구식 정보가 매 작업 강제 주입**.
- **연쇄 불일치**: 모듈 수 4곳 4개 숫자(CLAUDE.md 7 / ADR README 6 / ADR 0004 8 / settings.gradle.kts **실제 9** — pattern-engine·market-trend-engine 누락). GitHub Actions 3중 모순(orchestrator SKILL "존재" / CLAUDE.md "폐기" / 근거 파일 `feedback_no_github_actions.md` repo 부재 / 실제 `.github/workflows` 없음). `master-architecture.md:279` domain 경로 `com/mj/` 오기. `docker-compose.dev.yml:18` `LOGGING_LEVEL_COM_MJ` no-op env.
- **개선**: 수치는 문서에서 제거하고 링크만 남기거나 release.sh 훅으로 자동 갱신. settings.gradle.kts를 모듈 수 단일 권위로 선언.

### ⑪ chart-curator 유령이 dispatch 경로에 잔존
- **근거**: `.claude/agents/frontend.md:19-26` — 해고된 chart-curator에게 차트 소유권 이관 지시(Superseded ADR 0085 기준). `.claude/skills/magicjar-chart-curator/SKILL.md` — 아카이브 없이 **live 트리거**("차트 추가"·"캔들 결함") + 존재하지 않는 `_workspace/chart-curator/` 참조 + 해고 author 커밋 지시(pre-commit whitelist가 통과시킴). `backend-core.md:108-119` cross-review 매트릭스도 현역 전제.
- **개선**: frontend.md를 ADR 0094 기준 재작성, chart-curator SKILL 아카이브 이동, backend-core 매트릭스 owner 재지정.

### ⑫ ADR 상태 라이프사이클 붕괴 + 인덱스 자기 주장 거짓
- **근거**: **Proposed** 0114가 CLAUDE.md "LOCK" + 코드 주석 + Accepted 0115의 기반으로 운용. 0123·0117도 동일 패턴. `adr/README.md:5` "불일치 0 정합" 주장 ↔ 스팟체크 5건 중 2건 불일치(0073 Superseded vs 파일 Proposed 등). 6~7주 방치 Proposed 8건, 그중 0090은 "chart-curator 합작 후 격상" — **해고된 에이전트를 기다리는 실행 불가 조건**. `.husky` 훅 주석의 ADR 번호 전부 옛 체계(0009=frontend SPA를 push 정책 근거로 참조 등).
- **개선**: 구현 머지된 0114·0123·0117 일괄 Accepted 승격, 인덱스↔헤더 정합 검증 스크립트를 release에 추가, 훅 ADR 번호 0031/0036 체계로 치환, 분기별 Proposed triage 의례.

## 5. P1 — 빌드·검증 인프라 + 보안

### ⑬ 통합 스모크 3 시나리오 전부 공허(vacuous) — 거짓 안전망
- **근거**: `scripts/smoke.sh:89` `POST /api/backtest` ↔ 실제 `/api/backtests`(`BacktestsController.kt:69`) → 영구 404 SKIP. `:135` `/api/news/scrape` — 매핑 자체 부재 → 영구 SKIP. 시나리오 2는 tick 발행 후 `/actuator/health`만 확인(시그널 검증 0). 그런데 exit 0.
- **개선**: endpoint 정정 + 시나리오 2를 signal 테이블/metric 확인으로 + SKIP 2연속 릴리스 시 FAIL 승격.

### ⑭ KIS 실계좌 appkey/appsecret 평문 파일이 레포 폴더 내 존재
- **근거**: `docs/operations/http-client.private.env.json` — real/vps 실값 평문. **git 미추적·이력 없음 확인**(.gitignore 정상) — 커밋 유출은 아니나, 레포 zip/공유/AI 세션 노출 시 실계좌 키 동반 유출.
- **개선**: `~/.config/magicjar/` 등 레포 밖 이동 + 예방적 키 재발급 권장.

### ⑮ api/consumer/batch 3중 복붙 — 공유 모듈 원칙(ADR 0083/0088/0092) 미완, 이미 diverge 시작
- **근거**: KIS rate limiter 3벌(consumer판 주석이 "동일 알고리즘" 자인). api↔consumer 동일 파일명 11종 — `TraceIdKafkaProducerInterceptor`(64 vs 48줄)·`ChartCandleProperties`(101 vs 122줄) **이미 diverge**. hotfix를 3벌에 각각 반영한 흔적 존재.
- **개선**: rate limiter 1건부터 공유 라이브러리 승격(domain port 기존재 — 구현 1개+모듈별 @Configuration). 나머지는 후속 ADR.

---

## 6. P2 (16건 요약)

| # | 발견 | 근거 | 개선 |
|---|------|------|------|
| 1 | 백테스트 체결가 = 신호봉 close (라이브는 다음 봉+2-hop 지연) — 낙관 편향 | `JvmBacktestEngine.kt:244-372` | 다음 봉 open 체결 옵션 or INTRADAY_REALISTIC 이관 |
| 2 | LEGACY 백테스트에 정규장(09:00-15:30) 필터 없음 — NXT 시간대 봉으로도 매매 | 캔들은 08~20시 수집, 라이브는 suppress | barTimeKst 정규장 필터 추가 |
| 3 | MAX_HOLD elapsedBars: 라이브=벽시계, 백테스트=거래봉 — 오버나잇 시 개장 직후 강제 청산 | `PlanExecutor.kt:292-301` vs `JvmBacktestEngine.kt:607` | 거래시간/캔들 카운트 기준 통일 |
| 4 | 트레일링 고점 인메모리 전용(재기동 시 리셋) + stale tick 전면 무시 → degraded feed에서 TP/TRAIL/MAX_HOLD 평가 정지 | `PlanExecutor.kt:81,143` | highWatermark 영속 + fallback 가격 direct path |
| 5 | `position` 테이블 qty/quantity 이중 컬럼 + api·consumer @Entity 정의 상이(user_id 40 vs 20) — 보유 수량 진실 2개 위험 | 양 모듈 `PositionJpaEntity.kt`, `V18__position.sql` | quantity 단계적 폐기, persistence-shared 단일화 |
| 6 | domain에 `TradingMode` enum 2벌 (position/ vs order.execution/) — 매매 안전 타입 중복 | convention §2-4 위반 | order.execution판으로 통일(typealias 3단계) |
| 7 | stale-jar 가드 사각 — 공유 라이브러리 6모듈 변경은 freshness 미탐지 (전략 DSL 수정 후 옛 jar 잔존 가능) | `smoke.sh:28-44` | find 대상에 6모듈 추가 (6줄) |
| 8 | dev compose jar mount 실효 0 — 핫 리로드 착시 (ENTRYPOINT는 COPY된 jar만 읽음) | `docker-compose.dev.yml:14-15` | 무의미 volumes 삭제 |
| 9 | build-and-deploy "healthy 대기" = sleep 30 + 무조건 exit 0 | `build-and-deploy.sh:171-173` | health poll 90s + unhealthy 시 exit 1 |
| 10 | OPSP8997 해제 거부 무교정 + TR_TYPE 주석 상호 모순("2" vs "절대 2 금지") — slot 회계 침식 | `KisWebSocketBridge.kt:200,1163-1168` | KIS 계약값 재확정 + ledger 보정 |
| 11 | sendSink `tryEmitNext` 반환값 전부 미확인 — HOLDING 구독 메시지도 silent drop 가능 | `KisWebSocketBridge.kt:188-191 외` | 실패 시 재시도 큐 or WARN+카운터 |
| 12 | 공휴일 캘린더 3중 불일치 (미시드 시 주말 fallback만) — 연휴 뒤 false stale·불필요 KIS 호출 | `ConsumerBusinessDayCalendar.kt:44-53` 외 | boot 시 시드 검증 + calendar 포트 통일 |
| 13 | Naver/DART 실패가 "빈 결과"로 위장 (파싱 실패→emptyList, key 미설정→INFO skip) — 수집 사망 감지 불가 | `NaverNewsAdapter.kt:247-251` 외 | source별 last_successful_fetch gauge |
| 14 | S-AI-1 월 $10 비용 알림 dead code — `isOverThreshold()` caller 0건 | `AiCostTracker.kt:34,53` | 일 1회 스케줄러 → SystemEvent CRITICAL |
| 15 | DLQ depth 메트릭 0 + 재처리 runbook 0 — `order.intent.v1.DLT` 유실 주문 방치 가능 | `OrderDltMonitorListener.kt`, runbook R1~R5 | DLT counter + R6 replay runbook |
| 16 | 종목상세 tick·호가마다 페이지 전체 리렌더 (SymbolChart 1,218줄 등 memo 0) + backlog 3,376줄 아카이브 미집행 + fallback 캔들 BUY default 허용 | `SymbolDetailPage.tsx:192-224` 외 | memo화·tick store 격리 / done/ 분리 / default false 검토 |

## 7. P3 (요약)

수학·계약상 무해하나 인지 필요: RSI가 Wilder 아닌 EWM(KIS 정합 목적, 문서화됨) · evaluator에 "마지막 캔들=마감봉" 검증 부재(현 호출부는 안전) · BoundaryClosingKafkaListener Double 경유 파싱 · Spring AI 1.0.0-M3 milestone BOM + allprojects milestone repo · market-trend-engine 주석의 소비자 과장 · consumer admin REST 7종 문서 밖(8082 무인증 backfill wipe 등 — 보안 검토 필요) · compose: consumer-app depends_on redis 부재, batch/consumer/frontend healthcheck 미정의 · `@Service`가 controller 패키지 상주 · DETAIL sendBeacon false 반환 시 unsubscribe 누락 + idle 5분(코드) vs 15분(정책) 불일치 · stomp.ts prod console.debug + 파싱 실패 무음 drop · WATCHLIST owner 주석 잔재 · DART/Naver 시각 파싱 실패 시 `Instant.now()` 대체.

---

## 8. 잘 되어 있는 점 (교차 검증 완료 — 유지할 것)

- **domain 순수성 실제 이행** (Spring/JPA import 0건), 라이브러리 모듈 단방향, 순환 의존 0.
- **실계좌 3중 게이트**(MODE=REAL ∧ ALLOW_REAL ∧ 사용자 동의), paper 전략 무조건 MOCK 강제.
- **멱등·중복 방지 다층**: Redis SETNX 주문 멱등(fail-closed), signal dedupe, outbox 정석 구현.
- **룩어헤드 가드**: LookaheadGuard + history `ts < boundaryTs` exclusive — 평가 시점 오염 없음. FVG/OrderBlock 수학 정상(룩어헤드 없음).
- **1m closing 파이프라인**: 원자 RENAME 스냅샷 + deterministic closingTs + ON CONFLICT 멱등 upsert.
- **rate limit·토큰 관리**: 3모듈 공유 Redis bucket, 10 TPS LOCK, 23h TTL+분산락.
- **AI 매매 결정권 0 실제 준수**: 주문/시그널 경로 AI import 0건, LLM import 2곳 격리(룰 5 준수), 4중 게이트 실배선.
- **FVG 가드 준수**: mock 박스 없음, 오류/준비/재수집 3경로 실구현. TS strict 완전 적용. 로그 정책 100% 정합.
- **런타임 정책-코드 정합 우수**: 거버넌스 트랙이 의심한 8개 claim 중 **코드 측 위반 0건** — 부패는 문서 계층에 집중.

---

## 9. 권장 조치 로드맵

**Phase 1 — 이번 주 (자본 보호·저비용 고효과)**
1. P0-1 WS 재연결 3채널 재구독 (VI 보호 복원)
2. §2-① 1m 풀스캔 → 범위 쿼리 (단조 악화 절단)
3. §3-⑨ STOMP 재구독 5줄 패치
4. §5-⑭ KIS 키 파일 레포 밖 이동 + 재발급
5. §2′-⑤ tick 0원 가드 (파서+aggregator)

**Phase 2 — 2주 내 (성과 정합·기아 해소)**
6. §2-③ priority eviction 구현 (ADR 0114 완성)
7. §2-② LEGACY SL/TP 평가 삽입 → INTRADAY_REALISTIC default 승격 트랙
8. §3-⑧ Alertmanager + 최소 알림 규칙 4개
9. §5-⑬ smoke.sh 계약 정정 (반나절)

**Phase 3 — 이번 달 (부채 상환)**
10. §4 문서 대청소 1회전: CLAUDE.md/INDEX 갱신·chart-curator 아카이브·Proposed 일괄 승격·훅 ADR 번호 치환·backlog done/ 분리 (코드 무변경 PR)
11. §2′-⑥ NEW_MKOP_CLS_CODE 세션 파싱 (ADR 0124 완성)
12. rate limiter 공유 모듈 승격 ADR + position qty/quantity 통일
13. P2 잔여(트레일 영속, DLQ runbook, 백테스트 체결/장시간 정합) 트랙화

> 모든 변경은 `magicjar-direction-guard` 적용 대상. 특히 ⑥·⑦(백테스트 정합)과 ③(eviction)은 자본 흐름 결합 — ADR 발의 후 진행 권장.

---

## 부록 A. 실전 이상 매매 추적 — "금호타이어 3,690 매수 → 37분 후 3,675 매도" 반복 패턴 (2026-07-03 추가)

**증상**: REAL 계좌, 여러 종목에서 반복. 매수 후 ~37분 보유 → 소폭 손실(-0.4%대) MARKET 매도.

### 원인 순위 (코드 실측)

**1위 — DSL 청산조건(vwap/ma20 하향 이탈) 휩쏘 → MARKET 매도 (확신도 높음)**

메커니즘 체인:
1. 진입 = marketable limit **ask1 그대로** (`EntryPricePolicy`, cap +0.2%) → 항상 스프레드 위에서 매수 (3,690).
2. minHold 3봉(3m tf = 9분) 동안 DSL 매도 억제 (`RealtimeSignalGenerator.kt:552-556`, V94가 전 AUTOPILOT 전략에 min_hold_bars=3 소급 주입 — 주석에 "vwap-cross 즉시청산 휩쏘 완화" 자인).
3. 돌파 실패 → `close < vwap()` 참 → SELL 시그널.
4. DSL 매도는 exitContext 부재로 **무조건 MARKET** (`SignalGeneratedKafkaListener.kt:508-510`) → **bid 체결** (3,675).
5. 손실이 항상 작은 이유: VWAP 바로 아래 청산 + ask매수/bid매도 스프레드 고정 잠식.
6. 반복 이유: 재진입 쿨다운 15분 + 하루 3왕복 cap 후 재진입. `consumer/application.yml:167` 주석에 동일 계열 실측("2026-06-23 122640 ~8왕복, 거래비용 46% 잠식").
7. **백테스트에서 안 보인 이유**: 본 보고서 §2-② — LEGACY 백테스트가 이 청산 경로를 재현하지 못함.

**2위 — PlanExecutor TRAIL 1.2% (확신도 중간, DB 확인 필요)**: `oversold-crash-bounce`(trailing 1.2%)가 ACTIVE라면 `TradePlanFactory.kt:80-83`이 activatePct 없이 **즉시 무장** → 산술 정합: 진입 3,690 → 고점 3,720(+0.8%) → 3,720×0.988=3,675.36 → 틱내림 **3,675** MARKET(reason=TRAIL).

**3위 — 구조적 왕복 비용 (기여 요인)**: ask1 매수 + MARKET(bid) 매도 + 수수료·거래세 = 가격 제자리여도 왕복당 -0.3~-0.5% 고정.

**배제된 경로**: MAX_HOLD(라이브 plan에 maxHold 미박제 — `TradePlanFactory.kt:84`, 프리셋의 maxHoldBars는 백테스트 전용이며 `DaytradePaperService.kt:141`이 minHold로 **뒤집어 매핑**하는 별도 결함 존재) · 전역 SL default(-0.4% 없음, 라이브 SL 1.2~2.0%) · minHold 만료 큐잉(큐잉 없음, fresh 재평가) · EOD(15:15 고정) · VI(종목 한정).

### 검증 절차

```bash
docker exec magicjar-postgres psql -U magicjar -d magicjar
```
```sql
-- ACTIVE 전략과 청산 설정 (2위 TRAIL 판별)
SELECT id, name, status, tf, dsl_exit, take_profit_pct, fixed_stop_loss_pct,
       trailing_stop_pct, min_hold_bars FROM strategy WHERE owner='AUTOPILOT' ORDER BY id;
```
```bash
# 매도 사유 (INFO 레벨로 보임)
docker logs magicjar-consumer --since 24h 2>&1 | grep -E "시그널 발행:.*side=SELL"
docker logs magicjar-consumer --since 24h 2>&1 | grep "PlanExecutor MARKET intent"   # reason=SL|TRAIL|TP
```
프론트: `/orders` 목록 → 주문 상세 `OrderTriggerBadge` + 전략 상세 `SignalTracePanel`. idempotency prefix 판독: `auto-lq:`=PlanExecutor, `auto-sl:`=손절 watcher, `auto-tpt:`=익절 tick, `auto-vi:`=VI.

### 권장 조치
1. dsl_exit 버퍼(`close < vwap()*0.997` 류) 또는 min_hold_bars 3→8~10 상향 + "이익 구간에서만 vwap-exit, 손실 구간은 SL 위임" 게이트 검토 — ADR 발의 대상.
2. 6전략 dsl_exit를 INTRADAY_REALISTIC(ADR 0132)로 재검증.
3. TRAIL 확인 시 activatePct(수익 활성 임계) 추가.
4. DSL 청산 MARKET → bid1 marketable limit 전환 검토 (체결 보장 vs 비용 — 사용자 결재).
5. `.env` `PER_TRADE_PCT=100 / POOL_PCT=100 / MAX_POSITIONS=20` — 자본 안전 하니스가 풀린 상태. 반복 손실 패턴 확인된 현재 축소 검토 권장.
6. 시한폭탄 수리: `PlanExecutor.kt:292-301` wall-clock elapsedBars, `DaytradePaperService.kt:141` maxHold↔minHold 뒤집힘 매핑.
