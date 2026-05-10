# magicJar v3.2.0 Baseline — W2 마감 시점 종합 기능 정의서

> **태그 컷**: 2026-05-10 v3.2.0 (main `950420b` 시점, `release/v3.2` ADR 0031 immutable)
> **사이클**: PR-31 ~ PR-45 (#236 ~ #273, ~38 PR · 약 2~3일 단축 사이클)
> **상태**: PR-46 architect 영역 본격 작성 완료. backend-core(02·04) · market-data(03) · frontend(05) 영역은 sub-agent 별도 디스패치.
> **권위**: 본 baseline 폴더는 v3.2.0 시점 **종합 기능 정의서**. SoT는 `docs/architecture/master-architecture.md` v2 (ADR 0030).

---

## 한 장 요약

magicJar는 **룰 기반 quant trading 시스템**(룰 본체 + AI 옵션 + MCP 서버 미래)으로, v3.2.0 시점 **W2 ★ 트랙 4건 풀세트 클리어**를 달성했다. v3.1.0 → v3.2.0 사이 ~38 PR + ADR 4건 신규(0052·0053·0054·0055) + Flyway V9·V10·V11 + **JVM 모듈 4 → 5 전환(pattern-engine 라이브러리 신설)** + Strategy Builder UI(GUI 블록 빌더 1차) 발행.

**v3.2.0 시점 동작하는 핵심 흐름** (W1 6건 + W2 4건 통합):

1. **W1 풀세트 (v3.1.0 baseline 그대로 보존)** — T-ONBOARD · T-W1 · T-W2 · T-S1 · T-MS1 · T-CA1 · T-IM1 (6 트랙). 셋업 마법사 + 관심종목 + 실시간 시세 + 종목상세(LWC + 호가 STOMP) + 영업일·휴장 + 수정 주가 + 종목 마스터.
2. **T-IND (Indicator 80지표)** — `domain/indicator/*` port 7개 + `KisStrategyBuilderSource` (KIS open-trading-api `indicators.py` 1507 lines 1:1 port — 80 calc 함수) + `NativeIndicatorSource` 5종(MA·EMA·RSI·MACD·BB) + `IndicatorEngine` cache-aside (Redis 분봉 5분/일봉 1일 TTL) + `IndicatorController` REST + frontend 차트 overlay + `IndicatorPanel` 토글 + `useIndicators` hook (TanStack Query).
3. **T-DSL (Strategy DSL 텍스트 파서 + 평가 엔진)** — `domain/strategy/*` 23+ sealed class · enum · data class (KIS `parser.py` 664 lines 1:1 port + 3 rename) + `KisYamlDslParser` 21 token 정규식 + 6단 재귀 하강 + AST 빌더 + static 검증 visitor + complexity 4단(LOW/MEDIUM/HIGH/CRITICAL) + `JvmStrategyEvaluator` ADR 0047 sandbox(timeout 100ms · CPU budget 50ms · strategy-eval-pool 4 fixed) + auto-disable + `StrategyController` REST 7 endpoint + Flyway V9·V10·V11 + `NaturalLanguageDslTranslator`(자연어→DSL helper, ADR 0023 사용자 트리거 한정 + Spring AI Anthropic + MockDslHeuristic 9 패턴 fallback).
4. **T-PAT (57 캔들스틱 패턴 인식기)** — `domain/pattern/*` 7 port/data class + `PatternCatalog` 57 LOCK + `JvmPatternRecognizer` (`@Component("patternRecognizer")` Map registry — KIS `candlestick.py` 755 lines 1:1 port — `_simple_triple` 7 alias delegation + `tasuki_gap` 3 alias) + `detector/{single 14 / pair 16 / triple 27}` 57 분할 (R1 active 10 + R2 stub 47) + `util/CandleMath` KIS 7 helper + `helper/SimpleTripleHelper` + `helper/ConsecutiveDirectionCalculator`(magicJar own — DSL `consecutive("up"|"down")` 호환) + `RedisPatternCacheAdapter`(분봉 5분 / 일봉 1일 TTL) + `PatternController` REST + DSL `pattern_detected("name")` 토큰 신규 + frontend `PatternMarker` LWC v4 마커 overlay + `PatternToggleMenu` 57 catalog picker + `usePatterns` hook.
5. **T-SB (Strategy Builder UI — GUI 블록 빌더 1차)** — B+C-lite 하이브리드(트리 폼 빌더 본체 + textarea DSL 토글 — Reactflow·Monaco 거부, **의존 추가 0**, ADR 0055) + 27 신규 frontend 파일(`lib/dsl/{blockTree·serializer·parser·yaml·catalogIds}` + `api/{strategy·dslTranslate}` + `hooks/{useStrategy·useStrategyTranslate}` + `components/strategy/` 8 컴포넌트 + `pages/Strategy/StrategyBuilderPage`) + `/strategy/builder` 라우트 신규 + `INDICATOR_CATALOG` 80건 · `PATTERN_CATALOG` 57건 정합 + KIS .kis.yaml export/import + 자연어 모달 + DSL 직접 편집 모달.
6. **pattern-engine 라이브러리 모듈 신설 (ADR 0054 · 4 → 5 모듈)** — `consumer/.../application/pattern/` 62파일을 `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/`로 git mv (히스토리 보존) + 패키지 리네이밍 + `pattern-engine` 라이브러리 jar(Spring Boot main 0) + api·consumer 양쪽 `implementation(":pattern-engine")` 의존 추가 → api 컨테이너에서 `PatternRecognizer` 빈 등록 정상 + v3.1.2 hotfix `@ConditionalOnProperty` 임시 게이트 자연 제거. 컨테이너 수 불변(api·batch·consumer 3개).

**v3.2.0 누적 ADR 55건** (v3.1.0 베이스 51건 + 신규 4건 — 0052 baseline 사이클 정형화 · 0053 Living Policy Docs · 0054 pattern-engine 모듈 · 0055 Strategy Builder UI 컴포넌트 스택). ADR 0004 갱신(4 → 5 모듈).

**MD 룰 정책 진화 추가 4건**: Living Policy Docs(`docs/architecture/policies/`) 도입 · 단일 진입점 인덱스(`_workspace/INDEX.md`) · baseline 사이클 정형화 풀세트(skill + slash + 스크립트 4종 — `scripts/baseline-init.sh`·`baseline-render-html.sh` · slash command `/baseline-init`·`/baseline-render`) · 모듈 경계 갱신 ADR 발행 의무 강조.

**다음 Wave**: W3 진입 — Backtest · Screener · Signal Engine · Risk Guard · Order Executor · Reconciliation · Performance · Notification (T-BT · T-SC · T-SIG · T-RISK · T-O · T-RECON · T-PERF · T-J).

---

## 인덱스

| 파일 | 작성자 | 내용 | 상태 |
|------|--------|------|------|
| **[01-architecture.md](01-architecture.md)** | architect | 시스템 전경(Mermaid) + 모듈 책임 매트릭스(5모듈) + 외부 통합 매트릭스(KIS·Yahoo·DART·Naver·Anthropic) + Flyway V1~V11 + frontend 라우팅 + deployment 토폴로지 | 본격 작성 완료 (PR-46) |
| [02-data-flow.md](02-data-flow.md) | backend-core | 모듈별 데이터 흐름(REST·Kafka 토픽 매트릭스·STOMP·Redis 캐시) — W2 트랙 흐름 add | sub-agent 디스패치 |
| [03-use-sequences.md](03-use-sequences.md) | market-data | 사용자 유즈 시퀀스 (셋업·Watchlist·Symbol·시세 fallback·corp action) + W2 신규 시퀀스(Indicator 조회 · DSL 검증/평가 · Pattern 마커 · Strategy Builder 저장) + KIS-first raw 출처 | sub-agent 디스패치 |
| [04-feature-spec.md](04-feature-spec.md) | backend-core | W1 7 트랙 기능 명세(보존) + W2 4 트랙 기능 명세 추가(T-IND · T-DSL · T-PAT · T-SB) | sub-agent 디스패치 |
| [05-ui-inventory.md](05-ui-inventory.md) | frontend | 화면·메뉴·버튼 인벤토리(보존) + W2 신규 화면(SymbolDetail IndicatorPanel + PatternToggleMenu) + StrategyBuilderPage 8 컴포넌트 + 라우트 `/strategy/builder` | sub-agent 디스패치 |
| **[06-history-traceability.md](06-history-traceability.md)** | architect | PR·ADR·기능 3축 추적성 매트릭스 (v3.1.0 → v3.2.0 38 PR + 4 ADR 신규 + Flyway V9~V11 + 모듈 4→5 + W2 4 트랙) | 본격 작성 완료 (PR-46) |
| **[07-policy-evolution.md](07-policy-evolution.md)** | architect | 운영 헌법 7 § (보존) + ADR 0052·0053·0054·0055 발행 + 정책 진화 추가 4건(Living Policy + INDEX + baseline 정형화 + 모듈 경계 ADR) | 본격 작성 완료 (PR-46) |

> **인덱스 갱신 권한**: architect 단독 책임 (충돌 회피 — 헌법 §4 정합). sub-agent는 본인 파일만 in-place edit, README 인덱스는 변경 안 함.

---

## 어떻게 읽을지 (역할별 진입 경로)

### 신규 개발자 (시스템을 처음 본다)

1. **[01-architecture.md](01-architecture.md) §1 시스템 전경** — Mermaid 그림으로 5 JVM 모듈 + frontend + 외부 6개 + 인프라 3개 한 눈에 (v3.1.0 베이스에 pattern-engine 라이브러리 + Anthropic 박스 add)
2. **[01-architecture.md](01-architecture.md) §2 모듈 책임 매트릭스** — 어떤 코드가 어디 있는지 (api·batch·consumer·pattern-engine·domain·frontend × 트랙 11건)
3. **[02-data-flow.md](02-data-flow.md)** — REST + Kafka topic + STOMP + Redis 캐시 흐름 (backend-core 영역)
4. **[03-use-sequences.md](03-use-sequences.md)** — 셋업 → Watchlist → Symbol → Indicator → Pattern → DSL → Strategy Builder 흐름 (market-data 영역, KIS-first raw 출처)
5. `docs/architecture/master-architecture.md` v2 정독 — SoT (19 컴포넌트 + 4 영역 + 5 Wave)
6. `_workspace/INDEX.md` — 단일 진입점 인덱스 (의도별 진입 경로 5축, ADR 0053)

### 운영자 (배포·환경 설정)

1. **[01-architecture.md](01-architecture.md) §6 deployment 토폴로지** — `docker-compose.yml` 9 컨테이너 (인프라 3 + 앱 4 + 디버깅 2 — pattern-engine은 라이브러리 jar이라 컨테이너 추가 0) + 부팅 순서 + profile 분기
2. **[01-architecture.md](01-architecture.md) §3 외부 통합 매트릭스** — KIS REST/WS · DART · Yahoo · Anthropic(W2 T-DSL 자연어 helper) URL·tr_id 정합
3. **[01-architecture.md](01-architecture.md) §4 Flyway V1~V11** — 어떤 PR에서 어느 테이블 생겼는지 (V9·V10·V11 W2 T-DSL strategy 신규)
4. `docs/operations/initial-setup.md` — 초기 셋업
5. `docs/architecture/external-api-policy.md` — KIS 호출 빈도·토큰 정책 (ADR 0037·0038)
6. `docs/architecture/policies/` — Living Policy 6건(KIS credential · 로그 레벨 · KIS-first · task cycle · mermaid · ...) — ADR 0053 §1
7. `CLAUDE.md` "운영 함정 가드" — stale jar · docker compose env 우선순위 · default profile · 실계좌 이중 게이트

### 전략 작성자 (W2 마감 후 본격 사용 가능)

1. `docs/architecture/master-architecture.md` §B 분석·전략 영역 (5 컴포넌트 — Indicator·Pattern·DSL·Strategy Builder·Backtest)
2. **[03-use-sequences.md](03-use-sequences.md) Strategy Builder 시퀀스** — `/strategy/builder` 라우트에서 트리 폼 빌더로 룰 작성 → DSL 직렬화 → backend `KisYamlDslParser` 검증 → `JvmStrategyEvaluator` 평가 → 결과 카드 → Watchlist 추가
3. `_workspace/strategy/` 카탈로그 (KIS 80지표 · 57패턴 cross-check fixture)
4. `frontend/src/api/strategy.ts` 7 endpoint + `frontend/src/components/strategy/` 8 컴포넌트 (PR-45 신규)
5. **[07-policy-evolution.md](07-policy-evolution.md) §10 다음 Wave 정책 후보** — Backtest 표준 룩어헤드/세금/슬리피지 ADR · Screener 표준 ADR (W3 진입 시 발행 예정)

### architect / orchestrator / sub-agent (운영 룰 정독 의무)

1. **[07-policy-evolution.md](07-policy-evolution.md)** — 운영 헌법 7 § 인용 + ADR 0052·0053·0054·0055 + 정책 진화 추가 4건
2. `_workspace/arch/agent-charter.md` — 운영 헌법 본문 (헌법 §1~§7 정독 의무 — architect 단독 PR로만 갱신)
3. **[06-history-traceability.md](06-history-traceability.md) §4 hotfix 패턴** — v3.1.1·v3.1.2 hotfix 사례 (pattern-engine 모듈 분리 트리거가 된 빈 등록 결함 baseline)
4. `_workspace/arch/branching-rules.md` — 브랜치·머지·author 정책
5. `.claude/agents/<agent>.md` SKILL — 영역별 특수 룰만
6. `docs/architecture/policies/` — Living Policy 6건 (ADR 0053 — 현행 권위 단일 위치)

---

## v3.2.0 사이클 핵심 통계

| 지표 | v3.1.0 (2026-05-08) | v3.2.0 (2026-05-10) | 증감 |
|------|---------------------|---------------------|------|
| PR # 누적 | #235 | #273 | +38 |
| ADR 수 | 51 | 55 | +4 (0052·0053·0054·0055) |
| Flyway 버전 | V8 | V11 | +V9·V10·V11 (T-DSL strategy) |
| **JVM 모듈** | 4 (domain·api·batch·consumer) | **5 (+pattern-engine)** | **+1 (ADR 0004 갱신)** |
| W2 ★ 트랙 풀세트 | 0 | 4건 (T-IND · T-DSL · T-PAT · T-SB) | +4 |
| 운영 헌법 § 수 | 7 | 7 (보존) | 0 |
| Living Policy 문서 | 0 | 6건 (ADR 0053) | +6 |
| baseline 정형화 스크립트 | 0 | 4건 (ADR 0052) | +4 |
| 외부 통합 (총) | KIS + DART + Yahoo (3) | KIS + DART + Yahoo + **Anthropic** (4) | +1 (T-DSL 자연어 helper) |
| frontend 라우트 | 4 (`/`·`/setup`·`/watchlist`·`/symbol/:code`) | 5 (+`/strategy/builder`) | +1 |
| KIS 함수 1:1 port | 0 + chk_holiday + ksdinfo | + indicators.py 80 + parser.py + candlestick.py 57 | +138 함수/패턴 |

상세는 **[06-history-traceability.md](06-history-traceability.md) §6 결론**.

---

## v3.2.0 사이클 머지된 주요 PR 12건 (인용)

PR 본문 인용 — 시계열 전체는 [06-history-traceability.md](06-history-traceability.md) §2.

| PR | mergedAt | 트랙 / 종류 | 한 줄 요약 |
|----|----------|------------|------------|
| #248 | 2026-05-08 | meta · ADR 0053 | feat(docs+adr): Living Policy Docs 도입 + 단일 진입점 인덱스 (ADR 0053) |
| #243 | 2026-05-08 | meta · ADR 0052 | feat(toolkit+adr): baseline 사이클 정형화 풀세트 + ADR 0052 |
| #252 | 2026-05-09 | T-IND spec | spec(arch): W2 T-IND 진입 — 80지표 + Source 추상화 (PR-32 spec) |
| #253 | 2026-05-09 | T-IND 본격 | feat(W2 T-IND): 지표 80종 + Source 추상화 + Native 5종 + REST + Redis cache + frontend overlay (PR-33 단일) |
| #254 | 2026-05-09 | T-DSL spec | spec(arch): W2 T-DSL 진입 — Strategy DSL 텍스트 파서 + 평가 엔진 + 자연어 변환 W4 이월 (PR-35 spec) |
| #255 | 2026-05-10 | T-DSL 본격 | feat(W2 T-DSL): KIS DSL 텍스트 파서 + 평가 엔진 + sandbox + REST + Flyway + 자연어 변환 helper (PR-36 단일) |
| #256 | 2026-05-10 | T-PAT spec | spec(arch): W2 T-PAT 진입 — 차트 패턴 57종 인식 + DSL stub 1줄 교체 (PR-38 spec) |
| #258 | 2026-05-10 | T-PAT 본격 | feat(W2 T-PAT): JvmPatternRecognizer 57패턴 + R1 active 10 + DSL pattern_detected + REST + Redis cache (PR-39 단일) |
| #261 | 2026-05-10 | hotfix v3.1.2 | fix: v3.1.2 hotfix 5건 — 컨테이너 부팅 정상화 (Hibernate6 NOW + 빈 함정 + healthcheck) |
| #263 | 2026-05-10 | pattern-engine spec · ADR 0054 | docs(arch): PR-41 — pattern-engine 라이브러리 모듈 분리 spec/plan + ADR-0004 갱신 + ADR-0054 신규 |
| #265·#266 | 2026-05-10 | pattern-engine 본격 | feat: PR-43 빌드 그래프 + PR-42 62 main + 4 test 모듈 이동 (동시 머지) |
| #269 | 2026-05-10 | T-SB spec · ADR 0055 | spec(arch): W2 T-SB 진입 — Strategy Builder UI (GUI 블록 빌더) + ADR 0055 (PR-44 spec) |
| #271 | 2026-05-10 | T-SB 본격 | feat(frontend): PR-45 W2 T-SB — Strategy Builder UI (B+C-lite 하이브리드, 시나리오 C 단일 PR) |
| #273 | 2026-05-10 | baseline init | chore(baseline): v3.2.0 init — 폴더 + CHANGELOG 슬롯 5 사전 발행 |

---

## 작성 규약 (sub-agent 디스패치 시 준수)

- 본문은 한국어, 코드/명령은 영어 그대로
- **Mermaid 다이어그램 의무** (시각화 가능 수준) — `_workspace/baseline/v3.2.0/html/` 변환 시 `scripts/baseline-render-html.sh` (ADR 0052)
- **raw 출처 인용 의무** — KIS API · DART · 코드 path 명시 (`pattern-engine/.../JvmPatternRecognizer.kt:L42` 등)
- 추측·관념적 서술 금지 — 코드 inspection 결과만 인용
- **TBD 표시**: 미작성 영역은 명시. 다른 sub-agent 영역은 cross-reference (`→ 02-data-flow.md 참조` 식)
- `docs/architecture/master-architecture.md` §X · `_workspace/arch/adr/NNNN-*.md` 같은 SoT 출처 명시 의무
- v3.1.0 baseline 보존 영역은 **변경 0** — 추가 분만 W2 add (예: 01-architecture.md §1 Mermaid는 v3.1.0 베이스에 pattern-engine + Anthropic 박스만 추가)

---

## 다음 Wave 진입 시 본 baseline 갱신

W3(Backtest · Screener · Signal Engine · Risk Guard · Order Executor · Reconciliation · Performance · Notification) 진입 시 본 폴더는 immutable(ADR 0031). v3.3.0 신규 baseline 컷 권장(`_workspace/baseline/v3.3.0/`).

W3 진입 시 본 §1·§3·§4가 본격 확장:
- **§1 Mermaid** — backtest·screener·signal·order·reconcile·notify 박스 add (master §C 영역)
- **§3 외부 통합** — KIS 주문 endpoint(TTTC·VTTC) + KIS WS H0STCNI0 체결통보 + Notification(Slack webhook) add
- **§4 Flyway** — V12~ backtest_run·screener_result·signal_event·order_audit 테이블 add
- **§5 frontend 라우트** — `/backtest`·`/screener`·`/dashboard` 본격 add

W4(AI 옵션 + MCP 서버) 진입 시 mcp 컨테이너 + AI Console 박스 add. **본 v3.2.0 시점 Anthropic 통합은 T-DSL 자연어→DSL helper 한정 (사용자 트리거 + 매매 결정권 0)** — ADR 0023·0026 정합.
