# 07 — MD 룰 정책 진화 (v3.2.0 → v3.2.2)

> **작성자**: architect (PR-48)
> **시점**: 2026-05-11 v3.2.2 태그 컷 직후
> **사용자 명시 의도**: "우리 md 룰 정책의 변화로 더 나아지고 있다는 점을 어필 할 수 있는 목록" (v3.1.0 baseline에서 도입 — v3.2.2까지 누적 갱신)

본 문서는 **v3.2.0 → v3.2.2 정책 진화**를 추적한다. 추측 0 — 모든 룰은 운영 헌법(`_workspace/arch/agent-charter.md`) 7 § + ADR 0049~0063 + backlog 처리 매트릭스로 추적 가능.

각 § 본문은 **도입 전 / 도입 후 / 효과 / 사례 PR** 4단 구조다 (v3.1.0·v3.2.0 baseline 정합).

**v3.2.0 baseline 보존**: §1~§7 운영 헌법 7 § + ADR 0049~0055 정책은 v3.2.2 사이클에서 **갱신 0** (안정화). 본 v3.2.2에서는 §1~§5 5건 신규 정책 진화 + §6 다음 Wave 정책 후보 갱신.

**v3.2.2 사이클 정책 진화 5건**:
1. ADR 0056 — W3 T-BT Backtest Engine Architecture (룩어헤드·세·수수료·슬리피지 + sandbox 통합 — W3 본격 진입 baseline)
2. ADR 0057 — KIS OpenAPI 단일 데이터 출처 (Yahoo 폐기 + prod credential 단일 채널 LOCK — 데이터 정책 진화 핵심)
3. ADR 0062 — 5m·15m·30m·90m·1h 합산 가공 plan (1m → N분봉 백엔드 precomputed — 사용자 비전 정합 baseline)
4. ADR 0063 — NXT/통합거래 매트릭스 cover (시장 분류 코드 결정 — KIS-first raw fetch 정책 강화)
5. 사용자 메모리 보강 2건 — `feedback_kis_first_policy.md`·`feedback_kis_key_realonly_for_data.md` (KIS-only 데이터 정책 강화)

---

## §0. 운영 헌법 7 § (보존 — v3.1.0·v3.2.0 baseline 그대로)

운영 헌법 7 §는 v3.1.0 PR-26(#224)에서 통합 발행된 후 v3.2.0 사이클에서 보존 + v3.2.2 사이클 동안 **본문 갱신 0**. 안정화 검증 — 본 v3.2.2 36 PR 사이클에서 다음 영역 모두 헌법 정합:

| § | 제목 | v3.2.2 사이클 검증 |
|---|------|---------------------|
| §1 | task.mjs 4단계 의무 | 모든 sub-agent 디스패치 사이클(PR #284·#295·#300·#301·#302·#303·#48) start·check·done 4단계 호출 — 누적 task archive PR #285·#288·#291·#307 정합 |
| §2 | 머지 후 정리 4단계 | PR #284·#295·#300·#301·#302·#303·#48 머지 후 즉시 worktree 제거 + task archive PR 4건 (#285·#288·#291·#307) |
| §3 | KIS-first raw fetch 의무 | **본 v3.2.2 사이클이 헌법 §3을 가장 강하게 검증한 사례** — ADR 0057·0062·0063 모두 KIS open-trading-api raw URL 직접 인용 + 자체 추론 0건. 사용자 명시 "kis에 있는 데이터만 사용하라고" + "NXT 8시작 하고 밤에 끝나는거" 의제 — 모두 raw fetch 결과로 검증. ADR 0063 §1-4에 `inquire_time_dailychartprice.py` raw URL + `fid_cond_mrkt_div_code (str): [필수] 조건 시장 분류 코드 (ex. J:KRX, NX:NXT, UN:통합)` line 직접 인용. |
| §4 | CHANGELOG 슬롯 채번룰 | PR #294·#298·#299·#306·#309 모두 anchor 사전 발행 + sub-agent in-place edit. modify-shared 충돌 0건. **본 PR-48 baseline 사이클도 anchor 4건(`<!-- pr-48-slot-architect/backend-core/market-data/frontend -->`) 사전 발행 정합** |
| §5 | PR 생성 직전 rebase 의무 | 본 PR-48 포함 모든 사이클 적용 — 통합 빌드 fail 0건 |
| §6 | JPA Repository default fun 함정 | 신규 Repository 0건 — 변경 0 |
| §7 | sub-agent fail 분기 룰 | PR #295·#300·#301·#302·#303 5 본격 사이클 — 재디스패치 0건. orchestrator 자율 진행(헌법 §7 자율 진행 정합) |

**baseline 정합**: v3.2.0 시점 통합한 헌법이 v3.2.2 데이터 정책 3중 결정 묶음 + W3 T-BT 진입 + 6 hotfix 사이클을 완주 — 갱신 의제 0건.

---

## §1. ADR 0056 — W3 T-BT Backtest Engine Architecture

### 도입 전

v3.2.0 시점 master-architecture.md §B 분석·전략 영역의 5번째 컴포넌트 Backtest는 **placeholder 상태**:
- `domain/backtest/*` 코드 0건
- 백테스트 결과 영속 0건 (Flyway V12+ 미발행)
- W3 사이클 본격 진입 시 sandbox 정합·룩어헤드 가드·세금·수수료·슬리피지 표준 결정 미발행

W3 본격 진입 전에 **백테스트 엔진의 핵심 책임·sandbox 재사용 정책·룩어헤드 가드 표준**을 architect 단독 결정으로 baseline 컷 필요.

### 도입 후

ADR 0056 (PR #282 — PR-47 spec) — Backtest Engine 강화 + W3 진입 baseline:

**도메인 본체 (PR #284 — PR-48 본격)**:
- `domain/backtest/*` 5 sealed class (Pure Kotlin · Spring·JPA 의존 0):
  - `BacktestRun` — 백테스트 실행 단위 (universe·tf·strategy·기간·자본·결과)
  - `BacktestResult` — 결과 (수익률·MDD·승률·Sharpe·체결 시뮬레이션)
  - `LookaheadGuard` — 룩어헤드 가드 (signal 발생 시점의 timestamp 강제 검증)
  - `Adjustment` — 보정 (세 0.23% · 수수료 0.015% · 슬리피지 시뮬레이션 옵션)
  - `BacktestError` — 결함 분류 (DATA_NOT_FOUND · LOOKAHEAD_VIOLATION · TIMEOUT 등)

**sandbox 통합 정책**:
- `JvmStrategyEvaluator` 재사용 (ADR 0047 정합) — 백테스트 hot-loop에서 strategy 평가 시 sandbox 시간 한도 100ms · CPU budget 50ms · strategy-eval-pool 4 fixed
- `auto-disable` 콜백은 백테스트 hot-loop에서 비활성화 (전체 백테스트 timeout이 별도 25초 한도 ADR 0056 §3)
- 단위 테스트는 도메인 본체 단위 (JVM 의존 0)

**룩어헤드 가드 정책**:
- 백테스트 hot-loop의 각 candle 시점에서 strategy 평가는 **해당 시점 이전의 데이터만** 접근 가능
- `LookaheadGuard` 검증 — `signal.timestamp >= candle.timestamp` 위반 시 `BacktestError.LOOKAHEAD_VIOLATION` throw
- 지표 계산도 동일 — `IndicatorEngine` cache-aside는 룩어헤드 가드 fixture 있는 모드로 호출 (백테스트 hot-loop 한정)

**보정 로직 정책**:
- 세 0.23% 한국 주식 매도 시 강제 적용
- 수수료 0.015% 매수·매도 양쪽 강제 적용 (KIS 일반 수수료 baseline)
- 슬리피지 시뮬레이션 옵션 (기본 0, 사용자 설정으로 0.05% 또는 0.1% 옵션)

### 효과

- W3 본격 진입 baseline 확립 — 다음 minor 사이클(v3.3.0)에서 API REST `BacktestController` + 영속 Flyway V12 `backtest_run` + frontend `/backtest` 라우트 발행 시 도메인 본체 변경 0건
- sandbox 재사용으로 코드 중복 0건 (T-DSL과 동일 sandbox)
- 룩어헤드 가드는 백테스트 결과 신뢰성의 핵심 — fitness gap 0

### 사례 PR

- **PR #282** (PR-47 spec) — architect spec/plan + ADR 0056 신규 발행
- **PR #284** (PR-48 본격) — domain+api+consumer 통합 — strategy-engine 단일 sub-agent + ADR 0056 정합

---

## §2. ADR 0057 — KIS OpenAPI 단일 데이터 출처 (Yahoo 폐기 + prod credential 단일 채널 LOCK)

### 도입 전

v3.2.0 시점 데이터 출처가 혼합:
- 일·주·월·년봉 = KIS `FHKST03010100`
- 1m·5m·15m·30m·90m·1h cold-start = Yahoo Finance v8 chart endpoint (`https://query1.finance.yahoo.com/v8/finance/chart/{ticker}`)
- 틱 = KIS WS `H0STCNT0`

**사용자 dogfooding 결함 (2026-05-11 채팅)**:
- 호출: `GET /api/symbols/000660/candles?interval=1d&limit=200`
- 기대: SK하이닉스 일봉 200건 (2009-11 ~ 2026-05 KST 일자별)
- 실제: 매월 1일 KST 00:00 정확히 떨어지는 ts·`source=YAHOO`·316건 = **월봉이 일봉으로 라벨링되어 응답**

root cause: `YahooCandleFetcherAdapter`의 `CandleInterval.ONE_DAY to "max"` 매핑이 Yahoo v8 chart endpoint의 monthly downsample 응답을 일봉 라벨로 INSERT.

**사용자 명시 결정 (2026-05-11 채팅 2회)**:
1. "야후꺼 쓰지 말라니깐"
2. "kis에 있는 데이터만 사용하라고"

→ Yahoo Finance 전면 폐기 + KIS OpenAPI 단일 출처 정책 확립 의제 발의.

**모의/실전 credential 정책 미명확** (사용자 명시 2026-05-11):
> "정보 수집 API는 prod 키 단일 채널 사용. 모의/실전 분기는 주문 라우터 거래 액션에만 적용"

기존 ADR(0028·0029)에는 "사용자 credential 분기" 가능성이 모호하게 남아있었음 — v3.2.2 사이클에서 LOCK.

### 도입 후

ADR 0057 (PR #294) — KIS OpenAPI 단일 데이터 출처 (Yahoo Finance · 네이버 금융 등 보조 출처 폐기):

**데이터 출처 매트릭스 (§2-1)**: 틱·1m·1d·1wk·1mo·1y 모두 KIS REST/WS 단일 출처 + prod credential 단일 채널 (`AccountType.REAL` only)

**5m·15m·30m·90m·1h 처리**: ADR 0019 §2-5 "합성 폐기" 결정 부분 supersede — "1m 합산 가공으로 처리 보류 (backlog §31)" + 위치(백엔드/프론트) + source 시간 범위 결정은 ADR 0062로 후속 (사용자 명시 채팅 "ADR 폐기는 하지말어 백로그로 남겨")

**Yahoo 폐기 절차 (§2-4)**:
- `YahooCandleFetcherAdapter.kt` `@Deprecated` + `@Component` 제거
- `WatchlistIngestionWorker` cold-start KIS-only 전환
- `ColdStartExecutor` 7 interval → 4 interval (KIS REST 1d·1wk·1mo·1y만)
- `application.yml` `yahoo.*` 환경 제거
- 일회성 SQL `DELETE FROM candle WHERE source='YAHOO' AND interval='1d'` + KIS REST 재backfill (PR #295)

**fitness gap 분석 (§1-4)**: Yahoo = v2 잔재 lens 정합 — 폐기 정당. KIS-only는 자동매매 본체(룰 기반 quant trading + KIS-first 정책 — `master-architecture.md` §1) 정합.

**사용자 메모리 보강 2건**:
- `feedback_kis_first_policy.md` — KIS-only 데이터 정책
- `feedback_kis_key_realonly_for_data.md` — 정보 수집 API = prod credential 단일 채널

### 효과

- 사용자 dogfooding 결함 root cause 해소 (월봉→일봉 INSERT 결함 자체 발생 불가)
- KIS 단일 source 정합 — 룰 엔진·DSL·신호·백테스트 모두 동일 출처(KIS 수정주가) — fitness gap 0
- cold-start 단순화 — Yahoo backfill 호출 7건 제거 → KIS REST 4건만 + 당일 1m Redis only
- prod credential 단일 채널 LOCK — 모의/실전 분기 모호성 해소

### 사례 PR

- **PR #294** (architect spec/plan) — ADR 0057 발행 + ADR 0019 §2-5 부분 supersede + backlog §31 등록 (5m~1h 합산 위치·source 결정 트랙)
- **PR #295** (market-data 본격) — Yahoo Finance 전면 폐기 + KIS-only 1d/1m cold-start backfill

---

## §3. ADR 0062 — 5m·15m·30m·90m·1h 합산 가공 plan (1m → N분봉 백엔드 precomputed)

### 도입 전

ADR 0057 발효 직후 5m·15m·30m·90m·1h은 "1m 합산 가공 예정 (backlog §31 트랙)" 표시 상태 — 사용자 채팅 "1분봉만 kis에서 제공하니깐 임시로 쓰는거고 나중에 5m·15m·30m·90m·1h 봉에 대한 처리를 보류할뿐임".

사용자 비전(`project_system_vision.md`) lens — magicJar는 **MCP 서버 + 룰 기반 quant trading 본체** — 합산 위치 결정이 자동매매 본체 정합에 직접 영향:
- **MCP 외부 client** (Claude·Cursor·Cline 등) 5m·15m·30m·90m·1h interval 요청 가능해야 → **프론트 합산으로는 cover 불가**
- **룰 엔진·DSL** (T-DSL·T-SB) 5m·30m 인터벌 조건식 사용 → **백엔드 합산 정합**
- **백테스트 엔진** (T-BT, ADR 0056) 5m·15m 데이터 호출 시 in-memory 매번 합산 비효율 → **precomputed 정석**
- **시그널 엔진** (T-SIG) 실시간 5m·30m 시그널 생성 시 합산 fresh 필요

### 도입 후

ADR 0062 (PR #298 plan + PR #300·#302·#303 본격) — A-1 백엔드 precomputed + 옵션 2 과거 30 거래일 + 5종 활성화 채택:

**의제 A 합산 위치**: A-1 백엔드 precomputed (cold-start 1m 적재 직후 5m~1h 합산 → DB INSERT) 채택. A-2 on-the-fly · A-3 하이브리드 · B 프론트 합산 거부 사유 명시 (사용자 비전·룰엔진·MCP·백테스트 정합 lens).

**의제 B source 시간 범위**: 옵션 2 과거 30 거래일 채택 (`FHKST03010230` prod credential, 현재 main 1m 적재 완료 상태 활용 — 추가 KIS 호출 0건).

**의제 C UI 매트릭스**: 5종 활성화 (5m·15m·30m·90m·1h 모두) — `IntervalToggle` 5종 노출 + LWC v4 차트 `setData()` 정상 동작.

**`CandleAggregator` 도메인 본체** (`domain/.../market/CandleAggregator.kt`, PR #300):
- Pure Kotlin · Spring·JPA 의존 0
- 입력 `List<Candle1m>` (정렬 보장) · 출력 `List<CandleNm>`
- 합산 공식 OHLCV = (first.open, max(highs), min(lows), last.close, sum(volumes))
- 단위 테스트 17건 (JVM 의존 0)

**`AggregateBackfillRunner`** (`consumer/.../market/AggregateBackfillRunner.kt`, PR #303):
- `@Component` ApplicationRunner
- cold-start 시 1m 적재 직후 5m~1h 5종 일괄 합산 + DB INSERT(`ON CONFLICT DO NOTHING`)

**§9 NXT 결합 영향**: ADR 0063 채택 후 boundary 정렬 매트릭스 + DB ROW 예측 + DailyCloseFinalizer 20:00 KST 트리거 확장 + sub-agent 영역 분리·직렬 권장 매트릭스 추가.

### 효과

- 5m·15m·30m·90m·1h 합산 가공 활성화 — 사용자 비전(MCP·룰엔진·백테스트) 정합
- DB ROW 38K → 71K 추가 (현재 1m 100K+ 대비 미미) — NXT cover 결합 영향 검증
- 추가 KIS 호출 0건 — 1m 적재 완료 상태 활용 (현재 main에 30 거래일 적재 완료)
- LWC v4 차트 즉시 응답 — precomputed SELECT
- 자동매매 본체 정합 — 룰 엔진·DSL·백테스트 모두 동일 합산 source 참조
- MCP 서버 노출 정합 — 외부 client(Claude·Cursor 등) 5m~1h interval 요청 가능

### 사례 PR

- **PR #298** (architect spec/plan) — ADR 0062 발행 + 의제 3건 사용자 채택 (Accepted 갱신)
- **PR #300** (strategy-engine) — `CandleAggregator` 도메인 본체 + 단위 테스트 17건
- **PR #302** (frontend) — `IntervalToggle` 5종 활성화 + LWC NXT X축 범위 점검
- **PR #303** (market-data) — `AggregateBackfillRunner` 신규 + `ColdStartExecutor` 확장 + `DailyCloseFinalizer` 20:00 KST 확장

---

## §4. ADR 0063 — NXT/통합거래 매트릭스 cover (KIS-first raw fetch 정책 강화)

### 도입 전

NXT(Next-Trading Korea Exchange — 2025년 출범)는 KOSPI 200 등 KRX 정규 상장 대형주를 **연장된 시간대(08:00~09:00 pre-hour + 15:30~20:00 after-hour)**로 거래 가능. 대표 종목: 005930(삼성전자) · 000660(SK하이닉스) · LG에너지솔루션 등.

v3.2.0 시점 magicJar 데이터 cover **KRX 정규장만(09:00~15:30)**:
- `KisRestCandleAdapter` `fid_cond_mrkt_div_code="J"` (KRX 고정)
- 시작 hour `153000` · 종료 조건 `< 09:00 KST` · `MIN_1M_CANDLE_THRESHOLD=350`

**사용자 dogfooding 의제 (2026-05-11 채팅)**:
> "삼성전자나 sk하이닉스 같은경우는 정상장 시간보다 빨리시작하고 늦게까지 장이 열리잖아? NXT 8시작 하고 밤에 끝나는거 그런건 감안 안되있는거같은데?"

→ NXT pre-hour + after-hour 거래 데이터 누락 — 자동매매 본체의 시그널·룰엔진·백테스트가 NXT 시간대 가격 변동을 인지 못함.

**KIS-first raw fetch (헌법 §3) 점검**:
- ADR 0019·0028·0029·0057 모두 NXT 인용 0건
- `docs/architecture/external-api-policy.md` · `candle-data-policy-v2.md` 모두 NXT 인용 0건
- `instrument` 테이블 NXT 거래 가능 종목 식별 컬럼 부재

→ NXT는 magicJar 운영 자료에서 **명시적으로 미인식** 상태.

### 도입 후

ADR 0063 (PR #299 plan + PR #301 본격) — A UN 통합 + 시간 1 08:00~20:00 + threshold 350 → 700 채택:

**KIS-first raw fetch 의무 충족 (§1-4)**:

```
raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py

fid_cond_mrkt_div_code (str): [필수] 조건 시장 분류 코드 (ex. J:KRX, NX:NXT, UN:통합)
```

→ 자체 추론 0건. KIS 공식 함수 시그니처 line 직접 인용 + UN 코드 사용으로 KRX+NXT 통합 응답 수신.

**의제 A 시장 분류 코드 cover**: A UN 통합 채택 — `fid_cond_mrkt_div_code="UN"` 단일 호출 → KIS가 KRX+NXT 통합 응답 반환. B NX 분기 + instrument 컬럼 신설 거부 사유 명시(추가 마이그레이션 부담 + KOSDAQ 미지원 risk).

**의제 B 시간 범위 cover**: 옵션 1 08:00~20:00 명시 채택 — 시작 hour `200000`, 종료 조건 `< 08:00 KST`. 옵션 2 KIS 응답에 맡김 거부 사유 명시.

**의제 C threshold 갱신**: 350 → 700 채택 — KRX 정규장 ~390건 → NXT 통합 ~720건 cover. 700 = 720의 97% 마진.

**fitness gap 분석 (§3)**:
- 자동매매에 필요 — NXT 거래 종목(KOSPI 200 대형주)의 pre-hour 시초 + after-hour 시세는 룰 기반 시그널·백테스트·자동매매 본체에 필수
- v1/v2 잔재 아님 — NXT는 2025년 출범 (v1/v2 시점 미존재)
- AI 매매 결정 0건 강제 위반 X — 데이터 수집 매트릭스 결정 only
- KIS-first 정합 — `fid_cond_mrkt_div_code` 옵션은 KIS 공식 raw fetch 인용
- 정보 수집 prod credential 단일 — NXT 호출도 prod credential 단일 채널 사용

**운영 영향 (§4)**:
- KIS Rate Limit: +33% (2,640 calls / 11종목 cold-start) — ADR 0037 한도 안전
- DB 영속: 영향 0 (1m은 Redis only) · Redis 메모리 +5 MB
- cold-start 시간: 종목당 ~12초 (현재 ~9초의 33% 증가) · 풀 11종목 ~2분 12초

### 효과

- NXT 거래 종목(KOSPI 200 대형주)의 pre-hour + after-hour 시세가 시그널·룰엔진·백테스트에 진입
- 자동매매 본체가 NXT 시간대 가격 변동을 인지 → 룰 기반 트레이딩 fitness 보완
- KIS-first 정책 raw fetch 의무 강화 — 모든 `fid_cond_mrkt_div_code`·threshold·시각 상수 결정 시 KIS open-trading-api raw URL 인용 의무 (헌법 §3 baseline 강화)
- ADR 0062 합산 매트릭스 결합 — 1m 베이스 390 → 720 bar/일 확장 + DailyCloseFinalizer 20:00 KST 확장

### 사례 PR

- **PR #299** (architect spec/plan) — ADR 0063 발행 + 의제 3건 사용자 채택 (Accepted 갱신)
- **PR #301** (market-data) — NXT cover hotfix — `KisRestCandleAdapter` 4 line revert + `CandleBootstrapRunner` threshold 700
- **PR #304** (hotfix) — FAILED 3종목 정정 — KOSDAQ market_div J 분기 + 거래정지 skip (NXT cover 후속 dogfooding 결함 정정)

---

## §5. 사용자 메모리 보강 2건 (KIS-only 데이터 정책 강화)

### 도입 전

기존 사용자 메모리:
- `feedback_v3_skepticism_no_regression.md` — v1/v2 회귀 금지 + 자동매매 적합성 의심 lens
- `feedback_no_mock_data.md` — Mock 데이터 사용 금지

NXT cover + KIS-only 결정 직전 시점에 **KIS-first 데이터 정책의 명시적 사용자 메모리 부재** — ADR 0028·0029 본문에 "사용자 credential 분기" 가능성이 모호하게 남아있었음.

### 도입 후

사용자 메모리 보강 2건 추가:

**`feedback_kis_first_policy.md`** (KIS-only 데이터 정책):
> 사용자 명시 (2026-05-11 채팅): "kis에 있는 데이터만 사용하라고" · "야후꺼 쓰지 말라니깐"
- 모든 캔들 데이터 출처 = KIS OpenAPI 단일 채널
- Yahoo·네이버·DART 캔들 0건 (DART는 공시·기업코드 한정 보존)
- KIS MCP 적극 활용 + open-trading-api 레포 baseline
- tr_id·endpoint·DSL 자체 추론 금지 — MCP/공식 레포에서 가져오기 의무

**`feedback_kis_key_realonly_for_data.md`** (정보 수집 API = prod credential 단일 채널):
> 사용자 명시 (2026-05-11 채팅): "정보 수집 API는 prod 키 단일 채널 사용. 모의/실전 분기는 주문 라우터 거래 액션에만 적용"
- 시세·캔들·호가·종목 마스터 등 정보 수집 = prod 키 (실전 키) 단일 채널
- `AccountType.REAL` credential로 KIS REST/WS 호출
- 모의 credential은 정보 수집에서 사용 0건
- 모의/실전 분기는 주문 라우터(`OrderRouter` 등) 거래 액션에만 적용

### 효과

- ADR 0057·0062·0063 본문 모두 본 사용자 메모리 인용 baseline 확보
- 향후 sub-agent 디스패치 시 credential 분기 모호성 해소 — prod 단일 채널 LOCK
- KIS-first 정책 명시화로 헌법 §3 raw fetch 의무 강화 정합

### 사례 PR

- **PR #294** (ADR 0057 발행) — 사용자 메모리 정합 인용
- **PR #298** (ADR 0062 plan) — 사용자 메모리 정합 인용 + § header KIS 키 정책 명시
- **PR #299** (ADR 0063 plan) — 사용자 메모리 정합 + KIS-first raw fetch 의무 §3 강화

---

## §6. 다음 Wave 정책 후보 (W3 본격 진입 시 발행 예정)

W3 본격 진입(Backtest 데이터 적재·실행·결과 영속 + Screener · Signal · Risk · Order · Reconcile · Performance · Notification) 시 발행 예정 ADR/정책:

### 6-1. ADR 후보

| ADR 후보 | 주제 | 트리거 |
|---------|------|--------|
| ADR 0064 | Backtest 표준 룩어헤드/세금/슬리피지 baseline 본격 적용 | W3 T-BT 본격 사이클 시 (ADR 0056 후속) |
| ADR 0065 | Screener 표준 (universe 분류·매트릭스 응답) | W3 T-SC 본격 사이클 시 |
| ADR 0066 | Signal Engine 표준 (signal_event 영속·STOMP fanout) | W3 T-SIG 본격 사이클 시 |
| ADR 0067 | Risk Guard 표준 (kill-switch·MDD·일일 한도) | W3 T-RISK 본격 사이클 시 |
| ADR 0068 | Order Executor 표준 (TTTC·VTTC 분기·idempotency·재시도) | W3 T-O 본격 사이클 시 |
| ADR 0069 | Notification 표준 (Slack webhook·outbox 패턴) | W3 T-J 본격 사이클 시 |
| ADR 0070 | Performance 표준 (Sharpe·MDD·Calmar 계산 baseline) | W3 T-PERF 본격 사이클 시 |
| ADR (1건) | ADR 0019·0028·0029 본문 갱신 통합 (NXT cover + 1m 합산 정합) | v3.3.0 minor 진입 시 architect 단독 PR |

### 6-2. 정책 진화 후보

| 정책 후보 | 주제 |
|----------|------|
| Yahoo Finance 완전 삭제 (v3.3.0 일괄) | `YahooCandleFetcherAdapter.kt` · `YahooFinanceClient.kt` · `application.yml` yahoo.* · 일회성 SQL · 테스트 mock 케이스 모두 삭제 |
| KIS 1m 과거 데이터 cover 확장 (현재 30 거래일 → 60·90·180 거래일) | 백테스트 long-period 정합 + KIS Rate Limit 영향 측정 |
| 합산 가공 source 시간 범위 확장 (당일·30 거래일 → 추가 옵션) | 사용자 결정 + ADR 후속 |
| frontend `/backtest`·`/screener`·`/dashboard` 라우트 발행 | W3 본격 사이클 |
| MCP 서버 노출 (Spring AI MCP starter 활용) | W4 본격 사이클 |
| Risk Guard kill-switch 활성화 (ADR 0042 정합) | W3 T-RISK 진입 시 |

### 6-3. v3.2.2 baseline 보존 정합 의무

W3 본격 진입 시점에 본 v3.2.2 baseline은 `_workspace/baseline/v3.2.2/` immutable(ADR 0031). v3.3.0 신규 baseline 컷(`_workspace/baseline/v3.3.0/`) 시 본 §1~§5 정책 진화 5건은 v3.3.0 baseline 07-policy-evolution.md에 **누적 인용** — `_workspace/baseline/v3.2.2/07-policy-evolution.md §X` 직접 참조 + v3.3.0 신규 정책만 add.

---

## §7. v3.0.1 → v3.2.2 누적 정책 진화 요약 (3 baseline cumulative)

본 baseline 시점까지 누적된 MD 룰 정책 진화는 **v3.1.0 (4건)** + **v3.2.0 (4건)** + **v3.2.2 (5건)** = **13건**. 모두 ADR로 추적 가능:

| baseline | 사이클 | 정책 진화 | ADR 신규 |
|---------|--------|-----------|----------|
| v3.1.0 | PR-26~PR-29 | 운영 헌법 7 § 통합 + ADR 0049 baseline + ADR 0050 슬롯 채번룰 + ADR 0051 Repository default fun | 0049·0050·0051 |
| v3.2.0 | PR-31~PR-45 | ADR 0052 baseline 사이클 정형화 + ADR 0053 Living Policy Docs + ADR 0054 pattern-engine 모듈 분리 + ADR 0055 Strategy Builder UI 컴포넌트 스택 | 0052·0053·0054·0055 |
| **v3.2.2** | **PR-47~PR-48** | **ADR 0056 W3 T-BT + ADR 0057 KIS-only + ADR 0062 합산 가공 + ADR 0063 NXT cover + 사용자 메모리 보강 2건** | **0056·0057·0062·0063** |

**누적 ADR 60건** (v3.1.0 베이스 51건 → v3.2.0 55건 → v3.2.2 60건, 실제 신규 4건 + 슬롯 1건). ADR 0019 부분 supersede revoke(1m 합산 가공 트랙 완료 시 §2 재통합 — v3.3.0 minor 진입 시 architect 단독 PR로 본문 정정).

**baseline cumulative 정합**: v3.1.0 baseline `07-policy-evolution.md` (4건) + v3.2.0 baseline `07-policy-evolution.md` (4건) + 본 v3.2.2 baseline (5건) = 13건 모두 누적 보존 + ADR/메모리/헌법 추적 가능. 사용자 명시 의도 "md 룰 정책의 변화로 더 나아지고 있다는 점을 어필 할 수 있는 목록" 정합.
