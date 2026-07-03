# magicJar Backlog (미뤄둔 의제)

> **위치**: `_workspace/backlog.md`
> **관리자**: orchestrator (사이클 시작 시 점검 의무) + architect (사용자 의제 흡수 시 갱신)
> **형식**: 항목 = 사유 + 트리거 조건 + 보강 안 + 예상 처리 PR 또는 사이클
> **신설일**: 2026-05-07 (PR-10 사이클 — 사용자 명시 "미뤄두는 의제들 관리해줘")

---

> **처리 완료 아카이브**: [backlog/done/pre-v3.15.md](backlog/done/pre-v3.15.md) — 2026-07-03 감사 P2 대청소로 `✅ 처리 완료`/`✅ DONE` § 19건 이전(원문 그대로 보존). 완료/미완 판별이 모호한 §은 보수적으로 본 파일에 잔류.

## §0-A. P1-4 KIS Rate Limit Bucket4j 실 구현 의제 (2026-05-19 신설 · architect ADR 0037 amend + spec 발행 완료 · **Phase 1 backend-core 완료 PR #N**)

### 사유

market-data PR #555 system audit evidence — **Rate Limit 가드 DEGRADED**:

- ADR 0037 §1 LOCK "초당 10건" vs 코드 주석 **"20 req/s" 15+ 위치** spec drift (실제 KIS 한도 절반 수준의 잘못된 mental model)
- `KisRateLimiterPort` 도메인 포트 0건 + Bucket4j dependency 0 + 모든 KIS adapter 직접 `webClient.get()/post()` (sleep(50ms) 마진만 적용 → 20 req/s 가정)
- EGW00201 / HTTP 429 명시 backoff path 각 1곳만 부분 구현
- Circuit Breaker 5분 차단 (ADR 0037 §2-2) 0건 + ArchUnit 빌드 게이트 0건

→ 사용자 트리거 burst(16종목 × 5 timeframe = 80건/burst) 또는 batch backfill 폭주 시 KIS 한도 침범 risk → 계정·앱키 차단 → 본체 자동매매 전체 중단 catastrophic.

### Spec/ADR 발행 (architect, 2026-05-19 완료)

- ADR 0037 Accepted (amend) — `_workspace/arch/adr/0037-kis-rate-limit-enforcement.md` (§1-1·§2-1·§2-2·§5·§6 amend)
- Spec 본문 — `_workspace/specs/2026-05-19-p1-4-bucket4j-kis-rate-limiter-spec.md` (9 § 풀세트)

### Phase 1 완료 (backend-core, 2026-05-19 PR #N)

- 영역: domain port `KisRateLimiterPort` + `KisCallPriority` enum + sealed `KisRateLimitException` 2종 (`KisRateLimitTimeoutException`·`KisCircuitOpenException`) + Bucket4j-Redis adapter + Bean wiring + Gradle dep + application.yml + 단위 테스트 4건 = ~7 신규 파일 + 3 갱신 파일
- adapter 모듈 위치 1차 결정: **옵션 a 채택** (api 모듈 단독 Bean — Phase 2 batch·consumer 별도 Bean wiring 후속 dispatch). 사유: Phase 1 단순화 LOCK + ADR 0088 모듈 패턴은 JPA 기반이라 Bucket4j Redis-only는 부적합. Phase 2 합류 후 multi-JVM 모듈 분리 의제 별도 평가.
- Gradle dep: `com.bucket4j:bucket4j-core:8.10.1` + `com.bucket4j:bucket4j-redis:8.10.1` (api 단독)
- 단위 테스트 4건 GREEN: (1) acquire 즉시 return (2) polling 후 token 회복 return (3) timeout 시 `KisRateLimitTimeoutException` throw (4) `onTpsExceeded()` 시그널 카운터 누적
- spec §6-1 #2(EGW00201 backoff)·#3(HTTP 429 backoff)·#4(Circuit Breaker OPEN 5분)는 Phase 2·3 합류 후 통합 테스트로 검증 (Phase 1 어댑터 자체는 Circuit Breaker stub — `onTpsExceeded`/`onSuccess` 시그널만 누적)

### Phase 1 follow-up backlog (2026-05-19 발행)

- **Bucket4j 8.10.1 `withExpirationStrategy` deprecation 정정** — `KisRateLimiterConfiguration.kt:95` `LettuceBasedProxyManager.builderFor().withExpirationStrategy(...).build()` deprecated. Bucket4j 9.x baseline 또는 8.x 신규 대체 API 검증 후 정정. K2 compile warning 1건만 — 빌드는 GREEN. Phase 2 합류 또는 별도 chore PR로 처리.
- **Circuit Breaker 실 차단 path** — Phase 1은 `onTpsExceeded`/`onSuccess` 카운터만, OPEN/HALF_OPEN 차단 0. Phase 3 Resilience4j 또는 Bucket4j Listener 합류 의무.
- **우선순위 큐** — `KisCallPriority` Phase 1 정보 수집만, USER_PATH 즉시 / INGESTION / BACKFILL 라우팅 0. Phase 2 합류 후 priority별 polling interval 또는 Redis-side queue 검토.

### Phase 2 대기 (Phase 1 머지 후 market-data dispatch)

- 모든 KIS REST 어댑터에 `kisRateLimiterPort.acquire(priority=...)` 합류 (~20 어댑터)
- EGW00201 / HTTP 429 → `onTpsExceeded()` 호출 + 1초 backoff + 1회 재시도
- spec drift 정정: `20 req/s` → `초당 10건` replace_all + `Thread.sleep(50)` → acquire 도입(또는 sleep(100ms) 정합)
- 예상 LOC ~400

### Phase 3 대기 (별도 사이클 · backend-core + qa)

- Circuit Breaker 통계 5분/50% 임계 구현 (Resilience4j 또는 Bucket4j Listener)
- Slack alert P1 (`notification_outbox` reuse — ADR 0044)
- ArchUnit 빌드 게이트 (`RestClient`/`WebClient` 직접 KIS 호출 detection)
- ktlint custom rule
- 예상 LOC ~300

### 트리거

- **우선순위 ★★**: 자본 흐름 차단 risk(계정 차단) — 사용자 결재 후 Phase 1 backend-core dispatch
- v3.6.x patch 후보(`feedback_release_policy_patch_first` v3.7=W4 마감 정합 — 본 P1-4는 patch 누적)

### 의존성

- Phase 1은 ADR 0088(persistence-shared) Phase 1 머지 후 dispatch 권장 (adapter 모듈 위치 옵션 b 채택 시 — 모듈 신설 1회 운영 흡수 + 본 ADR 0037 adapter 합류)
- Phase 2는 Phase 1 머지 후 market-data 영역 단일 dispatch (~20 어댑터 일괄)

---

## §1. ADR 0050 슬롯 채번룰 보강 (hunk 분리 + 영역 분배 강화)

### 사유

PR-9 첫 운영에서 한계 노출:
- (a) git이 hunk-based merge라 인접한 슬롯 row가 같은 hunk로 묶여 CHANGELOG 충돌 1건 발생
- (b) ADR 0050은 CHANGELOG에만 적용되고 다른 modify-shared 파일은 dispatch §4-1 영역 분배 모호로 add-add 충돌 4건 발생

### 보강 안

1. **CHANGELOG hunk 분리** — 슬롯 row 사이에 anchor line 또는 markdown 안전한 분리 마커 삽입
   - 본 PR-10에서 HTML 주석 anchor (`<!-- pr-10-slot-{agent} -->`) 첫 시도 — 사용자 검증 후 ADR 0050 본문 정정 여부 결정
   - 단순 빈 줄은 markdown table 깨짐 risk → 부적합
2. **dispatch-spec §4-1 작성 의무에 영역 분배 LOCK 항목 추가**:
   - 양 sub-agent의 add-add 가능 파일 사전 점검 의무 (본 PR-10에서 사전 비교 검증)
   - consumer 모듈 신규 add 파일은 어느 sub-agent 책임인지 명시 의무
   - application.yml 보강은 한 sub-agent 단독으로 분배 (양쪽 분배 금지)
   - domain port 신규는 양 sub-agent의 산출물에 어디 속하는지 명시
3. **architect SKILL에 영역 침범 점검 의무 명시** — sub-agent에 디스패치 전 architect가 행렬 점검 의무

### 트리거

- **우선순위 ↑**: PR-11 이후에서 또 modify-shared 충돌 또는 add-add 발생 시
- **우선순위 ↓**: PR-10에서 HTML 주석 anchor 시도가 성공하면 ADR 0050 본문에 anchor 명세 추가만 (기능 안정 시 보강 충분)
- 안 발생하면 사용자 명시 트리거까지 대기

### 예상 처리 PR

PR-11~12 사이클 또는 사용자 명시 트리거 시 — architect 단독 PR (`docs(adr): ADR 0050 slot anchor 명세 보강`).

---

## §2. KisRestPort 인터페이스 분리 (KisRestCandlePort + KisRestPricePort)

### 사유

PR-9b에서 발견된 결함:
- `KisRestPort.fetchPrice` 추가 시 `KisRestCandleAdapter`(backend-core 산출, candle만 담당)가 fetchPrice 미구현 컴파일 에러
- 임시 fix `NotImplementedError throw` 적용 (commit `7adedea`)

### 정정 안

KisRestPort를 다음과 같이 분리:
- `KisRestCandlePort` — `fetchCandles(code, interval, count)` only
- `KisRestPricePort` — `fetchPrice(code): TickEvent` only
- `KisRestPriceMetricsPort` — `fetchPriceMetrics(code): PriceMetrics` only (본 PR-10b 신규 — 기존 KisRestPort에 추가하지 않고 별도 port)

각 어댑터는 자기 port만 implement:
- `KisRestCandleAdapter` → KisRestCandlePort
- `KisRestPriceAdapter` → KisRestPricePort + KisRestPriceMetricsPort (1 어댑터 2 port)

### 트리거

- **우선순위 ↑**: KisRestCandleAdapter.fetchPrice 호출 시 NotImplementedError 발생 (현재 임시 fix로 발생 가능성 0이지만 향후 reflection 호출 시 risk)
- **우선순위 ↓**: 향후 KisRestPort 추가 메서드 도입 시점 (T-O·T-RECON·T-IM1)
- **본 PR-10에서 부분 정정**: PR-10b가 `KisRestPriceMetricsPort` 별도 port로 신규 (KisRestPort에 추가하지 않음) — 분리 정책 정합

### 예상 처리 PR

T-O 또는 T-RECON 진입 시 — backend-core PR (`refactor(domain): KisRestPort 책임 분리 — KisRestCandlePort + KisRestPricePort`).

---

## §3. CLAUDE.md ADR 인덱스 SoT 갱신

### 사유

root `CLAUDE.md` "단일 진실원" 표 중 다음 라인이 옛 정보:
- `ADR 인덱스 ... README.md (1~40, 파일명 = 본문 헤더 번호)` — 현재 ADR 50건 발행됨

### 정정 안

다음 중 택1:
- (A) 정확한 카운트로 갱신 — `(1~50, 파일명 = 본문 헤더 번호)` (단 발행할 때마다 갱신 필요)
- (B) 카운트 제거 — `(1~N, README가 권위)` (README가 SoT 정합)
- **architect 권장 = 옵션 B** (자동 갱신 부담 최소)

### 트리거

- 다음 메타 정리 사이클
- 사용자 의식 시 또는 ADR 50건 초과 시점

### 예상 처리 PR

PR-11 이후 메타 정리 사이클 또는 별도 hotfix PR (architect 단독, ~5분).

---

## §4. worktree-agent-* fallback 브랜치 정리

### 사유

Agent 도구 isolation worktree가 만든 임시 fallback 브랜치 7건 누적:
- origin push 0이라 `-d` 거부
- `-D` 강제 삭제는 사용자 정책에서 거부 가능성 (사용자 메모리 `feedback_worktree_cleanup.md` — "머지 후 정리는 그대로 의무")

현재 branch list 예시 (본 PR-10 시작 시):
- `worktree-agent-a726ac98dd501f34b`
- `worktree-agent-a6ae1b8738b4ca062`
- (외 5건 가정)

### 정정 안

다음 중 택1:
- (A) 누적 더 늘면 한 번에 사용자 직접 승인 후 일괄 정리
- (B) Agent 도구 자체에 isolation worktree 자동 cleanup 기능 추가 (도구 변경 — 사용자 결정 필요)
- (C) 정기 cleanup cron (1주 단위) — Husky/CI 외부 작업

**architect 권장 = 옵션 A** (사용자 결정 단순).

### 트리거

- worktree-agent-* 누적 15건 초과
- 사용자 명시 cleanup 요청
- 또는 Agent 도구 isolation worktree 정책 재검토 사이클

### 예상 처리 PR

별도 chore PR (architect 또는 사용자 직접 — `chore: worktree-agent-* fallback 브랜치 일괄 cleanup`).

---

## §5. backend-core 영역 침범 패턴 (consumer 모듈에 add-add)

### 사유

PR-7·PR-9에서 backend-core가 consumer 모듈에 ConsumerWatchlistJpa·ConsumerCandleJpa 등 add. dispatch-spec §F의 "consumer 자체 모듈 사본 패턴" 정합으로 명시되긴 했으나, market-data 영역과 add-add 충돌 4건 야기 (PR-9 운영 결과).

### 정정 안

1. **dispatch-spec §4-1 작성 의무에 명시 (본 PR-10에서 적용 — backlog §1과 함께)**:
   - "consumer 모듈에 새로 add할 entity/repository/adapter는 어느 sub-agent 책임인지 명시"
   - 양 sub-agent의 add-add 가능 파일 사전 비교 검증
2. **ADR 신규 발행 (장기)**:
   - 모듈 영역 매트릭스 정의 — backend-core/market-data/strategy-engine/ai-engineer/frontend가 각 모듈에 add 가능한 디렉토리 명시
   - 예: backend-core는 consumer 모듈 "persistence·application·service" 디렉토리만, market-data는 consumer 모듈 "ws·infrastructure/kis·market·ingestion" 디렉토리만
3. **본 PR-10에서 부분 적용**:
   - dispatch-spec §4-1 영역 분배 LOCK으로 add-add 0% 사전 검증
   - frontend·market-data 영역이 frontend vs api/consumer/domain로 자연 분리

### 트리거

- **우선순위 ↑**: PR-11 이후 또 영역 침범 add-add 발생 시
- **우선순위 ↓**: ADR 0050 보강(§1)과 함께 처리 가능
- 또는 모듈 영역 매트릭스 정의 ADR 발행 결정 시 (예: ADR 0051 후보)

### 예상 처리 PR

backlog §1과 함께 — `docs(adr): ADR 0051 모듈 영역 매트릭스 (sub-agent × 모듈 × 디렉토리)` (architect 단독, ~30분).

---

## §6. 사용자 첫 dogfooding 결과 흡수 (PR-10 머지 후)

### 사유

본 PR-10 머지 후 사용자 첫 dogfooding 직전 — 사용자 메모리 `project_track1_dogfooding_paused.md` 정합. 사용자가 화면에서 발견한 결함은 본 backlog에 흡수 후 다음 사이클 처리 의무.

### 정정 안

dogfooding 결과 발견 결함 분류:
- (A) **단순 결함 (1줄·1파일)**: 즉시 hotfix PR (frontend 또는 architect 단독)
- (B) **정책 변경 또는 다중 모듈**: 본 backlog에 항목 추가 + 다음 사이클 architect 디스패치
- (C) **블로킹 결함 (사용 불가)**: 사용자 → 오케스트라 호출 → architect 즉시 진입 → 새 PR 사이클

### 트리거

- 본 PR-10 머지 후 24시간 이내 사용자 dogfooding 진행
- 발견 결함은 **본 항목에 sub-section 추가** (dogfooding 시점 갱신)

### 예상 처리 PR

dogfooding 결함 발견 시 즉시 갱신 — 사용자 명시 + orchestrator 다음 사이클 첫 의제로 점검 의무.

### dogfooding 결과 (예약 — 본 PR-10 머지 후 채움)

| 발견 시점 | 결함 항목 | 분류 | 처리 PR |
|---|---|---|---|
| 2026-05-07 (1차) | docker-compose `SPRING_DATASOURCE_URL` env_file이 application.yml override → magicjar-api 부팅 실패 (`localhost:5432` Connection refused) | C 블로킹 | PR-12 ✅ |
| 2026-05-07 (2차) | nginx `/api/` 프록시 누락 → SetupWizard `POST /api/onboarding/kis/validate` 405 | C 블로킹 | PR-13 ✅ |
| 2026-05-07 (2차) | docker-compose dozzle 중복 정의 → YAML parse error | C 블로킹 | PR-15 ✅ |
| 2026-05-07 (3차) | audit_trail `detail_json` jsonb mismatch → 500 / batch `KisInstrumentMasterPort` bean missing / consumer `KisRestPort` 2 bean ambiguous | C 블로킹 | PR-16 ✅ |
| 2026-05-07 (4차) | batch WebClient bean missing / DashboardPlaceholder 셋업 버튼 무한루프 / frontend healthcheck wget / build-script dozzle 누락 | C 블로킹 | PR-17 ✅ |
| ~~2026-05-07 (5차 — 미처리)~~ | **관심종목: 종목명 조회 안 됨 (코드만 가능)** | B 정책·다중 | PR-11b ✅ (한글 자동완성 InstrumentSearchInput) + PR-29c 회귀 검증 |
| ~~2026-05-07 (5차 — 미처리)~~ | **관심종목: 일부 종목 노출 안 됨 (예: 004090 한국석유)** | B 정책·다중 | 7b6b904 ✅ (scrt_grp_cls_code='ST' 필터 + sentinel sanitize) + PR-29c 회귀 검증 |
| ~~2026-05-07 (5차 — 미처리)~~ | **관심종목 등록 후 클릭 시 "종목 정보 찾을 수 없습니다"** | C 블로킹 | PR-21b ✅ (InstrumentJpaEntity 83 필드 + Repository·DTO 매핑) + 9dde856 ✅ (delisted UX) + PR-29c 회귀 검증 |

### 갱신 (2026-05-07 — PR-11 사이클 진입)

사용자 명시 — dogfooding 잠시 보류 + "다음 일정 진행". orchestrator가 T-IM1 우선 결정:
- **사유 1**: 종목 검색 자동완성으로 dogfooding 가시성 ↑ (한글 검색 지원 → "삼성" 입력만으로 등록 가능)
- **사유 2**: PR-7 옵션 C raw code 함정 자연 해소 (instrument stub `market='UNKNOWN'`이 첫 sync 후 정확 데이터로 UPDATE)
- **사유 3**: T-IND·T-DSL·T-SC·T-BT 선행 의무 (universe 정의 부재 시 후속 모든 트랙 차단)

본 PR-11 머지 후 dogfooding 재개 가능 트리거 — 사용자 의제 → 위 표 dogfooding 결과 sub-section 채움.

---

## §7. 후속 트랙 진입 가이드 (W1 잔여 + W2 시작점 결정)

### 사유

PR-10 머지 후 W1 ★ 3건(T-W1·T-W2·T-S1) 완료. W1 잔여 트랙(T-MS1·T-IM1·T-CA1) + W2 진입 결정 사용자 의제.

### 후보 트랙 (tracks-roadmap §1)

W2 — 분석 도구 (T-IND·T-PAT·T-DSL·T-SB):
- **T-IND** (80지표 일괄 + Source 추상화) — XL 분량
- **T-PAT** (57패턴 인식 + 차트 마커) — M 분량 (T-IND 선행)
- **T-DSL** (.kis.yaml superset 파서·평가기) — L 분량 (T-IND·T-PAT 선행)
- **T-SB** (GUI 블록 빌더) — XL 분량 (T-DSL 선행)

W1 잔여 후보:
- **T-MS1** (Market Session FSM + Fallback 3-tier T3 last-day-close) — M 분량 (W1 보강이지만 후속 모든 트랙 선행)
- **T-IM1** (Instrument Master + 종목 검색·자동완성) — M 분량 (T-CA1·T-IND 선행) — **본 PR-11 진입 ★ (orchestrator 결정)**
- **T-CA1** (Corp Action + adjusted) — M 분량 (T-BT 선행)

### 정정 안 (2026-05-07 갱신 — PR-11 진입)

**현재 진행 — T-IM1 (PR-11)**:
- 사용자 dogfooding 보류 중 + 다음 일정 진행 의제 → orchestrator T-IM1 우선 결정
- 사유: 자동완성 가시성 ↑ + PR-7 옵션 C 자연 해소 + T-IND·T-DSL 선행 (`pr-11-im1-design.md` §1-3 비즈니스 가치 정합)

**PR-11 머지 후 다음 트랙 후보**:
- (A) **T-MS1** (KRX 영업일 캘린더 + last-day-close fallback) — W1 잔여 마무리
- (B) **T-IND + T-PAT 병렬** (W2 직접 진입)
- (C) **T-CA1** (Corp Action — T-BT 선행)

**architect 권장**: PR-11 머지 후 T-MS1 진입 — 휴장일 정합 + 시세 fallback 부족 보강(현재 SimpleMarketSessionResolver 시간 stub). W2 진입은 T-MS1 완료 후.

### 트리거

- PR-11 머지 + dogfooding 결과 + backlog §6 점검 후
- 사용자 결정 → orchestrator 다음 사이클 첫 의제

### 예상 처리 PR

PR-12 architect 디스패치 — 다음 트랙 spec/plan 발행 (T-MS1 또는 사용자 결정).

---

## §8. STOMP relay broker 도입 (post-MVP)

### 사유

본 PR-10b는 simple broker (in-process). 사용자 path 분산 시 한도. relay broker(Redis backend)는 multi-tenant 본격 진입 시 필요.

### 정정 안

`@EnableWebSocketMessageBroker` 설정에서 `enableStompBrokerRelay` 사용 + Redis backend.

### 트리거

- multi-tenant 본격 진입 시점
- 사용자 부담 분산 측정 시 (post-MVP)

### 예상 처리 PR

post-MVP — 별도 트랙 또는 사용자 명시 시.

---

## §9. frontend 모바일 반응형 1차 미지원

### 사유

본 PR-10a는 desktop 우선. 모바일은 stacked layout 1차 OK 정도. 사용자 dogfooding 모바일 시 표시 깨짐 가능.

### 정정 안

`tailwindcss` breakpoint(`md:`·`lg:`) 본격 적용 + 모바일 chart UX 정책.

### 트리거

- 사용자 모바일 dogfooding 시 깨짐 발견
- T-LWC 진입 시 함께 정합

### 예상 처리 PR

T-LWC 또는 별도 frontend hotfix PR.

---

## §10. AI 에이전트 영역 침범 ADR 누락 (장기)

### 사유

magicJar 7 sub-agent 중 strategy-engine·ai-engineer는 본 PR-10까지 미출연. 출연 시 영역 침범 정책 부재.

### 정정 안

ADR 0036 §2 (orchestrator 룰) 또는 신규 ADR로 sub-agent별 modify 가능 영역 매트릭스 정의.

### 트리거

- W2(T-IND·T-DSL) 진입 시 strategy-engine 첫 출연 → 영역 침범 risk 검증
- W4(T-AI) 진입 시 ai-engineer 첫 출연

### 예상 처리 PR

W2·W4 진입 시 architect 사전 발행.

---

## §11. 변경 이력

| 날짜 | 변경 | 사유 |
|------|------|------|
| 2026-05-07 | 본 backlog 신설 (10건 흡수 — 사용자 명시 5건 + architect 추가 5건) | PR-9 완료 후 사용자 명시 — "미뤄두는 의제들 관리해줘". architect가 5건 추가 (§6 dogfooding 결과·§7 W2 진입·§8 STOMP relay·§9 모바일 반응형·§10 AI 에이전트 영역 침범) |
| 2026-05-07 | PR-11 진입 갱신 — §6 dogfooding 보류 명시 + §7 W1 잔여 진입(T-IM1) 정정 + §11 SymbolControllerTest mock 별도 PR 권장 명시 | 사용자 dogfooding 보류 + "다음 일정 진행" 의제 → orchestrator T-IM1 우선 결정. ADR 0017·0039 정합 spec/plan PR-11 발행 (`_workspace/specs/pr-11-im1-design.md` 외 2종) (architect) |
| 2026-05-07 | PR-21 진입 갱신 — §16 instrument 14 컬럼 fitness gap 신설(사용자 비판 응답 진행) + §17 architect SKILL KIS-first raw fetch 의무 강화 신설 | 사용자 강한 비판 ("kis 데이터 규격 확인 안하고 막 만들어서 꾸겨넣은거잖아") → PR-21 spec 5종 발행 (KIS 헤더 raw 검증 보고서 baseline + 70 필드 add-only V5 + Entity 83 필드 + 파싱 본격 LOCK) + ADR 0051 신설 검토 (architect) |
| 2026-05-08 | PR-26 운영 헌법 통합 — §15·§17 ✅ 처리 완료 마킹 + §18 batch test @Disabled 신설 + §19 task.mjs --force ✅ + §20 Spring Data default fun trap ✅ | `_workspace/arch/agent-charter.md` 신설 (7 § 룰 통합) + 9 sub-agent SKILL 슬림화 (1줄 헌법 참조) + ADR 0051 신설 + scripts/task.mjs --force parsing fix. 사용자 명시 "강령 만들어야 하고", "토큰 소모 없이 가게". (architect) |
| 2026-05-08 | PR-29 W1 마감 사이클 진입 — §21 신설 (W1 마감 + v3.1.0 진입) + §6.5 dogfooding 5차 3건 처리 마킹 (PR-11b/7b6b904/PR-21b/9dde856) + §11·§18 PR-29d 처리 예정 마킹 | 옆 세션 hotfix 2건 머지 후 사용자 명시 "얼른 W1 마감하고 릴리즈 찍자" Auto Mode → architect spec 4건 발행(design + dispatch + conflict + CHANGELOG slot) + KIS-first 7 endpoint raw + DART OPENDART raw + Flyway V6/V7/V8 LOCK + 시나리오 A 적극 병렬. ADR 0013·0014·0015·0016·0018 정합 — 신규 ADR 0건. (architect) |

---

## §11. SymbolControllerTest OnboardingGuardFilter mock 보강 (PR-29d 처리 예정)

### 사유

PR-10 통합 빌드 시 `SymbolControllerTest` 3건 모두 401 응답으로 fail (OnboardingGuardFilter가 셋업 미완으로 인식). hotfix PR에서 `@Disabled`로 일시 비활성화. 사용자 dogfooding 진입 우선 + 별도 PR로 정정.

### 정정 안

- `@MockBean` 또는 `@SpringBootTest` 설정에서 `UserAccountJpaRepository.existsAny()` mock = `true` 반환
- 또는 `OnboardingGuardFilter` 자체를 `@MockBean`으로 stub (모든 요청 통과)
- 또는 test profile에서 `OnboardingGuardFilter` 비활성화

### 트리거

**2026-05-07 갱신 — PR-11 사이클 진입 시 정합**:
- PR-11b backend-core가 `InstrumentControllerTest` 신규 작성 시 동일 mock 패턴 적용 의무 (`@MockBean UserAccountJpaRepository.existsAny()=true`) — `pr-11-dispatch-spec.md` §4-2-I 명시
- 본 PR-11 사이클에서는 SymbolControllerTest 정정 자체는 **별도 PR 권장** (영역 침범 회피 — 룰 2 단일 책임)
- PR-11 머지 후 또는 W1 R3 일괄 테스트 사이클에서 묶음 처리

### 예상 처리

별도 소형 PR (`hotfix/symbol-test-mock`) — backend-core 영역 (PR-10b 발행 어댑터 테스트 정정). PR-11과 묶지 않음 (단일 책임).

---

## §16. instrument 테이블 14 컬럼 fitness gap (PR-21 처리 진행 중)

### 사유 (사용자 비판 응답)

사용자 강한 비판 (2026-05-07):

> "kis 에서 제공받는 데이터 규격 확인 안하고 그냥 막 테이블 만들어서 꾸겨넣은거잖아 ... kis openapi 최소한 규격만봐도 이렇게 데이터가 많은데 ... 이렇게 하는게 말이되냐"

**위반 사실**:
- PR-11 V4 결정 시 KIS 헤더 raw 정독 0건 → instrument 테이블 14 컬럼 자체 추론
- KIS open-trading-api `종목마스터정보(코스피).h` 73 필드 + `종목마스터정보(코스닥).h` 67 필드 제공인데 **본 시스템 6 필드만 활용** (fitness gap 91.7%)
- 사용자 메모리 `feedback_kis_first_policy.md`(자체 추론 금지) + `feedback_v3_skepticism_no_regression.md`(fitness gap 의무) 위반

### 정정 안 (PR-21 진행)

PR-21 spec 5종 발행 (architect 자율 결정 사이클):
- `_workspace/specs/pr-21-instrument-kis-first-design.md` — 70 필드 add-only Tier 1·2·3·4 + V5 LOCK + Entity 83 필드 LOCK + KisInstrumentMasterAdapter 파싱 본격 LOCK + scrt_grp_cls_code 권위 매핑 + KOSPI/KOSDAQ 차이 14건 흡수 + 트레이드오프 9건
- `_workspace/specs/pr-21-kis-header-raw-verification.md` — KIS 헤더 raw 검증 보고서 (KOSPI 73 필드 + KOSDAQ 67 필드 + Python 파서 widths 1:1 정합 + byte offset 매트릭스)
- `_workspace/specs/pr-21-dispatch-spec.md` — PR-21a market-data + PR-21b backend-core 분배 + KIS-first raw fetch 의무 강화
- `_workspace/specs/pr-21-conflict-matrix.md` — 영역 분배 LOCK + add-add 0% + modify-shared 1건(CHANGELOG)만
- `.claude/agents/architect.md` (또는 SKILL) — KIS-first raw fetch 의무 강화 (재발 방지)

### 트리거

- 본 backlog 항목은 PR-21 spec PR 머지 시점에 추가
- PR-21a + PR-21b 양쪽 머지 후 ✅ 처리 완료 마킹
- 사용자 dogfooding 검증 — Watchlist row에 type·시총·매매가드 노출 확인

### 예상 처리 PR

- spec PR (architect — 본 사이클)
- PR-21a (market-data) — V5 + 파싱 본격 + InstrumentType 'DR' enum
- PR-21b (backend-core) — InstrumentJpaEntity 83 필드 + Repository·Service·DTO 보강

### 사용자 비판 응답 ★ 재발 방지

1. **본 사이클에서 fitness gap 91.7% → 13% 축소** (Tier 1·2 자동매매 본체 + Tier 3·4 후속)
2. **architect SKILL 강화** — spec 산출 시 외부 데이터 규격 raw fetch 의무 명시 (`.claude/agents/architect.md` 또는 ADR)
3. **재발 방지 체크리스트** — 새 KIS endpoint 사용 시 → KIS open-trading-api 레포 raw fetch 의무 (코드 inline 주석에 raw URL 명시)
4. **자체 추론 0 정책** — sub-agent 디스패치 명세에 명시 의무 (sub-agent 코드 inline에 출처 인용)

---

## KIS-first raw fetch 의무 (`feedback_kis_first_policy.md` + 사용자 비판 응답)

spec/plan PR 발행 시 KIS API·외부 데이터 규격을 결정하는 경우:

1. **KIS open-trading-api 레포 raw fetch 의무** — `curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/...` 직접 다운로드 후 정독
2. **자체 추론 0 정책** — tr_id·endpoint·필드·byte offset·DSL 자체 결정 금지. 모든 결정은 raw 출처 인용 의무
3. **검증 보고서 산출 권장** — 새 endpoint·새 데이터 규격 결정 시 `_workspace/specs/<pr>-kis-raw-verification.md` 신설 (예: PR-21에서 `pr-21-kis-header-raw-verification.md`)
4. **sub-agent에게 raw 출처 직접 인용 의무 전파** — dispatch §에 KIS-first 출처 인용 의무 명시 + sub-agent 코드 inline 주석에 raw URL 명시
5. **MCP 사용 권장** — `mcp__kis-code-assistant__*` 호출 결과 spec 또는 PR 본문에 인용

위반 시: 사용자 비판 risk + fitness gap 발생 risk + sub-agent 자체 추론 risk 누적.
```

또한 새 ADR 검토:
- 후보 (a) ADR 0051 — KIS-first raw fetch 의무 (강화 ADR)
- 후보 (b) ADR 0017 보강 (Instrument Master 1급 시민) — KIS 헤더 raw fetch 의무 추가

architect 권장: **(a) ADR 0051 신설** — 명확한 별도 ADR로 sub-agent SKILL에서도 cross-ref 가능.

### 트리거

PR-21 사이클 일환. spec PR에 SKILL 강화 + ADR 0051 신설 포함.

### 예상 처리 PR

architect spec PR-21 (현재 사이클) — `architect.md` SKILL 보강 + ADR 0051 신설 옵션 (architect 자율 결정).

---

## §18. SymbolControllerTest 미정 + batch test @Disabled 2건 (PR-23 잔재) (PR-29d 처리 예정)

### 사유

- PR-23 통합 빌드 hotfix에서 batch test 2건 `@Disabled` 추가 (이름 미정 — 사용자 보고 시점에 정확)
- backlog §11 SymbolControllerTest mock 보강과 동일 패턴

### 트리거

W1 R3 일괄 테스트 사이클 또는 사용자 명시 트리거 시.

### 예상 처리 PR

별도 소형 PR (`hotfix/batch-test-mock`) — backend-core 영역.

---

## §21. W1 마감 + v3.1.0 진입 (PR-29 사이클)

### 사유

PR-26(헌법 통합) + 옆 세션 hotfix 2건(7b6b904 KIS sentinel + 9dde856 delisted UX) 머지 후 W1 잔재 3 트랙(T-MS1·T-CA1·T-S1 잔재) 마무리 + v3.1.0 릴리즈 게이트 진입. 사용자 명시 — "얼른 W1 마감하고 릴리즈 찍자". Auto Mode.

### 트랙 구성

- **T-MS1** (Market Session FSM + Fallback 3-tier + REJECTED Audit) — backend-core 책임 (PR-29a)
- **T-CA1** (Corporate Action + Adjusted Price) — market-data 책임 (PR-29b)
- **T-S1 잔재** (LWC 차트·호가 STOMP·dogfooding §6.5 회귀 검증) — frontend 책임 (PR-29c)
- **W1 R3 일괄 테스트** — backend-core 책임 (PR-29d, R3 후반)

### 산출물

- `_workspace/specs/pr-29-w1-close-design.md` — 통합 design (Flyway V6/V7/V8 LOCK + KIS-first 7 endpoint raw + DART OPENDART raw + 함정 8건)
- `_workspace/specs/pr-29-dispatch-spec.md` — 4 sub PR 분배 + 영역 LOCK + add-add 0% 사전 검증
- `_workspace/specs/pr-29-conflict-matrix.md` — 시나리오 A 적극 병렬 권장 + modify-shared 5건 모두 LOCK 분배
- CHANGELOG.md — anchor 5건 + row 5건 사전 발행 (architect 본 PR + 4 sub-agent TBD)
- 본 backlog §21 신설 + §6.5·§11·§18 처리 마킹

### KIS-first raw fetch 출처 (헌법 §3 의무)

7 endpoint raw 출처 인용 (design §3-3·§4-3 + dispatch §2-2·§3-2):

1. `chk_holiday/chk_holiday.py` — `/uapi/domestic-stock/v1/quotations/chk-holiday` / `CTCA0903R`
2. `ksdinfo_dividend/ksdinfo_dividend.py` — `/uapi/domestic-stock/v1/ksdinfo/dividend` / `HHKDB669102C0`
3. `ksdinfo_rev_split/ksdinfo_rev_split.py` — `/uapi/domestic-stock/v1/ksdinfo/rev-split` / `HHKDB669105C0`
4. `ksdinfo_merger_split/ksdinfo_merger_split.py` — `/uapi/domestic-stock/v1/ksdinfo/merger-split` / `HHKDB669104C0`
5. `ksdinfo_bonus_issue/ksdinfo_bonus_issue.py` — `/uapi/domestic-stock/v1/ksdinfo/bonus-issue` / `HHKDB669101C0`
6. `ksdinfo_paidin_capin/ksdinfo_paidin_capin.py` — `/uapi/domestic-stock/v1/ksdinfo/paidin-capin` / `HHKDB669100C0`
7. `ksdinfo_cap_dcrs/ksdinfo_cap_dcrs.py` — `/uapi/domestic-stock/v1/ksdinfo/cap-dcrs` / `HHKDB669106C0`

DART OPENDART 출처: `https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001` (list endpoint + pblntf_ty B/D 매핑)

### 릴리즈 게이트

- PR-29a~d 모두 머지 후 main `./gradlew check` GREEN + `scripts/smoke.sh` 통과
- dogfooding 1사이클 통과 (셋업 → Watchlist → SymbolDetail → 차트·호가 동작)
- v3.1.0 태그 컷 + `release/v3.1` 베이스라인 push (ADR 0031 immutable)
- CHANGELOG.md "## [3.1.0] - 2026-05-?? — W1 마감" 섹션 본 사이클 PR 묶음 요약

### ADR 신규 발행 검토

**결론: 신규 0건 권장** (design §9). 0013·0014·0015·0016·0018 모두 발행됨 — 결정 사유 정합. 본 사이클 발견 함정·결정 사유는 본 §21 또는 별도 backlog § 흡수.

### 처리 시점

- 본 architect spec PR-29 머지 후 → PR-29a + PR-29b + PR-29c 동시 디스패치 (시나리오 A)
- PR-29a~c 머지 후 → PR-29d (R3 일괄 테스트) 디스패치
- PR-29d 머지 후 → v3.1.0 태그 + release/v3.1 베이스라인

### 트리거 (다음 사이클)

본 §21 = W1 본격 머지 완료. PR-29d(R3) + v3.1.0 태그 후 ✅ 처리 완료 마킹.

### 예상 처리 PR

- ✅ PR #228 architect spec 머지 (951d09b)
- ✅ PR #229 frontend T-S1 잔재 머지 (15e407b)
- ✅ PR #230 backend-core T-MS1 머지 (4d79299)
- ✅ PR #231 market-data T-CA1 머지 (15795f4)
- ⏳ PR-29d (backend-core, R3 일괄 테스트 — SymbolControllerTest mock + batch test @Disabled 정정)
- ⏳ v3.1.0 태그 + release/v3.1 컷

### 갱신 (2026-05-08 — W1 본격 머지 완료 + V7·V8 위치 검토 close)

**main HEAD: 4eb11d4** — T-MS1 + T-CA1 + T-S1 잔재 본격 코드 풀 머지. worktree 3개·agent/* 3개·worktree-agent-* 3개 정리 완료.

**V7·V8 위치 검토 결과 — spec dispatch 오류, sub-agent 정정이 옳음:**
- spec dispatch §4-5/§6-2: V7·V8을 `batch/src/main/resources/db/migration/`에 LOCK 명시
- 실제 머지(PR #231): V7·V8 모두 `api/src/main/resources/db/migration/`에 위치
- 검증: `batch/src/main/resources/application.yml` line 17~18에 **`spring.flyway.enabled: false`** (ADR 0006 — api 모듈만 Flyway 실행)
- 결론: spec이 batch 모듈로 LOCK한 게 명백한 오류 — 그대로 두면 Flyway 절대 실행 안 됨 → corp_action 테이블·candle ALTER 영구 미적용 risk. market-data sub-agent의 api 모듈 정정 판단은 **운영 정합** + 정정 불필요.
- 교훈 (다음 architect spec/plan): Flyway 위치 결정 시 `application.yml` `flyway.enabled` 모듈별 상태 사전 검증 의무 — design §3-4·§4-5에 모듈 매트릭스 추가.

**KisKsdInfoAdapter 기본 disabled 의제** — KIS 무료 키 endpoint 미호출 가능성. 운영 활성화 정책은 별도 의제(다음 사이클 또는 dogfooding 시점 사용자 결정).

---

## 운영 메모

- **orchestrator 책임**: 사이클 시작 시 본 backlog 점검 의무 — 우선순위 ↑ 항목 first 처리
- **사용자 책임**: dogfooding 결과 → §6 sub-section 추가 (또는 orchestrator에 통보)
- **architect 책임**: ADR 발행 시 본 backlog cross-reference 의무 (예: ADR 0050 보강 시 §1 처리 + 항목 close)
- **항목 close 정책**: 처리 완료 PR 머지 시 backlog에서 제거 OR `### §X. ✅ 처리 완료` 마킹 + 변경 이력 갱신

---

## §23. KIS 자격증명 user_id 식별 규약 불일치 — REAL vs MOCK (W3 T-O 진입 시 본격 정리)

### 사유

`OnboardingService.kt`에서 KIS 키 저장 시 `user_kis_credential.user_id` 컬럼에 들어가는 값이 REAL/MOCK 다름:

| 모드 | line | 저장 값 | 의미 |
|------|------|---------|------|
| REAL | 136 | `request.accountNumber` | 계좌번호 (예: `12345678-01`) |
| MOCK | 231 | `request.userId` | KIS user_id (예: `thdeodns`) |

반면 `KisWebSocketBridge.defaultUserId`는 application.yml에서 KIS user_id 단일 값으로 주입 → REAL 진입 시 lookup fail.

### v3.1.0 hotfix (PR #246)

`KisApprovalAdapter.issueApprovalKey()`에 `firstOrNull()` fallback 추가 — single-user 환경에서는 user_id 매칭 fail해도 첫 credential 사용. 작동 보장.

### W3 T-O (Order) 진입 시 본격 정리 의제

multi-tenant 진입 시 식별 규약 통일 필요. 후보:
1. **REAL/MOCK 모두 user_id 통일** — `request.userId` 또는 user_account.user_id 권위
2. **user_kis_credential.user_id 의미 명시** — DB 컬럼 주석 + ADR 보강
3. **KisWebSocketBridge `defaultUserId` 의미 분기** — REAL 모드면 계좌번호, MOCK 모드면 user_id (현 흐름 정합)
4. **kis_user_id 컬럼 별도 추가** — 가장 명시적, 마이그레이션 비용

### 트리거

- W3 T-O (Order) — 사용자별 주문 권한 격리 시점
- 또는 multi-tenant 사용자 추가 요구 발생 시

### 참조

- PR #246 — v3.1.0 hotfix (firstOrNull fallback)
- `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/OnboardingService.kt:136, 231`
- `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/infrastructure/kis/KisApprovalAdapter.kt:124-130`
- ADR 0046 — Onboarding & Credential Registration (식별 규약 명시 보강 의제)

---

## §24. 종목상세 화면 dogfooding 결함 묶음 (v3.2 일괄 정정) — 결함 8·LG 일봉 일부 처리완료 PR-46

### 트리거

PR-31 (PR #249·#250) 머지 후 사용자 dogfooding 결과: 6+1건 결함 1차 정정 완료. 다음 dogfooding에서 추가 3건 발견. 단일 사이클로 잡기엔 분봉 backfill 잡 재설계가 들어가서 v3.2 W2 트랙과 묶어 일괄 처리.

메모리 `feedback_dev_priority_qa_batch.md` 정합 — "단일 사이클 hotfix·테스트 mock·minor 정정에 매달리지 말 것. backlog 흡수 + 다음 트랙. QA는 여러 사이클 누적 후 연관성 묶어 일괄".

### 결함 7: 호가 polling fallback 부재 (frontend + market-data)

- 현재: STOMP 미연결 시 호가 polling fallback 메커니즘 없음. ADR 0048 polling은 시세에만 적용, 호가에 미적용.
- 수정 영역: frontend(SymbolOrderbook polling) + market-data(KIS REST `inquire-asking-price` 어댑터 신규).
- v3.2 W2 트랙 적합 — KIS REST 신규 어댑터 추가 작업.

### 결함 8 (일봉 fix 완료 PR-46, 분봉 미완): 분봉/15m/30m/90m 데이터 적재 부실 — market-data 영역 ★

**PR-46(2026-05-10) 처리 완료 부분**: DailyCloseFinalizer Redis 타입 불일치(opsForHash vs opsForValue) + Yahoo 1d ts 비표준(KST 00:00:00 정규화). 이제 WS 구독 종목 일봉은 정상 영속됨.
**미완 부분**: 분봉/30m/90m backfill 잡 재설계 — 아래 원문 그대로 잔류.

DB candle 적재가 종목별로 일관되지 않음. PR-31 진단 결과:

| 종목 | 1m | 5m | 30m | 1h | 1d |
|------|-----|-----|------|-----|-----|
| 005930 (삼성) | 2521 | 4239 | 709 | 2895 | 317 |
| 027710 (팜스토리) | **6** | **59** | **59** | 484 | 312 |
| 439090 (마녀공장) | **6** | **59** | **59** | 484 | 152 |

- **사용자 인지 현상**: "1분봉인데 일봉처럼 6개 캔들" / "30분봉이 어제까지 일봉 형태" — 1m 6건이 매일 1건씩, 30m 59건이 일별 1건씩 적재되어 시각적으로 일봉처럼 보임.
- **진짜 원인 추정**: 분봉 backfill 잡이 005930 우선 종목만 적용되어 있거나, 종목별 KIS REST `FHKST03010200` (분봉 backfill) 호출이 일부만 실행. 다른 종목은 일별 1건씩(아마 장 마감 시점 1건 적재 path)만 누적.
- **수정 영역**: market-data 영역 — 분봉 backfill 잡 재설계 + 전 watchlist 종목 적용. KIS REST rate limit 고려해 야간/주말 schedule.
- **참조**: ADR 0019 §2-2 candle 5종 정책 + ADR 0029 candle minute 실시간 정합

### 결함 9: 가격 배지 `getPriceBadge()` 로직 — frontend 영역

- 위치: `frontend/src/lib/marketHours.ts:55`
- 현재 로직:
  ```typescript
  if (isWithin30Seconds(asOf, now)) return 'live'  // ← 결함
  if (source === 'closed') return 'closed'
  if (source === 'polling' && !isKoreanMarketOpen(now)) return 'closed'
  ...
  ```
- **결함**: polling이 5초마다 호출되어 `asOf`가 항상 30초 이내 → 장중/장외 무관하게 'live' 반환. SymbolPriceCard만 'live'(녹) 표시, SymbolOrderbook은 별도 `isKoreanMarketOpen()` 체크로 'closed'(회) 표시 — 두 컴포넌트 표시 불일치.
- **올바른 로직**:
  ```typescript
  if (source === 'live' && isWithin30Seconds(asOf, now)) return 'live'
  if (source === 'closed' || (source === 'polling' && !isKoreanMarketOpen(now))) return 'closed'
  if (source === 'polling' && isKoreanMarketOpen(now)) return 'delayed'
  return 'delayed'
  ```
- **수정 영역**: frontend 단독 — `marketHours.ts` 한 함수 정정 + 단위 테스트 추가.

### 처리 시점

v3.2 W2 트랙 진입 사이클에 묶음 처리. 결함 7+8은 market-data 작업, 결함 9는 frontend 작업.

### 참조

- PR #249 (frontend) · PR #250 (backend-core) — v3.1.0 1차 정정
- `frontend/src/lib/marketHours.ts:50` — getPriceBadge()
- `_workspace/baseline/v3.1.0/` — 1차 정정 시점 snapshot

---

## §25. `@Component` + `@ConditionalOnMissingBean` 함정 회귀 방지 (v3.1.2 hotfix 잔재)

### 사유

v3.1.2 hotfix 사이클(2026-05-10)에서 `@Component` 클래스 레벨에 `@ConditionalOnMissingBean(name=)` 또는 `@ConditionalOnMissingBean(class)`을 함께 붙이면 **자기 자신의 BeanDefinition까지 매칭되어 자기 등록을 자기가 차단**하는 함정 발견. 응급으로 `StrategyEvalMetricRecorder`·`NoopAutoDisableCallback` 2건 어노테이션 제거. 동일 패턴이 1건 더 잠재:

- `consumer/src/main/kotlin/.../infrastructure/session/SimpleMarketSessionResolver.kt:32` — `@ConditionalOnMissingBean(name = ["krxMarketSessionResolver"])` (자기와 다른 이름이라 우연히 통과 중이지만 PR-36b 등에서 동일 이름 경합 시 회귀 가능)

원래 의도는 "다른 모듈에서 같은 타입/이름의 빈이 등록되면 그 빈이 우선되는 fallback" 패턴인데, **`@Component`에 직접 붙이는 방식은 부적합**. Spring 공식 권장은 `@Configuration` + `@Bean` 메서드에 `@ConditionalOnMissingBean`을 붙이는 것.

### 보강 안

1. `SimpleMarketSessionResolver`도 응급 처리 4건과 동일하게 어노테이션 제거 (실제 fallback 필요 시점에 `@Configuration` + `@Bean` 패턴으로 재구성).
2. `@ConditionalOnMissingBean`을 클래스 레벨 `@Component`와 함께 쓰는 패턴 자체를 **금지 패턴**으로 ADR 0051 또는 신규 ADR로 명문화.
3. 가능하면 ktlint custom rule 또는 detekt 룰로 회귀 자동 차단(우선순위 ↓).

### 트리거

- **우선순위 ↑**: PR-36b StrategyService 합류 시 `EvaluatorAutoDisableCallback` 진짜 구현이 들어올 때 함께 정리. fallback 패턴 재정립 cycle에 흡수.
- **우선순위 ↓**: 안 발생하면 v3.2 정리 사이클까지 대기.

### 예상 처리 PR

PR-36b 합류 사이클 또는 별도 architect spec PR (`docs(adr): @Component + @ConditionalOnMissingBean 금지 패턴 + fallback 재정립`).

### 참조

- v3.1.2 hotfix CHANGELOG row (2026-05-10)
- `consumer/.../strategy/StrategyEvalMetricRecorder.kt`·`NoopAutoDisableCallback.kt` (응급 처리됨)
- `consumer/.../infrastructure/session/SimpleMarketSessionResolver.kt:32` (잠재 — 처리 대기)

---

## §26. pattern-engine 모듈 신설 사이클 진행 중 (B-1 결정, v3.1.2 직후)

### 사유

W2 T-PAT PR-39b에서 `JvmPatternRecognizer`(@Component) + 62파일은 consumer 모듈에, `PatternController`는 api 모듈에 분리 배치되어 **api 컨테이너가 빈을 찾지 못해 부팅 실패**. v3.1.2 hotfix에서 PatternController에 `@ConditionalOnProperty(magicjar.pattern.enabled, default=false)` 임시 게이트 적용. 사용자(최고 관리자) 결정으로 **B-1 (`pattern-engine` 라이브러리 모듈 신설) 사이클로 정합 fix 진행**.

domain은 순수 port만 유지(ADR 0004 정책 보존). pattern-engine은 domain과 동일하게 `@SpringBootApplication` 0 / 컨테이너 0 / 라이브러리 jar로만 빌드. api·consumer가 dependency로 끌어다 씀. **컨테이너 수 변동 없음**(api/batch/consumer 3개 그대로).

### 작업 범위

1. `settings.gradle.kts`에 `:pattern-engine` include
2. `pattern-engine/build.gradle.kts` 신설 — kotlin·spring 플러그인 + spring-context·domain 의존
3. `consumer/.../application/pattern/*` 62파일을 `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/*`로 이동
4. 패키지 리네이밍: `kr.spdaewoon.magicjar.consumer.application.pattern.*` → `kr.spdaewoon.magicjar.pattern.*`
5. consumer의 `JvmStrategyEvaluator` 등 import 갱신
6. `api`·`consumer` build.gradle.kts에 `implementation(project(":pattern-engine"))` 추가
7. `PatternController` `@ConditionalOnProperty` 게이트 제거 (자연 정상화)
8. ADR 0004 갱신 — 4모듈 → 5모듈 모듈 경계 다이어그램
9. 신규 ADR 발급 — pattern-engine 모듈 분리 사유 + 도메인 순수성 보존 vs 라이브러리 분리 trade-off

### 트리거

- **즉시 진행** — v3.1.2 hotfix 머지 직후 architect spec PR 디스패치 (orchestrator 사이클 Step 3~5)
- 사용자 결정 받은 사항이라 backlog 등록은 추적 목적, 처리는 즉시.

### 예상 처리 PR

PR-Na (architect spec/plan + ADR 갱신·신규) → PR-Nb (strategy-engine: 62파일 이동 + 패키지 리네이밍) + PR-Nc (backend-core: api·consumer build.gradle.kts 의존 추가 + PatternController 게이트 제거) — 시나리오 A 완전 병렬 가능 (파일 영역 분리).

### 참조

- v3.1.2 hotfix CHANGELOG row (2026-05-10)
- `api/.../controller/PatternController.kt` — 임시 게이트 위치
- `consumer/.../application/pattern/` — 이동 대상 62파일 + 4 디렉토리(detector/{single·triple·pair}, helper, cache, util)
- `docs/architecture/master-architecture.md` §5 — 모듈 경계 (ADR 0004 SoT)

---

## §29. 소켓 데이터 처리 메모리 점검 — bounded growth 5건 (확신 낮음 / 증상 미확정)

### 사유

2026-05-11 사용자 dogfooding 중 macOS "메모리 없음" 시스템 경고 → orchestrator 소켓 처리부 일괄 grep 진단. 5건의 unbounded map / sink 패턴 식별했으나 재계산 결과 **bounded growth + 상한 매우 작음** (모두 KB 단위)로 확인. JVM heap 누수 아니며 OS 메모리 부족은 재부팅으로 회수됨. **확신 낮음 + 실제 영향 미확정** 상태로 백로그 등록.

### 식별된 패턴 (재부팅 후 증상 재현 시 hotfix 검토)

| # | 위치 | 패턴 | 실측 상한 |
|---|------|------|----------|
| 1 | `api/.../MarketTickStompForwarder.kt:26` | `lastEmitMs: ConcurrentHashMap<String, Long>` cleanup 0건 | watchlist 50 종목 × 80 byte = ~4 KB |
| 2 | `api/.../MarketOrderbookStompForwarder.kt:26` | 동일 패턴 (200ms throttle) | 동일 ~4 KB |
| 3 | `consumer/.../OrderbookSubscriptionRouter.kt:36` | `pendingDebounce: MutableMap<String, Long>` expired entry 영구 잔존 | 50 종목 × ~24 byte = ~1.2 KB |
| 4 | `consumer/.../KisWebSocketBridge.kt:199` | `Sinks.many().unicast().onBackpressureBuffer<String>()` size unbounded | 정상 시 ~6 KB. reconnect 폭주 시 risk |
| 5 | `consumer/.../KisWebSocketBridge.kt:347~404` | `handleTickData` 매 tick `entries()` 2회 호출 → 새 HashMap 할당 | 분당 ~3.6 MB young-gen allocation (GC 무난) |

### 트리거 조건

다음 중 하나가 발생하면 본 의제 다시 진단:
- consumer 또는 api 컨테이너 heap 사용량 모니터링 결과 시간에 비례한 누적 패턴 관찰
- `OutOfMemoryError` / `GC overhead limit exceeded` log
- JVM heap dump에서 `lastEmitMs` / `pendingDebounce` map size가 종목 수 × 시간 비례 증가 확인

### 보강 안 (실제 결함 확인 시)

- #1·#2: `LinkedHashMap` accessOrder + 상한 1000 또는 `@Scheduled` 5분 마다 stale entry(lastEmit < now - 1h) 제거
- #3: `pendingDebounce.entries.removeIf { now > it.value }` 진입 시 자동 정리
- #4: `Sinks.many().unicast().onBackpressureBuffer(bufferSize=1024)` 명시 + overflow drop 정책
- #5: KIS-first 정합 유지하면서 Redis Lua script로 `entries() + putAll()` 2 RPC → 1 RPC

### 참조

- 2026-05-11 사용자 메시지: "코드내에 메모리 관련 이슈가 있어 소켓데이터 처리 부분을 처리해야할듯해" → orchestrator 진단 → 사용자 재평가 "백로그로 명확한 확신이 없어"

---

## §30. 차트 캔들 API 초기 로드 시 잘못된 `before` 파라미터 의심 (사용자 dogfooding 2026-05-11)

### 사유

사용자 dogfooding 중 호출:
```
GET /api/symbols/000660/candles?interval=1d&limit=200&before=2009-10-31T15:00:00Z
```
응답 첫 항목 `ts=2000-01-31T15:00:00Z` — 매우 오래된 데이터로 시작. 사용자 "현재 기준으로 가져오고 반대로 해야할듯?" 표현.

### root cause 가설 (재부팅 후 검증 대상)

API 자체 동작은 `findByCodeAndIntervalAndTsBefore` JPQL `WHERE ts < :before ORDER BY ts DESC LIMIT 200` 후 service에서 ASC 변환 — 정상. 즉 `before` 이전 200개의 **최신** candle을 반환하는 의도된 동작.

문제 가능성:
1. **Frontend default `before` 파라미터가 hardcoded 또는 잘못 계산된 옛 ts** — 차트 초기 로드 시 `before` 전달하면 안 되는데 전달 중
2. **차트 페이징 콜백(onVisibleRangeChange) 초기 호출이 차트 leftmost ts를 잘못 계산** — 빈 차트 상태에서 0 또는 epoch start ts 전달
3. **DB에 000660 1d candle이 200개 미만** — `WHERE ts < 2009-10-31` 조건에 해당하는 candle을 전부 반환 (그러면 결함 아니나 frontend가 왜 그런 호출을 했는지가 별개 결함)

### 확인 명령

```sql
-- DB 데이터 양 확인
SELECT count(*) FROM candle WHERE code='000660' AND interval='1d' AND ts < '2009-10-31'::timestamp;
SELECT min(ts), max(ts), count(*) FROM candle WHERE code='000660' AND interval='1d';
```

```javascript
// Frontend 호출 stack 확인 (Network 탭)
// useCandles hook에서 어떤 before 값 전달하는지
```

### 보강 안 (실제 결함 확인 시)

- Frontend 초기 로드는 `before` 파라미터 안 보냄 → API가 `findByCodeAndIntervalLatest`로 최신 200개 반환 (이미 backend는 정상 동작)
- 차트 페이징 시에만 `before=<현재 차트 leftmost ts>` 전달
- 응답 `hasMore=false` 시 더 이상 페이징 요청 안 함

### 참조

- `api/.../controller/SymbolController.kt:113`
- `api/.../service/CandleQueryService.kt:52~153`
- `api/.../persistence/CandleJpaRepository.kt:35~45`
- 2026-05-11 사용자 dogfooding 메시지

---

## §31. 5m·15m·30m·90m·1h 1m 합산 가공 트랙 (ADR 0062 plan 발행 — 사용자 결정 대기)

### 사유

2026-05-11 사용자 결정 — Yahoo Finance 전면 폐기 + KIS OpenAPI 단일 출처 정책(ADR 0057) 채택. ADR 0019 §2-5 "합성 폐기" 결정 중 5m·15m·30m·90m·1h 인터벌 부분은 본 KIS-only 전환으로 **부분 revoke**.

**사용자 명시 결정 (2026-05-11 채팅)**:

> "5m·15m·30m·90m·1h 백엔드 또는 프론트 합산으로 할거고 ADR 폐기해버리면 안될듯 1분봉만 kis에서 제공하니깐 임시로 쓰는거고 나중에 5m·15m·30m·90m·1h 봉에 대한 처리를 보류할뿐임 이거 없다고 생각하면 안되니깐 ADR 폐기는 하지말어 백로그로 남겨"
> "3번 해야지" (= 본 §31 트랙 진행 결정, 2026-05-11)

→ **5m·15m·30m·90m·1h은 1m 합산 가공으로 처리 — 방향 확정**. ADR 0019 보류 + ADR 0057 §2-5에 방향 명시. **2026-05-11 ADR 0062 plan 발행 완료** — 합산 위치·source 시간 범위·IntervalToggle 매트릭스 사용자 결정 대기.

### 진행 상황 (2026-05-11)

- ✅ ADR 0057 §2-5 — 방향 확정(1m 합산 가공) 명시
- ✅ ADR 0062 plan 발행 — 권장안(A-1 백엔드 precomputed + 옵션 2 과거 포함) + fitness gap 분석 + 매트릭스 + sub-agent 디스패치 plan (본 §31 발행 PR)
- ⏳ **사용자 결정 대기** — 본 ADR 0062 §2 결정 의제 (위치·source·UI 매트릭스)
- ⏳ 사용자 결정 후 ADR 0062 `Accepted` 갱신 + sub-agent 병렬 디스패치 (strategy-engine·market-data·frontend·qa)
- ⏳ 트랙 완료 시 본 §31 처리완료 archive

### 결정 사항 (방향 확정)

- 5m·15m·30m·90m·1h = **1m 합산 가공으로 처리** (interval 폐기 0건 · 보조 출처 부활 0건 · KIS-only 정합 유지)

### 잔여 결정 (ADR 0062 plan 발행 — 사용자 결정 대기)

> ADR 0062 plan 본문(`_workspace/arch/adr/0062-aggregation-plan.md`) §2 의제 인용. 권장안: A-1(백엔드 precomputed) + 옵션 2(과거 포함, 30 거래일) + IntervalToggle 5종(5m·15m·30m·90m·1h) 활성화.

**A. 합산 위치**
- **위치 옵션 A: 백엔드 합산** (`CandleAggregator` 컴포넌트 — api 또는 consumer 모듈에 신설)
  - Pro: 모든 클라이언트(SPA·MCP·후속 모바일)가 동일 합산 결과 공유 · 캐시 효과 큼 · 자동매매 룰 엔진·백테스트가 동일 합산 source 참조 가능
  - Con: 백엔드 부하 + DB 또는 cache 추가 영속 검토 필요
- **위치 옵션 B: 프론트 합산** (frontend `candleAggregator.ts` 부활)
  - Pro: 백엔드 변경 0 · 사용자 클라이언트에서 자유롭게 interval 변경 가능 · LWC 차트 단일 source 호환 자연
  - Con: 클라이언트마다 합산 중복 + MCP·후속 모바일에서도 합산 재구현 의무 + 룰 엔진·백테스트(백엔드 본체)는 별도 합산 또는 backend 합산 추가 의무

**B. 합산 source 시간 범위**
- **source 옵션 1: 당일 한정** — KIS REST `FHKST03010200` (1m 당일분, prod credential 단일 채널)
  - Pro: 추가 endpoint 도입 0 · KIS rate limit 영향 최소
  - Con: 5m~1h 장기 차트 불가 · 자동매매 룰 엔진·백테스트의 5m·15m·30m·1h 봉 backtest 본질 불가
- **source 옵션 2: 과거 포함** — KIS REST `FHKST03010230` (1m 과거 데이터, prod credential 단일 채널) 도입 + 1m 장기 영속
  - Pro: 5m~1h 장기 차트 + 자동매매 룰 엔진·백테스트 정합
  - Con: KIS rate limit 영향 큼 (종목당 backfill 호출량 증가, ADR 0037 모니터링 의무) · DB 사이즈 증가 (1m 장기 영속) · raw 출처 `docs/operations/kis-api-reference.md` §5-A 검토 필요

**credential 정책 (방향 확정 — 사용자 명시 정책 정정 2, 2026-05-11)**: 위치·source 옵션 어느 것이든 정보 수집 호출은 **prod credential 단일 채널**. 모의/실전 분기는 주문 라우터(거래 액션)에만 적용. 모의 credential 사용 0건. 본 트랙에서 "모의 키 사용자 vs 실전 키 사용자" 분기는 적용하지 않음.

### fitness gap 분석 (사용자 메모리 `feedback_v3_skepticism_no_regression.md` lens)

- "자동매매에 필요한가?" — 5m·15m·30m·1h 봉은 quant trading 표준 (`_workspace/strategy/` DSL 카탈로그 정합) → **필요**. 1m 합산 가공으로 처리 결정은 자동매매 본체 정합.
- "v1/v2 잔재 아닌가?" — `CandleInterval` 5종 enum은 v1/v2 시점부터 정의이지만 자동매매 본체 룰 엔진 적합성에서 enum 보존 가치 있음. 본 트랙으로 enum 활용도 부활.

### 트리거 조건

- 사용자가 5m~1h 차트 노출 명시 요청 시 위치·source 결정 사이클 진입
- 또는 archive 충돌 발생 (`feedback_dev_priority_qa_batch.md` 정합 — 누적 후 일괄 결정 권장)
- 또는 KIS rate limit 모니터링 결과 측정 후 source 옵션 결정

### 처리 PR

- ✅ **architect — ADR 0062 plan 발행 (본 PR, 2026-05-11)** — 권장안 A-1+옵션 2 + fitness gap + 매트릭스 + sub-agent 디스패치 plan
- ⏳ 사용자 결정 대기 — ADR 0062 §2 의제(위치·source·UI 매트릭스)
- ⏳ 채택 후 sub-agent 병렬 디스패치 매트릭스:
  - **strategy-engine** (또는 backend-core) — `domain/.../market/CandleAggregator.kt` 본체 (Pure Kotlin, Spring 의존 0)
  - **market-data** — `AggregateBackfillRunner` + `TickToMinuteAggregator` boundary 마감 합산 + `DailyCloseFinalizer` 확장 + Redis 키 매트릭스 + STOMP fanout + 일회성 backfill admin endpoint
  - **frontend** — `IntervalToggle.tsx` 15m·90m 추가(5종 활성화) + `useSymbolCandles.ts` 정합 + LWC v4 dogfooding evidence
  - (선택) **qa** — 합산 결과 검증 (5m = 1m 5건 합산 정합)
- 정합 갱신 — ADR 0019 §2 매트릭스 재통합 + ADR 0057 §2-1·§2-2·§2-3·§2-5 갱신 + ADR 0029 §2-1 Redis 키 매트릭스 확장 + master-architecture §10 ADR 인덱스 + candle-data-policy-v2.md 매트릭스 갱신

### 참조

- **방향 확정 권위**: 사용자 채팅 2026-05-11 (인용 위)
- ADR 0057 §2-5 (1m 합산 가공 방향 확정 명시) · ADR 0019 §2 매트릭스 (보류 부분 — 본 트랙 완료 시 재통합)
- `docs/operations/kis-api-reference.md` §5-A (FHKST03010230 후속 검토 — source 옵션 2 후보)
- `_workspace/strategy/` (DSL 카탈로그 — 5m·15m·30m·1h 시그널 표현)
- 사용자 메모리 `feedback_v3_skepticism_no_regression.md` (fitness gap lens) · `feedback_kis_first_policy.md` (KIS-only) · `feedback_kis_key_realonly_for_data.md` (정보 수집 prod credential 단일 채널 — 정책 정정 2) · `feedback_dev_priority_qa_batch.md` (누적 후 일괄)

---

## §32. NXT/통합거래 매트릭스 cover 트랙 (ADR 0063 plan 발행 — 사용자 결정 대기)

### 사유

2026-05-11 사용자(최고 관리자) dogfooding 의제 발의 — 명시 인용:

> "삼성전자나 sk하이닉스 같은경우는 정상장 시간보다 빨리시작하고 늦게까지 장이 열리잖아? NXT 8시작 하고 밤에 끝나는거 그런건 감안 안되있는거같은데?"

→ NXT(Next-Trading Korea Exchange, 2025년 출범) 시간대(08:00~20:00 KST) 분봉·일봉 cover 결함. orchestrator 검증 결과:
- `KisRestCandleAdapter` L170·L380 `fid_cond_mrkt_div_code="J"` (KRX 고정)
- 페이지네이션 시작 `153000` + 종료 조건 `< 09:00 KST` → NXT pre-hour·after-hour 누락
- DB 실측 000660·005930 → 1m count 381건 (KRX 정규장 09:00~15:30만)
- ADR·정책 문서에 NXT 인용 0건

### 방향 (ADR 0063 plan 발행 — Proposed)

**ADR 0063** 신규 발행 (`_workspace/arch/adr/0063-nxt-integrated-trading-coverage.md`):
- 컨텍스트 + 결함 evidence + KIS raw fetch 인용 (`fid_cond_mrkt_div_code = J:KRX / NX:NXT / UN:통합`)
- 결정 의제 3건:
  - **A. 시장 분류 cover**: (A) UN 통합 권장 · (B) NX 분기 + instrument 컬럼 신설 · (C) KRX 단일 유지(현행)
  - **B. 시간 범위 cover**: (1) 08:00~20:00 명시 권장 · (2) KIS 응답에 맡김
  - **C. threshold 갱신**: 350 → 700 권장 · 350 유지
- fitness gap 분석 (자동매매 필요·v1/v2 잔재 아님·AI 매매 결정 0·KIS-first 정합·prod credential 단일)
- 운영 영향 (KIS 호출 +33%·Redis +5MB·cold-start +33%·ADR 0037 한도 안전)
- rollback (single-line revert)
- 영향 ADR/문서 매트릭스 (채택 후 별도 사이클 갱신 — modify-shared 충돌 회피)

### architect 권장안 (사용자 검토 대기)

**옵션 A + 1 + 700 (UN 통합 + 08:00~20:00 명시 + threshold 700)**

사유:
- 자동매매 fitness — NXT 거래 종목 시그널·룰엔진·백테스트 필수 cover
- 단순함 — 종목별 시장 분기·instrument 마이그레이션 회피
- KIS-first 정합 — KIS 공식 통합 모드 활용
- Rate Limit 한도 안전 (+33% 영향 측정 후 운영)

### 결정 후 트리거

ADR 0063 사용자 Accepted 갱신 후:

1. **market-data sub-agent**: `KisRestCandleAdapter` 4 line 변경 (`fid_cond_mrkt_div_code` UN + `currentHour="200000"` + 종료 조건 `< 08:00 KST` + `MIN_1M_CANDLE_THRESHOLD=700`) + 재수집 + DB·Redis evidence 보고 + CHANGELOG slot
2. **architect sub-agent**: ADR 0019 §2-1·§2-2 매트릭스 NXT 행 보강 + ADR 0028 §X cold-start 흐름 + `external-api-policy.md` §2-5 NXT/통합 매트릭스 추가 + `candle-data-policy-v2.md` 시각 매트릭스 갱신 + ADR 0063 status `Accepted` 갱신
3. **frontend sub-agent** (선택): `IntervalToggle` NXT 시각 표기 + LWC X축 범위 점검 (현재 9:00~15:30 가정 UI)
4. **qa sub-agent** (선택): NXT 시각 분봉 sample 검증 + 시그널 엔진 NXT 시간대 backtest 검증

### 트리거 조건

- 사용자 ADR 0063 결정 회신 (의제 A·B·C 옵션 채택)
- 또는 NXT 거래 종목 본격 운영 시점 도래

### 처리 PR

- **본 사이클**: PR-50 (예상) architect ADR 0063 plan 발행 (본 §32 + ADR 0063 + README + master + CHANGELOG)
- **후속 사이클** (결정 후): market-data + architect + (선택) frontend·qa 병렬 디스패치 — 단, PR #298(ADR 0062 합산 트랙)와의 modify-shared 회피 위해 PR #298 머지 후 진입

### 병렬 작업 영역 분리

- **PR #298 (ADR 0062 합산 트랙, OPEN, 사용자 결정 대기)** — 영역 충돌 0
  - 0062 = 합산 가공 (5m·15m·30m·90m·1h 위치·source 결정)
  - 0063 = fetch 매트릭스 (NXT/통합 시장 분류·시간 cover)
  - 두 ADR 모두 ADR 0019 §2 매트릭스 본문 갱신을 의제로 보유 → **본 NXT plan은 ADR 0019 본문 갱신 금지** (채택 후 별도 사이클로 분리). modify-shared 회피
- 본 PR은 **add-only 신규 파일** (ADR 0063 + backlog §32) + README 인덱스 + master §10 row + CHANGELOG row 만

### 참조

- **권위**: 사용자 dogfooding 의제 2026-05-11 (인용 위)
- ADR 0063 (본 트랙 plan 본문) · ADR 0019 (채택 후 §2 매트릭스 NXT 행 보강) · ADR 0028 (cold-start 흐름) · ADR 0037 (Rate Limit 영향 측정) · ADR 0057 (KIS-only 호출도 NXT 동일)
- KIS raw fetch — `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py`
- 사용자 메모리 `feedback_kis_first_policy.md`·`feedback_kis_key_realonly_for_data.md`·`feedback_v3_skepticism_no_regression.md`·`feedback_spec_plan_scope.md`·`feedback_3tier_hierarchy.md`·`feedback_orchestrator_active_parallel.md`

---

## §33. KIS API 500 retry 강화 (1회 → 3회 exponential backoff) — v3.3.0 미러스톤

### 사유

사용자 dogfooding 2026-05-11 — PR #297 backoff(500ms 1회 retry) 후에도 일부 종목(004090·064260·001040·035760)에서 KIS API 500 누적으로 `KIS_COLD_START_PARTIAL_FAIL` 패턴 발생. 외부 KIS 결함이지만 retry 강도 보강 시 종목별 fail 비율 감소 기대.

### 결정 사항 (architect plan 발행 후 사용자 결정 대기)

- 옵션 A: 3회 exponential backoff (500ms → 1s → 2s)
- 옵션 B: HTTP status code별 분기 (500 vs 503 vs 502 vs timeout 차등)
- 옵션 C: Dead Letter Queue 패턴 (실패 일자 별도 큐 + 백그라운드 재시도)

### 우선순위

v3.3.0 미러스톤. v3.2.x 라인은 backoff 1회 유지 (안정성 검증 후 격상).

---

## §34. Redis boundary fanout + STOMP 실시간 합산 갱신 (ADR 0029) — v3.3.0 미러스톤

### 사유

ADR 0062 §9 합산 가공이 cold-start + DailyCloseFinalizer(20:00 KST)에서만 갱신. 장중 5m·15m·30m·90m·1h boundary 마감(예: 09:05·09:10) 즉시 갱신이 안 됨 → 사용자 차트가 stale 5m bar 표시.

### 결정 사항

- Redis key 매트릭스 — `candle:current:{code}:5m` 등 boundary별 캐시
- boundary 마감 트리거 — KIS WS 체결 수신 시 boundary 도달 검사 + Redis 갱신
- STOMP fanout — frontend 실시간 차트 갱신
- DailyCloseFinalizer는 마감 후 DB flush 의무

### 진행 상태 (2026-05-12 갱신)

- **ADR 0064 plan 발행 (Proposed)** — `_workspace/arch/adr/0064-realtime-aggregation-fanout.md` (architect 작성, 2026-05-12)
- 결정 의제 3건 (사용자 검토 위임):
  - 의제 A — Redis 키 매트릭스: A-1 5종 별도 hash 키 (architect 권장) vs A-2 통합 hash 단일 키
  - 의제 B — boundary 마감 트리거: B-1 KIS WS H0STCNT0 in-process 검사 (architect 권장) vs B-2 별도 scheduled vs B-3 하이브리드
  - 의제 C — STOMP fanout 토픽: C-1 5종 별도 토픽 (architect 권장) vs C-2 단일 + payload interval field
- **architect 종합 권장**: A-1 + B-1 + C-1 (latency 최소·운영 visibility·ADR 0029 §2-1·§2-5 baseline 정합)
- 사용자 결정 후 sub-agent 디스패치 매트릭스 (ADR 0064 §7-1):
  - **market-data** — BoundaryDetector domain + BoundaryAggregator consumer + KisWebSocketBridge 확장 + RedisCandleCache 5종 + Kafka 토픽 신규
  - **frontend** — useSymbolCandles STOMP 5종 subscribe + LWC v4 차트 update + dogfooding evidence

### 우선순위

v3.3.0 미러스톤. 실시간 매매(W3+) 본격 시점에 필수. **사용자 결정 대기 (의제 A·B·C 3건)**.

---

## §35. architect ADR 0019·0028·0029 §2 매트릭스 NXT/합산 행 보강 — v3.2.3 minor

### 사유

ADR 0062·0063 채택 시 본문 §9·§2-E에서 영향 ADR 매트릭스 갱신을 별도 사이클로 분리 명시(modify-shared 충돌 회피). 본 §35가 그 후속 사이클.

### 결정 사항 (architect 단독 PR)

- ADR 0019 §2-1 매트릭스 — NXT 시각 행 + 합산 5종 행 추가
- ADR 0019 §2-2 — UI interval 매트릭스 갱신 (5종 활성화)
- ADR 0028 cold-start 흐름 — KOSDAQ 분기 + halt skip 명시
- ADR 0029 boundary 마감 트리거 시각 — 20:00 KST 확장
- `docs/architecture/external-api-policy.md` — KIS UN/J/NX 매트릭스
- `docs/architecture/candle-data-policy-v2.md` — 시각·합산 매트릭스 갱신

### 우선순위

v3.2.3 minor doc cut. .md만 변경, 코드 영향 0.


---

## §36. 운영 부채 정리 — GitHub Actions 결제 (A DONE) + local branch 잔재 — operational

**2026-05-12 A 처리 완료**: 사용자 결정 "GHA 결제 안 함" → release workflow 폐기(ADR 0049 Superseded) + 메모리 `feedback_no_github_actions.md` 정합. B(local branch 잔재)는 cosmetic으로 유지 — 다음 사이클 사용자 명시 권한 위임 시 force 삭제.


### 사유

**A. GitHub Actions release CI failure 누적** — v3.2.2·v3.2.3·v3.3.0 모두 release tag push 후 CI 실행 실패. 원인 = GitHub 계정 결제 결함 ("recent account payments have failed or your spending limit needs to be increased"). 본 시스템 코드 결함 0. ghcr.io 이미지 + Release draft만 자동 생성 안 됨. **로컬 docker compose 운영은 정상**.

**B. local agent/* branch 잔재** — `gh pr merge --rebase --delete-branch`로 origin 정합 정리되지만 local branch는 not-fully-merged 상태(rebase 머지 후 sha 분기)로 `-d` 안전 모드 거부. `-D` force 거부 패턴 (orchestrator destructive 권한).

### 결정 사항 (사용자 액션 필요)

**A 처리**:
- GitHub Settings → Billing & plans → 결제 정보 갱신
- 또는 GHA spending limit 상향
- 또는 alternative CI 도입 검토 (Cloud Build 등)

**B 처리**:
- 사용자 직접 `git branch -D <agent/*>` 일괄 실행
- 또는 orchestrator 명시 권한 위임 시 자동 처리

### 우선순위

- A: 사용자 결정 — 빠를수록 좋음(release 자동화 영향)
- B: cosmetic — 차후 일괄 정리 OK


---

## §39. T-BT 모델 확장 매트릭스 우선순위 등록 (PR-50 plan — 본 사이클 진행 0, 추후 사이클 발동 의제)

### 컨텍스트

ADR 0056 §3-7은 후속 ADR 0080+ 5건 예약. 본 § 신규 등록으로 우선순위 매트릭스만 정리 — **본 사이클 진행 0**. 사용자 실제 백테스트 사용 후 "현실 차이 발생" 확인 시점에 별도 사이클 발동.

architect 권장 (PR-50 §2 의제 E-1): **본 사이클 모델 확장 0건**. 사유:
- ADR 0056 default 모델 3종(KoreanStockTaxModel·KisDirectCommissionModel·FixedRateSlippageModel)으로 1차 사용자 흐름 충분
- 가설 우선 구현 회피 — 사용자 실제 검증 후 발동

### 우선순위 매트릭스 (사용자 실제 사용 후 재검토)

| ADR 후보 | 확장 | 발동 트리거 | 사이즈 |
|---|---|---|---|
| 0081+ | 거래량 기반 동적 슬리피지 | "큰 매수 시 슬리피지 큼" 확인 + KIS 거래량 데이터 가용 | M |
| 0082+ | 대주주 양도세 분기 | 사용자 보유 누적 ≥ 시총 50억 또는 지분 1% 도달 | L |
| 0083+ | 자동 batch job (매주 활성 strategy 시뮬 + 리포트) | 활성 strategy ≥ 5건 + 사용자 weekly 리뷰 욕구 확인 | M |
| 0084+ | backtest-eval-pool 분리 (strategy-eval-pool과 별도) | 동시 백테스트 ≥ 5건 도달 | S |
| 0085+ | Monte Carlo simulation | 사용자 통계 신뢰도 요건 발생 | L |

### 결정 사항

**본 § 신규 등록 = 우선순위 매트릭스만 보존**. 채택 시점은 사용자 실제 사용 후 발동.

### 우선순위

**3순위** — §37·§38 R2/R3 머지 + 사용자 dogfooding 누적 후 재검토.

### 연관

- ADR 0056 §3-7 후속 0080+ 5건 예약
- PR-50 plan §2 의제 E-1·§11-3
- 트랙 ID T-BT 후속 (별도 트랙 ID 부여 검토 — `T-BT-EXT` 또는 본 § 재발동)

---

## §40. T-SIG 실시간 시그널 엔진 (W3 두 번째 트랙 — ADR 0066 plan 발행, 사용자 결정 대기)

### 컨텍스트

v3.3.1 cut (T-BT W3 트랙 마감) 직후 진입. T-SIG는 자동매매 본체의 매매 결정 노드 — magicJar 비전(`project_system_vision.md`) 룰 기반 quant trading 본체의 W3 핵심.

사전 자산 (이미 main, v3.3.1):
- ADR 0064 boundary fanout — Kafka `market.candle.{interval}.closed.v1` 5종 baseline (시그널 트리거 진입점)
- ADR 0047 DSL Sandbox — strategy-eval-pool · timeout 100ms · auto-disable baseline
- ADR 0023 Signal Engine = 룰 매칭 only (AI 호출 0) 권위
- T-DSL·T-IND·T-PAT (W2 완료) + T-BT (v3.3.1 마감)

architect spec/plan PR(본 사이클) — ADR 0066 발행 + 결정 의제 6건 사용자 검토 위임.

### 결정 의제 (architect 권장안 — ADR 0066 §2)

| 의제 | 권장안 | 핵심 사유 |
|---|---|---|
| **A. 트리거 시점** | **A-1** Kafka boundary listen 5 listener | ADR 0064 baseline · latency <50ms · partition by code race 0 |
| **B. 룰 source** | **B-1** strategy 영속 DB | ADR 0047 §2-6 baseline · 사용자 UX 필수 · Redis cache 완화 |
| **C. 시그널 영속** | **C-1** 신규 signal 테이블 (Flyway V15) | T-J·MCP·dogfooding 필수 · ADR 0061 패턴 정합 |
| **D. 사용자 알림** | **D-1** STOMP fanout 1차 | MVP 단순 · ADR 0064 STOMP 인프라 100% 활용 · D-2 옵션 후속 |
| **E. 중복 시그널** | **E-1** DB UQ + Redis SETNX 30s 보조 | deterministic boundary ts · race 0 · DB level 강제 |
| **F. 비활성 룰** | **F-1** status enum 4단 (ACTIVE·PAUSED·DISABLED·DELETED) | ADR 0047 V26 baseline · transition 명확 |

### 채택 시 sub-agent 디스패치 (직렬 권장)

- **Phase 1 — strategy-engine** (R1): domain Signal·Strategy 확장 + RealtimeSignalGenerator + BoundaryClosingKafkaListener 5건 + JvmStrategyEvaluator sandbox 통합 + SignalDedupeGuard + SignalKafkaProducer
- **Phase 2 — backend-core** (R2): REST 8 endpoint(Strategy 6 + Signal 2) + JpaAdapter + Flyway V14·V15·V16 + RedisStrategyCache + StompSignalDispatcher
- **Phase 3 — frontend** (R3): `/strategy/list` 페이지 + StrategyCard · SignalListPanel · useStompSignal 토스트 + REST hook + routing 확장

병렬 가능 case: Phase 1·2 add-only 충돌 0 시 architect 검토 후 결정.

### 운영 영향

- DB ROW: signal 1년 ~365K row (5MB) — partition 불필요. 5명 사용자 + 5년 도달(~9M) 시 monthly partition 후속 ADR
- Kafka: `signal.generated.v1` 신규 1 topic ~1,000 msg/day (0.012 msg/s)
- STOMP: `/topic/signal.user.{userId}` ~1,000 msg/day (사용자 단위)
- Redis: strategy active cache ~750KB + dedupe ~1MB peak (30s window)
- KIS API 영향 0 (Kafka 내부 listen만)
- strategy-eval-pool burst 평균 wall-clock ~750ms (5m boundary 시점, 4 thread 분산)

### 우선순위

**1순위** — v3.4.0 미러스톤 후보 트랙. 사용자 결정 6건 채택 후 즉시 Phase 1 strategy-engine 디스패치.

### 트리거

- 사용자 결정 6건 명시 수신 (의제 A·B·C·D·E·F)
- 채택 후 Phase 1 → 2 → 3 직렬 디스패치 또는 architect 충돌 행렬 검토 후 부분 병렬화

### 연관

- ADR 0066 (T-SIG 실시간 시그널 엔진 plan — 본 § 권위)
- ADR 0023 (Signal Engine 룰 매칭 only — baseline)
- ADR 0047 (DSL Sandbox — strategy-eval-pool 통합)
- ADR 0064 (boundary fanout — Kafka listen 진입점)
- ADR 0022·0021 (DSL·Indicator Source)
- ADR 0061 (Backtest 영속 — REST 패턴 baseline)
- ADR 0044 (Notification — risk.violation.v1 payload 확장)
- tracks-roadmap §2 T-SIG row (본 § 권위 인용)
- master §10 ADR 0066 row
- 사용자 메모리 `project_system_vision.md`(룰 본체) · `feedback_v3_skepticism_no_regression.md`(fitness gap lens) · `feedback_3tier_hierarchy.md`(architect plan → sub-agent 병렬)

---

## §41. T-RISK 리스크 관리 (W3 세 번째 트랙 — ADR 0067 plan 발행, 사용자 결정 대기)

### 컨텍스트

v3.4.0 cut (T-SIG W3 두 번째 트랙 마감) 직후 진입. T-RISK는 자동매매 본체의 **자본 보호 안전망 노드** — T-SIG 시그널이 무방비로 T-O 주문에 진입하면 사용자 자본 손실 risk 직접 노출.

사전 자산 (이미 main, v3.4.0):
- ADR 0066 T-SIG 시그널 엔진 + `signal.generated.v1` Kafka topic + signal 테이블 영속
- ADR 0061 T-BT 백테스트 영속 (3 테이블 + REST 5 endpoint 패턴 baseline)
- ADR 0024 Risk Guard chain 6단 baseline 골격
- ADR 0042 Position Bootstrap (부팅 시 KIS 잔고 워밍 baseline)
- ADR 0043 KillSwitch 행동 정의
- ADR 0044 Notification System (`risk.violation.v1` payload 확장 baseline)
- Strategy 영속 (strategy 테이블, status 4단 + Redis cache)
- Kafka 백본 + STOMP 인프라

architect spec/plan PR(본 사이클) — ADR 0067 발행 + 결정 의제 8건 사용자 검토 위임.

### 결정 의제 (architect 권장안 — ADR 0067 §2)

| 의제 | 권장안 | 핵심 사유 |
|---|---|---|
| **A. 포지션 한도** | **A-1** 절대 + % 둘 다 min() 적용 | 자본 변동 대응 + 신규 사용자 안전 + 보수적 |
| **B. 일일 손실 한도** | **B-1** 전역 + 사용자 택1(절대 OR %) + 자정 reset | 자본 일일 보호 본질 + UX 단순 + safe default |
| **C. 동시 종목 한도** | **C-1** 사용자 설정 + default 5 | 자본 분산 + UI 슬라이더 + safe default |
| **D. 거래 빈도 한도** | **D-1** strategy당 분당 + 일별 dual + Redis sliding window | strategy 격리 + burst·누적 cover · O(1) |
| **E. 게이트 위치** | **E-1** Kafka `signal.generated.v1` consumer (Signal → Risk → Order intent) | strategy 패턴 일관 + audit trail + T-O decoupled |
| **F. 위반 처리** | **F-1** 4단(signal.risk_status + risk_event + STOMP + auto-PAUSE 10회) | audit + 사용자 인지 + 폭주 격리 |
| **G. 영속 매트릭스** | **G-1** 4 테이블/컬럼 dedicated (risk_config + risk_event + signal.risk_status + position) | 도메인 분리 + ADR 0061 baseline 일관 |
| **H. 설정 source** | **H-1** 사용자별 risk_config + 전역 default seed | multi-tenant + safe default + 신규 사용자 안전 |

### 채택 시 sub-agent 디스패치 (직렬 권장)

- **Phase 1 — strategy-engine** (R1): domain RiskConfig·RiskEvent·Position·RiskGatewayResult sealed + 6 port + RiskGatewayEvaluator + TradeFrequencyRedisGuard + DailyPnlCalculator + CapitalSnapshotProvider + AutoPauseGuard + SignalGeneratedKafkaListener + RiskViolationKafkaProducer + OrderIntentKafkaProducer + 단위 테스트 6 룰
- **Phase 2 — backend-core** (R2): REST 7 endpoint(RiskConfig 3 + RiskEvent 2 + Position 2) + JpaAdapter 3쌍 + signal.risk_status 컬럼 추가 + Flyway V16·V17·V18·V19 + RiskConfigService + StompRiskNotifier
- **Phase 3 — frontend** (R3): `/risk-config` · `/risk-events` · `/positions` 3 페이지 + RiskConfigForm · RiskEventBadge · PositionCard · PnlBadge + useStompRisk 토스트(BLOCKED + AUTO_PAUSED) + REST hook 3건 + routing + AppHeader 3 메뉴

병렬 가능 case: Phase 1·2 add-only 충돌 0 시 architect 검토 후 결정 (단 signal entity 컬럼 추가는 Phase 2 단독).

### 운영 영향

- DB ROW: risk_event 1년 ~70K row (BLOCKED only, 5MB) · risk_config 사용자 수 · position 사용자당 600/year. partition 불필요
- Kafka: `risk.violation.v1` ~200 msg/day(0.0023 msg/s) + `order.intent.v1` ~800 msg/day(0.0093 msg/s)
- STOMP: `/topic/risk.user.{userId}` ~200 msg/day(0.0023 msg/s)
- Redis: <650KB (risk_config cache + 2 sliding window ZSET + daily_pnl cache)
- KIS API 영향 0 (Kafka 내부 listen만)
- end-to-end latency: signal.generated → risk verdict → order.intent <75ms (signal pipeline 100ms 포함 시 <175ms)

### 우선순위

**1순위** — v3.5.0 미러스톤 후보 트랙. 사용자 결정 8건 채택 후 즉시 Phase 1 strategy-engine 디스패치.

### 트리거

- 사용자 결정 8건 명시 수신 (의제 A·B·C·D·E·F·G·H)
- 채택 후 Phase 1 → 2 → 3 직렬 디스패치 또는 architect 충돌 행렬 검토 후 부분 병렬화

### 연관

- ADR 0067 (T-RISK 리스크 관리 plan — 본 § 권위)
- ADR 0024 (Risk Guard chain 6단 — baseline 골격)
- ADR 0066 (T-SIG Signal Engine — `signal.generated.v1` 진입점)
- ADR 0042 (Position Bootstrap — Position 도메인 합류)
- ADR 0043 (KillSwitch 행동 — RISK_RULE.KILL_SWITCH_ON 정합)
- ADR 0044 (Notification — `risk.violation.v1` payload 확장)
- ADR 0061 (Backtest 영속 — 4 테이블 분리 패턴 baseline)
- ADR 0023 (Signal Engine 룰 매칭 only — AI 호출 0 정책 계승)
- tracks-roadmap §2 T-RISK row (본 § 권위 인용)
- master §10 ADR 0067 row
- 사용자 메모리 `project_system_vision.md`(룰 본체) · `feedback_v3_skepticism_no_regression.md`(fitness gap lens) · `feedback_3tier_hierarchy.md`(architect plan → sub-agent 병렬)

---

## §42. README 굴직 비전 트랙 (프로젝트의 꽃 — PR-52 plan 발행, 사용자 결정 대기)

### 컨텍스트

사용자 명시 의제 2026-05-12 인용:
> "프로젝트 README.md 에 엄청 간결하고 명확하고 깔끔하게 모든 내용이 들어갈 수 있게끔 되어야해 굴찍하게!!! 우리 깃헙 들어와서 readme만 보고 아 이거 이걸로 만들었고 이렇게 되는 프로그램이구나 에이전트는 어떻게 진행되었고 md 관리는 어떻구나까지 딱 한번에 눈에 들일 수 있도록. 이게 우리 완성된 프로젝트의 꽃이여야해"

현재 `README.md` 117 lines — v3.0.0 시점 기초 정보만, v3.4.0 진화(ADR 0066 T-SIG 가동 본체 등) 반영 0. GitHub 방문자가 README 한 번 정독에 5건 핵심을 즉시 인지해야:
1. 이걸로 만들었다 (기술 스택)
2. 이렇게 되는 프로그램 (E2E 흐름)
3. 에이전트 진행 (7 sub-agent + orchestrator + 헌법)
4. md 관리 (ADR 63건 + baseline 5세대 + devlog + memory)
5. 굴직·간결·명확·깔끔 (한 번에 눈에)

architect spec PR(본 사이클 PR-52) — 결정 의제 5건 + Mermaid 3건 outline + README 본문 sketch 발행, 사용자 결정 대기.

### 결정 의제 (architect 권장안 — PR-52 §2)

| 의제 | 권장안 | 핵심 사유 |
|---|---|---|
| **A. README 구조** | **A-1** 굴직 Hero + 5 section (Hero · Quick Start · Architecture · Agents · Documentation) | 사용자 비전 "한 번에 눈에 들어와야" 직접 충족 |
| **B. 비주얼 자원** | **B-2** Mermaid 다이어그램 3건 embed (GitHub 자동 render) | 외부 이미지 의존 0 + git diff 친화 |
| **C. 가이드 진입 경로** | **C-3** master-architecture + baseline html 둘 다 명시 | SoT 정합 + 진화 추적 동시 |
| **D. 에이전트 시스템 표기** | **D-3** 7 sub-agent 표 + orchestrator 일대기 + 헌법 7§ + ADR 63건 | 사용자 비전 "에이전트는 어떻게 진행되었고" 직접 충족 |
| **E. md 관리 표기** | **E-1** ADR/baseline/devlog/memory 4 매트릭스 표 | 사용자 비전 "md 관리는 어떻구나" 직접 충족 |

### 채택 시 sub-agent 디스패치 (1 Phase)

- **Phase 1 — architect** (단독 PR): README 본문 작성 (5 섹션 ~350~400 lines) + Mermaid 3건 + 4 매트릭스 표 + 운영 헌법 7§ 인용 + ADR 63건 인용
- Phase 2 옵션 (frontend): 사용자가 B-3 채택 시 screenshot.gif·banner.png 비주얼 자원 추가 (현 권장 B-2는 Mermaid only → Phase 2 불필요)

### 운영 영향

- `README.md` 117 → ~350~400 lines (굴직, readable)
- Mermaid embed 3건 (~70 lines)
- 코드 변경 0건 (.md only)
- 도구 영향 0 (CI/CD·husky·gradle 무관)
- badges 포함: version (v3.4.0) · Kotlin 2.0 · Spring Boot 3.3 · License
- badges 제외: GitHub Actions build (ADR 0049 Superseded GHA 폐기) · code coverage (qa on-demand)

### 우선순위

**1순위** — META 트랙. 자동매매 본체 fitness 직접 충족(GitHub first-impression). 사용자 결정 5건 채택 후 즉시 architect Phase 1 디스패치 (1 PR로 마감 가능).

### 트리거

- 사용자 결정 5건 명시 수신 (의제 A·B·C·D·E)
- 채택 후 architect 단독 README 본문 재작성 PR
- 사용자가 "다른 방향" 결정 시 본 § 보강 또는 폐기

### 연관

- 본 PR-52 plan (`_workspace/specs/pr-52-readme-flagship-vision-plan.md`) — 본 § 권위
- ADR 0001 (시스템 비전 — 룰 본체 + AI 옵션 + MCP 서버 미래 3 비전 README 명시 의무)
- ADR 0030 (SoT 마스터 아키텍처 — README 인용 정합)
- ADR 0036 (3단 hierarchy — README agent system 표기 권위)
- ADR 0051 (Repository 컨벤션 — 헌법 §6 README 인용)
- ADR 0066 (T-SIG 가동 본체 — README hero v3.4.0 미러스톤 표기)
- ADR 0049 Superseded (GHA 폐기 — badges 제외 권위)
- 신규 ADR 0068 검토 (옵션 — 사용자 채택 시)
- 사용자 메모리 `feedback_spec_plan_scope.md`(README 단일 파일 → spec만 발행) · `feedback_v3_skepticism_no_regression.md`(fitness gap lens) · `project_system_vision.md`(3 비전 표기 의무) · `feedback_devlog_html.md`(devlog 매트릭스) · `feedback_3tier_hierarchy.md`(3단 hierarchy Mermaid)



---

## §44. SettingsModal 후속 — KIS 키 변경 활성화 + 마스킹된 현재 키 표시 + active session 처리

> **★ 상태 (2026-05-14 v3.6.5 patch 재발행)**: **ADR 0074 1차 흡수 완료 — 풀세트 3건** (PUT/GET/DELETE 활성화 + 마스킹 표시 + active session invalidate 5초 잠금 + WS 재연결 graceful). spec 본문 `_workspace/specs/pr-N-settings-kis-change-killswitch-ai-spec.md` (PR 번호 N은 본 사이클 PR 생성 후 확정). **재발행 사유**: 직전 architect dispatch 시 ADR 번호 충돌 — T-NEWS(ADR 0073 점유)와 본 사이클 둘 다 ADR 0073 발행 → PR #415 close → 본 ADR 0074로 재발행 + 결정 내용 변동 0(번호만 갱신).

### 사유

PR #389 SettingsModal 1차 구현은 ADR 0069 §3 1차 scope LOCK 정합으로 KIS 키 변경 submit을 **Option A (frontend stub/disabled)** 채택했음. 후속 PR에서 다음 3건 풀세트로 활성화 의제:

1. **PUT endpoint 활성화** — 현재 SetupWizard `POST /api/onboarding/kis/{accountType}`는 첫 셋업 전용. 변경은 별도 endpoint `PUT /api/onboarding/kis/{accountType}` 분리 + AES 재암호화 + user_kis_credential 갱신
2. **마스킹된 현재 키 표시** — 사용자가 "변경" 클릭 시 현재 등록된 키가 마스킹 형식으로 표시(예: `PSxx****xxx9`)되어 어떤 키인지 식별 후 변경 가능. 평문 절대 응답 금지. `MaskedKisCredentialResponseDto` 신규
3. **active session invalidate** — KIS 키 변경 시 KisAuthAdapter Redis cache(`kis:approval:{userId}`·`kis:token:{accountType}:{userId}`) 즉시 invalidate + WS 재연결 트리거 + 5초 잠금(double-submit 방지)

### 트리거

- v3.6.x patch 사이클에서 우선순위 보고
- 또는 사용자가 dogfooding 중 "키 변경 못 함" 보고 시 즉시

### 보강 안 (예상 PR 매트릭스)

- **PR-A backend-core**: `PUT /api/onboarding/kis/{accountType}` + `MaskedKisCredentialResponseDto` + `GET /api/onboarding/kis/credentials` (마스킹 응답) + KisAuthAdapter Redis invalidate 메서드 + 5초 잠금 + Swagger 4단
- **PR-B frontend**: `KisCredentialSection` submit 활성화 + GET 마스킹 응답 표시 + 변경 후 토스트 + WS 재연결 안내

### 연관

- ADR 0069 §3 (1차 PR scope LOCK — 후속 의제 §I 명시)
- ADR 0046 (Onboarding Setup + AES 암호화 baseline)
- PR #389 (SettingsModal 1차 구현 — KIS 키 변경 submit disabled)
- 사용자 메모리 `project_kis_key_management.md`(운영 중 ⚙️ 입력)·`feedback_kis_key_realonly_for_data.md`(REAL 키 단일)·`feedback_secret_handling.md`(키 평문 응답 금지)

---

## §45. SettingsModal future API 키 카테고리 — OpenAI·Anthropic·Naver·KillSwitch·account_prdt_cd

> **★ 상태 (2026-05-14 v3.6.5 patch 재발행)**: **ADR 0074 부분 흡수** — §G KillSwitchSection 1차 흡수(SettingsModal Section 4 신설 + GET·PUT + ConfirmDangerToggle + 60초 cooldown + killswitch.engaged.v1 schema 확장 engaged:boolean) + §E OpenAI/Anthropic 키 카테고리 사전 정합(env 유지 + Section 5 UI 0건 1차, T-AI 사이클 실구현 분리). 잔류 4건: §F Naver 뉴스 API 키(T-NEWS 트랙 진입 시), §H account_prdt_cd 변경(사용자 선물옵션 필요 시), §I display_name 변경(multi-user 진입 시), §J `/settings` route 보존(모바일 진입 시 모달 vs 페이지 재논의). **재발행 사유**: 직전 architect dispatch 시 ADR 번호 충돌 → 본 ADR 0074로 재발행.

### 사유

ADR 0069 §3 1차 scope는 KIS 키 + System Config(DART·Slack) 2건. ADR §7 backlog 의제 10건 중 미래 확장 카테고리 의제:

- **§E OpenAI / Anthropic 키** — T-AI 진입 시 LLM provider 키 (env에서 옮길지 검토)
- **§F Naver 뉴스 API 키** — 뉴스 감성 분석 트랙 진입 시
- **§G KillSwitch 토글** — T-RECON·T-NOTIFY 마감 후 사용자 수동 KillSwitch UX
- **§H account_prdt_cd 변경** — 종합매매 vs CMA 등 계좌상품코드 변경 UX
- **§I display_name 변경** — user_account.display_name UX
- **§J `/settings` route 보존** — 모달 vs 페이지 분기 검토 (모바일 진입 시점)

### 트리거

- 각 카테고리 의제 진입 시점 (예: §E는 T-AI 진입 시, §G는 T-RECON·T-NOTIFY 마감 후)

### 연관

- ADR 0069 §3 + §7 backlog (10건 매트릭스)

---

## §46. Risk 페이지 reconcile timeline UI + KillSwitch 토글 UX 보강 (T-RECON Phase 2 마감 후 frontend 단발 디스패치)

### 사유

ADR 0070 T-RECON + T-NOTIFY 묶음 spec은 frontend 0 LOCK 채택. 자본 보호 핵심 흐름(drift → KillSwitch ON → Slack P0 → STOMP)은 backend 단독 완성 가능 + SettingsModal·KillSwitch baseline reuse로 1차 사이클 cost ↓. 그러나 운영 편의 보강 의제로 다음 3건 frontend 단발 디스패치:

1. **Risk 페이지 reconcile_run timeline** — `/risk` 페이지에 reconcile_run 시계열 표 + drift_count 그래프 + 마지막 cycle 시각 + 다음 cycle 예정 시각
2. **STOMP `/topic/recon.user.{userId}` 실시간 listen** — useStompReconcile hook + 4 event 토스트(reconcile.run.started·completed·drift.detected·killswitch.auto.engaged)
3. **KillSwitch 사용자 수동 토글 UX** — Risk 페이지 또는 SettingsModal에 KillSwitch ON/OFF 토글 + ON 시 사유 표시 + OFF 시 이중 confirm + 60초 cooldown 안내(ADR 0043 baseline 정합)

### 트리거

- T-RECON Phase 2 마감 후 (PR-392 backend-core-recon 머지 후)
- 또는 사용자가 dogfooding 중 "reconcile 결과 어디서 봐?" 보고 시 즉시

### 보강 안 (예상 PR 매트릭스)

- **PR-A frontend**: `RiskPage.tsx` reconcile timeline 추가 + `useReconcileRuns` hook + `ReconcileRunTimeline.tsx` 컴포넌트 + `useStompReconcile` hook + 4 event 토스트 + KillSwitchToggle.tsx 컴포넌트

### 연관

- ADR 0070 §2-8 frontend 0 LOCK (본 §46이 후속 의제 명시)
- ADR 0041 §2 (reconcile_run baseline)
- ADR 0043 (KillSwitch 행동)
- spec `_workspace/specs/pr-392-recon-notify-spec.md` §9 (UI 변경 매트릭스)

---

## §47. drift 임계치 사용자 설정화 (수량 1주·평균단가 0.5% 고정 → 사용자 토글)

### 사유

ADR 0070 §2-10 drift 검출 임계치는 baseline 고정값(수량 ≥ 1주, 평균단가 ≥ 0.5%). 본격 운영 시 사용자가 자기 매매 패턴에 따라 임계치 조정 의제 발견 시 — 예를 들어 1주 단위 매매 비중 높은 사용자는 임계치 5주로 상향 + KillSwitch toggle 줄이기 등.

### 트리거

- 본격 운영 사용자 의제 발견 시
- 또는 drift_count 누적이 1시간 ≥ 3회 false positive 패턴 발견 시

### 보강 안 (예상 ADR 0071+ 후보)

- system_config 또는 user_config 테이블에 `reconcile_qty_threshold`(default 1) + `reconcile_price_diff_threshold`(default 0.005 = 0.5%) 컬럼 ALTER
- SettingsModal Risk 섹션에 토글 추가 (ADR 0069 §3 SystemConfigSection pattern)
- BalanceReconciler에서 threshold 주입 (도메인 port + 조회 어댑터)

### 연관

- ADR 0041 §2-2 (임계치 고정값 baseline)
- ADR 0070 §2-10 (본 §47 후속 명시)
- ADR 0069 §7 (SettingsModal 미래 카테고리 §G KillSwitch 관련)

---

## §48. MarketSessionFsm cron skip 통합 (시장 휴장 시 reconcile cron skip)

### 사유

ADR 0070 §2-9 cron 시각은 `@Scheduled` cron expression 단순 LOCK (`0 */5 9-15 * * MON-FRI`). 그러나 KRX 공휴일·임시 휴장 시 cron이 fire되어도 KIS API 호출 의미 0 (오히려 RateLimiter 소모). ADR 0013 MarketSessionFsm 의존을 본격 통합하여 시장 휴장 시 cron skip.

### 트리거

- T-RECON Phase 1 1차 마감 후 운영 의제 발견 시
- 또는 휴장일 reconcile_run row가 N개 누적 (예: 5건/일) 발견 시

### 보강 안

- BalanceReconcileJob에 MarketSessionPort 주입 → fire 직전 `if (!session.isMarketOpenOrAdjacent()) return`
- ADR 0013 MarketSessionFsm 본격 구현 (현재 1차 stub LOCK — ADR 0048 §결정 6 정합)

### 연관

- ADR 0013 (MarketSessionFsm)
- ADR 0048 (KIS WS 41건 + MarketSession 1차 stub)
- ADR 0070 §2-9

---

## §49. user별 Slack webhook 분리 (multi-user 본격화 시)

### 사유

ADR 0044 §2-1·ADR 0070 §2-3 채택: Slack webhook은 single-tenant 가정으로 `system_config.notification_slack_webhook` 단일 row + 사용자 식별은 메시지 본문 `user_id(masked)` 필드. multi-user 본격화 시 user별 webhook 분리 의제 진입.

### 트리거

- 사용자 본인 외 추가 사용자 합류 시
- 또는 다중 워크스페이스 사용자 의제 발견 시

### 보강 안 (예상 ADR 0072 후보)

- user_account 또는 user_notification_config 테이블 신규 + per-user webhook 컬럼
- NotificationDispatcher routing — payload.userId 기준 user webhook lookup + fallback global system_config

### 연관

- ADR 0044 §4 (user별 webhook 후속 0070+ 후보)
- ADR 0070 §3-6 (트레이드오프 (f) Discord webhook 후속)

---

## §50. Discord·이메일·SMS 보조 채널 (Slack 보완)

### 사유

ADR 0044 §2-1·ADR 0070 §3-6 채택: 1차 Slack only. Slack 자체 장애·해외 사용자 등 보조 채널 필요 시점.

### 트리거

- post-MVP — Slack 장애 빈도가 사용자 운영 부담 누적 시
- 또는 Discord 사용자 의제 발견 시

### 보강 안 (예상 ADR 0073 후보)

- SlackWebhookPort에 일반화 NotificationChannelPort 추출 + DiscordWebhookPort·EmailSenderPort 신규
- notification_outbox.channel ENUM 확장 (SLACK · DISCORD · EMAIL · SMS · PUSH)
- system_config 컬럼 multi-channel 추가

### 연관

- ADR 0044 §4 후속 0072 후보
- ADR 0070 §3-6 트레이드오프 (f)

---

## §51. ADR 0045 system.startup.v1·system.shutdown.v1 publish 본격 합류 (Graceful Shutdown 트랙)

### 사유

ADR 0044 §2-2 7 트리거 중 system.startup.v1·system.shutdown.v1·notification.sent.v1은 ADR 0070 §2-4 채택으로 본 사이클 선택 publish. 본격 publish 합류는 ADR 0045 Graceful Shutdown 트랙 진입 시.

### 트리거

- ADR 0045 Graceful Shutdown 트랙 진입 시 (T-RECON·T-NOTIFY 마감 후 횡단 트랙)
- 또는 컨테이너 비정상 재시작 누적 인지 의제 발견 시

### 보강 안

- api·batch·consumer 각 ApplicationReadyEvent + SmartLifecycle.stop hook → Kafka publish
- NotificationDispatcher P2 listen (이미 토픽 정의됨)
- MAGICJAR_NOTIFICATION_SYSTEM_EVENTS toggle (dev profile false 권장)

### 연관

- ADR 0044 §2-2 (트리거 7 중 3 선택 publish)
- ADR 0045 (Graceful Shutdown)
- ADR 0070 §2-4 (본 사이클 선택 publish 정합)

---

## §52. T-NEWS 뉴스·공시 실시간 인입 트랙 (ADR 0075 흡수 완료 2026-05-15 — v3.6.7 patch)

> **★ 상태 (2026-05-15)**: **ADR 0075 Accepted — 사용자 결정 8 의제 채택 + 정밀 spec 발행 완료**. 본 §52는 ADR 0075로 흡수 마킹 + Phase 4 RSS 한경·구글은 §53 신규로 분리 잔류. spec 본문 `_workspace/specs/pr-434-t-news-spec.md`. 본 ADR 0075 머지 후 sub-agent 디스패치 3 Phase 직렬(market-data Phase 1 DART → backend-core Phase 2 Naver → frontend Phase 3 sidebar polling).

### 사유 (원본 보존)

2026-05-14 사용자 의제 응답 "뉴스 스크래핑, 공시 정보 실시간 속보 등 주요 뉴스는 시작해?". architect 자체 발행 ADR 0073 — analysis only. 옵션 A·B·C·D 매트릭스 + 사용자 결정 의제 8건 위임. architect 권장 옵션 A (T-NEWS 별도 트랙 신설 — W3.5 또는 W4 prereq).

### 2026-05-15 진행 (사용자 결정 — ADR 0075로 흡수)

- 사용자 자율 결정 ("ADR 0073 T-NEWS 트랙 진입 진행해") — architect 권장 8 의제 안 **모두 채택**
- ADR 0073 supersede (analysis only → 결정 채택 + 정밀 spec 위임)
- ADR 0075 발행 — T-NEWS 트랙 진입 spec/plan
- v3.6.7 patch 사이클 진입 (사용자 메모리 `feedback_release_policy_patch_first.md` 정합 — v3.7.0=W4 LOCK · v3.8.0=W5 LOCK · minor 인플레이션 회피)
- Phase 분리 LOCK 3 PR 직렬 (market-data Phase 1 → backend-core Phase 2 → frontend Phase 3 + Phase 4 RSS 옵션은 backlog §53 분리)
- Flyway V32 disclosure + V33 news_article (멱등성 UQ + IF NOT EXISTS)
- Kafka 2 토픽 신규 (disclosure.new.v1 + news.collected.v1 retention 7d)
- REST 2 endpoint 신규 (GET /api/symbols/{code}/news + /disclosures Swagger 4단)
- STOMP 0 LOCK (polling 60s 채택)
- DART list.json + Naver search/news.json 풀 raw fetch 인용 (헌법 §3 5단계 절차)
- KIS endpoint 호출 0 LOCK + AI 호출 0 + mock 합성 0
- ~25 산출 파일 매트릭스

### 트리거

- 사용자 옵션 1~8 결정 (ADR 0073 §6)
- 또는 W3 v3.6.4 T-J·T-PERF 마감 후 사용자 의제 재발화

### 옵션 (ADR 0073 §3 매트릭스)

| 옵션 | architect 권장 | 의미 |
|---|---|---|
| **A — T-NEWS 별도 트랙 신설** | ★★★ | DART 실시간 강화 + Naver 뉴스 어댑터 + frontend panel. ADR 0074 신규 발행 + 4 PR (Phase 1 market-data + Phase 2 backend-core + Phase 3 frontend + Phase 4 RSS 옵션). 1~1.5주 |
| B — T-AI 흡수 | ★★ | T-AI 진입 시 묶음 spec. 자본 보호 의제 지연 risk |
| C — DART 폴링 강화만 | ★ | 단발 PR. Naver 뉴스 비범위 LOCK |
| D — KIS-first 극단 확장 | 비권장 | 뉴스 영역 전체 비범위. 사용자 비전 (T-AI) 후퇴 |

### 사용자 결정 의제 8건 (ADR 0073 §6)

1. 트랙 신설 옵션 (A·B·C·D) — architect 권장 **A**
2. DART 실시간 폴링 빈도 (1분·5분·30초) — architect 권장 **1분**
3. Naver Open API 키 등록 시점 (Phase 2 진입 시 .env vs SettingsModal NaverApiSection 1차 PR) — architect 권장 **Phase 2 진입 시**
4. KillSwitch auto-ON 트리거 (정정공시 + 보유 종목 매칭 시) (자동 vs Slack 알림만 vs 비활성) — architect 권장 **Slack 알림만**
5. 뉴스 감성 분석 분리 vs T-AI 흡수 — architect 권장 **T-AI 흡수**
6. 뉴스 panel UI 위치 (sidebar vs /news 페이지 vs Dashboard 위젯) — architect 권장 **sidebar**
7. Kafka 신규 토픽 매트릭스 (2 토픽 분리 vs 단일 + discriminator) — architect 권장 **2 토픽 분리**
8. 진입 시점 (W3 마감 직후 v3.7.0 vs W4 T-AI 직전 묶음 vs 자율) — architect 권장 **W3 마감 직후 v3.7.0**

### 자체 추론 risk (채택 시 보강)

- Naver Open API 한도 25K/일 — 자체 추론 (공식 raw fetch 미수행). 채택 시 ADR 0074 풀 spec 발행 단계 raw fetch 의무 LOCK
- Naver Client ID/Secret 헤더명 (X-Naver-Client-Id / X-Naver-Client-Secret) — 자체 추론. 채택 시 raw fetch 의무

### 채택 시 산출물 매트릭스 (옵션 A — architect 권장)

- ADR 0074 신규 발행 (T-NEWS 풀 spec/plan — Phase 분리 LOCK + DDL + Kafka 토픽 + REST endpoint + UI + 검증 시나리오)
- raw fetch 4건 (DART list.json + corpCode.xml + Naver search/news.json + RSS 옵션)
- Phase 1 market-data + backend-core (DartDisclosureAdapter cron 1분 + Kafka disclosure.new.v1)
- Phase 2 backend-core (NaverNewsAdapter + Flyway V31 news 테이블 + Kafka news.collected.v1)
- Phase 3 frontend (NewsPanel + DisclosurePanel + STOMP 옵션)
- Phase 4 backend-core (RSS 보조 — Naver API 한도 보완)

### 비범위 (ADR 0073 §7)

- 자동 매매 결정 0 LOCK (뉴스 = 룰 매매 트리거 사용 0)
- 뉴스 본문 풀 텍스트 영속 0 (제목 + summary만)
- 영어 뉴스 (Bloomberg·Reuters) post-MVP
- 외국인·기관 수급 정밀 post-MVP
- AI 감성 분석 본격 → T-AI 흡수 backlog §62 후속
- 뉴스 알림 Slack/이메일 push → T-NOTIFY 합류 (ADR 0044 baseline)

### 연관

- ADR 0073 (analysis only · feasibility 분석 · Superseded by 0075)
- **ADR 0075 (T-NEWS 트랙 진입 spec/plan · Accepted 2026-05-15)**
- ADR 0010 §5 (멀티테넌트 news·news_sentiment 테이블 명시)
- ADR 0018 (Corp Action raw — DART 폴링 baseline)
- ADR 0026 §38 (T-AI prompt 컨텍스트 뉴스 명시)
- ADR 0057 (KIS-only — 시세 한정, 뉴스는 보조 OK)
- ADR 0069 §F (SettingsModal NaverApiSection 후보 — T-AI 사이클 흡수 ADR 0094+ 잔류)
- master-architecture §A.4·§B-3·§C.13 (뉴스·공시 영역 baseline)
- backlog §53 (T-NEWS Phase 4 RSS 한경·구글 잔류)
- backlog §62 (T-AI 감성 분석 흡수 후속)

---

## §53. T-NEWS Phase 4 RSS 한경·구글 어댑터 (ADR 0075 잔류 의제 — Naver 한도 보완)

### 사유

ADR 0075 §11 비범위 — Phase 4 RSS 한경·구글 어댑터는 본 ADR 1차 PR scope 외 잔류. Naver Open API 25K건/일 hard limit 도달 시(Watchlist 종목 ↑ + 분당 17건 한도 ↓) 보완 path 필요.

### 트리거

- Watchlist 종목 50건 이상 진입 시 (분당 17건 ÷ 50 종목 = 1 종목당 ~20분 polling 주기 → 사용자 인지 지연 한계)
- 또는 Naver API 일일 도달 ~80% 누적 (25K건의 80% = 20K건/일 임계)
- 또는 사용자 트리거 ("뉴스 더 다양하게")

### 옵션 매트릭스 (ADR 0075 §11 prepend)

| 옵션 | trade-off |
|---|---|
| A — 한경 RSS 어댑터 신규 | `https://www.hankyung.com/feed/all-news` (rate limit 미명시 + scrape 허용) — 분당 폴링 + 종목 매핑 (제목/본문 contains) |
| B — 구글 뉴스 RSS 어댑터 신규 | `https://news.google.com/rss/search?q=...` (rate limit 미명시 + 종목명 query) — 다국어 가능성 risk |
| C — 한경 + 구글 동시 | 다중 source 통합 + `source` 컬럼 분기 (news_article 영속 reuse) |

architect 권장: **A 단독 1차** (한경 한국어 + 종목 매핑 자연 + 구글 다국어 risk 회피).

### 비범위 (Phase 4 진입 시 별도 ADR)

- Phase 4 진입 시 신규 ADR (가칭 ADR 0095+) — RSS 어댑터 spec/plan
- raw fetch 의무 (한경 RSS feed schema · 구글 뉴스 RSS schema · TOS 검토)
- news_article 영속 reuse (Phase 2 baseline) + source 컬럼 분기 ("RSS_HANKYUNG" · "RSS_GOOGLE")
- Naver와 동일한 `<b>` strip 0(RSS는 HTML escape) + pubDate 파싱 (RSS는 RFC 822 동일 호환)

### 진입 시점

T-NEWS Phase 3 sub-agent 디스패치 완료 + dogfooding 1주 + Naver API 한도 도달 임계 검증 시. 사용자 의제 또는 운영 metrics 자동 감지.

### 연관

- ADR 0075 §11 비범위
- backlog §52 (본 ADR T-NEWS 흡수 완료 baseline)

---

## §54. KIS WS NXT/시간외 시세 sync 표준 정합 — ADR 0076 §9 비범위 + §7 잔류 의제 (Proposed 2026-05-15)

### 사유

ADR 0076 Proposed (2026-05-15) — 누적 hotfix #443·#444·#445 root cause 분석 + KIS open-trading-api raw fetch 100% 인용. architect 권장안 A-1+B-2+C-1+D-1+E-1+F-1+G-1 채택 시 sub-agent 디스패치는 별도 사이클로 분리. 본 backlog §54는 ADR 0076 채택 후 잔류 의제 등록.

### 잔류 의제

#### 의제 1: ADR 0076 §7-B-1 (H0STOUP0 풀세트 41 slot 한도 위반 시 풀 축소 검토)

ADR 0076 §7-B 의제 B-1(H0STOUP0 풀세트 20 slot 고정) 채택 시 — H0STCNT0(20) + H0NXCNT0(20) + H0STCNI0(1) + H0STOUP0(20) = 61 slot으로 ADR 0048 §2-1 한도 41 위반. 풀 ≤ 13 강제 시 watchlist 사이즈 측정 + SubscriptionRouter priority 재조정. ADR 0076 §10 권장안은 B-2 dynamic subscribe로 한도 회피이나, 시간외 시각대 풀 사이즈 ≥ 20일 때 B-1로 전환 검토.

> **현행 정합 note (2026-06-13)**: 위 산식은 H0STCNT0+H0NXCNT0 분리 이중 구독(종목당 2채널) 전제다. **2026-06-13 ADR 0124로 체결가가 H0UNCNT0 단일 채널(종목당 1채널)로 전환 + watchlist owner WS→polling 강등 + WS 한도 40 정정**되어 본 의제의 slot 산식은 재산정 대상이다. 시간외 H0STOUP0 풀세트 검토는 ADR 0123/0124 owner ledger·H0UNCNT0 단일 채널 기준으로 다시 평가한다.

#### 의제 2: ADR 0076 §7-C (호가 NXT/시간외 cover — C-2 풀세트 또는 C-3 H0UNASP0 통합)

- C-2: H0STASP0 + H0NXASP0 + H0STOAA0 풀세트 — slot 추가 30+ 소모
- C-3: H0UNASP0 통합 호가 단일 구독 — KIS examples_user/asking_price_total 정합. raw 응답 sample 검증 의무

채택 시 ADR 0076 §7-C-1 (H0STASP0만 유지) 위에 add-only ADR(0077+ 추정)로 분리.

#### 의제 3: ADR 0076 §7-A-3 (specific 조건 invalidate — OPSP8996 응답 수신 시만)

ADR 0076 권장안 A-1(invalidate 호출 제거) + F-1(SubscriptionResponse self-heal)이 결합되어 자동 fallback 가능. but A-1만 단독 채택 시 OPSP8996 재발 risk — A-3 specific 조건 invalidate (OPSP8996 응답 수신 시 invalidate + 1회 retry) 검토 잔류.

#### 의제 4: KIS WS server side disconnect 정책 raw 가이드 문의

ADR 0076 §3-D 가설 — 15:30 이후 KIS server side에서 H0STCNT0 stream 자동 종료 여부. KIS 가이드 raw 명시 없음 — KIS 측 답변 대기 의제. 답변 수신 시 ADR 0076 본문 갱신 + magicJar 측 reconnect 전략 정밀화.

#### 의제 5: KIS PINGPONG 주기 실측 + magicJar 측 timeout fine-tune

ADR 0076 §4 — KIS 표준 PINGPONG 주기 raw 명시 없음 (30s 또는 60s 통상 추정). dogfooding evidence 수집 후 magicJar 측 heartbeat watchdog timeout 30s/60s fine-tune. ADR 0076 §7-D-1 채택 후 추가 데이터 축적 + 후속 조정.

#### 의제 6: 시간외 단일가 시각대 backtest 룰 검증

ADR 0076 §7-G G-1 채택 시 시그널·룰엔진은 시간외 tick도 동등 처리. but BacktestEngine(ADR 0056)이 시간외 단일가 시각대 가격 변동을 정확히 반영하는지 검증 의무. W4 사이클 또는 별도 backtest QA 사이클 사후 검증.

#### 의제 7: ADR 0048 §2-1 slot 한도 41건 매트릭스 갱신

B-2 채택 시 시간 trigger H0STOUP0 추가로 41 slot 한도 매트릭스 재계산. ADR 0048 §2-1 본문 갱신 의무 (modify-shared 회피 — add-only § 추가만).

### 트리거

- **우선순위 ↑**: ADR 0076 §7 의제 사용자 채택 결정 후 — Phase 1·2·3·4 sub-agent 디스패치 시작
- **우선순위 ↓**: ADR 0076 채택 후 안정화 dogfooding 검증 evidence 누적 후

### 예상 처리 PR

- **Phase 1 PR**: market-data — `agent/market-data/kis-ws-approval-reuse-self-heal` (A-1 + F-1 의제)
- **Phase 2 PR**: market-data — `agent/market-data/kis-ws-heartbeat-graceful-close` (D-1 + E-1 의제)
- **Phase 3 PR**: market-data — `agent/market-data/kis-ws-overtime-single-price` (B-2 H0STOUP0)
- **Phase 4 PR** (선택, 별도 사이클): strategy-engine — `agent/strategy-engine/risk-time-gate` (G-1)

후속 ADR — 의제 1·2 채택 시 ADR 0077·0078 후보 (slot 한도 매트릭스 + 호가 cover 확장).

### 연관

- ADR 0076 §7 의제 매트릭스
- ADR 0076 §11 sub-agent 디스패치 매트릭스
- ADR 0048 §2-1 slot 한도 (B-2 채택 후 매트릭스 갱신 의무)
- ADR 0063 NXT 08:00~20:00 cover (cross-ref H0NXCNT0)
- ADR 0013 Market Session FSM (B-2 KisMarketSessionFsm 합류 후보)
- 누적 hotfix PR #443·#444·#445
- spec `_workspace/specs/pr-447-kis-ws-nxt-standard-spec.md`

---

## §55. 내부 자산 추출 트랙 — 재사용 가능한 프로젝트 부트스트랩 템플릿 (ADR 0078)

### 사유

magicJar의 검증된 운영 자산(SoT 5층 · harness · Task cycle · 룰북 · Release LOCK · 운영 가드 · Memory 시스템)을 별도 재사용 가능한 프로젝트 부트스트랩 템플릿으로 추출. 2026-05-16 사이클에서 caveman · graphify 외부 도구 도입 검토 후, 사용자가 "새 프로젝트에도 공통 룰처럼 가지고 갈 것"이 진짜 목표임을 명시 — **외부 도구 도입 방향 ➜ 내부 자산 추출 방향으로 트랙 전환**.

### 추출 자산 우선순위

- **Phase 1 (★★★)**: SoT 5층 + harness 패턴 + Task cycle (도메인 비종속)
- **Phase 2 (★★)**: 개발 룰북 5건 + Release minor LOCK + 운영 함정 가드 + Memory 4 타입 + branching · 머지 정책
- **Phase 3 (★)**: CHANGELOG 한 줄 정책 + devlog HTML 자동화 + 시크릿 처리 정책

### 패키징 잠정 추천

**A + C 결합** — 코드 · 스크립트는 GitHub Template Repository ("Use this template" 클릭), 문서 · 룰북은 별도 docs repo로 분리해 magicJar living policy 변경 시 동기화 가능. 시동 시점에 최종 결정.

### Plugin Independence — 핵심 제약 (★★★)

**추출 결과물은 `revfactory/harness` 플러그인 종속성 0이어야 한다.** Claude Code 본체 메커니즘(`.claude/agents/`·`.claude/skills/`·`.claude/commands/`·`.claude/hooks/`·`.claude/settings.json`)만으로 동작. 사용자 의도: "플러그인을 깔지 않았지만 의도한 방향대로 가도록 + sub-agent 잘 활용". 새 프로젝트 사용자에게 plugin install 의무 부여 X.

### MCP화와의 분리

룰셋의 MCP화는 부적합 (MCP = 함수 호출 인터페이스, 룰셋 = 코드/문서 패턴). magicJar 본체의 MCP 노출은 별건 — v4.0 비전 또는 별도 ADR.

### 트리거

- **사용자 명시적 호출 단일 조건** — 자동 시동 트리거 없음
- 남은 사이클 또는 backlog 모니터링 중 사용자가 호출하는 시점

### 예상 처리

시동 시점에 별도 spec/plan 작성 후 Phase 1부터 순차 진행 (~1주 추정).

### 연관

- ADR 0078 본문 (`_workspace/arch/adr/0078-internal-asset-extraction-strategy.md`)
- ADR 0030 (Master architecture SoT)
- ADR 0035 (Agent model tiering)
- ADR 0036 (Author whitelist + Task cycle)
- ADR 0052 (Baseline release lock)
- ADR 0053 (Living policies SoT)
- `docs/architecture/development-rules.md` — 개발 룰북 5건

## §57. Naver Open API 429 Rate limit 17건 동시 — fitness gap (Proposed 2026-05-17)

### 사유

v3.6.10 release cut 직전 docker logs 검사에서 api `NaverNewsAdapter` HTTP 429 17건 stockCode 동시 발생 (11:57 시점 1초 내 17개 모두 429). ADR 0075 §4 sliding window ZSET 17건/분 hard limit 사양 정합 의도였으나 실제로 17 stockCode가 동시에 429 — 정합 결함 가능성.

### 발견 evidence

```
11:57:23.610 WARN NaverNewsAdapter HTTP 429 — stockCode=027740 ... Rate limit exceeded ...
11:57:23.623 WARN NaverNewsAdapter HTTP 429 — stockCode=195500 ...
11:57:23.636 WARN NaverNewsAdapter HTTP 429 — stockCode=006110 ...
11:57:23.650 WARN NaverNewsAdapter HTTP 429 — stockCode=004710 ...
11:57:23.662 WARN NaverNewsAdapter HTTP 429 — stockCode=066570 ...
... (17건)
```

### 처리 방향

- WARN level (ERROR 아님) → release cut block 사유 아님
- 가설: (a) `NaverNewsRateLimiter.acquireSlot()` ZSET cleanup 흐름 race condition (b) Naver 키 quota 자체 17건/분보다 더 작음 (c) `WatchlistNewsScheduler` fixedRate 60_000 + 활성 watchlist > 17건 → 동시 polling burst

### 처리 방향

- backend-core 영역 — ZSET sliding window 분석 + Naver 키 quota 실제 한도 검증 + 사용자에게 Naver Open API 키 quota 사양 확인 필요
- backlog로만 등재 — 우선순위 낮음 (W4 진입 전 prereq 아님, T-NEWS 보조 흐름이라 시세 본체 영향 0)

### 연관

- ADR 0075 §4 Naver sliding window 17건/분 hard limit
- ADR 0075 §11 비범위 — Phase 4 RSS 한경·구글 어댑터 (backlog §53)
- `kr.spdaewoon.magicjar.api.infrastructure.naver.NaverNewsRateLimiter`
- `kr.spdaewoon.magicjar.api.infrastructure.naver.NaverNewsAdapter`

## §58. K-1 사용자 알림 UI — SetupWizard 게이트 가이드 (Proposed 2026-05-17)

### 사유

ADR 0077 의제 K (사용자 알림 UI)가 hotfix 범위 lock으로 backlog 등재됨. v3.6.11 hotfix에서 J-1 사전 검증 + WARN 로그까지만 적용되었고 사용자 알림은 미구현.

### 처리 방향

- SetupWizard 또는 SettingsModal에 "REAL 자격증명 미등록 시 시세 WS 작동 불가" 명확 게이트 + 등록 유도 UI
- frontend 영역 (architect spec → frontend dispatch)
- 우선순위 낮음 (현재는 WARN 로그만으로도 운영 안정성 회복 — 자동매매 본체 영향 0)

### 연관

- ADR 0077 §의제 K-1 (배제)
- `KisWebSocketBridge.kt` J-1 사전 검증 (PR #467 적용)
- `WatchlistIngestionWorker.onStartup()` WARN 로그
- SetupWizard 흐름 ([[project_kis_key_management]] 정합)

## §60. consumer candle UNIQUE constraint violation race condition (v3.6.12 cut docker logs 1건 발견)

### 사유

v3.6.12 cut 직전 docker logs 검증(2026-05-18 09:24~09:31, 7분 모니터링)에서 consumer 1건 ERROR 발견:

```
2026-05-18T09:26:00.127+09:00 ERROR ... duplicate key value violates unique constraint "uq_candle_code_interval_ts"
  Detail: Key (code, "interval", ts)=(001040, 1m, 2026-05-18 09:25:00+09) already exists.
```

본 사이클(C1·C2) 도입 X — 기존 KIS WS tick 처리 path race condition. UNIQUE constraint가 데이터 정합성 정상 보호 작동 → 데이터 손실 0. cut blocker 아님.

### 가설

- (a) KIS WS 1m boundary tick 도착 시점에 BoundaryAggregator/KisWebSocketBridge 양쪽이 동시 insert race
- (b) backfill scheduler와 실시간 tick processing 시점 중첩 (1m boundary `09:25:00` 시점)
- (c) Kafka consumer at-least-once 의미상 동일 메시지 2회 처리

### 처리 방향

- backend-core 또는 market-data 영역 — race condition 진단 후 INSERT ... ON CONFLICT (code, interval, ts) DO NOTHING 적용 또는 분기 정리
- backlog로만 등재 — 우선순위 낮음 (WARN→ERROR 1회/7분, 데이터 영향 0)
- W3 종착 또는 W4 진입 전 안정성 Cycle 3로 묶음 처리 가능

### 연관

- `consumer/.../aggregation/BoundaryAggregator.kt`
- `consumer/.../ws/KisWebSocketBridge.kt` (handleTickData)
- `domain/.../market/CandleJpaEntity.kt` (uq_candle_code_interval_ts)
- v3.6.12 cut docker logs evidence (orchestrator 2026-05-18)

## §64. T-ACCT 신규 트랙 — 잔액·예수금·계좌 잔고 사용자 노출 endpoint + UI (사용자 결함 진단 2026-05-18)

### 사유

사용자 (최고 관리자) dogfooding 2026-05-18 14:50 KST:
> "지금 잔액조회 예수금 조회 이런게 아예 없는데?"

orchestrator + architect 진단:
- ADR 0042 Bootstrap용 `KisBalanceAdapter`·`KisReconcileBalanceAdapter` 존재하나 **사용자 노출 endpoint 0건**
- output1(보유 종목)만 파싱 — **output2(예수금·평가금·평가손익) 완전 누락**
- frontend Dashboard·AppHeader 잔고 UI 0건
- ADR 0067 §7-1 CapitalSnapshotProvider Phase 2 미합류 → `getCapital(userId) → 0` → POSITION_LIMIT 룰 무력
- 사용자 메모리 `feedback_v3_skepticism_no_regression` lens — 자동매매 fitness gap 신규(v1/v2 잔재 아님)

### 결정

**신규 트랙 T-ACCT** 발행 — W3 보강 fitness gap 해소.

- spec: `_workspace/specs/2026-05-18-account-balance-positions-spec.md`
- ADR: 0081 Proposed (Account Balance & Positions User-Facing Exposure)
- 3 endpoint: `GET /api/account/balance·positions·summary`
- 신규 port: `domain/account/port/KisBalanceQueryPort`
- 신규 adapter: `api/infrastructure/kis/ApiKisBalanceQueryAdapter` (api 직접 KIS REST 호출 + output1·output2 파싱)
- Redis cache: `account:balance:{userId}` TTL 30s
- frontend Dashboard 격상: `DashboardPlaceholder → DashboardPage` + `AccountSummaryCard` + `HoldingsTable`
- CapitalSnapshotProvider 실제 어댑터 교체 (`@ConditionalOnMissingBean` 정합 — revert 시 default no-op 자동 fallback)
- 모듈 경계 정합 (ADR 0004): consumer KisBalanceAdapter 0 touch (관심사 분리)
- KIS-first raw fetch 의무 (헌법 §3): output2 11 필드 LOCK · raw URL 4건 inline KDoc LOCK
- mock 합성 0 LOCK (`feedback_no_mock_data`)
- AI 호출 0 LOCK (룰 5)

### sub-agent dispatch plan

| Phase | sub-agent | 영역 | 우선순위 |
|---|---|---|---|
| 1·2 (단일 PR 묶음) | backend-core | domain/account + api/{controller,service,infrastructure/kis} | HIGH |
| 3 | backend-core | consumer/risk (CapitalSnapshotProvider 교체) | MED |
| 4 | frontend | frontend/src/{api,pages/Dashboard} | HIGH |
| 5 (옵션) | frontend | AppHeader 배지 + PositionsPage BalanceCard | MED |

### release

**v3.6.14 patch cut** 권장 — 사용자 메모리 `feedback_release_policy_patch_first` 정합 (v3.7=W4 LOCK, T-ACCT는 W3 보강).

### 처리 상태

⏳ spec 발행 단계 (PR agent/architect/spec-account-balance-positions-track) — sub-agent dispatch 대기.

### 연관

- `_workspace/specs/2026-05-18-account-balance-positions-spec.md`
- `_workspace/arch/adr/0081-account-balance-user-facing-exposure.md`
- ADR 0042 (Bootstrap · consumer 0 touch)
- ADR 0067 §7-1 (CapitalSnapshotProvider cross-ref add-only)
- 사용자 dogfooding 2026-05-18 14:50 KST

---

## §66. 결함 3 — indicator-impl 모듈 신설 (IndicatorSource 모듈 경계 fix · 2026-05-18)

### 컨텍스트

사용자 dogfooding evidence (최고 관리자, 2026-05-18 KST):

```
GET http://localhost:8080/api/indicators?code=066570&name=ema&interval=1d&params=period=20&adjusted=false
→ HTTP/1.1 400 Bad Request
```

ADR 0021 (2026-05-03 Accepted) 5 native + 80 KIS Strategy Builder 합계 81지표 전부 400. 사용자 차트 overlay·DSL 평가 핵심 자원 무력.

### root cause

- `api/.../api/config/IndicatorEngineConfig`: Spring autowire `List<IndicatorSource>` 수집.
- `NativeIndicatorSource`(priority=50, 5종)·`KisStrategyBuilderSource`(priority=100, 80지표) 모두 `@Component` + **consumer 단독 거주** (`consumer/src/main/kotlin/.../consumer/application/indicator/source/`).
- api → consumer 의존 **0** LOCK (ADR 0004 §의존 방향 — KafkaListener·KIS WS 격리).
- 결과: api ApplicationContext `sources=emptyList()` → 모든 호출 `IllegalArgumentException` → `IndicatorService` catch → 400.
- ADR 0054 §후속에 "indicator-engine 동일 패턴 적용 검토 — backlog 추가" 명시(2026-05-10)가 운영 부채로 누적된 결과.

### 결정 (사용자 옵션 B 명시 2026-05-18)

**`indicator-impl` 라이브러리 모듈 신설** — pattern-engine 패턴 1:1 정합.

- `indicator-impl/` root 직속 (domain·pattern-engine 동일 레벨)
- Kotlin + Spring Context (Spring Boot main 0)
- 패키지 루트: `kr.spdaewoon.magicjar.indicator.impl.*`
- 의존: indicator-impl → domain만. api·consumer → indicator-impl 신규 의존
- 15 파일 git mv (consumer→indicator-impl, ~3,433 line, 패키지 rename)
- 잔류 3건 (consumer): `LiveCandleMerger`·`LiveCandleMergingSource`·consumer 측 `IndicatorEngineConfig` (자본 보호 hotfix 2026-05-15 Redis key `candle:today:{code}:1d` 정합 보존)
- 모듈 매트릭스 5→6 갱신 (ADR 0004 헤더 갱신 + master §5·§6 갱신)

### 결함 3-B 분리 (별도 후속)

api 모듈 `IndicatorEngineConfig`는 `LiveCandleMergingSource` decorator wrap 부재 → 자본 보호 critical hotfix(2026-05-15)가 **api 측 누락**. 단, api 경로는 `IndicatorService` cache-aside read-only endpoint 한정 사용(시그널 생성 진입 0) → 자본 영향 risk 낮음. 본 §66 처리 후 별도 검토.

### sub-agent dispatch plan

| Phase | sub-agent | 영역 | 우선순위 |
|---|---|---|---|
| 1·2 (단일 PR) | backend-core | settings.gradle.kts + indicator-impl/build.gradle.kts + api·consumer build.gradle.kts + 15 파일 git mv + 패키지 rename | HIGH |
| 3 (검증) | orchestrator | dogfooding curl `/api/indicators?code=066570&name=ema&interval=1d&params=period=20` → 200 GREEN | HIGH |

### release

**v3.6.14 patch cut** 합류 권장 — T-ACCT(§64) + T-MAN(별도) + 본 §66 3건 합류 patch row 1건. 사용자 메모리 `feedback_release_policy_patch_first` 정합.

### 처리 상태

⏳ spec 발행 단계 (PR `agent/architect/spec-indicator-impl-module-fix-3`) — sub-agent dispatch 대기.

### 연관

- `_workspace/specs/2026-05-18-indicator-impl-module-spec.md`
- `_workspace/arch/adr/0083-indicator-impl-module.md`
- ADR 0004 (MSA 모듈 재구성) — 6 모듈 갱신
- ADR 0021 (IndicatorSource 추상화) — source 구현 위치 변동
- ADR 0054 (pattern-engine 모듈) — 1:1 정합 패턴
- 사용자 dogfooding 2026-05-18 KST

---

## §65. T-MAN 신규 트랙 — 사용자 수동 주문 발행 UI + endpoint (사용자 결함 진단 2026-05-18)

### 사유

사용자 (최고 관리자) dogfooding 2026-05-18 KST:
> "주문을 하는 화면이 없어 --;; 잔액조회도"
> "C가 권위적이긴 하지만 실제로 자동매매 하는거 결국엔 호가, 가격, 수량, 포지션을 넘겨서 주문을 성사 할테니 확장형으로 별도 페이지 있어도될거같은데?"
> "하려는 이유는 가상계좌 잔고 등 통합테스트 하려면 이게 있어야해"

architect 진단:
- `OrderRouterKafkaListener` 자동 path 12 step LOCK 존재 + **수동 entry point 0건**
- `KisOrderHttpAdapter`·`TradingModeGateImpl`·`OrderQueryController`·`OrderCancelController` 재사용 가능
- `OrdersPage` read-only — **[신규 주문] button 0건**
- `OrderIntent`·`OrderRequest` 도메인 signalId·strategyId NOT NULL → 수동 주문 sentinel 의제
- 사용자 의제 핵심 = **가상계좌(MOCK) 통합테스트 dogfooding** (signal seed SQL 부담 해소)
- 사용자 메모리 `feedback_v3_skepticism_no_regression` lens — 자동매매 fitness gap 신규(v1/v2 잔재 아님)
- 사용자 메모리 `project_system_vision` — 사용자=최고 관리자 비전 정합(룰이 못 잡는 종목 수동 주문 권한)

### 결정

**신규 트랙 T-MAN** 발행 — W3 보강 fitness gap 해소 + 가상계좌 통합테스트 dogfooding.

- spec: `_workspace/specs/2026-05-18-t-man-manual-order-issue-spec.md`
- ADR: 0082 Proposed (Manual Order Issuance Track — T-MAN)
- 1 endpoint: `POST /api/orders/manual`
- 신규 페이지: `/orders/new`
- 컴포넌트: `NewOrderPage`·`OrderPreviewModal`·`useManualOrderMutation` hook
- 자본 path 단일 LOCK: `OrderRouterKafkaListener` 12 step 100% 재사용 (자동·수동 동일)
- 3중 게이트 동일 적용: env MAGICJAR_MODE × env MAGICJAR_ALLOW_REAL × user_account.real_trading_consent_at
- T-RISK Guard chain 우회 + KillSwitch만 재검증 (사용자=최고 관리자 비전 정합)
- sentinel signalId=0L · strategyId=0L · Kafka payload `source="MANUAL"` 필드 add-only
- UI 8 layer 안전망 (종목 6자리·수량≥1·LIMIT 가격>0·3중 게이트 표시·KillSwitch LOCK·미리보기 confirm·60s cooldown·REAL 강조)
- KIS endpoint 신규 호출 0 (ADR 0068 §14-5 cross-ref만)
- AI 호출 0 LOCK (룰 5)
- mock 합성 0 LOCK (`feedback_no_mock_data` — 시장가 "체결 후 확정")
- 모듈 경계 정합 (ADR 0004): api 신규 4 파일 + consumer 1 파일 add-only + frontend 신규 3 + 수정 add-only 3 + Flyway V31 micro(옵션)

### sub-agent dispatch plan

| Phase | sub-agent | 영역 | LOC | 우선순위 | 병렬 |
|---|---|---|---|---|---|
| 1 | backend-core | api/controller/order + api/service/order + api/messaging/order + api/dto + Flyway V31(옵션) | ~250 | HIGH | Phase 2 병렬 |
| 2 | frontend | pages/orders/NewOrderPage + components/order/OrderPreviewModal + api/order + hooks + routes | ~350 | HIGH | Phase 1 병렬 |
| 3 | backend-core | consumer/order/OrderRouterKafkaListener source 필드 add-only | ~30 | MED | Phase 1·2 직후 |

병렬 dispatch 사유: 영역 add-only · DTO 명세 spec §3 LOCK · modify-shared risk 0.

### dogfooding 케이스

- 케이스 9 (수동 MOCK 주문 발행 — 가상계좌 통합테스트 핵심)
- 케이스 10 (3중 게이트 차단 검증)
- T-O Phase 4 plan §4.2 케이스 6·7·8 강화 통합 (signal seed SQL 부담 해소)

### release

**v3.6.15 patch cut** 권장 — 사용자 메모리 `feedback_release_policy_patch_first` 정합 (v3.7=W4 LOCK, T-MAN은 W3 보강).

T-ACCT v3.6.14와 묶음 cut 안 함 사유: 영역 분리 (T-MAN=주문 액션 / T-ACCT=잔고 조회) + 사용자 메모리 `feedback_dev_priority_qa_batch` 정합.

### 처리 상태

⏳ spec 발행 단계 (PR agent/architect/t-man-manual-order-issue-spec) — sub-agent dispatch 대기.

### 연관

- `_workspace/specs/2026-05-18-t-man-manual-order-issue-spec.md`
- `_workspace/arch/adr/0082-t-man-manual-order-issuance.md`
- ADR 0068 (T-O — `OrderRouterKafkaListener` 12 step 재사용 cross-ref only)
- ADR 0067 (T-RISK — Guard chain 우회 cross-ref)
- ADR 0024 (KillSwitch — step 4 재검증)
- ADR 0081 (T-ACCT — DashboardPage 잔고 변화 evidence path cross-ref)
- 사용자 dogfooding 2026-05-18 KST

### 후속 backlog §66 (T-MAN 1차 MVP 후속)

T-ACCT Phase 4 + T-MAN Phase 1·2·3 머지 후 후속 의제:
- frontend position 사전 검증 (T-ACCT API 의존 — `useAccountPositions` hook 호출 → 매도 form에서 보유 수량 검증)
- 호가·현재가 표시 (KIS `inquire-price` REST 1회 호출 + manual refresh 버튼)
- 60초 cooldown UI 우회 차단 강화 (현재는 LocalStorage 만 — backend OrderIdempotencyGuard Redis 권위로 차단되지만 UX 향상)
## §78. 외부 코드 리뷰 13 코멘트 흡수 종합 트랙 (주문 path 안정성 P0·P1·P2 · 2026-05-18)

### 컨텍스트

사용자(최고 관리자, 2026-05-18 KST) 외부 코드 리뷰 2건 수신 → 13 코멘트(중복 제거 11 의제) → magicJar 현 시스템 grep·코드 정독 검증 → ✅ 2건 충족 + 🟡 5건 부분 + ❌ 4건 미충족 매트릭스 도출.

사용자 명시 결정: **P0·P1·P2 단계적 트랙 발행 + sub-agent dispatch**. spec 발행 사이클 (architect 2026-05-18).

### 11 의제 매트릭스

| § | 의제 | 현재 상태 | gap evidence | priority |
|---|---|---|---|---|
| §78-1 | #1 idempotency_key UNIQUE | ✅ 충족 — close | `order_request.uq_order_idempotency` (Flyway V20) + Redis SETNX 60s | close |
| §78-2 | #2 DB↔Kafka Transactional Outbox | 🟡 부분 | NotificationOutbox는 Slack only — order 4 producer 직접 Kafka publish | P1 |
| §78-3 | #3 KIS REST 타임아웃 → PENDING_RECONCILE | 🟡 부분 | 5xx 1회 retry — PENDING_RECONCILE 상태 grep 0건 + KIS 주문조회 reconciliation 0 | **P0** |
| §78-4 | #4 WS 체결 race + 재연결 backfill | ❌ 미충족 | `inquire-ccnl` grep 0건 — race·재연결 backfill 0 | **P0** |
| §78-5 | #5 부분체결 SUM 가드 | ✅ 충족 — close | `sumExecutedQuantityByOrder` + §14-6 LOCK 분기 | close |
| §78-6 | #6 주문 정정 흐름 | 🟡 부분 | `OrderCancelController` 합류(취소만) — RVSE_CNCL_DVSN_CD="01" 정정 0건 | P2 |
| §78-7 | #7 DLQ / Kafka 재시도 정책 | ❌ 미충족 | `DefaultErrorHandler·RetryableTopic·DeadLetterPublishing` grep 0건 → 무한 재시도 risk | **P0** |
| §78-8 | #8 STOMP 알림 영속 (재접속 push) | 🟡 부분 | NotificationOutbox SLACK 한정 — 재접속 unread fetch 0건 | P1 |
| §78-9 | #9 분산 추적 traceId/MDC | ❌ 미충족 | `MDC·traceId` grep 0건 — 사고 추적 cost ↑ | P1 |
| §78-10 | #10 시간 컨텍스트 (장 휴장·토큰 mutex) | 🟡 부분 | MarketSessionFsm 1차 stub + KisAuthAdapter token mutex 재검증 필요 | P2 |
| §78-11 | #11 KIS hashkey 헤더 | ❌ 미충족 | `hashkey` grep 0건 — KIS POST 주문 hashkey 의무 확인 + 구현 | **P0** |

### v3.x cut 시점 매트릭스

`feedback_release_policy_patch_first` + `feedback_docker_log_verify_before_cut` 정합:

| 마감 시점 | 버전 | 트리거 의제 |
|---|---|---|
| P0 완료 | v3.6.16 | #11·#3·#4·#7 — 자본 잠재 risk 해소 (REAL 진입 필수) |
| P1 완료 | v3.6.17 | #2·#8·#9 — 정합성·관측성 보강 |
| P2 완료 | v3.6.18 또는 W4 직전 묶음 | #6·#10 — UX·운영 편의 |

각 사이클 tag cut 직전 `scripts/build-and-deploy.sh` 재배포 + 4 모듈 docker logs 에러 검사 → 0건 확인 후 cut.

### sub-agent dispatch plan

| Phase | sub-agent | 점유 영역 | PR 수 |
|---|---|---|---|
| P0 | market-data | consumer/order(KisHashKeyAdapter·KisOrderInquireDailyCcldHttpAdapter·KisOrderInquireCcnlHttpAdapter) + consumer/ws(WS race fix·backfill hook) + OrderReconciliationWorker | 4~5 |
| P0 | backend-core | consumer Kafka config(DefaultErrorHandler + DeadLetterPublishingRecoverer) + DLT listener + Flyway V35·V36 + domain `OrderStatus.PENDING_RECONCILE` enum | 1~2 |
| P1 | backend-core | api/messaging(OrderEventOutbox + worker·NotificationOutbox STOMP_USER 채널 + Flyway V37·V38) + MDC TraceIdServletFilter + Kafka header propagation + logback pattern | 3~4 |
| P1 | frontend | WebSocket onConnect unread fetch + toast/모달 큐잉 | 1 |
| P2 | backend-core | OrderModifyController + KisOrderHttpAdapter.modify(RVSE_CNCL_DVSN_CD="01") + MarketSessionFsmGate AOP | 1~2 |
| P2 | market-data | KisAuthAdapter token mutex 재검증(변경 없을 수도 있음) | 0~1 |

**총 PR 수 추정**: 10~14건 · 사이클 3건 (v3.6.16·v3.6.17·v3.6.18)

### KIS-first raw 인용 (헌법 §3)

| 의제 | raw URL |
|---|---|
| #4 WS backfill | https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_ccnl/inquire_ccnl.py |
| #3 PENDING_RECONCILE | https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_ccld/inquire_daily_ccld.py |
| #11 hashkey | https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/auth/auth.py (getHashKey) + examples_user/domestic_stock/domestic_stock_functions.py L10097~L10213 |
| #6 정정 | https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions.py L10771~L10884 (def order_rvsecncl, RVSE_CNCL_DVSN_CD="01") |

### 사용자 결재 의제 (architect 권장 매트릭스)

1. **P2 격상 여부** (#6·#10) — 권장 = P2 유지 (UX·운영 편의, 자본 영향 X)
2. **WS race fix 패턴 (#4)** — pre-insert(kisOrderNo NULL 허용 + DB UNIQUE) vs Redis pending buffer → 권장 = **pre-insert** (DB single source of truth + 의존성 최소)
3. **DLT 알림 priority (#7)** — P0 즉시 vs P1 audit only → 권장 = **P0** (자본 lock 의제)
4. **v3.6.18 cut 시점** (P2) — 단독 cut vs W4 진입 직전 묶음 → 권장 = **W4 묶음** (의제 #6·#10 cost 낮음 + cycle 절약)

### 상태

⏳ spec 발행 단계 (PR `agent/architect/spec-order-path-stability-p0-p1-p2`) — 사용자 결재 후 P0 sub-agent dispatch 발사.

### 연관

- `_workspace/specs/2026-05-18-order-path-stability-spec.md`
- `_workspace/arch/adr/0084-order-path-stability-p0-p1-p2.md`
- ADR 0068 (T-O) §14-2·§14-5·§14-6 superset
- ADR 0070 (T-RECON+NOTIFY) outbox + reconcile 매트릭스 superset
- ADR 0077 (OPSP8996) WS 재연결 backfill 결합
- ADR 0044 (notification) DLT P0 + STOMP_USER 채널
- ADR 0013 (MarketSessionFsm) Gate 통합
- ADR 0038 (KIS OAuth) token mutex 재검증
- ADR 0041 (broker reconciliation) order reconciliation 패턴 정합
- ADR 0007 (Kafka backbone) outbox + DLT
- ADR 0008 (docker MSA) MDC 분산 추적

---

## §79. T-CC 신규 트랙 — 캔들 정합성 (chart-curator 발화 트랙 · 2026-05-19)

### 트리거

사용자 dogfooding evidence (최고 관리자 2026-05-19 KST):
- **한국석유(004090)** 일봉 last=2025-12-24 ≈5개월 stale vs 1분봉 last=2026-05-18 15:19 정상
- 단일 종목 내 timeframe 간 SLA 분기 발생
- 3 영역(market-data·strategy-engine·frontend) 교차 점유 단일 owner 부재 → ADR 0085 chart-curator 영입

### 트랙 정의

**T-CC** (Candle Curation) — 캔들 데이터 SLA·정합성 + 차트 시각화 트랙. chart-curator 단독 owner (ADR 0085).

### Phase 매트릭스

#### Phase 1 — dogfooding fix 즉시 (chart-curator 첫 발화)

1. **한국석유 일봉 backfill** — KIS `/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice` (tr_id: FHKST03010100) 호출 + 2025-12-25 ~ 2026-05-18 (≈5개월) 페이지네이션
2. **`last_candle_at` 모니터링 워커 신설** — daily watchdog 잡 + 모든 watchlist 종목 stale 임계치 검증
3. **stale 임계치 정책** — 일봉=3 영업일 / 주봉=2주 / 월봉=1~2개월 / 분봉=2분 (chart-curator SKILL.md 매트릭스 정합)
4. **검증** — dogfooding 사이클: `curl /api/candles?code=004090&interval=1d` last_candle_at=2026-05-18(직전 거래일) 회복

#### Phase 2-A — 자동 backfill 워커 + 갭 검출 (신규 책임) · ✅ 마감 (PR #525 머지 · v3.6.17 release 포함)

> **spec**: `_workspace/specs/2026-05-19-t-cc-phase2-auto-backfill-gap-detection-spec.md`
> **ADR**: 0086 Accepted (데이터 햇징 룰 매트릭스 13건 LOCK · amend 2026-05-19 §11·§12·§13 추가)

1. **갭 검출 알고리즘** — 종목별 timeframe별 영업일 캘린더(ADR 0013) `isBusinessDay()` 기반 expected sequence + DB actual sequence diff (윈도우 30 거래일 LOCK) · `CandleGapDetectorPort`(domain) + `CandleGapDetectorAdapter`(consumer) 신설
2. **자동 backfill 트리거** — 갭만 정밀 backfill (KIS REST 부분 호출 — 전체 재수집 대비 90%+ 효율) · `CandleBackfillWorker` 신설 + Phase 1 `LastCandleAtMonitor.checkAllCodes()` 1 line edit
3. **재시도 정책** — 3회 backoff (즉시 + 1s + 5s) + KillSwitch 트리거 (ADR 0043 정합) · 전체 failure 시 자동매매 중지 / 부분 failure 시 warn + Slack notice
4. **사용자 보고** — Slack outbox + audit_trail 5 path (`CANDLE_BACKFILL_START/SUCCESS/FAIL/SKIPPED/ROLLBACK`) + `backfill_batch_id` 추적
5. **★ 데이터 햇징 룰 매트릭스 10건 LOCK (신입 chart-curator 보호)** — 이중 검증·dry-run·rollback·idempotency·source-of-truth·갭 false positive/negative·재시도+KillSwitch·audit_trail·운영자 우회 (ADR 0086 §2-1 + chart-curator SKILL.md §데이터 햇징 룰 매트릭스)
6. **운영자 admin endpoint** — `POST /admin/candle-curator/backfill?code=...&interval=1d|1m&dry-run=true|false` · `CandleBackfillAdminController` 신설
7. **Flyway V39 micro** — `candle.backfill_batch_id VARCHAR(96) NULL` add-only (인덱스 0)

#### Phase 2-B — 햇징 룰 §11·§12·§13 구현 (chart-curator dispatch 대기 · 한국석유 PR #535 dogfooding evidence)

> **트리거**: ADR 0086 amend (2026-05-19) — 한국석유(004090) PR #535 dogfooding evidence + 사용자 명시 active gap detection 정합 룰 추가. ADR 본문: `_workspace/arch/adr/0086-t-cc-phase2-auto-backfill-data-hedging.md` §2-6·§2-7·§2-8.

**§11 구현 의제 (market-data 영역 — chart-curator 협업)**:
1. `KisRestCandleAdapter.parseKisDailyResponse` 반환 직전 `.sortedBy { it.ts.epochSeconds }` 1줄 add-only
2. `KisRestCandleAdapter.parseKisMinuteResponse` 동일 패턴 add-only
3. 향후 추가될 모든 KIS 캔들 응답 parse 함수 동일 LOCK
4. 단위 테스트 — DESC 응답 mock 입력 + ASC 정렬 결과 검증
5. evidence: 한국석유 PR #535 root cause — KIS DESC + sanity check reject 99건 → insertedCount=0

**§12 구현 의제 (chart-curator 영역)**:
1. `CandleGapDetectorAdapter.detectGaps()` 본문에 4축 검증 강화
2. expected ts list 생성 시 `market_calendar.is_business_day = TRUE` 의무 (현재 `BusinessDayCalendar.isBusinessDay()` LOCK이 1차 권위)
3. 각 ts마다 instrument status snapshot 조회 → 거래정지·정리매매·상장이전 ts skip
4. 위 가드 통과 ts에서 candle absence = gap 결과 리스트
5. `audit_trail.CANDLE_GAP_DETECTED` row 발행 (4축 통과 evidence inline)
6. 단위 테스트 — 4축 매트릭스 (각 축 FALSE일 때 skip 검증 + 모두 TRUE인데 candle 부재일 때 gap 검출)
7. **신입 chart-curator 잘못된 "5+ 영업일 임의 임계치" 제안 영구 차단** — 사용자 비판 인용

**§13 구현 의제 (chart-curator 영역 — frontend SymbolChart)**:
1. `SymbolChart.tsx` line 99·105 `chartApiRef`/`candleSeriesRef` → `useState`로 lift
2. cleanup 시 `setChartApi(null)` + `setCandleSeries(null)` → 자식 useEffect는 null 인식하면 진입 skip
3. 자식 컴포넌트(IndicatorOverlay·PatternMarker) props 타입 명시: `chart: IChartApi | null` + `series: ISeriesApi<'Candlestick'> | null`
4. 자식 useEffect 진입 가드: `if (!chart || !series) return`
5. evidence: 한국석유 PR #535 결함 #1 — React 18 StrictMode 이중 mount + ref mutation re-render 미발생 race

**dispatch**: chart-curator(§12 + §13) + market-data(§11) 영역 분리 병렬 가능. release v3.6.19 patch 또는 W4 묶음 흡수 후보.

**dispatch (Phase 2-A 마감 evidence)**: chart-curator 단일 PR (#525 머지) — domain port + consumer 어댑터 + 워커 + admin endpoint + Flyway V39 + Phase 1 1 line edit + unit test (햇징 룰 10건 검증 27개 ALL GREEN).

**release**: v3.6.17 (Phase 2-A 마감 · v3.6.17 release 포함) + Phase 2-B는 후속 patch 또는 W4 묶음.

#### Phase 3 — MTF 정합성 검증 (신규 책임 · 자동매매 룰 본체 결합)

1. **1분봉 → 5m·15m·30m·1h 합산 정합성 검증** — ADR 0062 합산 가공 5종 정합
2. **1분봉 → 일봉 집계 정합성 검증** — KIS 직접 조회 vs 1m 합산 결과 일치 검증
3. **daily watchdog 잡 신설** — 모든 watchlist 종목 MTF 정합성 검증 + 불일치 시 자동 backfill + 보고
4. **검증 매트릭스 문서** — `_workspace/chart-curator/mtf-integrity.md`

#### Phase 4 — Corporate Action candle adjustment (ADR 0018 cross-ref)

1. **액면분할·배당락·합병 시점 검출** — KIS corp action endpoint(market-data 어댑터 인터페이스)
2. **adjusted candle 발행** — raw 데이터 보존 + retroactive 정정 결과 별도 영속
3. **UI 토글** — raw/adjusted 표시 토글 (frontend 협업)

#### Phase 5 — TradingView LWC v4 확장 (W5 T-LWC 진입 시)

1. **multipane MTF 시각화** — 1m / 5m / 15m / 1h / 일봉 별도 chart 인스턴스 + crosshair sync
2. **plugin 확장** — 자체 도형·주석·57 candle pattern overlay (ADR 0025 정합)
3. **인터랙션 매트릭스** — zoom·crosshair·툴팁·drag 표준화

### KIS 캔들 endpoint 매트릭스 (KIS-first raw fetch 의무)

| endpoint | tr_id | timeframe | 우선순위 |
|---------|-------|----------|---------|
| inquire-daily-itemchartprice | FHKST03010100 | 일/주/월 | Phase 1 ★ |
| inquire-time-itemchartprice | FHKST03010200 | 분봉 (1·3·5·10·30분) | Phase 2 |
| inquire-time-conclusion | (tick) | tick | Phase 2 |
| inquire-daily-overtimeprice | FHPST02320000 | 시간외 (ADR 0076) | Phase 4 |

raw 출처: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/` + MCP `mcp__kis-code-assistant__*` 검증 의무.

### 사용자 결재 의제 (chart-curator 첫 발화 dispatch)

1. **dogfooding fix 즉시 dispatch 여부** — 권장: ADR 0085 머지 직후 즉시 chart-curator Phase 1 dispatch (한국석유 backfill)
2. **last_candle_at 모니터링 워커 배치 모듈 위치** — `batch` 모듈 vs `consumer` 모듈 → 권장: **batch 모듈** (daily watchdog 잡 정합)
3. **MTF 정합성 검증 cron 빈도** — daily 1회 vs hourly → 권장: **daily 1회** (KIS rate limit 보호)
4. **Phase 1·2·3 사이클 분리** — 단독 cut vs 묶음 → 권장: **Phase 1 즉시 hotfix patch (v3.6.17 또는 v3.6.18)** + Phase 2~3 별도 사이클

### 상태

- Phase 1: ✅ 마감 (PR #514 머지, 2026-05-19)
- Phase 2-A: ✅ 마감 (PR #525 머지 · v3.6.17 release 포함 · 햇징 룰 10건 LOCK + 27 단위 테스트 GREEN)
- Phase 2-B: 🚧 진행 중 (ADR 0086 amend 2026-05-19 — 햇징 룰 §11·§12·§13 추가 · chart-curator + market-data 영역 분리 병렬 dispatch 대기)
- Phase 3·4·5: ⏳ 후속 사이클

### 연관

- ADR 0013 (영업일 캘린더 — Phase 2 갭 검출)
- ADR 0085 (chart-curator 영입)
- ADR 0086 (T-CC Phase 2 — 자동 backfill + 데이터 햇징 룰 매트릭스 13건 · Accepted 2026-05-19 · amend §11·§12·§13 추가)
- ADR 0019 · 0028 · 0029 (캔들 저장 정책 / Ingestion Worker / Redis 캐시)
- ADR 0025 (LWC 확장)
- ADR 0048 (KIS WS 41건 구독 한도)
- ADR 0057 (KIS-only 데이터 출처)
- ADR 0062 (합산 가공 5종)
- ADR 0063 (NXT/통합거래 매트릭스)
- ADR 0018 (Corporate Action raw + adjusted)
- ADR 0037 (KIS API Rate Limit)
- ADR 0043 (KillSwitch 행동 정의 · Phase 2 KillSwitch 트리거)

---

## §80. `docs/architecture/policies/ai-trigger-gate.md` 발행 의제 (ai-engineer 후속 · 2026-05-19)

### 사유

PR #552 cross-validation evidence — `NaturalLanguageDslTranslator.kt:102` 부재 path `docs/architecture/policies/ai-options.md` 인용 발견. 정정 PR(본 PR)에서 인용은 실 존재 SoT(`_workspace/ai/prompt-catalog.md`)로 교체했으나, **현행 권위 정책 SoT(`docs/architecture/policies/`)에 AI 옵션 트리거 게이트 단일 정책 페이지 부재** → architect SKILL §6 정책 위치 LOCK 정합 gap.

### 발행 안

`docs/architecture/policies/ai-trigger-gate.md` (architect 영역 발행 — ADR-0053 living policy 체계 §6 정합):

- §1. AI 옵션 게이트 4단 (endpoint 503 · `MAGICJAR_AI_ENABLED` · `mock-mode` auto/mock/real · 화이트리스트 regex)
- §2. provider LOCK (anthropic 단독, OpenAI 도입 시 별도 ADR)
- §3. 사용자 트리거 endpoint 카탈로그 (`POST /api/ai/dsl/translate` 현행 + W4 baseline 분석 5종 발행 예정)
- §4. 비용 통제 (`ai_analysis_log` V17 + Spring AI raw metadata 처리 한계)
- §5. 룰 5 (W1·W2·W3 LLM provider import 자제) 예외 패키지 매트릭스 (`api/ai/dsl/` 1건)

권위 출처: ADR 0026·0023 + spec PR-35 §5-1 + `prompt-catalog.md` §1.

### 트리거

- **우선순위 ↑**: W4 T-AI baseline 진입 직전 1회 발행 의무 (분석 5종 endpoint 카탈로그 합류 시점)
- **우선순위 ↓**: 사용자 명시 트리거 또는 dogfooding에서 ai-trigger-gate 정책 부재 결함 재발견 시 즉시
- 발생하지 않으면 W4 진입 첫 PR(architect)에 합류

### 예상 처리 PR

architect 단독 PR — `docs(policies): AI 옵션 트리거 게이트 단일 정책 페이지 신설 (ai-trigger-gate.md)`. 본 PR(ai-engineer P2-3 정리)은 인용만 정정 + 발행 backlog 등록까지가 영역 LOCK.

### 연관

- ADR 0026 (AI 옵션 — 사용자 트리거 한정 매매 결정권 0)
- ADR 0023 (AI 사용자 트리거 helper 정책)
- ADR-0053 (현행 권위 정책 living policy 체계)
- PR-35 spec §5-1 (KIS 텍스트 1줄 표현식 LOCK)
- `_workspace/ai/prompt-catalog.md` §1·§3 (광고-실재 매트릭스)
- `feedback_release_policy_patch_first` (v3.7=W4 마감 정합)

---

## §81. 차트 캔들 히스토리 limit 정책 + Lazy Backfill + 사용자 입력 봉수 (ADR 0093 · 2026-05-20)

### 사유

사용자(최고 관리자) 2026-05-20 KST 명시 결함 보고 — "차트에서 보이는 캔들이 너무 적다. 일반 증권앱은 과거로 한참 스크롤 가능한데 magicJar는 막힌다." 코덱스 협업 답변 정합 4 의제 통합.

**현행 결함 evidence**:
- `api/service/CandleRefreshService.kt:87` `DAILY_MAX_PAGES=6` × KIS 100건 = 600봉 cap (사용자 수동 trigger)
- `consumer/infrastructure/kis/KisRestCandleAdapter.kt:119` `MAX_PAGES=6` 동일 cap (consumer cursor pagination)
- `api/controller/SymbolController.kt:116~135` `GET /candles` endpoint `before`+`limit(default 200, max 500)` 부분 완비 but DB 모수 부족
- LWC `subscribeVisibleLogicalRangeChange` 가드 2단(backlog §30 fix) 존재 but 실작동 dogfooding 미실시

코드 주석 cover: D 2.5년 / W 11년 / M 50년 / Y 600년 — 일봉 2.5년은 일반 증권앱 (10년+) 미달.

### ADR 0093 결정 매트릭스

| 항목 | 결정 |
|------|------|
| 기본 patten | **Lazy backfill** (대안 B) — A(단순 limit 확장) 거부 / C(백그라운드 prefetch) 거부 / D(streaming) 거부 |
| Default 봉수 | 1d=300 · 1wk=200 · 1mo=120 · 1y=50 · 1m=600 · 5m~1h=480 |
| Max cap | 1d=5000 · 1wk=2000 · 1mo=1200 · 1y=600 · 1m=4800 · 5m~1h=3840 |
| 사용자 입력 UI | select(300/600/1200/3000) + 직접입력 모달 + localStorage 전역 1개 |
| API endpoint | 기존 `GET /candles?before=&limit=` 재사용 + `limit` cap 500 → 5000 |
| MAX_PAGES 상수 | property 외부화 (`magicjar.candle.max-pages-by-interval` map) |
| ADR 0037 Rate Limit | baseline LOCK으로 cover (추가 정책 0) |
| 마이그레이션 | 0 (add-only) |

### Phase 분리 dispatch 매트릭스 (5 PR)

| Phase | sub-agent | 영역 | 산출물 | 예상 PR |
|-------|-----------|------|--------|---------|
| 1 ✅ | architect | ADR + backlog | 본 ADR 0093(Proposed) | 본 PR |
| 2 | chart-curator | spec 발행 | `_workspace/specs/2026-05-20-chart-candle-history-limit-spec.md` | 1 |
| 3 | backend-core | api/consumer property화 + cap 확장 | `application.yml` + `CandleRefreshService` + `KisRestCandleAdapter` (~50 LOC) | 1 |
| 4 | frontend | 봉수 input UI + localStorage | `SymbolChart` 헤더 select + 직접입력 모달 + clamp 검증 (~80 LOC) | 1 |
| 5 | chart-curator | LWC 가드 강화 + dogfooding | `LightweightChart.tsx` `subscribeVisibleLogicalRangeChange` 가드 보강 + max cap toast + 실작동 evidence | 1 |

### chart-curator 1차 lens 의무 (ADR 0087 페르소나 매트릭스 정합)

자본 path 직결 차트 영역 = 보수 lens 우선 — backend-core(Phase 3) + frontend(Phase 4) PR 모두 chart-curator 🔴/🟡/🔵 검토 후 머지. chart-curator Phase 2 spec + Phase 5 dogfooding은 single owner LOCK(ADR 0085 §책임 영역).

### 트리거

- **우선순위 ↑**: 사용자 결함 보고 직후(2026-05-20) → 다음 즉시 사이클(v3.6.x patch 또는 W4 진입 직전 묶음)
- chart-curator spec 발행(Phase 2) 1순위 → 그 후 backend-core·frontend 병렬 dispatch 가능(Phase 3·4 add-only 충돌 없음 — `feedback_orchestrator_active_parallel` 정합)
- Phase 5(LWC 가드 + dogfooding)는 Phase 3·4 머지 후 직렬 — 실 사용자 path 작동 evidence 의무

### 예상 cut

v3.6.x patch (Phase 1·2 머지 시 spec 사이클 cut + Phase 3·4·5 묶음 후 별 patch cut) — `feedback_release_policy_patch_first` 정합 (v3.7=W4 마감 이전 patch만).

### 연관

- ADR 0019 (Watchlist 기반 캔들 저장 정책 — DB row 누적 8배 영향 monitoring 의무)
- ADR 0025 (LWC 확장)
- ADR 0028 (Ingestion Worker 1~5초 polling — 영향 없음, 본 ADR은 조회 path만)
- ADR 0029 (당일 진행 중 캔들 Redis 캐시 — 현행 LOCK)
- ADR 0037 (KIS API Rate Limit — baseline LOCK으로 cover)
- ADR 0085 (chart-curator single owner — Phase 2·5 LOCK)
- ADR 0086 (데이터 햇징 룰 14건 — §14 helper class 분리 + §6 passive 가드 정합)
- ADR 0087 (페르소나 매트릭스 — chart-curator 보수 lens 1차)
- ADR 0091 (NXT resolver — 영향 없음, 본 ADR은 candle 모수만)
- backlog §30 (LWC false trigger 가드 evidence — 가드 2단 LOCK 유지)
- 사용자 메모리 `feedback_dev_priority_qa_batch` (개발 진도 우선 + QA 일괄)

---

## §57. KIS EGW00123 self-heal 잔여 어댑터 확장 (2026-05-21 신설 · backend-core)

### 사유

PR #641 (hotfix) — `ApiKisBalanceQueryAdapter` 에 EGW00123 catch + `tokenHelper.evictToken` + 1회 재시도 self-heal 패턴 적용 완료. dogfooding 09:43~09:44 시점 stale REAL 토큰 무한 폭주 root cause 차단.

그러나 동일 helper(`KisTokenCacheHelper`)를 쓰는 다른 어댑터들은 catch 분기 미적용 — 동일 결함 재현 가능:

- `api/src/main/kotlin/kr/spdaewoon/magicjar/api/adapter/kis/KisRestPriceMetricsAdapter.kt:62` — `tokenHelper.resolveTokenAndKeys(userId, REAL)` 사용, EGW00123 catch 없음
- `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/CandleRefreshService.kt:821` — 동일 helper 호출, 동일 catch 분기 부재
- consumer 모듈 `KisAuthAdapter`·`KisBalanceAdapter`·`KisReconcileBalanceAdapter` 등도 같은 토큰 prefix 공유 — 동일 결함 보유 추정 (확정은 별도 audit)

### 보강 안

PR #641 패턴 그대로 복제:
1. catch (WebClientResponseException) 분기에 body `EGW00123` 검출 시 `tokenHelper.evictToken(userId, accountType)` + retried flag 1회 재시도
2. 어댑터별 단위 테스트 — `ExchangeFunction` mock 패턴(`ApiKisBalanceQueryAdapterTest` 인용)
3. 가능하면 helper에 `executeWithSelfHeal` 같은 inline 함수 추가로 boilerplate 축약 검토 (다만 어댑터 시그니처 다양해서 사례 검증 후 결정)

### 예상 LOC

어댑터당 ~50, 테스트당 ~80. 5 어댑터 = ~650 LOC.

### 트리거

- **우선순위 ↑**: 자가 치유 없는 결함 — 다음 사이클 dispatch 권고 (P0 잔재)
- v3.6.x patch 후보

### 연관

- PR #641 (hotfix · 2026-05-21 머지 `775c87fe`)
- ADR 0038 (KIS 분산락 패턴 — helper 골격 유지)
- ADR 0081 (T-ACCT)
- `KisTokenCacheHelper.evictToken` (신규 public API · 2026-05-21)

---

## §58. KIS EGW00201 폭주 차단 — 클라이언트 backoff/circuit-breaker (2026-05-21 신설 · backend-core + frontend)

### 사유

PR #641 self-heal 도입으로 EGW00123 root cause 차단됐으나, frontend 폴링이 502를 받고 즉시 재시도 → KIS rate limit 트리거(EGW00201) 부수효과는 별개 결함으로 잔존. 본 hotfix에선 명시 제외.

§0-A (Bucket4j Phase 2·3) 합류 시 자동 처리될 수 있으나, 어댑터별 EGW00201 명시 backoff + frontend useAccountBalance 재시도 정책 갱신은 별도 검토 필요.

### 보강 안

- backend: 어댑터 catch에 EGW00201 검출 시 1초 backoff + 1회 재시도 (Phase 2 합류 시 통합)
- frontend: `useAccountBalance` 502 응답 시 즉시 재시도 금지, exponential backoff (1s → 2s → 4s, max 3회)

### 트리거

- §0-A Phase 2 합류 후 backlog 흡수 또는 별도 사이클

### 연관

- §0-A Phase 2 (Bucket4j KIS Rate Limit)
- PR #641 (self-heal hotfix — EGW00123만 처리)

---

## §59. candle venue 컬럼 도입 + KIS J/X path 분리 (QA 첫 임무 root fix · 2026-05-22 신설 · backend-core + market-data)

### 사유

QA 첫 임무 evidence (2026-05-21 보고서 `_workspace/reports/2026-05-21-qa-first-mission-symbol-detail.md`):
- SK하이닉스 2026-05-21 candle DB row close=1,918,000 vol=4,850,226 (NXT 야간 의심)
- baseline 2026-05-19 close=1,719,000 vol=7,805,248 (정규장 정합)
- 비율 4,850,226 / 7,805,248 = **62%** → PR #597 휴리스틱(10%) 통과 + PR #682 강화(30%) 통과
- 즉 **휴리스틱 자체로는 venue 구분 불가능한 경계 case 잔존**

본 §59 P1 의제는 옵션 B(QA 보고서 §6) — **근본 fix**:
- candle 테이블에 venue 컬럼 도입 → KIS J(KRX 정규장) vs X(NXT)을 schema level 분리
- 휴리스틱 폐기 + 정확한 single source of truth

### 옵션 A 즉시 fix (본 PR · #682) — 완료
- ✅ NXT 휴리스틱 30% 임계 강화 (`SymbolMetaService.kt`)
- ✅ stale 30분 시간 경과 검증
- ✅ 한계: 62% 경계 case 잔존 (SK하이닉스 evidence)

### 옵션 B 본격 fix (본 §59 backlog) — 추후

#### 산출물

1. **Flyway migration** — `candle` 테이블에 `venue VARCHAR(10) NOT NULL DEFAULT 'KRX'` 컬럼 추가
   - UQ 변경: `(code, interval, ts)` → `(code, interval, ts, venue)` (NXT/KRX 같은 ts 별도 row 허용)
   - backfill: 기존 row 모두 `venue='KRX'` 기본값 + NXT 의심 row(`vol < prev_vol * 0.30`) 보정 별도 migration
2. **KIS chart adapter 2 path 분리** — `fid_cond_mrkt_div_code`
   - 현재: `UN` (통합)
   - 신규: `J` (KRX 정규장) + `X` (NXT)
   - 각 path 별도 호출 + 응답 row에 `venue` 부여 후 INSERT
3. **SymbolMetaService candle 조회 시 `venue='KRX'` 필터** — 휴리스틱 폐기
4. **chart·indicator·pattern consumer 영향 평가** — 모두 정규장 source 의도면 `venue='KRX'` 필터 일괄 추가
5. **NXT venue 활용 path 별도 검토** — 시간외 분석·NXT 분리 차트 등 (P2)

### 영향 모듈

- backend-core (lead — schema migration + SymbolMetaService 필터 + candle query API)
- market-data (KIS chart adapter J/X 2 path 분리 + venue 응답 부여)
- batch (CandleRefreshService backfill — venue 매트릭스)
- consumer (CandleClosedStompListener — venue=KRX 필터 일괄)
- indicator-impl + strategy-engine (input candle filtering — 외부 호출자 영향)
- frontend (LWC v4 chart — venue tab 미래 검토 P2)

### 트리거

- v3.8.x sunset 시점 (운영 단계 점진 고도화)
- 또는 QA 시나리오에서 30% 임계 회귀 재발견 시

### evidence

- QA 첫 임무 보고서: `_workspace/reports/2026-05-21-qa-first-mission-symbol-detail.md` §6 옵션 B
- PR #682 (본 옵션 A 즉시 fix · QA 첫 임무 P0 hotfix)
- 보강 테스트: `api/src/test/.../SymbolMetaServiceNxtStaleTest.kt` (62% 경계 case명시 — venue 분리 P1 정당화 evidence)

---

## §82. 2026-06-04 주문내역 dogfooding 결함 3건 (시그널↔주문 전수 대조)

> 2026-06-04 dogfooding — 실전 주문내역과 시그널/체결을 전수 대조하여 발견. P1 결함 3건 + 후속 ADR 의제 + 정상 확인 3건. 결함 1은 본 § 발화 PR(backend-core)에서 fix, 결함 2·3은 병렬 에이전트 PR에서 처리.

### 결함 1 — 백필이 이미 FILLED 주문을 재마킹 (completed_at 덮어쓰기 + FULL_FILL audit 중복) · P1 · ✅ 본 PR fix (backend-core)

**근본 원인** — `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/order/KisExecutionBackfillService.kt`:
- KIS WS가 매시 정각 끊기고 재연결 → `onReconnect` hook이 매시간 backfill 실행
- `persistBackfillRowDetailed()`가 skip 판정(ALREADY_SYNCED — event_id dedup 라인 333~337, 누적 이하 라인 347~357)을 내려도 **`syncOrderStatusAfterBackfill()`을 무조건 호출**
- `syncOrderStatusAfterBackfill()`이 이미 FILLED인지 가드 없이 `orderRepo.markCompleted(orderId, now, FILLED)`(라인 460) 재실행 → **completed_at을 now로 덮어씀** + FULL_FILL audit(라인 466~476) 재발행

**evidence** — FILLED 주문 11건의 `completed_at`이 전부 13:00:11로 동일(실체결 09:00:26~10:02:05는 `order_execution.executed_at`에 보존). FULL_FILL audit 53건(정상이라면 11건).

**fix (보수적 최소 변경 — ADR 0084 P0-3 백필 설계 보존)**:
- `syncOrderStatusAfterBackfill()`에서 신규 execution INSERT 없는 호출(execution == null, ALREADY_SYNCED 경로)이고 status가 이미 종결(FILLED·CANCELLED·REJECTED·EXPIRED)이면 no-op (markCompleted·audit 0). PENDING_RECONCILE은 의도적으로 제외 — 후속 정상화 경로 보존.
- 보존: (a) PARTIALLY_FILLED→FILLED 정상 전환(신규 execution 합류 시), (b) WS 누락 체결 backfill 보정(ord 126 사례), (c) EXECUTION_OVERFLOW 분기, (d) PENDING_RECONCILE 후속 정상화
- 데이터 보정 스크립트: `scripts/ops/2026-06-04-restore-completed-at.sql` (orchestrator 머지 후 실행)
- 회귀 테스트: `KisExecutionBackfillServiceTest` (j)(k) 케이스 추가

### 결함 2 — 장운영시간 게이트 누락 · P1 · strategy-engine 병렬 PR

- `consumer/.../RealtimeSignalGenerator` 계열이 장 마감 후에도 시그널을 평가·발행할 여지. 장운영시간(09:00~15:30 KST + 동시호가 경계) 게이트 정합 필요.
- 후속: ADR 0066·0068 amend로 장운영시간 게이트 본문화 (architect).

### 결함 3 — TickToMinuteAggregator 분 경계 race · P1 · market-data 병렬 PR

- `consumer/.../TickToMinuteAggregator` 분 경계 전환 시점 race condition으로 마지막 tick이 다음 분봉으로 새거나 누락될 여지.

### 후속 의제 (architect)

- ADR 0066·0068 amend — 장운영시간 게이트 본문화 (결함 2 정합).

### 정상 확인 3건 (전수 대조 결과 정상)

- 전략 8건 PAUSED — 사용자 직접 조작(자동정지 아님). 정상.
- ord 126 — WS 누락 체결을 backfill이 보정한 정상 흐름 (결함 1 fix가 이 흐름을 보존해야 함).
- ord 124 — gap 발생 후 롤백 정상 처리.

### 영향 모듈

- backend-core (lead 결함 1 — KisExecutionBackfillService 종결 상태 재마킹 가드)
- strategy-engine (결함 2 — 장운영시간 게이트)
- market-data (결함 3 — TickToMinuteAggregator 분 경계 race)
- architect (후속 — ADR 0066·0068 amend)

---

## §83. Gemini 연동 하이브리드 — MCP protocol 승격 + Gemini 웹 export 팩 (2026-06-06 신설 · 사용자 A안 결재)

> 2026-06-06 사용자 의제 — "Gemini한테 프로젝트를 연동시키고 싶다. 우리 프로젝트가 MCP로 데이터 넘겨줄 때가 됐다." brainstorming으로 A안(하이브리드) 결재 후 백로그 보존 (사용자: "A로 할건데 일단 백로그에 남기자").

### 사유 (현황 갭)

- T-MCP는 이미 상당 부분 구현 — `api/.../api/mcp/` McpController + tool 6종(get_candles · run_screener · get_journal · explain_indicator · backtest_strategy · get_market_trend) + ApiKeyAuthFilter + mcp_audit(V52) 전부 존재 (ADR 0027 정합).
- **그러나 현 구현은 "MCP 정합 simplified HTTP REST"** (`GET /api/mcp/tools` + `POST /api/mcp/tools/{name}`) — 진짜 MCP protocol(JSON-RPC 2.0 over stdio / Streamable HTTP)이 아니라서 **Gemini CLI·Claude Desktop·Cursor 등 MCP 클라이언트가 직접 붙을 수 없음** (McpController.kt 주석 명시 "MCP `tools/list` 정합 simplified").
- Gemini 웹/앱(gemini.google.com)은 **커스텀 MCP 커넥터 자체가 미지원** (2026-06 실측 — Gemini Enterprise StreamableHTTP Preview는 유료 엔터프라이즈 전용·기각). 따라서 웹용은 별도 우회 경로 필요.

### 사용자 목적 3건 (브레인스토밍 확인)

1. 매매 데이터 분석 세컨드 오피니언 (매매일지·백테스트·시그널 이력)
2. 프로젝트 아키/코드 질문 상대 (Claude Projects 유사 용도)
3. 실시간 시세/스크리너 온디맨드 조회 — **MCP 클라이언트(CLI)에서만 가능** (웹 제약)

### 보강 안 (A안 — 하이브리드 2갈래)

**갈래 1 — T-MCP protocol 승격 (실시간 조회 + ADR 0027 비전 완성):**

- 현 simplified REST 위에 **진짜 MCP protocol 어댑터 층** 추가 — 기존 tool 6종·ApiKeyAuthFilter·McpAuditService 그대로 재활용, protocol 어댑터만 신규.
- 구현 옵션 (W4 본격 진입 시 architect 결정): (a) Spring AI MCP server starter 기반 Streamable HTTP endpoint, (b) 얇은 stdio bridge 스크립트(`scripts/*-mcp-bridge.mjs`)가 stdio MCP ↔ 기존 REST proxy — 기존 `scripts/claude-discovery-bridge.mjs` 패턴 참고.
- 클라이언트: Gemini CLI(2026-06-18부터 Antigravity CLI 승계) + Claude Desktop/Code + Cursor — ADR 0027 "우리가 server, AI가 client" 원문 비전 그대로.
- read-only 6종 유지·write tool 금지·localhost 한정·API key 인증 — ADR 0027 §2-2·§2-4 LOCK 불변.

**갈래 2 — Gemini 웹용 export 팩 (세컨드 오피니언 + 아키 질문):**

- `scripts/gemini-export.mjs` (가칭) — 매매일지 digest + 백테스트 성과 + 시그널/주문 이력 요약 + 아키텍처 요약(기존 baseline html·master.html 재활용)을 한 묶음으로 생성.
- 사용자가 Gemini 웹 **Gem**(커스텀 지식 파일)에 업로드 → 갱신 시 재업로드. live 연결 아님(snapshot 기준)을 사용자 인지 완료.
- mock 합성 금지 — 데이터 없으면 "데이터 없음" 명시 (기존 정책 정합).

### 트리거

- 사용자 "Gemini 연동 시작하자" / "MCP 마무리하자" 발화 시
- 또는 W4 T-MCP 잔여 마감 사이클 진입 시

### 영향 모듈

- architect (protocol 어댑터 위치 결정 — ADR 0027 amend 가능성: api 내 Streamable HTTP vs stdio bridge 스크립트)
- backend-core (갈래 1 — MCP protocol 어댑터 층)
- ai-engineer (갈래 2 — export 팩 digest 구성)
- qa (MCP 클라이언트 실연동 검증 — Gemini CLI/Claude Code 실측)

### evidence

- 기존 구현: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/mcp/` (tool 6종 + auth + audit), `V52__mcp_audit.sql`
- ADR 0027 (Accepted · 2026-05-24 amend — tool 6종 + V52 LOCK)
- Gemini 웹 MCP 미지원 확인: Gemini Apps Community 스레드 + Gemini Enterprise custom MCP docs (2026-06-06 WebSearch 실측)

## §84. DSL 포지션 인지 지표 — 수익률 조건 (2026-06-08 신설 · 사용자 D안 과제 지정)

### 사유

잔익절 방어 논의(ADR 0114 후속)에서 사용자 D안: 매도전략 DSL에 "수익 X% 이상일 때만" 같은 포지션 인지 조건을 직접 쓰고 싶음. 현재 DSL은 캔들 기반 지표만 지원 — 포지션 상태(평단·수익률·보유 캔들 수)를 참조하는 식 불가.

### 트리거 조건

- 사용자가 전략 DSL에서 `profit_pct`·`hold_bars`·`avg_price` 류 식별자 요구 시
- 또는 minHoldBars(B안)로 부족한 케이스 실증 시

### 보강 안

- DSL 평가 컨텍스트에 position snapshot 주입 (`StrategyEvaluator` 입력 확장 — live/백테스트/리플레이 3원 동일 주입 의무)
- 신규 식별자: `profit_pct`(평단 대비 현재 수익률) · `hold_bars`(보유 캔들 수) · `entry_price`
- 백테스트는 가상 포지션 state로 동일 재현 — ScaleInExitCalculator와 책임 경계 주의 (라인 청산은 calculator, DSL 조건은 evaluator)

### 예상 처리

strategy-engine 1 사이클 (R1 evaluator 입력 확장 → R2 식별자 3종 → R3 백테스트 정합)
