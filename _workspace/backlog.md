# magicJar Backlog (미뤄둔 의제)

> **위치**: `_workspace/backlog.md`
> **관리자**: orchestrator (사이클 시작 시 점검 의무) + architect (사용자 의제 흡수 시 갱신)
> **형식**: 항목 = 사유 + 트리거 조건 + 보강 안 + 예상 처리 PR 또는 사이클
> **신설일**: 2026-05-07 (PR-10 사이클 — 사용자 명시 "미뤄두는 의제들 관리해줘")

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

## 운영 메모

- **orchestrator 책임**: 사이클 시작 시 본 backlog 점검 의무 — 우선순위 ↑ 항목 first 처리
- **사용자 책임**: dogfooding 결과 → §6 sub-section 추가 (또는 orchestrator에 통보)
- **architect 책임**: ADR 발행 시 본 backlog cross-reference 의무 (예: ADR 0050 보강 시 §1 처리 + 항목 close)
- **항목 close 정책**: 처리 완료 PR 머지 시 backlog에서 제거 OR `### §X. ✅ 처리 완료` 마킹 + 변경 이력 갱신

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

