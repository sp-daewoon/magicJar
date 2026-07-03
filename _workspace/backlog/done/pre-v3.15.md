# magicJar Backlog — 처리 완료 아카이브 (pre-v3.15)

> **출처**: `_workspace/backlog.md` — 2026-07-03 감사(P2-⑯ backlog 3,376줄 아카이브 미집행) 대청소로 `✅ 처리 완료`/`✅ DONE` 마킹 § 19건을 원문 그대로 이전.
> **원칙**: append-only·원문 무수정 보존. § 번호는 이전 당시 backlog.md 번호 그대로(중복 번호는 시대별 재사용 흔적).
> **미완 항목은 계속 `_workspace/backlog.md`에 있다.**

---

## §0. ✅ 처리 완료 — P0-A persistence-shared 모듈 구현 (2026-05-19 신설 → 2026-05-22 마감 · ADR 0088 Accepted 격상)

### 사유

qa cross-validation evidence + backend-core 자가 진단 PR #524·#523 운영 흐름 작동률 **0% root cause**:

- consumer `OrderOutboxPublisherWorker`·`NotificationOutboxRetryWorker`의 `ObjectProvider<OrderEventOutboxRepository>` always empty
- 어댑터가 `api/persistence/` 모듈 단독 발행 + consumer→api 의존 0 (ADR 0004 §의존 방향 LOCK 정합)
- 매 schedule cycle `log.warn` + skip → Kafka `order.requested` 발행 0건 → 주문 path 자본 흐름 차단

### Spec/ADR 발행 (architect, 2026-05-19 완료)

- ADR 0088 **Accepted** (2026-05-22 격상) — `_workspace/arch/adr/0088-persistence-shared-module-outbox-wiring.md`
- Spec 본문 — `_workspace/specs/2026-05-19-persistence-shared-module-outbox-wiring-spec.md` (10 § 풀세트)
- 헌법 §1-A-extended 동반 신설 (P0-C — extensions.worktreeConfig=true 의무 + 헬퍼 스크립트)

### Phase 1 마감 (2026-05-22)

- `persistence-shared/` 모듈 root 직속 신설 + 9 어댑터 git mv (Order Outbox·Notification Outbox·STOMP Event 각 3 파일)
- Gradle 4 파일 변경 (`settings.gradle.kts`·`persistence-shared/build.gradle.kts`·`api/build.gradle.kts`·`consumer/build.gradle.kts`)
- 검증 GREEN — 컨테이너 부팅 후 Bean 등록 + drain·retry worker `log.warn skip` 0건 + 8시간+ 운영 안정
- 주문 path 자본 흐름 작동률 0% → 100% 회복

### 잔존 evidence

- PR #556 close (T-AI W4 진입 spec) — 사용자 명시 "W4 진입 안 함" 정합. T-AI 산출물은 W4 진입 시점에 재발행 path 보존
- ADR 0088 슬롯 점유 (persistence-shared) — 사용자 우선순위 정합(자본 흐름 차단 해소 > W4 진입)

---

## §12. ✅ 처리 완료 — env_file vs environment block 우선순위 함정 (PR-12)

### 사유 (역사 보존)

PR-11 머지 후 사용자 첫 dogfooding(`scripts/build-and-deploy.sh`) → `magicjar-api` 컨테이너 부팅 실패 (Connection refused at `localhost:5432`).

근본 원인: `.env.example`의 `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/magicjar`가 사용자 `.env`에 그대로 복사 → `docker-compose.yml`의 `env_file: ../.env`로 컨테이너 env 주입 → **Spring Boot relaxed binding**으로 환경변수 `SPRING_DATASOURCE_URL`이 application.yml의 `${DB_URL:fallback}` placeholder를 덮어씀 → docker-compose가 set한 `DB_URL: postgres:5432` 무시되고 `localhost:5432`로 접속 시도.

### 처리 (PR-12)

`docker-compose.yml`의 api·batch·consumer 3 서비스 `environment` block에 다음 4 변수 명시 override 추가 (env_file보다 후순위 우선):

```yaml
SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/magicjar
SPRING_DATA_REDIS_HOST: redis
SPRING_DATA_REDIS_PORT: "6379"
SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
```

`.env.example`에 운영 메모 주석 추가 — IDE bootRun(localhost) vs docker compose(서비스명) 분리.

### 교훈

- env_file에 `SPRING_*` 변수가 있으면 application.yml `${PLACEHOLDER}` 패턴이 **덮어써짐** — Spring Boot relaxed binding 정합.
- docker-compose의 environment block은 env_file보다 **후순위 우선** (Compose 공식 문서).
- 향후 신규 외부 자원 변수 추가 시 양쪽 동기화 의무 (또는 SPRING_* 1차원 변수만 사용).

---

## §13. ✅ 처리 완료 — 머지 후 정리 룰 흐려짐 (PR-14)

### 사유 (역사 보존)

사용자 보고 (PR-12 dogfooding 시점) — `_workspace/tasks/T-*` 28건 누적, `task.mjs archive` 명령 호출 안 됨. ADR 0036 §47·§57·§79와 ADR 0033 §92·§112에 명시되어 있으나 sub-agent·orchestrator 실행 단계에서 흐려짐.

> "에이전트 작업 시 _workspace/tasks/T-* 생성, .claude/worktrees/agent-* 생성, local git worktree-agent-* 생성하잖아 완료되면 _workspace/tasks/done옮기기, worktrees/agent-* 자기꺼 삭제, local git 자기꺼 삭제하던거 같은데 이 adr? 룰? 을 잊은거 같은데" (사용자 명시)

### 처리 (PR-14)

1. **orchestrator SKILL.md에 "머지 후 정리 4단계 의무" 명시** — main pull → `task.mjs archive` → worktree remove → `git branch -d`. 사이클 시작 시 자동 점검 룰 추가.
2. **누적 28 task 일괄 archive** (chore commit) — `_workspace/tasks/T-*.md` → `_workspace/tasks/done/`로 일괄 이동.
3. **Dozzle 추가** (사용자 요청 — docker compose 로그 web UI 빠르게 구성).

### 교훈

운영 룰은 ADR에만 있어선 안 되고 **SKILL.md에 단계별 의무로 명시화** 필요. 다음 사이클 시작 시 자동 점검(`ls _workspace/tasks/T-*.md | wc -l` 0이 아니면 일괄 archive) 의무.

---

## §14. ✅ 처리 완료 — task 파일 데이터 무결성 흐려짐 (PR-18)

### 사유 (역사 보존)

사용자 보고 — `_workspace/tasks/done/T-20260507-1256-pr-9-w2-market-data.md` 등 RUNNING 상태로 done/ 디렉토리에 존재. 결과물 체크리스트 체크 0. completedAt null.

> "id, agent, model, branch, 말고는 정보들이 명확히 갱신안되는거 같아 시간도 현시간들이랑 이상하고 이거 백로그에 남기더라도 꼭 해소는 하고 가야할거같아" (사용자 명시)

### 근본 원인

1. **PR-14 누적 28 task 일괄 archive 시 status 검증 0건** — `task.mjs archive`가 status=DONE 검증 없이 단순 mv 동작
2. **sub-agent들이 `task.mjs check "<항목>"` 호출 누락** — 결과물 체크리스트 빈 채로 진행
3. **sub-agent들이 `task.mjs done --pr <N>` 호출 누락** — status RUNNING 그대로 + completedAt null

### 처리 (PR-18)

1. **`scripts/task.mjs cmdArchive` 무결성 보강**:
   - status=DONE 검증 강제, 아니면 archive 거부
   - `--force` flag로 우회 가능 (옛 누적 처리용) — force 시 status·completedAt 자동 보강 후 archive
2. **옛 done/ 30 파일 일괄 정정** (inline node script):
   - 22개 정정 (status RUNNING/BLOCKED → DONE, completedAt null → file mtime ISO)
   - 8개는 이미 DONE 상태 (정상)

### 잔여 의제 → §15

sub-agent SKILL에 `task.mjs check + done` 호출 의무 명시화 — 별도 사이클 처리.

---

## §15. ✅ 처리 완료 — sub-agent SKILL에 task.mjs 호출 의무 명시화 (PR-26)

### 사유 (역사 보존)

PR-14·18 운영 룰 정정 결과로 **task.mjs check + done 호출 의무**를 sub-agent SKILL에 명시화 필요. 현재 sub-agent들이 task.mjs start만 호출하고 check·done 흐름이 흐려짐.

### 처리 (PR-26)

`_workspace/arch/agent-charter.md` §1 (task.mjs 4단계 의무)로 통합 — start·check·done·archive 4단계를 운영 헌법 단일 출처에 명시. 9 sub-agent SKILL은 헌법 1줄 참조만 (중복 제거 + token 효율).

### 교훈

운영 룰을 sub-agent SKILL에 분산 명시 시 매번 흐려짐. 단일 출처 헌법으로 통합 → token 절감 + 정합 보장.

---

## §17. ✅ 처리 완료 — architect SKILL — KIS-first raw fetch 의무 강화 (PR-21 → PR-26 헌법 통합)

### 사유 (역사 보존)

§16의 재발 방지 일환. architect spec/plan PR 발행 시 외부 데이터 규격(KIS 헤더·tr_id·필드 byte offset·DSL 등) raw fetch 검증 의무를 SKILL.md 또는 ADR에 명시화. 현재 사용자 메모리 `feedback_kis_first_policy.md`만 있고 architect SKILL 자체에는 명시 부재.

### 처리 (PR-21 + PR-26)

- PR-21에서 `.claude/agents/architect.md`에 KIS-first raw fetch 의무 5단계 절 추가
- PR-26에서 `_workspace/arch/agent-charter.md` §3 (KIS-first raw fetch 의무)로 통합 — architect·market-data 영역 핵심 책임으로 헌법 단일 출처 명시. architect SKILL은 헌법 §3 참조 + ADR 발행 의무만 영역별 특수 룰로 유지.

### 잔여 의제 — architect SKILL 본문 ADR 미발행

ADR 0051 후보였던 "KIS-first raw fetch 의무 ADR" 자체는 본 PR-26 ADR 0051(Spring Data default fun trap)으로 자리 차지됨. KIS-first ADR은 별도 사이클에서 검토 (또는 헌법 §3로 충분 — sub-agent SKILL에서 cross-ref 가능).

### 정정 안

`.claude/agents/architect.md`에 다음 절 추가:

```
## §19. ✅ 처리 완료 — task.mjs --force flag parsing fix (PR-26)

### 사유 (역사 보존)

PR-18에서 `cmdArchive`에 `--force` 옵션 추가했지만 `parseArgs` 함수가 boolean flag 인식 못 함. `--force` 단독 호출 시 다음 토큰을 value로 가져가서 undefined 반환 → `args.force === true` 검증 통과 안 됨.

### 처리 (PR-26)

`scripts/task.mjs` `parseArgs` 함수 보강 — 다음 토큰이 없거나 `--`로 시작하면 boolean true로 인식. 기존 key=value 패턴은 호환 유지.

```js
// PR-26 정정 후 동작
parseArgs(['archive', 'T-test', '--force'])
// → { _: ['T-test'], force: true }  ← 정확
```

### 검증

- `--force` 단독: `out.force = true` ✅
- `--agent architect`: `out.agent = 'architect'` (기존 동작 유지) ✅
- 끝에 `--force`: 다음 토큰 부재 → `true` ✅
- 중간에 `--force` + 다음 `--xxx`: `--xxx`가 `--`로 시작 → `true` ✅

---

## §20. ✅ 처리 완료 — Spring Data Repository default fun 함정 (PR-26 ADR 0051)

### 사유 (역사 보존)

PR-23·PR-25에서 발견한 결함 — Repository interface 안에 body 가진 fun이 Spring Data query inference에 걸려 부팅 실패.

- PR-23: `BatchInstrumentJpaRepository.upsert(7파)` wrapper 추가 → 부팅 실패 (`No property 'upsert' found`)
- PR-25: wrapper 제거 + `InstrumentSeedRunner.upsertSeed` private fun helper로 이동 → 정정

### 처리 (PR-26)

ADR 0051 신설 (`_workspace/arch/adr/0051-spring-data-default-fun-trap.md`) — 회피 패턴 3종 명시 (caller helper · extension fun · abstract only). 헌법 §6에 본 ADR cross-ref + sub-agent SKILL(backend-core·market-data) 영역별 특수 룰로 강조.

### 교훈

Kotlin interface는 `default` 키워드 없이도 body 가진 fun이 default 동작 → Spring Data가 abstract와 구분하지 못해 query inference 시도. JVM bytecode level에서 동일하게 보임 → Repository proxy 생성 시 부팅 실패.

---

## §22. ✅ 처리 완료 — DART OpenDART SSL handshake_failure (PR #260)

> 옵션 3 채택: DART 전용 `dartWebClient` 빈 신설 + SslContextBuilder(TLSv1.2·JDK provider). `./gradlew check` GREEN. KIS·Yahoo·Naver 미영향.

~~## §22. DART OpenDART CorpCode ZIP — handshake_failure (v3.1.0 dogfooding)~~

### 사유

batch 부팅 시 `DartCorpCodeRepository.@PostConstruct` 가 `https://opendart.fss.or.kr/api/corpCode.xml` 다운로드 시도. JDK 21 + reactor netty + opendart 서버 TLS cipher suite 호환성 문제로 `Received fatal alert: handshake_failure`.

### 영향

- batch 부팅 자체는 graceful degrade (try-catch — `log.error` + 빈 map 유지)
- DartCorpCodeRepository 빈 → `CorpActionSyncJob` 의 DART 리스트 fetch 실패 (동일 origin)
- **사용자 흐름 차단 X** — Watchlist·SymbolDetail·일봉 마감 모두 영향 없음
- W3 백테스트 진입 시 `corp_action.adjustment_factor` 정확도 critical (특히 액면분할·증자 종목 backtest)

### 분류 결정

**📋 백로그** — W3 진입 직전 본격 fix. v3.1.0 시점에는 KIS ksdinfo만으로 corp_action 부분 보완 가능 (DART는 보조).

### 처리 옵션 (W3 진입 시 검토)

1. **JVM TLS 옵션 추가** — `-Djdk.tls.client.protocols=TLSv1.2` 또는 `-Dhttps.protocols=TLSv1.2`
2. **reactor netty SslProvider 명시** — `HttpClient.create().secure(spec -> spec.sslContext(SslContextBuilder.forClient()...))`
3. **WebClient 별도 빈** — DART 전용 WebClient 빈 + custom SSL context
4. **외부 cert chain 점검** — opendart.fss.or.kr 인증서 체인 verify

### 트리거

W3 T-BT (Backtest Engine) 또는 T-SC (Screener) 진입 시.

### 참조

- PR #231 — T-CA1 본격 구현 (DartCorpCodeRepository 도입)
- ADR 0018 §4-1 — DART CORPCODE.xml 출처

---

## §27. ✅ 처리 완료 — KIS WS connect() 누락 결함 (사용자 dogfooding 2026-05-11)

### 사유

2026-05-11 10:27 KST 장중 사용자 dogfooding: 종목상세 화면에서 실시간 가격 변동 전혀 없음 보고.
진단 결과 `KisWebSocketBridge.connect()` 메서드가 public으로 존재하나 `KisWsPort` 인터페이스에 미선언,
`WatchlistIngestionWorker.onStartup()`에서 호출 0건 → `sendSink` null 상태에서 `subscribe()` 메시지 폐기
→ WS 핸드셰이크 0건 → 실시간 데이터 수신 0건 (critical — 룰 기반 quant trading 시그널 입력 차단).

### 처리 내용 (PR-N — hotfix)

- `KisWsPort` 인터페이스에 `fun connect()` 추가 (KDoc: ADR 0028 §2-1 + ADR 0048 §2-4 인용)
- `KisWebSocketBridge.connect()`에 `override` 키워드 추가
- `WatchlistIngestionWorker.onStartup()`에 `kisWsPort.connect()` 호출 추가 (subscribe보다 선행)
- `WatchlistIngestionWorkerTest` 신규 작성 (4 케이스 — connect 호출, 순서 보장, 빈 watchlist, 세션 분기)
- `OrderbookSubscriptionRouterTest.TestKisWsPort`에 `connect()` no-op 구현 추가
- `:domain:check` + `:consumer:check` + 전체 `check` GREEN

### 변경 파일

- `domain/.../port/KisWsPort.kt` (+23 LOC)
- `consumer/.../ws/KisWebSocketBridge.kt` (+10 LOC)
- `consumer/.../ingestion/WatchlistIngestionWorker.kt` (+11 LOC)
- `consumer/.../ingestion/WatchlistIngestionWorkerTest.kt` (신규 +98 LOC)
- `consumer/.../ingestion/OrderbookSubscriptionRouterTest.kt` (+2 LOC)

### 처리 완료 마킹

2026-05-11 hotfix PR — market-data 단독 (ADR 0004 모듈 경계 정합). v1/v2 잔재 0건. fitness gap: 실시간 시세 = 시그널·주문 1차 입력 — critical 결함 수정.

---

## §28. ✅ 처리 완료 — KIS WS SSL handshake timeout (사용자 dogfooding 2026-05-11, PR #278 DART 패턴 적용)

### 사유

PR #286(KIS WS connect() 호출 fix) 머지 + consumer 재기동 후 추가 결함 발견:
`io.netty.handler.ssl.SslHandshakeTimeoutException: handshake timed out after 10000ms` (ops.koreainvestment.com:21000).
connect 직후 10초 타임아웃 → disconnect/reconnect 무한 루프. 실시간 시세 수신 0건 지속.

### 근본 원인

JDK 21 + reactor netty 기본 SSL stack과 KIS WS 서버 TLS cipher suite 호환성 결함.
batch 모듈 PR #278(backlog §22)에서 DART(`opendart.fss.or.kr`)에 동일 패턴 적용 선례 존재.

### 처리 내용 (PR 본 항목)

- `consumer/config/WebClientConfig.kt` — `kisWebSocketClient(): ReactorNettyWebSocketClient` 빈 신설
  - SslProvider.JDK + TrustManagerFactory + protocols("TLSv1.2", "TLSv1.3") — batch dartWebClient 패턴 1:1 정합
  - `@Qualifier("kisWebSocketClient")` 발행
- `consumer/ws/KisWebSocketBridge.kt` — 생성자 주입으로 변경 (`private val wsClient = ReactorNettyWebSocketClient()` 제거)
  - `@Qualifier("kisWebSocketClient") private val wsClient: ReactorNettyWebSocketClient` 주입

### 참조

- PR #278 (batch §22 DART fix) — 패턴 출처
- PR #286 (KIS WS connect() 호출 fix) — 본 결함의 선행 fix (connect 호출 누락 별도 결함)
- ADR 0048 §3-1 — KisWebSocketBridge 설계

---

## §37. T-BT R2 결과 영속 + GET/DELETE endpoint 트랙 ✅ DONE — PR agent/backend-core/t-bt-r2-persistence (2026-05-12)

### 컨텍스트

T-BT R1(ADR 0056) 머지 완료 — **stateless POST /api/backtest/run** LOCK. 즉시 결과 반환 + DB 영속 0 + frontend 페이지 0. ADR 0056 §3-7은 "**0080+ 후보** Backtest 결과 영속 + 조회 endpoint (backlog §27-2 발동 시)"로 후속 의제 예약 — 본 § 신규 등록.

W3 후속 트랙(T-SIG·T-RISK·T-PERF) 진입 시 strategy 활성화 사전 게이트로 "최근 N일 내 백테스트 SUCCESS" 검증 필요 — DB 영속 의무.

### 결정 사항 (architect 권장 + 사용자 결정 위임 — PR-50 §2 정합)

**의제 A**: 영속 시점 — **A-1 본 사이클** vs A-2 post-MVP vs A-3 Redis cache 한정
**의제 B**: 영속 매트릭스 — **B-1 3 테이블 분리** vs B-2 단일+JSONB vs B-3 blob
**의제 C**: REST endpoint 매트릭스 — **C-1 5 endpoint** vs C-2 단일 vs C-3 중간 2건

architect 권장: **A-1 + B-1 + C-1** (PR-50 §3 정합).

### 영속 Sketch (B-1 채택 가정)

```
backtest_run    — V12 (메타 + KPI 12 컬럼 + status + abort_reason + universe TEXT[])
backtest_trade  — V13 (매매 시계열 + run_id FK CASCADE)
backtest_position — V13 (포지션 + run_id FK CASCADE)
```

권장 인덱스:
- `idx_backtest_run_user_executed (user_id, executed_at DESC)`
- `idx_backtest_run_strategy_executed (strategy_id, executed_at DESC)`
- `idx_backtest_trade_run_ts (backtest_run_id, executed_at)`

### REST API Sketch (C-1 채택 가정)

| HTTP | endpoint | 책임 |
|---|---|---|
| POST | `/api/backtest/run` (기존) | 실행 + 영속 동시 호출 (`BacktestRunPersister.persist` wire-up) |
| GET | `/api/backtest?strategyId=&page=&size=` | 본인 백테스트 목록 페이징 |
| GET | `/api/backtest/{id}` | 메타 + KPI 12종 |
| GET | `/api/backtest/{id}/trades?page=&size=` | 매매 시계열 페이징 |
| GET | `/api/backtest/{id}/positions` | 포지션 전체 |
| DELETE | `/api/backtest/{id}` | 본인 삭제 (CASCADE) |

권한: 모든 endpoint `userId` 본인 검증 + 위반 시 403 (ADR 0010 multi-tenant 정합).

### 디스패치 매트릭스 (채택 후)

- **sub-agent**: backend-core (sonnet 4.5 1M)
- **branch**: `agent/backend-core/t-bt-r2-persistence`
- **PR**: PR-51
- **deliverables**: Flyway V12·V13 + JPA 3쌍 + Persister·QueryService + Controller 5 endpoint + DTO 보강
- **ADR**: 0061 신규 발행 (Backtest 결과 영속 — ADR 0056 §3-4 supersede §1)

### 우선순위

**1순위** — T-SIG 진입 전 의무 (strategy 활성화 게이트 사전 의무). 본 § 처리 미완 시 T-SIG 진입 시 게이트 부재 회귀.

### 연관

- PR-50 plan: `_workspace/specs/pr-50-w3-t-bt-rounds-plan.md` §4·§5
- ADR 0056 §3-7 — 후속 0080+ 예약 → 본 § + ADR 0061 격상
- 트랙 ID T-BT R2


---

## §38. T-BT R3 frontend `/strategy/backtest` 페이지 트랙 ✅ DONE — PR agent/frontend/t-bt-r3-page (2026-05-12)

### 컨텍스트

T-BT R1 머지 — backend API만 존재 (Swagger 호출로만 접근 가능). 사용자 워크플로우 흐름:

1. `/strategy/builder` (T-SB W2 완료) — DSL 작성·저장
2. **`/strategy/backtest` (본 § 신규)** — 작성한 전략을 과거 데이터로 시뮬 → 결과 확인 → 활성화 결정
3. (W3 후속) — 활성 전략 → T-SIG 시그널 생성

`/strategy/backtest` 페이지 부재 시 사용자가 Swagger 직접 호출 의존 — UI 가치 미달.

### 결정 사항 (architect 권장)

**의제 D**: 페이지 구조 — **D-1 단일 페이지 (왼쪽 폼 + 오른쪽 목록 + 상세 모달)** vs D-2 2 페이지 분리 vs D-3 EvaluationResultCard 확장

architect 권장: **D-1**. ADR 0040 (관심종목 우선 UI 흐름) 정합 — Strategy Builder 내부 자식 routing 자연스러움.

### 페이지 Sketch (D-1)

```
/strategy/backtest
┌──────────────────┬──────────────────────┐
│ BacktestRunForm  │ BacktestRunList      │
│ (실행 폼)        │ (목록 페이징)         │
│                  │ 행 클릭 → DetailModal│
└──────────────────┴──────────────────────┘
DetailModal:
  - KpiCards 12종
  - EquityCurve (LWC line)
  - TradeMarker (LWC chart + 매매 마커)
  - TradeTable (페이징)
  - PositionTable
```

### 신규 컴포넌트 (D-1)

- `frontend/src/pages/Strategy/Backtest.tsx`
- `frontend/src/components/backtest/BacktestRunForm.tsx`
- `frontend/src/components/backtest/BacktestRunList.tsx`
- `frontend/src/components/backtest/BacktestDetailModal.tsx`
- `frontend/src/components/backtest/BacktestKpiCards.tsx`
- `frontend/src/components/backtest/BacktestEquityCurve.tsx` (LWC line series)
- `frontend/src/components/backtest/BacktestTradeMarker.tsx` (LWC + 매매 마커)
- `frontend/src/api/backtest.ts` REST client

### 사용자 메모리 정합

- `feedback_no_mock_data.md` — candle 부족 503 시 "데이터 없음" 표시. mock 합성 절대 금지
- `feedback_honorific.md` — 모든 UI 문구 한국어 존댓말
- `feedback_swagger_pattern.md` — backend Swagger 4단 그대로 유지 (frontend가 그 응답 소비)

### 디스패치 매트릭스 (§37 R2 머지 후)

- **sub-agent**: frontend (sonnet 4.5 1M)
- **branch**: `agent/frontend/t-bt-r3-page`
- **PR**: PR-52
- **deliverables**: 1 페이지 + 6 컴포넌트 + REST client + 라우팅 추가
- **검증**: 사용자 dogfooding — 실행·목록·상세·삭제 e2e

### 우선순위

**2순위** — §37 R2 머지 후 진입. R2 API 확정 전 진행 시 mock spec drift risk (PR-50 §2 의제 F-1 직렬 권장).

### 연관

- PR-50 plan: `_workspace/specs/pr-50-w3-t-bt-rounds-plan.md` §7
- 트랙 ID T-BT R3


---

## §43. ✅ 처리 완료 — T-O 주문 실행 (W3 네 번째 트랙, Phase 1·2·3 합류 마감 2026-05-18 — ADR 0068 Accepted 격상)

### 컨텍스트

W3 자동매매 본체 트랙의 종착 실행 노드. T-RISK ALLOWED 게이트 통과 후 실제 KIS REST 매수/매도 주문 발행. 사용자 자본 실 변동 첫 점. 사전 자산 main:
- ADR 0067 T-RISK Accepted (v3.5.0 마감, PR #351·#353·#354·#355·#356·#357)
- Kafka `order.intent.v1` topic (T-RISK ALLOWED 시 publish — 본 트랙 진입점 LOCK)
- ADR 0024 Risk Guard chain 6단 (KillSwitch 재검증 baseline)
- ADR 0042 Position Bootstrap (KIS 잔고 워밍 + 체결 후 position 갱신 합류점)
- ADR 0043 KillSwitch 행동 정의
- ADR 0044 Notification System (order.requested·filled·rejected·cancelled.v1 Slack 합류)
- ADR 0046 Onboarding REAL/MOCK credential 2종 row baseline
- ADR 0048 KIS WS 41건 구독 한도 (H0STCNI0 추가 영향 검증)
- ADR 0057 KIS-only (정보 수집은 REAL 키 단일, 본 트랙 주문 액션만 MAGICJAR_MODE 분기 — 사용자 메모리 정합)
- STOMP 인프라 (`/topic/signal.user.{userId}`·`/topic/risk.user.{userId}` 패턴 baseline)

T-O가 그 자산의 W3 종착 노드 — Signal → Risk → Order 흐름 완성.

architect spec PR(본 사이클 — ADR 0068 plan 발행) — 결정 의제 8건 + REST API + 도메인 모델 + Flyway V20·V21·V22·V23 + 실행 엔진 + sub-agent 디스패치 매트릭스 발행, 사용자 결정 대기.

### 결정 의제 (architect 권장안 — ADR 0068 §2)

| 의제 | 권장안 | 핵심 사유 |
|---|---|---|
| A. 모의/실전 분기 | **A-1** env × 2 + 사용자 동의 토글 (3중) | CLAUDE.md 함정 4번 + 사용자 명시 동의 |
| B. KIS endpoint | **B-1** 단일 port + adapter 내부 mode 분기 | KIS-first raw 인용 + 흐름 일원화 |
| C. 주문 가격 | **C-1** 시장가/지정가 (ORD_DVSN 00·01) | MVP 적정 + 슬리피지 후속 |
| D. 체결 추적 | **D-1** KIS WS H0STCNI0 실시간 | 실시간 + Rate Limit 영향 0 |
| E. 정정/취소 | **E-1** 사용자 수동 only (1차 MVP) | 사용자 통제 + 비전 정합 |
| F. 영속 | **F-1** 3 테이블 분리 (request+execution+audit) | ADR 0061·0067 패턴 + 1:N cover |
| G. 알림 | **G-1** 단일 STOMP + 4 event discriminator | UX + ADR 0067 패턴 일관 |
| H. 멱등성 | **H-1** Redis SETNX 60s + DB UQ 이중 | 자본 보호 본질 + ADR 0066 패턴 |

### KIS-first raw 인용 (헌법 §3)

자체 추론 0. raw 출처 `https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/`:

| 액션 | env | tr_id | 출처 line |
|---|---|---|---|
| 매수 | REAL | `TTTC0012U` | `domestic_stock_functions.py` L10097~10213 |
| 매수 | MOCK | `VTTC0012U` | 위와 동일 |
| 매도 | REAL | `TTTC0011U` | 위와 동일 |
| 매도 | MOCK | `VTTC0011U` | 위와 동일 |
| 정정/취소 | REAL | `TTTC0013U` | `domestic_stock_functions.py` L10771~10884 (`def order_rvsecncl`) |
| 정정/취소 | MOCK | `VTTC0013U` | 위와 동일 |
| 체결통보 WS | REAL | `H0STCNI0` | `domestic_stock_functions_ws.py` L389~446 (`def ccnl_notice`, 26 컬럼) |
| 체결통보 WS | MOCK | `H0STCNI9` | 위와 동일 |

### 트리거

- 사용자 결정 8건 명시 수신 (의제 A·B·C·D·E·F·G·H)
- 채택 후 ADR 0068 status Proposed → Accepted 갱신 + sub-agent 직렬 디스패치(Phase 1 strategy-engine → Phase 2 backend-core → Phase 3 frontend)
- 사용자가 일부 의제 다른 선택 시 본 § 보강 + ADR 본문 갱신

### 예상 PR 매트릭스 (채택 후)

- **Phase 1 strategy-engine**: consumer + domain — OrderRouterKafkaListener + OrderIdempotencyGuard + TradingModeGate + KisOrderHttpAdapter (REST + raw KDoc 의무) + KisExecutionWsListener (H0STCNI0) + 4 Kafka producer (order.requested·filled·rejected·cancelled.v1) + 도메인 14 + port 7 + 단위 테스트 ~15건. AI 호출 0 grep 검증 의무.
- **Phase 2 backend-core**: api — Flyway V20·V21·V22·V23 + JPA 3쌍 + REST 4 endpoint(GET orders list·single·POST cancel + PUT real-trading-consent) + Swagger 4단 + StompOrderNotifier(4 event discriminator) + 단위 테스트 ~8건.
- **Phase 3 frontend**: `/orders` + `/orders/:id` 페이지 + OrderCard + ExecutionTable + AuditEventTimeline + OrderStatusBadge + OrderCancelButton + SettingsModal "실거래 모드 동의" 토글 + useStompOrder hook(4 event 색상 토스트) + REST client `order.ts`·`account.ts` + AppHeader 메뉴 1 + 라우팅.
- **Phase 4 qa (선택)**: 모의 주문 dogfooding (signal → risk → order → KIS VPS → H0STCNI9 → STOMP FILLED full chain).

### 진행 현황 (2026-05-18 architect — spec `2026-05-18-to-main-track-entry-plan.md` 실측 매트릭스)

- **Phase 2 완료** (100%) — Flyway V20~V23 + OrderQuery/Cancel/RealTradingConsent Service + StompOrderNotifier + JPA 3쌍 + REST 4 endpoint 모두 합류
- **Phase 3 완료** (100%) — OrdersPage·OrderDetailPage·OrderCard·ExecutionTable·AuditEventTimeline·OrderStatusBadge·CancelOrderButton·RealTradingConsentToggle·useOrders·useStompOrder·order.ts·AppHeader 메뉴·라우팅 모두 합류
- **Phase 1 잔여 2건** — (B-1) `KisWebSocketBridge:489` H0STCNI0/H0STCNI9 wiring stub + (B-2) `ConsumerOrderExecutionRepositoryAdapter` 합류 (PR #481 동일 패턴). 영역 add-only 병렬 가능.
- **Phase 4 dogfooding** — prereq 4 MUST + 본 spec 3 신규(케이스 6·7·8) = 7 MUST GREEN → v3.6.13 patch cut

### 연관

- 본 ADR 0068 (`_workspace/arch/adr/0068-t-o-order-execution.md`) — 본 § 권위
- ADR 0067 (T-RISK Accepted — 사전 자산 권위)
- ADR 0066 (T-SIG — Signal → Risk → Order 흐름 baseline)
- ADR 0024·0043 (Risk Guard + KillSwitch — 재검증 baseline)
- ADR 0042 (Position Bootstrap — 체결 후 갱신 합류점)
- ADR 0041 (Broker Reconciliation — T-RECON 후행)
- ADR 0044 (Notification System — order.filled.v1 합류 — T-NOTIFY 후행)
- ADR 0046 (Onboarding REAL/MOCK credential — `user_kis_credential` baseline)
- ADR 0048 (KIS WS 41건 한도 — H0STCNI0 영향 검증)
- ADR 0010 (Multi-tenant userId 격리)
- ADR 0023 (AI 호출 0 정책 계승)
- ADR 0037 (KIS Rate Limit — 주문 burst 영향)
- ADR 0051 (Repository default fun 함정 — 헌법 §6 의무)
- ADR 0057 (KIS-only — 본 트랙은 정보 수집 vs 주문 분기 권위)
- tracks-roadmap §2 T-O row (본 § 권위 인용)
- master §10 ADR 0068 row + §C.12 (T-O 진입 표기)
- 사용자 메모리 `project_system_vision.md`(룰 본체) · `feedback_v3_skepticism_no_regression.md`(fitness gap lens) · `feedback_3tier_hierarchy.md`(architect plan → sub-agent 직렬) · `feedback_kis_first_policy.md`(KIS-first raw 인용) · `feedback_kis_key_realonly_for_data.md`(정보 수집 REAL 키 단일, MAGICJAR_MODE 분기는 주문 액션만)

### 처리 결과 (2026-05-18 architect — Phase 1·2·3 합류 마감 + dogfooding 케이스 5 GREEN + 통합 빌드 evidence 종합)

본 §43은 T-O 본 트랙 마감 PR 사이클로 종결됨 — ADR 0068 status는 plan 단계 Accepted(2026-05-13)에서 **Phase 1·2·3 합류 마감 Accepted**(2026-05-18)로 격상.

**Phase 1·2·3 합류 PR 매트릭스** (orchestrator 직렬 dispatch 종결):

| Phase | sub-agent | PR | 머지 시각 (KST) | 비고 |
|---|---|---|---|---|
| Phase 2 (Repository 합류) | architect 자율 | [PR #481](https://github.com/sp-daewoon/magicJar/pull/481) | 2026-05-18 12:32 | dogfooding 케이스 5 prereq — Consumer 측 OrderRequest/OrderAuditEvent Adapter 합류 |
| Phase 1 B-2 (OrderExecution Adapter) | backend-core | [PR #485](https://github.com/sp-daewoon/magicJar/pull/485) | 2026-05-18 14:35 | `KisExecutionWsListener.handleExecution()` 분할 체결 영속 path 활성화 |
| Phase 1 B-1 (KIS WS wiring) | market-data | [PR #486](https://github.com/sp-daewoon/magicJar/pull/486) | 2026-05-18 14:37 | `KisWebSocketBridge:489` H0STCNI0/H0STCNI9 → `KisExecutionWsListener.onWebSocketMessage()` wiring + 자동 subscribe/unsubscribe |

**Phase 2·Phase 3 본문**(OrderQuery/Cancel Service · RealTradingConsent · Flyway V20~V23 · JPA 3쌍 · REST 4 endpoint · StompOrderNotifier · OrdersPage·OrderDetailPage·8 컴포넌트·hooks·라우팅)은 prereq spec(PR #479) 발행 시점 이전에 직렬 dispatch 완료 (PR #484 §1.2·§1.3 실측 매트릭스 GREEN evidence).

**dogfooding 케이스 5 GREEN evidence** (자본 안전 게이트 LOCK):

```
2026-05-18 KST — Kafka publish order.intent.v1 { signalId=2, userId=74683223 }
  → consumer OrderRouterKafkaListener Step 1~6 GREEN
  → TradingModeGate.resolveMode → MOCK (envMode!="REAL")
  → KisAuthAdapter.resolveCredential(MOCK) → row 부재 → REAL_GATE_DENIED:CREDENTIAL_NOT_FOUND
  → order_request row { id=2, status=REJECTED, reject_reason="REAL_GATE_DENIED:CREDENTIAL_NOT_FOUND", mode=MOCK }
  → Kafka publish order.rejected.v1 + STOMP /topic/order.user.74683223 (REJECTED)
```

**통합 빌드 evidence** — KIS WS H0STCNI9 MOCK subscribe (체결통보 본격 가동):

```
2026-05-18T14:38:59.xxx+09:00 INFO  KisWebSocketBridge: KIS WS 체결통보 subscribe: trId=H0STCNI9 userId=thde**** mode=MOCK emit=OK
```

**연관 산출물**:
- ADR 0068 §18 "Accepted 마감 evidence" 신규 § 추가 + §19 변경 이력 shift + 본문 status 격상
- `_workspace/arch/adr/README.md` §13 후속 예정에서 0068 제거 + §14 변경 이력 row 추가
- `docs/architecture/tracks-roadmap.md` §2 T-O row "Accepted 2026-05-18" mark
- `CHANGELOG.md` 2026-05-18 row prepend

**dogfooding 잔여** (orchestrator 별 사이클): prereq 케이스 1·2·3·4(OPTIONAL) + 본 spec 케이스 6(체결 통보 풀체인)·7(OrdersPage STOMP)·8(OrdersPage 취소) → 7 MUST GREEN 후 v3.6.13 patch release cut (사용자 메모리 `feedback_release_policy_patch_first` — T-O는 W3 트랙 minor 종착 아님 → patch).

---

## §56. ✅ 처리 완료 — KIS WS OPSP8996 ERROR 재발 (v3.6.11 hotfix 마감 2026-05-17)

### 처리 결과

- ADR 0077 deep dive 발행 (PR #465) → root cause 3건 발굴 (silent fallback + counter reset 누락 + MOCK 분기 정합 위반)
- v3.6.11 hotfix market-data 단일 PR (#467) — G-1 + H-1 + I-1 + J-1 + L-1 4 fix 적용
- evidence: docker logs 1시간 86건 → 0건 (재배포 후 1분 모니터링 — KIS WS 연결 성공 + approval_key type=REAL 작동)
- unit test 5건 GREEN
- v3.6.11 cut + master HTML 첫 자동 갱신 evidence

### 사유 (history 보존)

v3.6.10 release cut 직전 docker logs 검사에서 consumer `KisWebSocketBridge` OPSP8996 ERROR 5회 연속 발생 (11:00 시점 attempt 64→68). ADR 0076 Phase 1·2·3 머지 + v3.6.9 cut 이후에도 재발 — 즉 ADR 0076이 fix 완전치 못함. "ALREADY IN USE appkey" 누적 = 자동매매 본체 시세 sync 영향 가능.

### 발견 evidence

```
2026-05-17T11:00:05.161+09:00 ERROR ... KIS WS OPSP8996 2회 fail — skip: attempt=64 ...
2026-05-17T11:00:15.743+09:00 ERROR ... attempt=65 ...
2026-05-17T11:00:15.744+09:00 ERROR ... attempt=66 ...
2026-05-17T11:00:15.744+09:00 ERROR ... attempt=67 ...
2026-05-17T11:00:15.751+09:00 ERROR ... attempt=68 ...
```

### 처리 방향

- 사용자 결정: v3.6.10 cut 진행 + 본 issue는 v3.6.11 hotfix 분리
- ADR 0076 Phase 1·2·3 결과물 vs 실 KIS 서버 응답 비교 분석 필요 (architect 자율 또는 사용자 검증 위임)
- architect deep dive 후 hotfix PR 분리 (market-data 영역)

### 연관

- ADR 0076 §3 OPSP8996 root cause 가설 6건 (A-F) — 추가 가설 또는 Phase 1 invalidate revert 회귀 검증 필요
- ADR 0077 (예약 슬롯) — OPSP8996 deep dive 후속 ADR 발행 가능
- v3.6.9 cut 머지 (ADR 0076 Phase 3 B-2 — PR #449)

## §59. ✅ 처리 완료 — worktree git author + task 파일 commit 의무 강화 (안정성 Cycle 2 마감 2026-05-18)

### 사유 (안정성 Cycle 1 evidence)

안정성 Cycle 1(PR #473·#474·#475·#476) 머지 후 2건 운영 부채 발견:

**부채 1: worktree git author 결함**
- Agent tool `isolation:"worktree"` 생성 worktree들이 모두 `bot+frontend@magicjar.local` author로 default 설정됨
- PR #473(backend-core 영역), PR #475(market-data 영역) commit author 모두 `magicjar-frontend`로 attribution (pre-commit 화이트리스트는 통과하나 git blame·CHANGELOG `(작성: <agent>)` 마커 부정확)
- evidence: `gh pr view 473 --json commits` → `authorEmail: "bot+frontend@magicjar.local", authorName: "magicjar-frontend"` (실제 작업은 backend-core 영역 `api/scheduler·api/messaging`)
- root cause = `.claude/agents/<agent>.md` frontmatter `git-author:` 는 선언 수준일 뿐 worktree 로컬 git config 강제 부재. sub-agent SKILL에 「작업 시작 시 워크트리 로컬 author 갱신 의무」가 일관되게 명시되지 않음

**부채 2: task 파일 untracked**
- PR #473·#474·#476 sub-agent가 task 파일을 commit하지 않음 (worktree 로컬에만 존재)
- PR #475(market-data)만 task 파일이 main에 흔적 남음 (`_workspace/tasks/T-...-worktree-agent-...md`)
- 결과: orchestrator가 머지 후 archive 시 task 파일 찾을 수 없음 → `--force` 우회 또는 archive 누락
- root cause = 헌법 §1 task.mjs 4단계가 「start → check → done → archive」이나 commit 의무 명시 부재

### 처리 결과

본 §59는 안정성 Cycle 2 단일 PR(`agent/architect/stability-cycle2-worktree-author-task-commit`)로 마감됨:

**A. 헌법 §1 강화** (`_workspace/arch/agent-charter.md`)
- §1-A worktree 진입 의무 신설 — worktree 진입 즉시 `git config user.email "bot+<agent>@magicjar.local"` + `git config user.name "magicjar-<agent>"` 강제. 위반 시 sub-agent 작업 진행 거부.
- §1-C task 파일 commit 의무 신설 — `task.mjs start` 직후 `git add _workspace/tasks/T-<id>-*.md` (또는 `done` 자동 add 의존). 머지 PR에 task 파일 포함 의무.
- §8 변경 이력 row 추가.

**B. sub-agent SKILL/agent.md 갱신**
- 6 agent.md (architect·backend-core·market-data·frontend·strategy-engine·ai-engineer) 본문 헤더 직후 헌법 §1-A·§1-C 인용 1줄 강조.
- 5 SKILL.md (magicjar-adr·magicjar-baseline·magicjar-backtest-engine·magicjar-kis-openapi·magicjar-orchestrator) 본문 헤더 직후 헌법 §1-A·§1-C 인용 1줄 강조.
- 본문 중복 금지 헌법 원칙 정합 — SKILL/agent.md는 ref만, 본문은 헌법에만.

**C. scripts/task.mjs `done` 자동화**
- `cmdDone()` 끝에 `execSync('git add <task path>')` 추가 (graceful — repo 아니거나 add 실패해도 done 성공).
- commit은 수행하지 않음 — 다음 코드 commit에 자연 stage. 무관 코드 stage risk 회피.
- 별도 `--commit` flag 미신설 — 자동 commit은 sub-agent context 손실 risk(어떤 메시지·author로 commit할지).

**D. backlog §59 신설** — 본 §.

**E. CHANGELOG row 추가** — add-only 1줄.

### 연관

- 헌법 `_workspace/arch/agent-charter.md` §1-A·§1-C·§8
- 6 `.claude/agents/*.md` + 5 `.claude/skills/magicjar-*/SKILL.md`
- `scripts/task.mjs` cmdDone
- ADR 0036 §2-3 (memory `feedback_3tier_hierarchy.md` "정정 예정" — 본 cycle에서는 ADR 0036 본문 미수정, 헌법 §1 단독 강화)
- evidence: PR #473·#474·#475·#476 (안정성 Cycle 1)

## §61. T-O Phase 2 미완 — OrderRouterKafkaListener Repository 미합류 (dogfooding 발견 2026-05-18) ✅ 처리 완료 (2026-05-18 architect)

### 사유

T-O 진입 prereq spec (PR #479, `_workspace/specs/2026-05-18-to-prereq-mock-prd-verify.md`) 채택 후 dogfooding 케이스 5(CREDENTIAL_NOT_FOUND) 실행 시 ERROR 발견:

```
2026-05-18T12:02:55.912+09:00 ERROR 1 --- [magicjar-consumer] [ntainer#0-0-C-1] k.s.m.c.order.OrderRouterKafkaListener   : OrderRouterKafkaListener — OrderRequestRepository 미합류 (Phase 2 미완): userId=74683223 signalId=99001
```

spec §6.3은 "Phase 2 LOCK 코드 — OrderQueryService ✓ 완료, OrderCancelService ✓ 완료, RealTradingConsentController ✓ 완료" 라고 진술했으나 **실측 — consumer 모듈에 OrderRequestRepository bean 합류 부재** → order.intent.v1 메시지 dispatch 실패 → **모든 주문 dispatch 작동 불가** (자본 흐름 차단 = 의도치 않은 안전이긴 하나, T-O 본 트랙 진입 사실상 불가).

### 영향

- 케이스 5 dogfooding fail (Repository 미합류 ERROR로 즉시 fall-through)
- 케이스 2·3 dogfooding은 같은 ERROR로 안전망 검증 자체 불가 (TradingModeGate까지 도달 못 함)
- 케이스 1(MOCK 정상)도 작동 불가 (MOCK credential 등록 무관)

### 처리 방향

- backend-core 또는 strategy-engine 영역 — OrderRouterKafkaListener의 OrderRequestRepository bean 합류 (Repository wiring + 영속 path)
- ADR 0068 정합 — §14-3 3중 게이트가 작동하려면 Repository 합류 의무
- architect spec/plan 발행 의제 — 본 backlog 진입 사이클

### 연관

- spec `_workspace/specs/2026-05-18-to-prereq-mock-prd-verify.md` (PR #479)
- ADR 0068 (T-O plan)
- `consumer/.../order/OrderRouterKafkaListener.kt`
- domain `OrderRequestRepository` port

### 처리 결과 (2026-05-18 architect)

본 §61는 단일 PR(`agent/architect/to-phase2-repository-wire`)로 마감됨:

**root cause 확정** (가설 b — 모듈 경계 합류 누락):
- port `OrderRequestRepository`·`OrderAuditEventRepository`는 domain 모듈에 위치 (OK).
- JPA Adapter(`api/persistence/order/OrderRequestRepositoryAdapter`·`OrderAuditEventRepositoryAdapter`)는 **api 모듈 단독** 거주.
- consumer는 `domain` + `pattern-engine`만 의존 (`consumer/build.gradle.kts` line 10·12 검증) — api 모듈 미의존이 ADR 0004 모듈 경계 정합.
- 따라서 consumer JVM의 Spring scan(`scanBasePackages = ["kr.spdaewoon.magicjar"]`)이 api 모듈 bean을 발견 불가 → `ObjectProvider.getIfAvailable()` 항상 null → 모든 주문 dispatch 실패.

**fix 안** (ConsumerCandle·KillSwitch 패턴 동일 — 모듈 경계 LOCK 유지):
- 신설 4 파일 (consumer/persistence/):
  - `ConsumerOrderRequestJpaEntity.kt` — `@Table("order_request")` 동일 테이블 사본 entity (Flyway V20 정합)
  - `ConsumerOrderRequestJpaRepository.kt` — abstract fun만 (헌법 §6)
  - `ConsumerOrderRequestRepositoryAdapter.kt` — port 구현 (`@Component`, 7 fun)
  - `ConsumerOrderAuditEventJpaEntity.kt` — `@Table("order_audit_event")` 동일 테이블 사본 entity (Flyway V22 정합)
  - `ConsumerOrderAuditEventJpaRepository.kt` — abstract fun만
  - `ConsumerOrderAuditEventRepositoryAdapter.kt` — port 구현 (`@Component`, 2 fun)

**모듈 경계 정합 (ADR 0004)**:
- consumer 모듈 의존성 변경 0건 (api 미의존 유지).
- 동일 테이블 entity 사본 패턴은 ConsumerCandleJpaEntity·ConsumerInstrumentJpaEntity·KillSwitchJpaEntity baseline (모듈 분리 정합).
- Flyway 마이그레이션은 api 모듈 단독 관리 유지 (ADR 0068 §15-1 LOCK 정합).

**ADR 0068 정합**:
- §14-2 12 step 흐름 — Step 5(persistRequested) + Step 8(updateKisAck) + Step 9(writeAudit) 모두 Repository 합류 활성화로 정상 작동.
- §14-3 3중 게이트는 Repository 합류 무관 (TradingModeGate.resolveMode 자체는 Repository 미사용) → backlog §61는 ADR 0068 §14-3 본문 변경 0건.

**단위 테스트** (consumer/src/test/.../persistence/):
- `ConsumerOrderRequestRepositoryAdapterTest.kt` — 4 케이스: (1) save 변환 + (2) updateKisAck ACK 전이 + (3) updateStatus REJECTED + (4) findById null 분기.

**연관 file**:
- 신규 6 파일 (consumer/persistence/Consumer*Order*)
- backlog `_workspace/backlog.md` §61 head + 처리 결과
- CHANGELOG.md row 추가
- task `_workspace/tasks/T-20260518-1222-to-phase2-repository-wire.md`

**dogfooding 재시도** = orchestrator 영역 (머지 후) — Kafka publish `order.intent.v1` 케이스 5 → ERROR 0건 + `reject_reason="CREDENTIAL_NOT_FOUND"` order_request row 영속 확인 의무.

## §62. ✅ OrderRouterKafkaListener — persistRequested catch 블럭 log 메시지 misleading (dogfooding 발견 2026-05-18 · 처리 완료 2026-05-18)

### 사유

T-O Phase 2 합류 fix(PR #481) 후 dogfooding 케이스 5(CREDENTIAL_NOT_FOUND) 재시도 시 첫 결함 진단:

```
2026-05-18T12:34:14.743 ERROR ... order_request violates foreign key constraint "order_request_signal_id_fkey"
2026-05-18T12:34:14.750 ERROR OrderRequestRepository.save 실패: userId=74683223 signalId=99002 err=...
2026-05-18T12:34:14.750 ERROR OrderRouterKafkaListener — OrderRequestRepository 미합류 (Phase 2 미완): userId=74683223 signalId=99002
```

**진단**: `persistRequested()` (L260~286)이 모든 save 실패를 catch → null 반환 → caller(L159)에서 null → "Repository 미합류" 출력. 실제로는 FK violation으로 save 실패 — 메시지 misleading.

### 처리 결과 (2026-05-18 — architect)

- `persistRequested()` 반환 타입 `OrderRequest? → PersistResult` sealed class로 변경 (`NotWired` / `SaveFailed(reason)` / `Ok(saved)` 3 분기).
- caller(`onOrderIntent` Step 5) `when` 분기로 두 결함 케이스 별도 log.error 메시지 출력:
  - `NotWired` → "OrderRequestRepository 미합류 (Phase 2 미완)" (기존 메시지 유지)
  - `SaveFailed` → "OrderRequestRepository.save 실패 (listener 진행 중단): reason=<exception message>" (root cause 명시)
- 단위 테스트 2건 추가 (`OrderRouterKafkaListenerTest`): (g §62) NotWired → 후속 0 / (h §62) SaveFailed → NotWired와 구분된 로그 + 후속 0.
- `./gradlew :consumer:test --tests "...OrderRouterKafkaListenerTest"` GREEN + `:consumer:ktlintCheck` GREEN.
- ADR 신규 0건 (sealed class 도입은 내부 helper 분기 — 외부 계약 변경 0).

### 연관

- `consumer/.../order/OrderRouterKafkaListener.kt` (sealed class PersistResult 도입)
- `consumer/.../order/OrderRouterKafkaListenerTest.kt` (g·h 케이스 추가)
- PR #481 (Phase 2 Repository 합류 fix)
- dogfooding evidence 2026-05-18 (orchestrator)

## §63. dogfooding signal seed data 부재 (spec §4.2 gap) — ✅ 본 spec `2026-05-18-to-main-track-entry-plan.md` §4.1 Step 4 흡수

### 사유

spec `2026-05-18-to-prereq-mock-prd-verify.md` §4.2 케이스 1·5는 `signalId` 임의 값으로 dogfooding 시도 명시. 실측 — `order_request.signal_id` 컬럼이 `signal` 테이블 FK 제약 — signal table 비어있으면 모든 dogfooding이 FK violation으로 실패.

orchestrator는 minimal strategy + signal seed row를 DB 직접 insert로 우회 — 케이스 5 GREEN 달성.

### 처리 방향

- spec gap — 추후 dogfooding 사이클을 위한 admin seed endpoint 신설 검토
- 또는 backend-core가 `_workspace/scripts/dogfood-seed.sql` 또는 Flyway development seed migration 발행
- 또는 spec 본문에 seed 단계 추가 (architect)
- 우선순위 낮음 (인프라 의제)

### 연관

- `_workspace/specs/2026-05-18-to-prereq-mock-prd-verify.md` §4.2
- `signal` 테이블 (V8?) + `strategy` 테이블 FK 의존
- dogfooding evidence 2026-05-18 (orchestrator)
