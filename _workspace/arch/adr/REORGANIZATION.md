# ADR 재정리 보고서 (v2 베이스라인)

> **상태**: DRAFT — 사용자 검토 대기
> **작성일**: 2026-05-03
> **작성자**: architect (opus 4.7 1M)
> **선행**: `docs/architecture/master-architecture.md` (v2 풀 재작성)
> **트리거**: 사용자 비전 정정(2026-05-02) — "AI는 옵션, 시스템 본체는 룰 기반 quant + 오프라인 동작 가능". 이전 architect 1차안의 "AI quant trading platform · AI 직접 매매" 가정과 정면 충돌. 클린 베이스라인 재시작.

본 문서는 기존 ADR 0001~0030(전 30건) + 1차안 인라인 draft(0031~0045) 전체를 새 비전 위에서 재분류한다. 산출물:
1. 기존 ADR 30건 분류 — Active / Superseded / Deprecated / Merged-into-master
2. 1차안 인라인 draft 14건(0036~0045 + 트랙2 0031~0035) 처리 — 폐기 / 새 ADR로 흡수
3. 새 ADR 세트 (v2 베이스라인 — `Active` 라벨로 발행 예정)
4. Deprecated/Superseded 처리 방식 권장

---

## §1. 기존 ADR 0001~0030 재분류

| 번호 | 제목 | 현 상태 | **새 분류** | 사유 |
|------|------|---------|-----------|------|
| 0001 | 언어·프레임워크 (Kotlin 2.0 + JDK 21 + Spring Boot 3.3) | Accepted | **Active** | v2 베이스라인 그대로 유지 |
| 0002 | Gradle 멀티모듈 (수직 도메인 7) | Restructured by 0019 | **Superseded** (0019) | 0019가 4 JVM 모듈로 재배치 — 본 ADR은 archive |
| 0003 | KIS OpenAPI 채택 | Accepted | **Active** | KIS 결정 유지. 단 "AI 의사결정·매매 자동화" 가정 부분은 master §1로 흡수 (룰 기반 본체 + KIS는 시세·주문 외부 source) |
| 0004 | 영속 저장소 (PostgreSQL + Redis) | Accepted (확장 by 0025) | **Superseded** (0025) | 0025가 본격 결정. 본 ADR은 archive |
| 0005 | LLM 공급자·Spring AI 추상화 | Accepted | **Deprecated → 새 NEW-AI-PROVIDER로 대체** | 1차안이 "AI 본체 결정권" 가정. 새 ADR은 "AI = 옵션 보조" 명시 |
| 0006 | OrderSender 3-mode + 안전 게이트 | Accepted | **Active (보강)** | 트랙1·트랙2 정합. master §13에 흡수. ADR 자체는 보존 |
| 0007 | 매매일지 아키텍처 | Accepted | **Active** | 매매일지는 v2 베이스라인의 핵심 영역. 본 ADR 유지 |
| 0008 | 하네스 운영 페이지 (`/harness`) | Accepted (재구현 by 0022) | **Superseded** (0022) | React SPA 흡수. 본 ADR은 archive |
| 0009 | 브랜치 전략 (Feature-branch + QA 게이트) | Accepted | **Superseded** (0015·0016) | 0015 단일 머지 흐름 + 0016 QA on-demand로 대체. 본 ADR은 archive |
| 0010 | Husky v9 git hooks | Accepted | **Active** | hook 자체 정책 유지 |
| 0011 | QA 스크린샷 게이트 | Accepted (개정 2026-05-01) | **Active (보강)** | 시나리오 모드 유지. 단 v2의 development-rules에서 "QA on-demand 0회 default" 명시 |
| 0012 | Feature → QA → Main 2단 머지 | Superseded by 0015 | **Superseded** (0015) | 본 ADR은 archive |
| 0013 | 단일 worktree·순차 정책 | Accepted | **Active** | 운영 정책 유지 |
| 0014 | 모델 차등 (architect/ai/strategy=opus, 나머지=sonnet) | Accepted | **Active (보강)** | v2 베이스라인 진행 시에도 유지. ai-engineer 책임은 W4로 한정(매매 결정권 0) |
| 0015 | 머지 흐름 단순화 (`agent/* → main`) | Accepted | **Active** | 운영 정책 유지 |
| 0016 | QA on-demand (MVP) | Accepted (보강 2026-05-01) | **Active (강화)** | v2는 더 강한 "QA 호출 0회 default" — development-rules.md에 명시 |
| 0017 | Git author per agent | Accepted | **Active** | 운영 정책 유지 |
| 0018 | Task 파일 시스템 | Accepted | **Active** | 운영 정책 유지 |
| 0019 | MSA 모듈 재구성 (4 JVM + pyworker + frontend) | Accepted | **Active** | v2 베이스라인 모듈 구조 그대로 |
| 0020 | Kafka 이벤트 백본 | Accepted | **Active** | 이벤트 백본 유지 |
| 0021 | 하이브리드 런타임 (api·batch·consumer 3 JVM) | Accepted | **Active** | 런타임 구조 유지 |
| 0022 | 프론트엔드 SPA (React + LWC, 커스텀 지양) | Accepted | **Active (TradingView Advanced 부분 폐기)** | LWC 본체 유지. 단 본 ADR §"커스텀 지양 원칙"의 "TradingView Advanced 전환 후속 트리거" 부분은 무효 (TradingView Advanced 라이선스 실현성 낮음, 새 ADR로 LWC 확장 명시) |
| 0023 | pyworker (FastAPI + Playwright) | Accepted | **Active** | pyworker 유지 |
| 0024 | Docker 표준 | Accepted | **Active** | docker compose 9 컨테이너 유지 |
| 0025 | PostgreSQL 단일 + 스키마 분리 | Accepted | **Active** | DB 정책 유지 |
| 0026 | MinIO 객체 저장소 | Accepted | **Active** | 객체 저장 유지 |
| 0027 | v0 베이스라인 + release/v0 | Accepted | **Active** | 베이스라인 보존 정책. 단 사용자 명시 — release/v0 외 git history 일괄 정리 예정 |
| 0028 | v1.0.1 베이스라인 + release/v1 | Accepted | **Deprecated** | 사용자 명시 — release/v1 포함 release 외 싹 삭제 예정. v2 베이스라인 컷 시 새 ADR |
| 0029 | 데이터는 실 KIS 베이스 / OrderSender만 분리 | Accepted | **Superseded → 새 NEW-DATA-POLICY** | 1차안 "AI 직접 매매" 가정 정합. v2 비전("AI 옵션·룰 기반 본체")으로 재작성 — 데이터 정책은 유지하되 mock profile 활성화 + 오프라인 동작 명시 |
| 0030 | 오케스트레이터 = architect 일체화 | Accepted | **Active** | 메타 운영 규약 유지 |

### §1-1. 분류 집계

- **Active**: 19건 (0001 · 0003 · 0006 · 0007 · 0010 · 0011 · 0013 · 0014 · 0015 · 0016 · 0017 · 0018 · 0019 · 0020 · 0021 · 0022 · 0023 · 0024 · 0025 · 0026 · 0027 · 0030)
- **Superseded** (다른 기존 ADR이 대체): 5건 (0002 → 0019, 0004 → 0025, 0008 → 0022, 0009 → 0015·0016, 0012 → 0015)
- **Deprecated** (사용자 명시 + 1차안 폐기 정합): 2건 (0028, 0005 LLM 공급자)
- **Superseded → 새 v2 ADR로 재작성**: 1건 (0029)

`Active` 22건이지만 일부는 (보강) 표시 — v2 master에서 §위치 명시 후 본 ADR은 그대로 유지.

---

## §2. 1차안 인라인 draft 처리

이전 architect 1차안 `docs/architecture/master-architecture.md` §8과 트랙2 spec §13에 ADR 0031~0045 draft 14건이 인라인 작성되었음. **본 14건 모두 폐기 — v2 비전 위반**. 사유 명시:

| 번호 | 1차안 제목 | 폐기 사유 | v2 대응 |
|---|---|---|---|
| 0031 (트랙2) | Market Session FSM 도입 | v2 정합 — AI 의사결정 가정 0 | **새 NEW-MARKET-SESSION 으로 재발행** (트랙2 spec 본체 살림) |
| 0032 (트랙2) | 시세 Fallback 정책 (3-tier) | v2 정합 | **새 NEW-DATA-FALLBACK 으로 재발행** |
| 0033 (트랙2) | REJECTED Audit Trail | v2 정합 | **새 NEW-REJECTED-AUDIT 으로 재발행** |
| 0034 (트랙2) | Profile 분기 매트릭스 정밀화 | "AI Decision·Signal·Screener까지 포함" 가정 — 부분 폐기, 룰 본체 한정으로 재작성 | **새 NEW-PROFILE-MATRIX (v2)** 로 재작성 (mock/dev/prd × source × AI on/off) |
| 0035 (트랙2) | EGW00202 hotfix · KIS 헤더 정밀화 | v2 정합 | **새 NEW-KIS-HTTP-HOTFIX 으로 재발행** |
| 0036 (1차안 master) | KIS Strategy Builder 흡수 (B + 부분 A) | "AI 의사결정 hook" 가정 일부 — 룰 기반 흡수만 유지하고 GUI 빌더 채택·source 추상화 추가로 재작성 필수 | **새 NEW-KIS-SB-INTEGRATION (v2)** — 본 보고서 §4 + adr-0036 재작성안 |
| 0037 (1차안) | Instrument Master 1급 시민 | v2 정합 | **새 NEW-INSTRUMENT-MASTER** |
| 0038 (1차안) | Corp Action raw + adjusted | v2 정합 | **새 NEW-CORP-ACTION** |
| 0039 (1차안) | Indicator Engine = JVM 우선 + pyworker dual | v2의 "지표 source 추상화 plug-in" 누락 — 재작성 필수 (KIS 외 TA-Lib·자체 구현·KRX 다 plug-in) | **새 NEW-INDICATOR-SOURCE-ABSTRACTION** |
| 0040 (1차안) | DSL = `.kis.yaml` 호환 + 확장 | v2 정합 | **새 NEW-DSL-FORMAT** |
| 0041 (1차안) | Signal Engine = 룰 + AI 컨설트 분리 | "AI 컨설트가 의사결정" 가정 — **AI는 분석 코멘트만, 매매 결정권 0** 으로 재작성 필수 | **새 NEW-SIGNAL-ENGINE-RULE-ONLY** (룰 매칭만, AI 호출 0) |
| 0042 (1차안) | AI Decision Audit Trail | "AI 결정 audit" 자체가 v2 위반 — 폐기 | **폐기**. v2의 AI는 결정권 0이므로 audit 대상 0. 대신 W4의 "AI 분석 코멘트" audit는 별도 NEW-AI-COMMENT-AUDIT로 |
| 0043 (1차안) | Risk Guard chain 6단 | v2 정합 (Guard chain은 룰 본체) | **새 NEW-RISK-GUARD-CHAIN** |
| 0044 (1차안) | Multi-tenant 구조 | v2 정합 | **새 NEW-MULTI-TENANT** (트랙1 완료 사실 흡수) |
| 0045 (1차안) | 본 마스터 = 단일 진실원 | v2 정합 (메타 규약) | **새 NEW-MASTER-SOT** |

### §2-1. 핵심 변경점 요약

이전 1차안의 결정적 결함:
1. **AI Decision Layer (1차안 ADR 0042)**가 "AI가 BUY/SELL 결정"을 정상 흐름으로 가정 → 본질 위반. v2는 AI 호출이 옵션·사용자 트리거·코멘트만.
2. **Signal Engine (1차안 ADR 0041)**가 "AI 컨설트로 시그널 보강"을 가정 → AI 호출 빈도가 시스템 흐름에 박혀있음. v2는 SignalGenerator 룰 매칭 단일, AI 호출 0.
3. **Indicator Engine (1차안 ADR 0039)**가 KIS 흡수만 가정. v2는 source 추상화 인터페이스 의무 — KIS·TA-Lib·자체 구현·KRX 모두 plug-in.
4. **Strategy Builder UI (1차안 0036)**가 "1차 YAML editor + 2차 GUI"라 yaml editor 우선 가정. v2는 사용자 명시로 GUI 블록 빌더 1차 (yaml export/import 동시).
5. **TradingView Advanced (1차안 0022 후속)**가 "후속 트리거"로 남겨졌으나 라이선스 실현성 낮음. v2는 LWC 확장 + 자체 도형/주석/패턴 시각화로 종결.

---

## §3. 새 v2 ADR 세트 (Active 발행 예정 — 0031~0042)

번호 충돌 회피 + 압축 — 1차안의 14건을 v2 에서 12건으로 정리(0042 AI Decision Audit Trail 폐기 + 일부 합병). **번호는 0031부터 새로 부여** (기존 0001~0030 위에 누적).

| 새 번호 | 제목 | 핵심 결정 한 줄 | master §위치 |
|---|---|---|---|
| **0031** | 시스템 비전 v2 — 룰 기반 본체 + AI 옵션 + MCP 서버 미래 | AI는 사용자 트리거 시 분석 코멘트만, 매매 결정권 0. 오프라인 동작 1순위 검증 기준. | §1·§2 |
| **0032** | Market Session FSM (트랙2 spec 흡수, AI 가정 제거) | KRX 영업일·장중·장외·휴장 FSM. MarketSessionResolver port. 60s cache. | §A.2 |
| **0033** | 시세 데이터 Fallback (3-tier: live → cache → last-day-close) | 장외 시 stale=true 명시 + UI 배지. cold cache 시 "데이터 수집 중" 명시. | §A.2 |
| **0034** | REJECTED Audit Trail (Guard reject·KisApiException 모두 orders INSERT) | 옵션 C 채택 — 사용자 dogfooding 가시성 확보. | §C.13 |
| **0035** | KIS REST/WS HTTP 정밀화 (EGW00202 hotfix + hashkey 표준) | RealKisRestAdapter raw HTTP 로깅 + 헤더·body 직렬화 표준화. | §외부.KIS |
| **0036** | KIS Strategy Builder 흡수 = 명세 port (재작성, AI hook 제거) | DSL·80지표·57패턴·10프리셋 명세만 Kotlin port. .kis.yaml 호환. UI는 magicJar React + GUI 블록 빌더 1차. | §B.5·§B.7·§B.8 |
| **0037** | Instrument Master 1급 시민 | KIS·KRX 종목 마스터 일일 sync + Redis 검색 인덱스. 상장폐지 보존(생존자 편향 차단). | §A.1 |
| **0038** | Corp Action raw + adjusted (룩어헤드 차단) | candle.close_raw·close_adjusted 양 컬럼 + corp_action 이벤트. retroactive 재계산 batch. | §A.3 |
| **0039** | Indicator Source 추상화 (plug-in 인터페이스) | `IndicatorSource` port + `KisStrategyBuilderSource`·`TaLibSource`·`NativeSource` adapter. KIS는 1차 import. | §B.5 |
| **0040** | Strategy DSL = `.kis.yaml` 호환 superset | `.kis.yaml` 그대로 채택 + magicJar 확장(다중 TF·시간 필터·종목 그룹·패턴 조건). | §B.7 |
| **0041** | Signal Engine = 룰 매칭 only (AI 호출 0) | DSL 평가만, LLM 호출 0. AI 컨설트는 옵션 W4의 사용자 트리거 코멘트로 분리. | §C.11 |
| **0042** | Risk Guard chain 6단 (KillSwitch·Session·DailyLimit·PositionLimit·DrawdownStop·PriceSanity) | 1차 매우 느슨 default. PositionLimit 종목당 10%·전체 80%. DrawdownStop 일일 -3%. | §C.13·§C.14 |
| **0043** | Multi-tenant 구조 (트랙1 완료 사실 흡수) | 모든 user-scoped 테이블에 user_id. 인증은 post-MVP. | §1·§D.18 |
| **0044** | LWC 확장 + 자체 도형/주석/패턴 시각화 (TradingView Advanced 폐기) | LWC v4 공식 API 한정 + 자체 overlay layer로 패턴·도형·주석. TradingView Advanced 의존 0. | §출력.frontend |
| **0045** | MCP 서버 미래 노출 (W4 안에 포함) | magicJar가 MCP 서버 expose — Claude/Cursor 등 AI 클라이언트가 magicJar 호출. tool 후보 5종. | §1.MCP·W4 |
| **0046** | AI 옵션 보조 (사용자 트리거 한정 + 분석 코멘트) | 종목 코멘트·기업 분석·차트 해석·지표 의미 설명. 매매 트리거 0. dev 호출 빈도 통제(prompt cache 5분 TTL). | §1·W4 |
| **0047** | 새 v2 마스터 = 단일 진실원 | 트랙 spec/plan은 master §X 위치 명시 의무. 충돌 시 master 선 개정. legacy 청소·release/v0 외 git history 정리. | §운영 |

총 17건 (0031~0047). 1차안 14건 → v2 17건 (KIS Strategy Builder 흡수 분할 + LWC 확장 + MCP 서버 + AI 옵션 보조 분리 + 비전 명시 ADR 신설).

### §3-1. 발행 순서 권장

W1 진입 직전에 0031·0032·0033·0034·0035·0037 (W1 트랙들의 직접 ADR) 일괄 발행. 나머지는 해당 트랙 진입 직전.

---

## §4. KIS Strategy Builder 흡수 ADR 0036 — v2 재작성 핵심 변경

기존 `docs/architecture/adr-0036-kis-strategy-builder-integration.md` 폐기 후 정식 ADR 위치 `_workspace/arch/adr/0036-kis-strategy-builder-integration.md`로 신규 발행. 핵심 변경:

| 영역 | 1차안 | **v2 (확정)** |
|---|---|---|
| **흡수 범위** | DSL·80지표·57패턴·10프리셋 명세 + AI hook | **AI hook 제거** — 흡수는 명세만, AI 의사결정 연결 0 |
| **80지표 1차 범위** | 30개(프리셋 사용분) | 사용자 명시 — **80개 일괄** + source 추상화 인터페이스(`IndicatorSource`) 의무 |
| **Strategy Builder UI** | 1차 YAML editor + 2차 GUI | 사용자 명시 — **GUI 블록 빌더 1차** (yaml export/import 동시) + 2차 풀 GUI |
| **TradingView Advanced** | 후속 트리거 (LWC 한계 도달 시) | **폐기** — LWC 확장 + 자체 도형/주석/패턴 시각화 |
| **AI 컨설트 흐름** | 시그널 → AI 의사결정 → 주문 (1차안 0041) | **시그널 → 룰 평가 → 주문** (AI 호출 0) + **사용자 트리거 시 AI 분석 코멘트** (W4) |

상세는 별도 ADR 0036 문서 참조 (`_workspace/arch/adr/0036-kis-strategy-builder-integration.md`).

---

## §5. Deprecated/Superseded ADR 처리 방식 (권장안)

세 후보:
- **(a) 파일 삭제** — git history에서만 보존
- **(b) `_archive/` 디렉토리 이동** — 본 ADR 디렉토리에서 분리해 가시성 줄임
- **(c) 본문 상단에 "DEPRECATED" 헤더 추가하고 같은 위치 보존** — 발견·참조 가능

**권장: (c) — 단 사용자 명시는 (a)+release/v0 외 싹 삭제**.

근거:
- 사용자 인용: "이젠 실수 용납안된다. 깃에 있는것도 release말곤 싹지울 예정이야"
- 사용자 의도는 git history 자체 정리 — 그러나 ADR은 **결정 추적성**이 가치이므로 history 정리 시점까지 (c)로 본문 표시 유지
- release 컷 시점에 (a)로 일괄 삭제 OR _archive로 일괄 이동 결정 필요

### §5-1. 사용자 결정 필요

본 보고서 검토 시 사용자가 확정:

1. **Deprecated/Superseded 13건 처리** — (a) 즉시 삭제 / (b) `_workspace/arch/adr/_archive/` 이동 / (c) 본문 헤더만 추가하고 보존?
2. **release/v0 외 git history 정리 시점** — v2 베이스라인 컷 시점? 아니면 마스터 v2 승인 직후?
3. **새 v2 ADR 0031~0047 17건 발행 시점** — W1 진입 직전 일괄? 아니면 본 보고서 + master 승인 즉시?
4. **0028 v1.0.1 release 처리** — Deprecated 분류 + release/v1 브랜치도 삭제? (사용자 "release말곤 싹지울 예정" 인용 정합 시 v0만 남김)

### §5-2. 사용자 명시 정합 처리 (선제안)

사용자 인용 "release말곤 싹지울 예정" 정합 처리안:

| 대상 | 처리 |
|---|---|
| `release/v0` (껍데기 베이스) | **보존** — 사용자 명시 |
| `release/v1` (v1.0.1 데모 가시성) | **삭제 후보** — 사용자 결정 필요 |
| 기존 ADR 0001·0003·0006·0007·0010·0013·0014·0015·0017·0018·0019·0020·0021·0022·0023·0024·0025·0026·0027·0030 (Active 22건) | **보존** — v2 베이스라인 동일 |
| 기존 ADR 0002·0004·0005·0008·0009·0011(보강 제외 본문)·0012·0028·0029 (Superseded/Deprecated 9건) | **(c) 본문 DEPRECATED 헤더 후 보존** — v2 컷 시점에 일괄 삭제 결정 |
| 1차안 인라인 draft 0031~0045 (14건) | **즉시 삭제** — 정식 발행 전이라 history 정리 부담 0. master v2·tracks-roadmap v2가 신규 0031~0047로 대체 |
| 기존 `docs/architecture/adr-0036-*.md` (1차안 단독 파일) | **즉시 삭제** + 새 `_workspace/arch/adr/0036-kis-strategy-builder-integration.md` 정식 발행 |

---

## §6. 사용 가이드 (architect·메인 세션 일상 참조)

본 재정리 보고서 발행 후 일상 운영:

1. **새 트랙 진입 시** master §X 위치 + 새 ADR 번호(0031~0047 중) 명시 의무.
2. **기존 ADR 충돌 시** 본 보고서 §1 분류 표 우선 참조 — Superseded인 0002·0004·0008·0009·0012는 참조 금지(대체 ADR 사용).
3. **Deprecated 0028(v1.0.1) 결정 사항**(태그·release/v1 브랜치)은 v2 베이스라인 컷 직전 일괄 정리.
4. **v2 ADR 발행 시** 번호 0031~0047 sequential 사용. 충돌 회피.

---

## §7. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-03 | 초안 — 기존 30건 분류 + 1차안 14건 폐기 + v2 17건 정의 (architect, opus 4.7 1M) |
