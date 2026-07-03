# ADR 0073 — T-NEWS 뉴스·공시 실시간 인입 트랙 feasibility 분석

- 상태: **Superseded by 0075** (2026-05-15 — feasibility 분석이 T-NEWS 트랙 진입 spec/plan ADR 0075로 승격, 0075가 현행 권위. 파일 헤더↔README 인덱스 정합: 2026-07-03 감사 정정 — README §15는 이미 Superseded로 등재되어 있었으나 본 헤더만 Proposed로 잔존했음)
- 작성일: 2026-05-14
- 결정자: architect (사용자 의제 응답 — "뉴스 스크래핑, 공시 정보 실시간 속보 등 주요 뉴스는 시작해?" 2026-05-14)
- 관계: ADR 0001 정합(룰 본체 + AI 옵션 비전) · ADR 0010 §5(`news`·`news_sentiment` 테이블 이미 명시) · ADR 0018(Corp Action raw — DART 폴링 baseline) · ADR 0026(AI 옵션 — 뉴스 컨텍스트 prompt 조립) · ADR 0046·0069(SettingsModal NaverApiSection §F 후보) · ADR 0057(KIS-only — 시세 단일 출처. 뉴스는 외부 보조 OK 별도 의제)

---

## 1. 컨텍스트 (왜 본 분석이 필요한가)

### 1-1. 사용자 의제 명시 (2026-05-14)

> "뉴스 스크래핑, 공시 정보 실시간 속보 등 주요 뉴스는 시작해?"

자동매매 본체 v3.6.x 마무리(W3 6 트랙 종착) + W4 AI 트랙 prereq 검토 시점에 발행된 사용자 의제. 본 ADR은 **시작 여부 결정 의제 + 트랙 신설 옵션 매트릭스 + KIS-first 정책 정합 검증**을 제공한다. 본 사이클은 **코드 변경 0** — analysis ADR + backlog 등재만, 트랙 진입은 사용자 명시 후 별도 사이클.

### 1-2. 현재 시스템 상태 매트릭스

| 영역 | 구현 상태 | 위치 | 비고 |
|---|---|---|---|
| **DART 공시 정기 폴링** | ✅ 운영 중 | `batch/.../infrastructure/dart/DartDisclosureAdapter.kt` (java.net.http.HttpClient, ADR 0018 §4-1) | 2026-05-12 v3.6.2 SSL handshake_failure hotfix 완료. CorpAction 갱신용. |
| **DART 실시간 속보** | ❌ 미구현 | (cron 폴링만, 빈도 TBD) | 현재 CorpActionSyncJob cron 트리거 — endpoint `https://opendart.fss.or.kr/api/list.json` |
| **네이버 뉴스 검색** | ❌ 미구현 | (ADR 0069 §F NaverApiSection 후보로만 등록) | API 키 부재 |
| **구글 뉴스 RSS** | ❌ 미구현 | (계획 0) | - |
| **뉴스 감성 분석** | ❌ 미구현 | (ADR 0026 §38 "지표·패턴·뉴스 컨텍스트" prompt 조립만 명시) | T-AI 흡수 후보 |
| **`news`·`news_sentiment` 테이블** | ❌ 미생성 | ADR 0010 §5 멀티테넌트 명시만(공유 테이블) | Flyway V0 미발행 |

**baseline ADR 보유** (재발견 — analysis 의제 의무 일관성):
- **ADR 0010 §5** — 멀티테넌트 테이블 분류표에 `news` · `news_sentiment` **이미 명시** ("뉴스 — 모든 사용자 공유")
- **ADR 0026 §38** — T-AI 종목 상세 응답에 "현재 지표·패턴·**뉴스** 컨텍스트" prompt 조립 명시
- **ADR 0026 prompt context** — consumer/PromptBuilder.kt가 "지표·패턴·뉴스 컨텍스트 조립" 책임
- **ADR 0069 §F** — SettingsModal `NaverApiSection` 후보 ("종목 뉴스·메타 정보 보조 출처 키 — 사용자 요청 시")
- **master-architecture §A.4** — "News & Disclosure" 영역 명시 ("부분 / 외부 source 끊겨도 본체 흐름 차단 0 / post-MVP 보강")
- **master-architecture §B-3 NAVER 금융 / 한경** — "뉴스 본문 / 옵션 / api 외부 어댑터 / 끊겨도 본체 영향 0"
- **master-architecture §C.13** — News (네이버·DART scrape 어댑터 + Kafka publish + AI 분석 listener + 뉴스 패널) 19 컴포넌트 중 하나

**결론**: v1·v2 잔재 의심 → **잔재 0**. 시스템 비전 ADR 0001 초기부터 뉴스·공시 영역은 보조 정보 출처로 설계됨. 단, 자동 매매 결정권은 0 (룰/시그널 본체). 따라서 본 분석은 "잔재 정리" 의제가 아니라 **post-MVP 보강 시점 + 진입 형태 결정** 의제.

### 1-3. v3 fitness 검증 — "자동매매에 진짜 필요한가?"

사용자 메모리 `feedback_v3_skepticism_no_regression.md` 의무 적용 (fitness gap lens):

| 데이터 | 자동매매 직접 영향 | 사용자 트리거 분석 | AI 옵션 prompt input | 결론 |
|---|---|---|---|---|
| **DART 공시 실시간 속보** | ★★★ critical (정정공시·악재·호재 즉시 반응 의무) | ★★★ (사용자가 종목 검토 시 즉시 인지) | ★★ (AI prompt에 최근 공시 inject 가치) | **필수 — DART 폴링 빈도 강화 1차 의제** |
| **네이버 뉴스 본문** | ★ (룰 매매 직접 영향 0 — 정량 신호 없음) | ★★ (사용자가 종목 검토 시 보조 정보) | ★★★ (AI prompt에 최근 뉴스 inject 핵심) | 보조 — T-AI prereq 또는 단순 정보 표시 |
| **뉴스 감성 분석** | ★ (룰 매매 직접 영향 0) | ★ (사용자 직접 판단 영역) | ★★★ (AI 응답 품질 직결) | T-AI 옵션 흡수 — 별도 트랙 분리 비용 부담 |

**fitness 종합**:
1. **DART 실시간 강화** = 자본 보호 직결 (정정공시·악재 즉시 반응) → ★★★ 필수
2. **네이버 뉴스** = 사용자 트리거 보조 + T-AI prereq → ★★ 옵션
3. **감성 분석** = T-AI 흡수 → ★ 별도 트랙 신설 가치 낮음

**v1/v2 회귀 의심**: PoC v1 시점 "AI Decision Layer T-RISK" 가정 폐기 시(2026-05-03) 뉴스·감성 분석도 함께 폐기됨(`tracks-roadmap.md` §8 v1 변경 이력). v3에서 부활 시 **"AI 매매 결정"이 아닌 "AI 보조 + 룰 본체"** 비전 정합 의무 (ADR 0001).

---

## 2. 외부 데이터 소스 매트릭스

### 2-1. 후보 소스 7건 — endpoint·한도·실시간성·KIS-first 정합

| 소스 | endpoint | 형식 | 한도 (공식) | 실시간성 | 인증 | KIS-first 정합 | 코드 보유 |
|---|---|---|---|---|---|---|---|
| **DART 공시 list.json** | `https://opendart.fss.or.kr/api/list.json` | JSON | 20,000건/일 (API 키 발급 기준) | 5분 polling 표준 (현 시스템) | API 키 (OPENDART_API_KEY) | OK (보조 출처) | ✅ DartDisclosureAdapter 운영 중 |
| DART corpCode.xml | `https://opendart.fss.or.kr/api/corpCode.xml` | ZIP/XML | (동일) | 1회/일 동기화 | (동일) | OK | ✅ DartCorpCodeRepository 운영 중 |
| **DART 실시간 알림 (TBD)** | (별도 endpoint 부재 — list.json 폴링 강화 단일 path) | - | - | 분 단위 폴링이 한계 | (동일) | OK | ❌ |
| **네이버 뉴스 검색 Open API** | `https://openapi.naver.com/v1/search/news.json` | JSON | 25,000건/일 (Client ID/Secret 기준) | 분 단위 polling 권장 | Client ID + Secret | OK (외부 보조) | ❌ |
| 네이버 금융 뉴스 RSS | `https://finance.naver.com/news/news_list.naver?...` | HTML/RSS | (rate limit 명시 X — scrape) | 분 단위 polling | (없음 — scrape risk) | risk (TOS) | ❌ |
| 구글 뉴스 RSS | `https://news.google.com/rss/search?q=...` | RSS XML | (rate limit 명시 X) | 분 단위 polling | (없음) | OK | ❌ |
| 한경 RSS | `https://www.hankyung.com/feed/all-news` | RSS XML | (rate limit 명시 X) | 분 단위 polling | (없음) | OK | ❌ |

**KIS-first 정책 (ADR 0057) 정합 검증** — 사용자 메모리 `feedback_kis_first_policy.md`:
- **시세·캔들·호가·체결**: KIS OpenAPI 단일 출처 LOCK (Yahoo 등 fallback 폐기)
- **뉴스·공시·재무**: 외부 보조 출처 OK — **본 정책의 범위 외**

ADR 0057 본문 검토 (이미 backlog §31 시 발행)→ 정책 텍스트는 "**시세 데이터**"에 한정. 뉴스·공시는 외부 보조 OK 명시 (master-architecture §B-3 정합). 따라서 본 트랙 진입은 KIS-first 정책 위반 0.

### 2-2. 자체 추론 vs KIS-first raw fetch 의무 (헌법 §3)

본 분석 ADR은 **사용자 결정 대기 status** = 트랙 진입 LOCK까지는 **KIS endpoint 호출 0 + DART/네이버 endpoint 호출 0**. 채택 시 sub-agent 디스패치 단계에서 (a) DART API 가이드 `https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001` 풀 raw fetch + (b) 네이버 검색 API 공식 문서 풀 raw fetch + (c) 한도·인증 헤더·응답 스키마 raw 인용 의무 (헌법 §3 5단계 절차).

본 ADR §2-1 매트릭스의 "한도·endpoint"는 **이미 운영 중인 DartDisclosureAdapter 코드 인용** + **publicly known 한도**. 채택 시 raw fetch + ADR 본 §2-1 갱신 의무 LOCK.

---

## 3. 트랙 신설 옵션 매트릭스 (A·B·C)

### 옵션 A — 별도 T-NEWS 트랙 신설 (W3.5 또는 W4 prereq)

| Phase | scope | 의존 | sub-agent | 예상 PR |
|---|---|---|---|---|
| **Phase 1 — DART 실시간 폴링 강화** | cron 빈도 5분 → 1분 + 실시간 KillSwitch 트리거(정정공시·악재 키워드 매칭) + Kafka `disclosure.new.v1` publish | DartDisclosureAdapter 운영 중 reuse | market-data + backend-core | 1 PR |
| **Phase 2 — 네이버 뉴스 검색 어댑터** | api 모듈 `NaverNewsAdapter` + Flyway V31(news 테이블, ADR 0010 §5 정합) + Kafka `news.collected.v1` publish + 종목 매핑(KRX_code → 검색어 query) | Phase 1 + NAVER_CLIENT_ID/SECRET .env 추가 | backend-core | 1 PR |
| **Phase 3 — frontend 뉴스/공시 panel** | 종목 상세 페이지에 `DisclosurePanel` + `NewsPanel` 컴포넌트 + STOMP `/topic/news/symbol/{code}` subscribe | Phase 1·2 | frontend | 1 PR |
| **Phase 4 — RSS 보조 (옵션)** | 한경·구글 뉴스 RSS adapter (Naver API 한도 보완) | Phase 2 | backend-core | 1 PR (옵션) |

**총 산출물**: ADR 0074 신규 + 3~4 PR + Flyway V31(news + news_disclosure) + Kafka 신규 2 topic + REST 2 endpoint + STOMP 1 topic + frontend 2 컴포넌트
**기간 추정**: 1~1.5주 (W3 마무리 직후 또는 W4 진입 직전)
**KIS endpoint 호출**: 0 (KIS-first 정책 정합)
**AI 호출**: 0 (T-AI 별도 트랙 — Phase 5 옵션 — backlog §62 후속)

### 옵션 B — T-AI 트랙에 흡수 (T-AI prereq로 통합)

| 흡수 방식 | 설명 |
|---|---|
| 뉴스 인입 = T-AI prereq 단일 묶음 spec | T-AI 진입 사이클 시작 시 ADR 0073 → ADR 0074 묶음 spec 발행 (ADR 0070 패턴 유사). T-NEWS Phase 1·2·3 + T-AI Phase 1·2·3 6 Phase 직렬 |
| 장점 | 사용자 비전 정합(룰 본체 + AI 옵션 보조) — 뉴스는 AI prompt input 핵심. 분리 trade-off 비용 회피 |
| 단점 | (a) DART 실시간 강화 단발 trace 의제가 T-AI 진입까지 LOCK — 자본 보호 직결 의제 지연 risk. (b) frontend 뉴스 panel은 T-AI 비활성 시에도 가치 있음. |

### 옵션 C — 기존 DART 폴링 강화만 (Naver 뉴스 비범위 LOCK)

| scope | 설명 |
|---|---|
| 단발 PR — DartDisclosureAdapter cron 빈도 1분 + 키워드 매칭 KillSwitch | 자본 보호 직결 의제 최소 path. Naver/감성 분석은 T-AI 흡수 LOCK |
| 장점 | 의제 단순화 + KIS-first 정책 일관성 확장 (외부 매매 데이터 = KIS만, 외부 보조 정보 = 1차 DART만) |
| 단점 | (a) 뉴스 패널 없음 → 사용자 종목 검토 시 외부 사이트 의존 지속. (b) T-AI prompt input 빈약 → AI 응답 품질 한계 |

### 옵션 비교 매트릭스

| 기준 | 옵션 A (T-NEWS 신설) | 옵션 B (T-AI 흡수) | 옵션 C (DART만) |
|---|---|---|---|
| 사용자 비전 정합 (룰 + AI 보조) | ★★★ 완전 | ★★ (T-AI 시점까지 지연) | ★ (AI 빈약) |
| 자본 보호 직결 의제 (DART 실시간) | ★★★ (Phase 1 단발 가능) | ★ (T-AI 시점까지 지연) | ★★★ (단발 PR) |
| frontend 정보 풍부도 | ★★★ (뉴스 panel) | ★★ (T-AI 시점) | ★ (DART만) |
| KIS-first 정합 | ★★★ (외부 보조 정의 명확) | ★★ (T-AI 흡수 시 검증 의무 잔존) | ★★★ (최소 의제) |
| 트랙 신설 비용 | M (3~4 PR) | L (T-AI 6 Phase 직렬) | S (1 PR) |
| 사용자 학습 가치 | ★★ (정보 표시 분리 가능) | ★★★ (AI 통합 경험) | ★ |

**architect 권장**: **옵션 A** (별도 T-NEWS 트랙 신설). 사유:
1. 자본 보호 직결 의제(DART 실시간)는 T-AI 시점까지 지연 risk 비허용
2. frontend 뉴스 panel은 T-AI 비활성 시에도 가치 있음 → T-AI 의존 분리가 자연
3. 사용자 비전 정합 = 룰 본체 + AI 옵션 보조 → 뉴스 = "정보 수집" 영역 (룰·AI 양쪽 input 둘다 가능)
4. T-AI 진입 사이클에서는 본 T-NEWS 출력(news 테이블 + Kafka 토픽) reuse만 하면 됨 → 의존 분리 깔끔

단, 본 권장은 **사용자 결정 위임 의제** — 최고 관리자 명시 우선.

---

## 4. Phase 분리 후보 (옵션 A 채택 시 정밀 명세 의제)

### Phase 1 — DART 실시간 폴링 강화 (market-data + backend-core)

| scope | 상세 |
|---|---|
| `DartDisclosureAdapter` cron 빈도 강화 | 현재 ? (CorpActionSyncJob 기존 cron) → 1분/회. KIS Rate Limit 무관 (DART 자체 한도 20,000/일 = 분당 ~14건 여유) |
| `disclosure.new.v1` Kafka topic publish | 신규 공시 detect 시 + 정정공시 분류 + 악재/호재 키워드 매칭(단순 contains — AI 호출 0) |
| `corp_action_disclosure` 테이블 갱신 | 영속(기존 baseline 활용) + 신규 공시 row INSERT + STOMP `/topic/disclosure/symbol/{code}` fanout (옵션) |
| KillSwitch auto-ON 트리거 | 정정공시 + 사용자 보유 종목 매칭 시 옵션 토글 (사용자 결정 대기 — 자동 vs 수동) |
| 단위 테스트 ~8건 | cron skip · 정정공시 분류 · 키워드 매칭 · Kafka publish · KillSwitch 트리거 |

### Phase 2 — 네이버 뉴스 검색 어댑터 (backend-core)

| scope | 상세 |
|---|---|
| `NaverNewsAdapter` api 외부 어댑터 | endpoint `https://openapi.naver.com/v1/search/news.json` + 헤더 `X-Naver-Client-Id` + `X-Naver-Client-Secret` + rate limit (25K/일 ≈ 분당 17건) |
| Flyway V31 — `news` 테이블 (ADR 0010 §5 정합 — 멀티테넌트 공유) | `id BIGSERIAL · symbol VARCHAR(20) · title TEXT · summary TEXT · source VARCHAR(50) · published_at TIMESTAMPTZ · url TEXT · created_at TIMESTAMPTZ` + UQ(symbol+url) 멱등성 |
| Kafka topic `news.collected.v1` publish | 신규 뉴스 detect 시 + 종목 매핑 (검색어 = `symbol.name`) |
| Watchlist 우선순위 cron | Watchlist 보유 종목 우선 검색 (rate limit 절감) |
| 단위 테스트 ~10건 | Naver API mock · 종목 매핑 · 멱등성 UQ · rate limit · Kafka publish |

### Phase 3 — frontend 뉴스·공시 panel (frontend)

| scope | 상세 |
|---|---|
| `NewsPanel.tsx` 컴포넌트 | 종목 상세 페이지 추가 — REST `GET /api/news/symbol/{code}?limit=20` + STOMP subscribe |
| `DisclosurePanel.tsx` 컴포넌트 | 종목 상세 페이지 추가 — REST `GET /api/disclosure/symbol/{code}?limit=20` + 정정공시 강조 |
| STOMP `/topic/news/symbol/{code}` 구독 | 실시간 신규 뉴스 push 표시 (옵션) |
| 단위 테스트 ~6건 | 빈 상태("뉴스가 없습니다") · loading · STOMP 실시간 push · 정정공시 강조 |

### Phase 4 — RSS 보조 (옵션)

| scope | 상세 |
|---|---|
| `GoogleNewsRssAdapter` · `HankyungRssAdapter` | Naver API 한도 보완 시. 단순 RSS parser + 종목 매핑 (제목/본문 contains) |

---

## 5. KIS-first 정합 검증

### 5-1. ADR 0057 본문 정합

ADR 0057 §1 — **시세 데이터** 단일 출처. 시세·캔들·호가 = KIS LOCK. 뉴스·공시·재무 = 외부 보조 OK 명시 (master-architecture §B-3 정합).

### 5-2. 사용자 메모리 `feedback_kis_first_policy.md` 정합

"Yahoo 등 외부 fallback 폐기. 시세·캔들 모두 KIS OpenAPI 단일 출처. MCP·open-trading-api 레포 정합 의무" — **시세·캔들에 한정**. 뉴스·공시는 별도 의제 (본 메모리 외 범위).

### 5-3. master-architecture §B-3 정합

> NAVER 금융 / 한경 — 뉴스 본문 — **옵션** — api 외부 어댑터 — 끊겨도 본체 영향 0

→ 본 ADR 0073 옵션 A·B·C 모두 정합. "끊겨도 본체 영향 0" 의무 LOCK (sub-agent 디스패치 시 NewsAdapter 호출 실패 → graceful degrade + UI "뉴스 데이터 없음" 한국어 표시 — mock 합성 0 정책 정합).

### 5-4. KIS-first 확장 안 매트릭스 (옵션 D — 사용자 의제 후보)

KIS-first 정책을 **모든 외부 데이터**로 확장하는 옵션:

| 확장 | 효과 | 단점 |
|---|---|---|
| 시세 + 뉴스 모두 KIS만 | 의제 단순화 극단 | KIS OpenAPI는 뉴스 endpoint 0 → 옵션 C 강제 + 사용자 비전 (T-AI 뉴스 prompt input) 후퇴 |
| 시세만 KIS / 뉴스는 보조 OK (현행) | 사용자 비전 정합 + 자본 보호 의제 trace | 본 ADR 옵션 A·B·C 비교 의제 잔존 |

→ **현행 정책 (시세만 KIS + 뉴스는 보조 OK) 유지가 사용자 비전과 정합** — 옵션 D는 비권장.

---

## 6. 사용자 결정 필요 항목 (의제 매트릭스)

본 사이클은 **analysis ADR만 발행** = 사용자 결정 후 별도 사이클에서 옵션 A·B·C 채택 + sub-agent 디스패치 진행.

### 의제 1 — 트랙 신설 옵션 (A·B·C·D)

| 옵션 | 권장도 | 의미 |
|---|---|---|
| **A — T-NEWS 별도 트랙 신설** | ★★★ architect 권장 | DART 실시간 + Naver 뉴스 + frontend panel. 1~1.5주. ADR 0074 신규 발행 |
| B — T-AI 흡수 | ★★ | T-AI 진입 시 묶음 spec. 자본 보호 의제 지연 risk |
| C — DART 폴링 강화만 | ★ | 단발 PR. Naver 뉴스 비범위 LOCK |
| D — KIS-first 극단 확장 | 비권장 | 뉴스 영역 전체 비범위. 사용자 비전 (T-AI) 후퇴 |

### 의제 2 — DART 실시간 폴링 빈도

| 옵션 | trade-off |
|---|---|
| 2-1 — 1분 (권장) | DART 20K/일 ≈ 분당 14건 여유. 사고 인지 ~1분 지연 한계 |
| 2-2 — 5분 (현행) | API 호출 절감. 사고 인지 ~5분 지연 |
| 2-3 — 30초 | rate limit 한계 근접. 효익 limited |

### 의제 3 — Naver Open API 키 등록 시점

| 옵션 | trade-off |
|---|---|
| 3-1 — Phase 2 진입 시 사용자 .env 등록 (권장) | ADR 0069 §F NaverApiSection 활성화 + .env.example 갱신 |
| 3-2 — SettingsModal NaverApiSection 1차 PR (Phase 2 prereq) | UI 통합 우선 — 사용자 입력 흐름 자연 |

### 의제 4 — KillSwitch auto-ON 트리거 (정정공시 + 보유 종목 매칭)

| 옵션 | trade-off |
|---|---|
| 4-1 — 자동 트리거 + Slack 알림 | 자본 보호 최강. false positive risk (정정공시 일부는 호재) |
| 4-2 — Slack 알림만 + 사용자 수동 KillSwitch | 안전 path. 사용자 부재 시 reaction 지연 risk |
| 4-3 — 비활성 (단순 표시만) | 의제 최소화. 자본 보호 효과 0 |

### 의제 5 — 뉴스 감성 분석 분리 vs T-AI 흡수

| 옵션 | trade-off |
|---|---|
| 5-1 — T-AI 흡수 (권장) | 별도 트랙 신설 비용 회피. AI 호출 비용 동반 |
| 5-2 — T-NEWS Phase 4 진입 | 룰 기반 키워드 매칭만 (AI 호출 0). 정확도 한계 |

### 의제 6 — 뉴스 panel UI 위치

| 옵션 | trade-off |
|---|---|
| 6-1 — 종목 상세 페이지 sidebar (권장) | ADR 0040 관심종목 우선 UI 흐름 정합 |
| 6-2 — 별도 `/news` 페이지 | 사용자 접근 path 추가 |
| 6-3 — Dashboard 위젯 | 사용자 부재 시 인지 우선 |

### 의제 7 — Kafka 신규 토픽 매트릭스

| 옵션 | trade-off |
|---|---|
| 7-1 — `disclosure.new.v1` + `news.collected.v1` 2 토픽 분리 (권장) | T-AI listener 분기 깔끔 |
| 7-2 — `news.event.v1` 단일 토픽 + discriminator | 토픽 절감. listener 분기 비용 |

### 의제 8 — 진입 시점 (사용자 의제 일정)

| 옵션 | trade-off |
|---|---|
| 8-1 — W3 마감(v3.6.4 T-J·T-PERF) 직후 → W3.5 v3.7.0 신설 (권장) | 자본 보호 의제 단발 trace |
| 8-2 — W4 T-AI 진입 직전 묶음 spec | 옵션 B와 유사 |
| 8-3 — 사용자 자율 트리거 시 | 의제 LOCK 해제 |

---

## 7. 비범위 (본 ADR 0073 명시)

- **자동 매매 결정** — 뉴스·공시는 절대 매매 트리거 사용 0 (ADR 0001 룰 본체 비전 LOCK). 옵션 4-1 KillSwitch auto-ON조차도 "매매 결정"이 아닌 "매매 중단"임을 명시
- **뉴스 본문 풀 텍스트 영속** — Naver API 응답은 제목 + summary만 영속 (DB 부하 + 저작권 risk). 본문은 url 링크만
- **뉴스 다국어** — 한국 주식 본체 비전 → 한국어 뉴스만. 영어 뉴스(Bloomberg·Reuters)는 post-MVP
- **외국인·기관 수급 정밀** — master-architecture §11 정합 post-MVP
- **Naver scrape (RSS 외 HTML 파싱)** — TOS risk + 안정성 risk → Open API 단일 path LOCK
- **AI 감성 분석 본격** — 옵션 5-1 T-AI 흡수. backlog §62 후속
- **뉴스 알림 push** — Slack/이메일 알림은 T-NOTIFY 합류 (ADR 0044 baseline) — 본 ADR 비범위

---

## 8. 한계 + architect 자체 검증

### 8-1. 자체 추론 lens 검증 (헌법 §3)

| 항목 | 자체 추론 여부 | raw 인용 |
|---|---|---|
| DART list.json endpoint·한도 | 일부 자체 추론 (운영 코드 인용 + 공식 가이드 URL 명시) | `https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001` ✅ |
| Naver Open API endpoint·한도 25K/일 | 자체 추론 risk (공식 raw fetch 미수행) | **채택 시 sub-agent 디스패치 단계 raw fetch 의무 LOCK** ❌ → ADR 본 §2-1 갱신 |
| Naver Client ID/Secret 헤더명 | 자체 추론 (메모리 기반) | **채택 시 raw fetch 의무** ❌ |
| 트랙 신설 옵션 trade-off | architect 자체 판단 | 사용자 결정 의제로 위임 ✅ |

→ **본 ADR 0073은 analysis only** = 자체 추론 risk 존재 부분은 사용자 결정 후 별도 사이클 raw fetch + ADR 0074 신규 발행 시 보강 LOCK.

### 8-2. 사용자 결정 의제 8건 요약

본 ADR §6 정합 — 의제 1(트랙 옵션 A·B·C·D) + 의제 2(DART 빈도) + 의제 3(Naver 키 시점) + 의제 4(KillSwitch auto-ON) + 의제 5(감성 분리/흡수) + 의제 6(UI 위치) + 의제 7(Kafka 토픽) + 의제 8(진입 시점).

architect 권장: **A-1 + 2-1 + 3-1 + 4-2 + 5-1 + 6-1 + 7-1 + 8-1** (옵션 A 채택 + DART 1분 + Naver Phase 2 진입 시 .env 등록 + Slack 알림만 + T-AI 흡수 + sidebar + 토픽 2분리 + W3 마감 직후 v3.7.0).

### 8-3. 후속 ADR 0074 발행 의무 (채택 시)

옵션 A 채택 시 ADR 0074 — T-NEWS 정밀 spec/plan 발행. ADR 0070 패턴 유사 (Phase 분리 LOCK + DDL + Kafka 토픽 + REST endpoint + UI + 검증 시나리오). raw fetch 4건(DART list.json + corpCode.xml + Naver search/news.json + RSS 옵션).

### 8-4. 본 ADR 0073 status 갱신 트리거

| 트리거 | 갱신 |
|---|---|
| 사용자 결정 옵션 A 채택 | Proposed → **Superseded** (by ADR 0074) — 분석 완료 + 정밀 spec 0074로 위임 |
| 사용자 결정 옵션 B 채택 | Proposed → **Accepted (T-AI prereq)** — T-AI 진입 사이클 시 묶음 spec ADR 0074 발행 |
| 사용자 결정 옵션 C 채택 | Proposed → **Accepted (단발 PR)** — DART 폴링 강화 단발 PR만 + Naver·감성 LOCK |
| 사용자 결정 옵션 D 채택 | Proposed → **Rejected** — KIS-first 극단 확장 (비권장) |
| 사용자 결정 지연 (>1 cycle) | Proposed 유지 + backlog §52 진행 보고 |

---

## 9. 결론 (architect 권장 summary)

| 항목 | 내용 |
|---|---|
| **시작 여부 답변** | **Yes — DART 실시간 강화 + Naver 뉴스 모두 권장** (옵션 A) |
| **권장 트랙 신설** | T-NEWS (W3.5 또는 W4 prereq) — 별도 트랙, T-AI 의존 분리 |
| **Phase 분리** | Phase 1 DART 실시간(market-data + backend-core) → Phase 2 Naver(backend-core) → Phase 3 frontend panel → Phase 4 RSS 보조(옵션) |
| **자본 보호 효과** | 정정공시·악재 즉시 인지 (사고 인지 ~1분) + KillSwitch 트리거 가능 (사용자 의제 4) |
| **AI 의존** | 0 (룰 기반 키워드 매칭만) — T-AI 진입 시 본 출력(news 테이블 + Kafka topic) reuse |
| **KIS-first 정합** | OK (시세만 KIS LOCK + 뉴스는 보조 OK — master §B-3 정합) |
| **사용자 결정 위임** | 의제 8건 (§6 매트릭스) — 본 사이클 코드 변경 0, 별도 사이클에서 ADR 0074 신규 발행 LOCK |
| **다음 단계** | (a) 사용자 옵션 1~8 결정 → (b) architect ADR 0074 풀 spec 발행 (Phase 분리 LOCK + raw fetch 4건 + DDL + Kafka + REST + UI + 검증 시나리오) → (c) sub-agent 디스패치 (market-data + backend-core + frontend) |
