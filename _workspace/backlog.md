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

## §22. DART OpenDART CorpCode ZIP — handshake_failure (v3.1.0 dogfooding)

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

## §24. 종목상세 화면 dogfooding 결함 묶음 (v3.2 일괄 정정)

### 트리거

PR-31 (PR #249·#250) 머지 후 사용자 dogfooding 결과: 6+1건 결함 1차 정정 완료. 다음 dogfooding에서 추가 3건 발견. 단일 사이클로 잡기엔 분봉 backfill 잡 재설계가 들어가서 v3.2 W2 트랙과 묶어 일괄 처리.

메모리 `feedback_dev_priority_qa_batch.md` 정합 — "단일 사이클 hotfix·테스트 mock·minor 정정에 매달리지 말 것. backlog 흡수 + 다음 트랙. QA는 여러 사이클 누적 후 연관성 묶어 일괄".

### 결함 7: 호가 polling fallback 부재 (frontend + market-data)

- 현재: STOMP 미연결 시 호가 polling fallback 메커니즘 없음. ADR 0048 polling은 시세에만 적용, 호가에 미적용.
- 수정 영역: frontend(SymbolOrderbook polling) + market-data(KIS REST `inquire-asking-price` 어댑터 신규).
- v3.2 W2 트랙 적합 — KIS REST 신규 어댑터 추가 작업.

### 결함 8: 분봉/15m/30m/90m 데이터 적재 부실 — market-data 영역 ★

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
