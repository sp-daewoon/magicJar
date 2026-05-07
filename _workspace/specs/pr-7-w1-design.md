# PR-7 T-W1 설계 가이드 (Watchlist CRUD + Position Bootstrap — UI·REST·DB·부팅 잔고 워밍)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 대상 PR 시리즈: PR-7a (backend-core) · PR-7b (frontend) — 병렬 가능 (§A.5 충돌 행렬 참조)
- 진입 prerequisite: PR-5a (4 JVM 모듈 + V1 baseline) MERGED ✅ · PR-5b (Vite SPA) MERGED ✅ · **PR-6 T-ONBOARD MERGED ✅** (V2 + Onboarding 5 endpoint + AesGcmCipher + KisAuthValidator + OnboardingGuardFilter + JPA 3종 + UserOnboardedEvent + SetupWizard 7-step)
- 정합 SoT:
  - **ADR 0042** Position Bootstrap & Recovery (1차 권위 — 부팅 잔고 워밍)
  - **ADR 0028** Watchlist 기반 Ingestion Worker (인터페이스만 정의, 구현은 T-W2)
  - **ADR 0040** 관심종목 우선 UI 흐름 (1순위 진입점)
  - **ADR 0046** Onboarding & Credential (KIS 키 복호화 사용 시점)
  - `docs/architecture/master-architecture.md` v2 §A.2 · §D.15 · §3-1
  - `docs/architecture/tracks-roadmap.md` §2 T-W1 행 · §3 T-W1 outline (요약)
  - ADR 0010 Multi-tenant · ADR 0019 Candle Storage · ADR 0011 Order 3-mode · ADR 0037 KIS Rate Limit · ADR 0038 KIS OAuth 캐시
  - `docs/architecture/development-rules.md` §1 룰 5건
- 작업 원칙 (개발 룰북 정합):
  - 룰 2 — 단일 책임 (PR-7a = backend, PR-7b = frontend)
  - 룰 3 — API 빌딩 한 스텝씩 순차. **본 PR-7은 R1 단일 라운드** (도메인 + 시그니처 + 어댑터 + UI 1차 화면). R2(시세 인입·실시간)는 T-W2, R3(테스트 일괄)는 T-S1 또는 별도 묶음
  - 룰 4 — 코드 먼저, 테스트 마지막 일괄 (§9 시나리오 골격 — sub-agent 본인 PR엔 smoke 1~2건만)
  - 룰 5 — AI 호출 0 (W1 트랙 — LLM provider import 절대 금지)

---

## §1. T-W1 한 줄 목표 + DoD

### 1-1. 목표

> **셋업 완료한 사용자가 magicJar 메인 대시보드에 진입했을 때 [Watchlist] 메뉴에서 종목 코드를 입력해 등록·조회·삭제할 수 있게 한다. 동시에 consumer 모듈이 부팅 직후 KIS REST 잔고를 조회해 DB position·Redis position 캐시에 워밍하여, 사용자가 HTS·MTS 다른 채널로 보유한 종목이 시스템 첫 화면부터 정합되게 보이도록 한다 (ADR 0042). Watchlist UI는 magicJar의 1순위 진입점 (ADR 0040 정합).**

### 1-2. DoD (Definition of Done) — PR-7 전체 머지 후 검증

| # | 검증 항목 | 명령·시나리오 | 기대 결과 |
|---|---|---|---|
| 1 | 셋업 완료된 사용자 → /watchlist 진입 | `curl http://localhost:3000` (PR-6 셋업 완료 후) | Watchlist 페이지 라우팅 성공 (Dashboard·Watchlist 메뉴 표시) |
| 2 | 빈 Watchlist 첫 진입 | Watchlist 페이지 진입 | "관심종목 등록하기" CTA + 빈 상태 메시지 |
| 3 | 종목 등록 — REST | `POST /api/watchlists -d '{"code":"005930"}'` | 201 + `{"id":1,"userId":"<8자리>","code":"005930","priority":0,"createdAt":"..."}` |
| 4 | DB 영속 | `psql -c "SELECT user_id,code,priority FROM watchlist"` | 1 row (삼성전자 005930) |
| 5 | 종목 list 조회 — REST | `GET /api/watchlists` | 200 + 1건 배열 |
| 6 | UI 등록 + 즉시 표시 | UI에서 "005930" 입력 + 등록 버튼 | 1초 이내 list 표시 (낙관적 업데이트 또는 invalidate) |
| 7 | 중복 등록 거부 | `POST /api/watchlists` 같은 code 재호출 | 409 + `{"code":"WATCHLIST_DUPLICATE","message":"이미 등록된 종목입니다"}` |
| 8 | 알 수 없는 ticker 형식 거부 | `POST /api/watchlists -d '{"code":"ABCDEF"}'` | 400 + `{"code":"INVALID_TICKER_FORMAT",...}` (6자리 숫자만 허용) |
| 9 | 종목 삭제 — REST | `DELETE /api/watchlists/005930` | 204 |
| 10 | 삭제 후 조회 | `GET /api/watchlists` | 200 + 빈 배열 |
| 11 | UI 삭제 + 즉시 갱신 | UI에서 휴지통 클릭 → confirm → 확인 | row 사라짐 |
| 12 | 부팅 잔고 워밍 — log 확인 | consumer 부팅 후 `docker logs magicjar-consumer-app | grep "PositionBootstrap"` | "Position bootstrap 시작 — user 1명" + "STAGE 0-1 KIS 잔고 조회" + "STAGE 0-2 DB upsert N건" + "STAGE 0-3 Redis 워밍" log |
| 13 | 부팅 잔고 워밍 — DB | `psql -c "SELECT code,quantity,avg_price FROM position WHERE user_id='<8자리>'"` | 사용자 KIS 보유 종목 수만큼 row (KIS 응답 정합) |
| 14 | 부팅 잔고 워밍 — Redis | `redis-cli GET position:current:<userId>` | JSON (보유 종목 list, TTL 24h) |
| 15 | 활성 user 0명일 때 부팅 정상 | 빈 DB 부팅 → consumer log | "Position bootstrap — 활성 사용자 0명 idle" + 정상 부팅 (EXIT 0 아님) |
| 16 | KillSwitch 통합 — 부팅 실패 시 KillSwitch ON | KIS 키 무효 mock (또는 wrong key) → consumer 부팅 | 해당 user `kill_switch_state.enabled=true` + reason="POSITION_BOOTSTRAP_FAILED" + 다른 user 부팅 계속 |
| 17 | OnboardingGuardFilter 통과 | 셋업 완료 사용자 → `GET /api/watchlists` | 200 (401 아님 — Filter pass-through) |
| 18 | LLM provider import 0 | `grep -rE "spring-ai\|anthropic\|openai" {api,consumer,batch}/src/main` | 0건 |
| 19 | watchlist FK CASCADE 검증 | `DELETE FROM user_account WHERE user_id='<8자리>'` | watchlist 모든 row 자동 삭제 (V1 baseline FK CASCADE 정합) |
| 20 | T-W2 인터페이스 신호 — Kafka publish | `POST /api/watchlists` 후 Kafka topic `watchlist.changed.v1` 메시지 | 1건 publish (payload `{userId, code, action: "ADDED"}`) — **단 본 PR-7은 publish만, 실 consumer는 T-W2** |

### 1-3. 비즈니스 가치

- magicJar의 1순위 사용자 진입점 활성화 (ADR 0040)
- T-W2(Ingestion)·T-S1(Symbol Detail)·T-SB(Strategy Builder)의 prerequisite — Watchlist 풀이 모든 후속 데이터 수집의 단위
- Position Bootstrap으로 사용자가 KIS HTS·MTS 다른 채널 매매한 잔고가 magicJar 첫 화면부터 정합 (사용자 신뢰)
- 부팅 잔고 워밍 = T-O(Order) Risk Guard chain의 PositionLimitGuard 신뢰 base 확보 (ADR 0024 정합)

---

## §2. ADR 0042 (Position Bootstrap) 준수 항목 체크리스트

ADR 0042 본문(`_workspace/arch/adr/0042-position-bootstrap-recovery.md`)의 모든 결정 사항을 PR-7에서 구현. 단 ADR 0042는 **Bootstrap + Recovery 2 부분**으로 나뉘는데, 본 PR-7은 **Bootstrap만 구현 + Recovery는 T-W2(WS 연결 자체가 T-W2 진입점)로 미룸**.

| ADR 0042 §위치 | 결정 사항 | PR-7 구현 위치 | 상태 |
|---|---|---|---|
| §2-1 Bootstrap STAGE 0-1 | KIS REST 잔고 조회 (`/uapi/domestic-stock/v1/trading/inquire-balance` TR_ID `TTTC8434R`/`VTTC8434R`) | consumer 모듈 `KisBalanceAdapter` (REST adapter) | **PR-7a 핵심** |
| §2-1 Bootstrap STAGE 0-2 | DB position upsert | `PositionRepository.upsertAll()` (PR-5a domain port에 이미 정의) + JPA adapter | **PR-7a 핵심** |
| §2-1 Bootstrap STAGE 0-3 | Redis `position:current:{userId}` SET (TTL 24h) | consumer `PositionCachePort` 구현 (Redis) | **PR-7a 핵심** |
| §2-1 Bootstrap STAGE 0-4 | KIS WS 구독 시작 | **본 PR-7 비범위 — T-W2** (consumer가 처음 KIS WS 도입하는 트랙) | T-W2 |
| §2-1 Recovery | WS reconnect 시 `inquire-daily-ccld` (TTTC0081R) gap 보정 | **본 PR-7 비범위 — T-W2** (WS 자체가 T-W2) | T-W2 |
| §2-3 Redis TTL | `position:current:{userId}` TTL 24h + 매 체결 시 refresh | 24h 적용. 매 체결 refresh는 T-W2(WS) 진입 시 | **PR-7a 부분** |
| §2-3 Redis TTL | `position:lastUpdatedAt:{userId}` TTL 7일 | Recovery용 — 본 PR-7에선 미설정. T-W2에서 추가 | T-W2 |
| §2-4 master §3-1 갱신 | `[0] Position Bootstrap` 단계 prepend | master `[0]`은 이미 §3-1에 있음 (PR-3a에서 추가됨) — 갱신 0 | **변경 0** |
| §2-5 활성 사용자 정의 | `users.trading_mode IN (MOCK_KIS, REAL_KIS) AND kis_app_key IS NOT NULL` | **PR-7a 정합 — `user_account` JOIN `user_kis_credential` (account_type='REAL')** (V1 baseline에는 trading_mode 컬럼 없음, 대신 user_kis_credential의 존재 여부로 판단) | **PR-7a 핵심** |
| §2-5 부팅 직렬 | 직렬 (rate limit 안전) — N명 × 1~2초 ≈ 30초 이내 | `forEach` 직렬 + KIS Rate Limiter (ADR 0037) — 본 PR-7은 단발 호출이라 별도 RateLimiter 미도입 OK | **PR-7a 정합** |
| §2-5 부팅 실패 | 해당 user만 KillSwitch auto-ON (ADR 0043) | `kill_switch_state` UPDATE — V1 baseline 테이블 활용 | **PR-7a 핵심** |
| §2-5 첫 사용자 등록 | UserRegisteredEvent listener → bootstrap 위임 | **본 PR-7 비범위** — UserOnboardedEvent listener는 T-W2(consumer 측 첫 기능 진입) | T-W2 |
| §2-5 KIS 키 무효 시 | 401 → 1회 재발급 → 그래도 실패 시 KillSwitch ON | 1회 재시도 — 본 PR-7은 단순화 (재시도 0회 + 즉시 KillSwitch ON) | **PR-7a 정합** |
| §3-2 컨테이너 영향 | consumer + domain 신규 port + (선택) api 디버그 endpoint | 본 PR-7 적용 — `POST /api/positions/bootstrap/{userId}` 디버그 endpoint **추가** (T-W2 진입 전 사용자가 수동 트리거 가능하게) | **PR-7a 추가** |

**누락 점검 결과**: ADR 0042 Bootstrap 부분만 본 PR-7에서 구현. Recovery + UserOnboardedEvent listener + KIS WS 구독 + lastUpdatedAt 키는 T-W2로 명시 미룸 (§8 비범위).

---

## §3. DB 변경 LOCK

### 3-1. 현황 — V1 baseline + V2 onboarding 적용 후

V1 baseline + V2 onboarding 적용 후 watchlist·position·kill_switch_state 테이블 모두 존재. 본 PR-7은 **테이블 신규 생성 0건**. 정정·인덱스만 검토.

| 테이블 | 현재 컬럼 (V1 + V2) | 비고 |
|---|---|---|
| `watchlist` | id BIGSERIAL · user_id VARCHAR(20) · code VARCHAR(8) · priority INTEGER DEFAULT 0 · created_at + UQ(user_id,code) + FK user_account CASCADE + FK instrument | ✅ 그대로 사용 |
| `position` | id BIGSERIAL · user_id · code · quantity INT · avg_price NUMERIC(19,4) · updated_at + UQ(user_id,code) + FK CASCADE + FK instrument | ✅ 그대로 사용 |
| `kill_switch_state` | user_id PK · enabled · reason VARCHAR(64) · updated_at · updated_by + FK user_account CASCADE | ✅ 그대로 사용 |
| `instrument` | code PK · isin · name · name_en · market · type · sector · listed_at · delisted_at · active · created_at · updated_at + market·type CHECK + idx(market, active) | ✅ 그대로 사용 (단 row 0건 — §3-3 참조) |
| `audit_trail` | id · actor · action · entity_type · entity_id · detail_json · occurred_at | ✅ 그대로 사용 (Watchlist 등록·삭제 audit row INSERT) |

### 3-2. 신규 V3 마이그레이션 — `V3__watchlist_position_refinements.sql`

위치: `api/src/main/resources/db/migration/V3__watchlist_position_refinements.sql` (단일 파일)

**왜 필요한가** — V1 baseline에 watchlist·position·instrument 테이블은 모두 있지만:

1. **`watchlist.code → instrument.code` FK 제약이 막힘** — V1에서 `instrument` row 0건 상태로 `watchlist` INSERT 시 FK violation. 본 PR-7은 T-IM1(Instrument Master) 진입 전이라 instrument 테이블이 비어있다 — **architect 결정: V3에서 instrument FK 제약을 일시 deferrable로 만들거나, OR watchlist 등록 시 자동으로 instrument row 생성 (애드훅 stub)**. 후자 선택 (사유 §3-2-A).
2. **`watchlist`에 priority 인덱스 부재** — 정렬 조회 시 풀스캔. priority 인덱스 추가.
3. **`position`에 user_id 인덱스 부재** — Bootstrap이 `findByUserId` 호출 시 시퀀스 스캔. 인덱스 추가.

#### 3-2-A. instrument FK 처리 — Watchlist 등록 시 stub instrument 자동 INSERT

**옵션 A (채택)**: V3에서 instrument FK 제약 그대로 유지 + Service 레이어에서 **stub instrument row upsert 후 watchlist INSERT** (한 트랜잭션). T-IM1이 진입하면 KIS 마스터 동기화로 stub row가 정식 데이터로 갱신.

**옵션 B (폐기)**: V3에서 FK DROP. T-IM1 진입 시 다시 ADD CONSTRAINT 후 violating row 검출.

**왜 옵션 A**:
- FK CASCADE 의도(데이터 무결성)는 보존
- T-IM1 진입 시 instrument row가 자연스럽게 풍부해짐 (sector·name 채워짐)
- stub row의 NOT NULL 컬럼은 placeholder (name="(unknown)", market="KOSPI" default — 사용자가 등록한 ticker라 KOSPI/KOSDAQ 모름. **architect 결정: market 컬럼은 NULL 허용 ALTER 또는 default "UNKNOWN"**)

**stub 룰**:
```kotlin
// WatchlistService.add(code) 내부:
// 1. instrumentRepository.existsByCode(code).not() → instrumentRepository.upsertStub(code)
// 2. watchlistRepository.add(entry)
// 3. Kafka watchlist.changed.v1 publish
```

**V3 SQL은 instrument 테이블 변경 없음** — Service 레이어 책임.

#### 3-2-B. instrument.market·type CHECK 완화

V1 baseline: `chk_instrument_market CHECK (market IN ('KOSPI','KOSDAQ','KONEX'))` + `chk_instrument_type CHECK (type IN ('STOCK','ETF',...))`. stub row는 market·type을 알 수 없음 → CHECK 위반.

**해결책 — V3에서 `'UNKNOWN'` 추가**:

```sql
-- V3 instrument CHECK 완화 — stub row 허용 (T-IM1 진입 시 정합 갱신)
ALTER TABLE instrument DROP CONSTRAINT chk_instrument_market;
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_market
    CHECK (market IN ('KOSPI', 'KOSDAQ', 'KONEX', 'UNKNOWN'));

ALTER TABLE instrument DROP CONSTRAINT chk_instrument_type;
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_type
    CHECK (type IN ('STOCK', 'ETF', 'ETN', 'PREFERRED', 'WARRANT', 'REIT', 'UNKNOWN'));
```

**왜**: 본 PR-7은 T-IM1 미진입 — KIS 마스터 동기화 부재. 사용자가 등록한 ticker의 market·type 추론 안 함 (검증 정책 §7 참조).

#### 3-2-C. watchlist priority + position user_id 인덱스 추가

```sql
-- V3 watchlist 정렬 조회 인덱스
CREATE INDEX IF NOT EXISTS idx_watchlist_user_priority
    ON watchlist (user_id, priority, created_at);

-- V3 position user_id 조회 인덱스 (Bootstrap STAGE 0-2 upsertAll 후 조회 빈도 높음)
CREATE INDEX IF NOT EXISTS idx_position_user
    ON position (user_id);
```

**watchlist UQ(user_id, code)는 leftmost로 user_id를 cover** — 단 priority 정렬 조회는 covering index 별도 필요.

#### 3-2-D. instrument에 stub 표시 컬럼 추가? — **결정: 추가 안 함**

옵션: `instrument.is_stub BOOLEAN DEFAULT false` 컬럼 추가 → T-IM1 진입 시 stub row 식별.

**architect 결정: 추가 안 함**. 사유:
- T-IM1 진입 시 KIS 마스터 동기화는 모든 row를 명시적 데이터로 UPDATE → stub 표시 불필요
- 1차 single-tenant 사용자가 등록할 종목 수 ≤ 30 — stub row identification 비용 < 가치
- `market='UNKNOWN' OR type='UNKNOWN'`으로 stub 식별 가능 (간접 판별)

### 3-3. V3 LOCK — 전체 SQL

위치: `api/src/main/resources/db/migration/V3__watchlist_position_refinements.sql`

```sql
-- =============================================================================
-- magicJar V3 — Watchlist + Position refinements (PR-7 T-W1)
-- ADR 0042·0028·0040 정합 — instrument CHECK 완화(stub) + 인덱스 2건
-- =============================================================================

-- 1. instrument.market·type CHECK 완화 — Watchlist stub row 허용 (ADR 0028·0040)
--    T-IM1(Instrument Master) 진입 시 KIS 마스터 동기화로 정식 데이터 갱신
ALTER TABLE instrument DROP CONSTRAINT chk_instrument_market;
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_market
    CHECK (market IN ('KOSPI', 'KOSDAQ', 'KONEX', 'UNKNOWN'));

ALTER TABLE instrument DROP CONSTRAINT chk_instrument_type;
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_type
    CHECK (type IN ('STOCK', 'ETF', 'ETN', 'PREFERRED', 'WARRANT', 'REIT', 'UNKNOWN'));

-- 2. watchlist 정렬 조회 인덱스 (ADR 0040 §2-2 list 진입 화면)
CREATE INDEX IF NOT EXISTS idx_watchlist_user_priority
    ON watchlist (user_id, priority, created_at);

-- 3. position user_id 조회 인덱스 (ADR 0042 Bootstrap STAGE 0-2 upsertAll 후 조회)
CREATE INDEX IF NOT EXISTS idx_position_user
    ON position (user_id);
```

### 3-4. Flyway 버전 결정 — 단일 V3 1개

**architect 결정 = 단일 V3 1개 파일**. 사유:
- 모두 PR-7 정합 (Watchlist 등록 + Position Bootstrap 한 묶음)
- V2의 단일 트랜잭션 패턴 정합
- backend-core가 V3 1개만 작성 → 작업 단순
- 후속 트랙(T-W2·T-S1·...)은 V4·V5·... 순차 사용

### 3-5. 정합 검증 게이트

- [ ] `psql -c "\d instrument"` → market CHECK에 'UNKNOWN' 포함
- [ ] `psql -c "INSERT INTO instrument (code, name, market, type) VALUES ('005930', '삼성전자', 'UNKNOWN', 'UNKNOWN'); SELECT code, market, type FROM instrument WHERE code='005930'"` → row 1건
- [ ] `psql -c "\di idx_watchlist_user_priority"` → 인덱스 존재
- [ ] `psql -c "\di idx_position_user"` → 인덱스 존재
- [ ] watchlist 등록 → `psql -c "EXPLAIN SELECT * FROM watchlist WHERE user_id='12345678' ORDER BY priority, created_at"` → Index Scan 사용

---

## §4. REST API 시그니처 LOCK

### 4-1. 엔드포인트 5개 전수 명세 (Watchlist CRUD 4개 + Position Bootstrap 디버그 1개)

위치: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/WatchlistController.kt` + `PositionController.kt` (신규)

**인증 정책**: PR-6의 OnboardingGuardFilter가 `/api/onboarding/*` 외 모든 `/api/*`에 셋업 완료 의무 부과. 본 PR-7의 `/api/watchlists/*`·`/api/positions/*`는 셋업 완료 후에만 200 응답. 미완 시 401 + `ONBOARDING_REQUIRED`.

**user_id 처리 (1차 single-tenant 단순화)**:
- 본 PR-7은 Spring Security 미도입 (PR-6과 동일 정책)
- 모든 endpoint는 `user_account` 테이블에서 첫 row의 user_id를 자동 사용 (`SELECT user_id FROM user_account ORDER BY created_at LIMIT 1`)
- multi-user 본격화 시 (post-MVP) HTTP header `X-User-Id` 또는 OAuth2 세션 도입
- 본 PR-7 endpoint URL에는 `{userId}` path variable 미사용 — 자동 resolve

#### 4-1-1. `GET /api/watchlists`

| 항목 | 명세 |
|---|---|
| HTTP method | GET |
| Path | `/api/watchlists` |
| 인증 | OnboardingGuardFilter 통과 (셋업 완료 사용자) |
| Request body | 없음 |
| Query params | `sort` (`priority`·`created` — default `priority`) |
| Response 200 body | `List<WatchlistEntryDto>` |
| 에러 codes | 401 (OnboardingRequired) |

```kotlin
// Response DTO
data class WatchlistEntryDto(
    val id: Long,
    val userId: String,      // 8자리 — frontend가 표시 시 마스킹
    val code: String,        // 6자리 KIS 종목 코드 (예: "005930")
    val priority: Int,       // 0=top
    val createdAt: Instant,
)
```

**구현 노트**:
- `WatchlistRepository.findByUserIdOrderByPriority(userId)` — V1 baseline에 이미 정의
- 응답 시간 ≤ 50ms 기대 (인덱스 idx_watchlist_user_priority 활용)
- 빈 list = 빈 배열 `[]` 반환 (200 OK)

#### 4-1-2. `POST /api/watchlists`

| 항목 | 명세 |
|---|---|
| HTTP method | POST |
| Path | `/api/watchlists` |
| 인증 | OnboardingGuardFilter 통과 |
| Request body | `WatchlistAddRequest` |
| Response 201 body | `WatchlistEntryDto` |
| 에러 codes | 400 (INVALID_TICKER_FORMAT), 401 (OnboardingRequired), 409 (WATCHLIST_DUPLICATE), 503 (DB 일시 장애) |

```kotlin
// Request DTO
data class WatchlistAddRequest(
    val code: String,        // 6자리 KIS 종목 코드 — @Pattern("^\\d{6}$")
    val priority: Int? = null,  // null이면 max(priority)+1 자동 부여 (default = 등록 순)
)
```

**구현 노트**:
- `code` validation = 6자리 숫자만 (KIS 종목 코드 형식 — 005930·035720 등). KOSPI=6자리, ETF/ETN도 6자리. 7자리 이상·영문은 거부.
- 등록 트랜잭션:
  1. `instrumentRepository.upsertStub(code)` — instrument row 부재 시 stub INSERT (market='UNKNOWN', type='UNKNOWN', name=code)
  2. `watchlistRepository.add(entry)` — UQ violation 시 409 반환
  3. `auditTrailRepository.add(action="WATCHLIST_ADDED", entity_id=code)`
  4. Kafka publish `watchlist.changed.v1` (payload `{userId, code, action: "ADDED"}`) — **단 본 PR-7에서 발행만, 실 consumer는 T-W2**
- priority 자동 부여: `SELECT COALESCE(MAX(priority),0)+1 FROM watchlist WHERE user_id=?` (대안: 모두 default 0으로 두고 created_at 정렬도 OK — 사용자가 priority 명시 시에만 사용. **architect 결정: priority 명시 안 하면 0 default + created_at 보조 정렬**)

**에러 응답**:
```kotlin
// 400 (validation)
{ "code": "INVALID_TICKER_FORMAT", "message": "종목 코드는 6자리 숫자여야 합니다 (예: 005930)" }
// 409 (이미 등록)
{ "code": "WATCHLIST_DUPLICATE", "message": "이미 등록된 종목입니다" }
```

#### 4-1-3. `DELETE /api/watchlists/{code}`

| 항목 | 명세 |
|---|---|
| HTTP method | DELETE |
| Path | `/api/watchlists/{code}` |
| 인증 | OnboardingGuardFilter 통과 |
| Path params | `code` (6자리 KIS 종목 코드) |
| Request body | 없음 |
| Response 204 body | (empty) |
| 에러 codes | 400 (INVALID_TICKER_FORMAT), 401, 404 (WATCHLIST_NOT_FOUND) |

**구현 노트**:
- `watchlistRepository.remove(userId, code)` — false 반환 시 404
- audit row INSERT — action="WATCHLIST_REMOVED"
- Kafka publish `watchlist.changed.v1` (payload `{userId, code, action: "REMOVED"}`) — T-W2가 unsubscribe 신호로 사용
- instrument stub row는 삭제 안 함 (다른 사용자가 등록 가능 — multi-user 정합)

#### 4-1-4. `PATCH /api/watchlists/{code}` (priority 변경) — **선택적 endpoint, R1 비범위**

priority 변경은 본 PR-7 R1에서 비범위 — UI에서 드래그 정렬은 T-S1 이후 구현. 본 PR-7은 추가 시 자동 priority + 삭제만.

**architect 결정: 본 PR-7 endpoint 4건 (GET·POST·DELETE 3개 + 다음 #4-1-5 1개) — PATCH는 후속 트랙**.

#### 4-1-5. `POST /api/positions/bootstrap` (디버그·수동 트리거)

| 항목 | 명세 |
|---|---|
| HTTP method | POST |
| Path | `/api/positions/bootstrap` |
| 인증 | OnboardingGuardFilter 통과 |
| Request body | 없음 (또는 `{"force": true}`) |
| Response 200 body | `PositionBootstrapResult` |
| 에러 codes | 401, 503 (KIS 다운), 504 (KIS timeout) |

```kotlin
data class PositionBootstrapResult(
    val userId: String,
    val maskedAccount: String,
    val holdings: Int,            // 보유 종목 수
    val totalQuantity: Int,       // 보유 총수량
    val executedAt: Instant,
)
```

**왜 디버그 endpoint를 추가하는가**:
- 본 PR-7은 consumer 모듈 ApplicationRunner가 부팅 시 자동 실행 — 단 사용자가 수동 재 트리거 가능해야 운영 디버그 편의
- ADR 0042 §3-2 명시 — `POST /api/position/bootstrap/{userId}` 디버그 endpoint (선택)
- frontend는 본 endpoint 미사용 (자동 실행으로 충분) — curl·Postman 검증용
- T-RECON(W3)에서 본 endpoint를 base로 reconcile manual trigger 보강

**구현 노트**:
- api 모듈에서 직접 KIS 호출 금지 (운영용 Adapter는 consumer 모듈) — **api 모듈은 Kafka publish만**: topic `position.bootstrap.requested.v1` (1회성 manual trigger)
- consumer 모듈이 본 topic listen → KIS 호출 + DB upsert + Redis 워밍 (자동 부팅 흐름 재사용)
- **architect 단순화 결정**: 1차 PR-7은 위 Kafka 우회 안 하고, **api 모듈에서도 KisBalanceAdapter를 import해 직접 호출**. multi-instance 본격화 시 Kafka로 전환. 이유 — PR-7 분량 폭증 회피.

**최종 결정**: api 모듈도 KisBalanceAdapter 사용 가능 (consumer와 공유 — 또는 api에 KisBalanceClient만 가벼운 wrapper). 본 endpoint는 **선택적**이며, 분량 부담이면 PR-7에서 빼고 T-W2로 미룬다 — sub-agent 결정 위임.

### 4-2. REST 응답 시간 SLO

| Endpoint | SLO | 비고 |
|---|---|---|
| `GET /api/watchlists` | p95 ≤ 50ms | 인덱스 활용 |
| `POST /api/watchlists` | p95 ≤ 200ms | instrument upsert + watchlist INSERT + audit + Kafka publish |
| `DELETE /api/watchlists/{code}` | p95 ≤ 100ms | DELETE + audit + Kafka publish |
| `POST /api/positions/bootstrap` | p95 ≤ 5000ms | KIS REST 호출 포함 (Resilience4j Circuit Breaker timeout 5s) |

### 4-3. Swagger 문서화 (사용자 메모리 정합)

```kotlin
@Tag(name = "Watchlist", description = "관심종목 CRUD — magicJar 1순위 진입점 (ADR 0040)")
@RestController
@RequestMapping("/api/watchlists")
class WatchlistController(...) {

    @Operation(
        summary = "관심종목 등록",
        description = "종목 코드 6자리(KOSPI·KOSDAQ·ETF·ETN — 예: 005930 삼성전자)를 등록. UQ(user_id, code) 위반 시 409. instrument 테이블에 stub row 자동 INSERT (T-IM1 진입 시 정식 데이터 갱신).",
    )
    @ApiResponse(responseCode = "201", description = "등록 성공")
    @ApiResponse(responseCode = "409", description = "이미 등록된 종목")
    @PostMapping
    fun add(@Valid @RequestBody request: WatchlistAddRequest): WatchlistEntryDto = ...
}
```

DTO `@Schema(example=...)` 의무 — code example "005930", priority example 0.

### 4-4. ErrorResponse 공통 포맷 (PR-6 정합 재사용)

```json
{ "code": "INVALID_TICKER_FORMAT" | "WATCHLIST_DUPLICATE" | "WATCHLIST_NOT_FOUND" | "ONBOARDING_REQUIRED" | "KIS_UNAVAILABLE" | "VALIDATION_ERROR" | ..., "message": "한국어 메시지" }
```

PR-6 `OnboardingController`의 `ErrorResponse` data class를 그대로 재사용 (또는 공통 모듈로 이동 — 본 PR-7에선 api/.../controller/ErrorResponse.kt 한 곳 + import).

### 4-5. Kafka 토픽 신규 — `watchlist.changed.v1`

```
topic: watchlist.changed.v1
producer: api (WatchlistService)
consumer: consumer (T-W2 — WatchlistIngestionWorker)
retention: 7d
payload (JSON):
  {
    "userId": "74683223",
    "code": "005930",
    "action": "ADDED" | "REMOVED",
    "occurredAt": "2026-05-07T10:23:45.123Z"
  }
```

**본 PR-7 범위**: producer만 — consumer 측 listener는 T-W2.

**Kafka topic 자동 생성 정책 (ADR 0007 정합)**: KafkaAdmin auto-create 활성 default. 별도 NewTopic Bean 정의 불필요 (기존 패턴 정합). Topic config (retention 7d 등)은 T-W2 진입 시 NewTopic Bean 추가.

---

## §5. 부팅 잔고 워밍 인터페이스 LOCK (ADR 0042 Bootstrap)

### 5-1. 트리거 시점

**채택**: **consumer 모듈 ApplicationRunner**.

```kotlin
// consumer/.../bootstrap/PositionBootstrapRunner.kt
@Component
class PositionBootstrapRunner(
    private val userKisCredentialRepository: UserKisCredentialRepository,
    private val kisBalancePort: KisBalancePort,
    private val positionRepository: PositionRepository,
    private val positionCachePort: PositionCachePort,
    private val killSwitchRepository: KillSwitchRepository,
    private val auditTrailRepository: AuditTrailRepository,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val activeUsers = userKisCredentialRepository.findAllByAccountType("REAL")
            // 또는 MOCK도 — magicjar.mode 결정 (1차는 MOCK 기본)
        if (activeUsers.isEmpty()) {
            log.info("Position bootstrap — 활성 사용자 0명 idle (셋업 미완)")
            return
        }
        activeUsers.forEach { credential ->
            try {
                runBootstrap(credential)
            } catch (e: Exception) {
                killSwitchRepository.enable(credential.userId, "POSITION_BOOTSTRAP_FAILED", e.message)
                log.error("Position bootstrap user=${credential.userId.maskedAccount()} failed", e)
            }
        }
    }
    private fun runBootstrap(credential: UserKisCredential) {
        // STAGE 0-1: KIS REST 잔고 조회
        val kisBalance = kisBalancePort.fetchBalance(credential.userId, credential.accountType)
        // STAGE 0-2: DB position upsert
        positionRepository.upsertAll(kisBalance.holdings.map { it.toPosition(credential.userId) })
        // STAGE 0-3: Redis position:current:{userId} SET (TTL 24h)
        positionCachePort.put(credential.userId, kisBalance.holdings, ttl = Duration.ofHours(24))
        // STAGE 0-4: KIS WS 구독은 T-W2 — 본 PR-7 비범위
        auditTrailRepository.add(action = "POSITION_BOOTSTRAP", entityType = "user", entityId = credential.userId, detail = ...)
    }
}
```

**왜 ApplicationRunner**:
- ADR 0042 §2-1 명시 (`ApplicationRunner`)
- consumer 부팅 직후 1회 실행 → 사용자 첫 진입 시 즉시 정합
- 활성 user 0명 시 즉시 return (idle 정상)

**왜 consumer 모듈**:
- ADR 0042 §3-2 명시 — consumer가 운영 KIS 호출 주체 (api는 검증용·디버그용 한정)
- KIS WS 구독은 T-W2부터 consumer가 처리 → consumer가 KIS 모든 진입점 통일
- batch는 cron 기반 (BalanceReconcileJob T-RECON), Bootstrap은 부팅 1회 — 다른 책임

### 5-2. KIS 호출 책임자 — KisBalanceAdapter (consumer 모듈)

위치: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/infrastructure/kis/KisBalanceAdapter.kt`

**market-data 영역 분리 vs backend-core 단독**: tracks-roadmap §2 T-W1 담당 = `frontend+backend-core`. **architect 결정: backend-core 단독으로 시작**. 사유:
- 본 PR-7 KIS 호출은 단발 Bootstrap 1회 — market-data 영역의 본격 도입(WS subscribe·tick 폴링)은 T-W2
- T-W2 진입 시 market-data 에이전트가 KisWsClient·KisRestPolling 등 인프라 일괄 도입
- 본 PR-7에서 KisBalanceAdapter만 backend-core가 작성 → T-W2에서 market-data가 KisWsClient + 본 어댑터 정합

**구현 표준**:
```kotlin
@Component
class KisBalanceAdapter(
    private val webClient: WebClient,
    private val secretCipher: SecretCipher,    // PR-6 AesGcmCipher 재사용
    private val kisAuthAdapter: KisAuthAdapter,  // 운영용 토큰 cache + 분산락 (ADR 0038) — **본 PR-7에서 신규**
    private val userKisCredentialRepository: UserKisCredentialRepository,
) : KisBalancePort {
    override fun fetchBalance(userId: String, accountType: AccountType): KisBalance {
        // 1. user_kis_credential에서 BYTEA 읽고 AesGcmCipher.decrypt → APP_KEY·SECRET_KEY
        // 2. KisAuthAdapter.getAccessToken(userId, accountType) — 24h cache (ADR 0038)
        // 3. KIS REST POST /uapi/domestic-stock/v1/trading/inquire-balance
        //    TR_ID: TTTC8434R (실전) / VTTC8434R (모의)
        //    Headers: authorization=Bearer <token>, appkey, appsecret, tr_id, custtype=P
        //    Query params: CANO=<userId 8자리>, ACNT_PRDT_CD="01", AFHR_FLPR_YN="N",
        //                  OFL_YN="", INQR_DVSN="02", UNPR_DVSN="01", FUND_STTL_ICLD_YN="N",
        //                  FNCG_AMT_AUTO_RDPT_YN="N", PRCS_DVSN="00", CTX_AREA_FK100="", CTX_AREA_NK100=""
        // 4. response output1 = 보유 종목 list (pdno, hldg_qty, pchs_avg_pric)
        //    output2 = 계좌 종합 (dnca_tot_amt, scts_evlu_amt 등) — 본 PR-7에선 holdings만 활용
        // 5. KisBalance(holdings = output1.map { Holding(code=it.pdno, quantity=it.hldg_qty.toInt(), avgPrice=it.pchs_avg_pric.toBigDecimal()) })
        // 6. 메모리 zero-fill (APP_KEY·SECRET_KEY)
    }
}
```

**KIS endpoint 정합 (kis-first 의무)**:
- 출처: `_workspace/arch/adr/0042-position-bootstrap-recovery.md` §2-2 + KIS open-trading-api `examples_llm/.../inquire_balance.py`
- 추가 endpoint 자체 추론 0건 — kis-code-assistant MCP로 검증 가능 (분량 부담 시 sub-agent 위임)

### 5-3. KisAuthAdapter (운영용 — 본 PR-7에서 신규)

위치: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/infrastructure/kis/KisAuthAdapter.kt`

**왜 본 PR-7에서 신규?**:
- PR-6의 `KisAuthValidator` (api 모듈)는 검증용 단발 — 운영용 토큰 cache·분산락 부재
- 본 PR-7부터 consumer가 KIS REST를 부팅 + cron + 시그널 등 본격 호출 시작 → 토큰 cache 필요
- ADR 0038 (KIS OAuth 24h 토큰 + Redis 분산락) 정합 의무

**구현 표준**:
```kotlin
@Component
class KisAuthAdapter(
    private val webClient: WebClient,
    private val redisTemplate: StringRedisTemplate,
    private val secretCipher: SecretCipher,
    private val userKisCredentialRepository: UserKisCredentialRepository,
) {
    fun getAccessToken(userId: String, accountType: AccountType): String {
        // 1. Redis cache 조회: kis:token:{userId}:{accountType} → 있으면 반환
        // 2. 없으면 분산락 획득 (Redis SETNX kis:token:lock:{userId}:{accountType} TTL 30s)
        // 3. 락 보유자: KIS POST /oauth2/tokenP → cache (TTL 23h, KIS 만료 24h 직전)
        // 4. 락 미보유자: 30s 대기 후 cache 재조회
    }
}
```

**ADR 0038 정합**:
- TTL 23h (24h 만료 1h 전 갱신)
- 분산락 — 동일 user 동시 요청 시 토큰 1회만 발급
- 만료 1h 전 백그라운드 갱신은 PR-7 비범위 (본 PR-7은 사용 시 cache miss → 발급 단순화)

**복호화 메모리 zero-fill 의무**: APP_KEY·SECRET_KEY는 fetch 후 즉시 zero-fill.

### 5-4. KisBalancePort 도메인 인터페이스

위치: `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/position/port/KisBalancePort.kt` (신규)

```kotlin
package kr.spdaewoon.magicjar.domain.position.port

import kr.spdaewoon.magicjar.domain.user.AccountType
import kotlinx.datetime.Instant

interface KisBalancePort {
    fun fetchBalance(userId: String, accountType: AccountType): KisBalance
}

data class KisBalance(
    val userId: String,
    val accountType: AccountType,
    val holdings: List<KisHolding>,
    val fetchedAt: Instant,
)

data class KisHolding(
    val code: String,
    val quantity: Int,
    val avgPrice: BigDecimal,
)
```

**위치 — domain**: ADR 0042 §3-2 명시 (`domain/.../position/port/KisBalancePort.kt`).

### 5-5. PositionCachePort (Redis 어댑터)

위치 (port): `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/position/port/PositionCachePort.kt` — **PR-5a에 이미 존재**.
위치 (adapter): `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/infrastructure/cache/RedisPositionCacheAdapter.kt` (신규)

```kotlin
// PR-5a domain port (이미 존재 가정 — 없으면 본 PR-7에서 추가)
interface PositionCachePort {
    fun put(userId: String, holdings: List<KisHolding>, ttl: Duration)
    fun get(userId: String): List<KisHolding>?
    fun applyExecution(userId: String, exec: ExecutionEvent)  // T-W2 시점 — 본 PR-7 비범위
    fun getLastUpdatedAt(userId: String): Instant?            // T-W2 비범위
}

// 본 PR-7 adapter 구현 — Redis JSON 직렬화 + TTL 24h
@Component
class RedisPositionCacheAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : PositionCachePort {
    override fun put(userId: String, holdings: List<KisHolding>, ttl: Duration) {
        val json = objectMapper.writeValueAsString(holdings)
        redisTemplate.opsForValue().set("position:current:$userId", json, ttl)
    }
    // applyExecution·getLastUpdatedAt는 T-W2 진입 시 구현 — 본 PR-7은 NotImplementedError 또는 stub return
}
```

**PR-5a port 정의 부재 시 처리**: domain에 port 추가 (architect 자율 — domain 파일 신규 add-only 안전).

### 5-6. idempotency · 재시도 · 실패 동작

| 시나리오 | 동작 |
|---|---|
| 부팅 N회 반복 | upsertAll = `INSERT ... ON CONFLICT (user_id, code) DO UPDATE` — idempotent |
| KIS REST 401 (토큰 만료) | KisAuthAdapter cache 무효화 + 1회 재발급 → 그래도 실패 시 KillSwitch ON + audit row |
| KIS REST 5xx · timeout | 재시도 0회 + 즉시 KillSwitch ON (사용자 안전 우선) |
| KIS 응답 빈 list (보유 0건) | DB position 0건 + Redis JSON `[]` SET — 정상 |
| 사용자 KIS 키 삭제 (재 셋업) | UserKisCredentialDeletedEvent listener — 본 PR-7 비범위 (post-MVP) |
| 부팅 시간 초과 (user 1명당 ≥ 5초) | log WARN — alert는 T-NOTIFY (W3) |

**audit_trail INSERT** — 부팅 시작·종료·실패 모두 1 row.

### 5-7. Position 도메인 vs Watchlist row 관계

**다른 책임**:
- `watchlist` = 사용자가 monitoring 의도로 등록한 종목 풀 (사용자 의도 manual)
- `position` = KIS 잔고 = 실 보유 종목 (KIS REST 결과 mirror)

**교집합 처리**:
- 사용자가 KIS HTS로 매매한 종목은 자동으로 position에는 들어옴 — watchlist에는 안 들어옴
- 사용자가 watchlist에 005930 등록 + 실제 보유 0주 → watchlist 1건 + position 0건
- frontend Watchlist 화면은 priority 1, 2, 3... + position 정보를 별도 표시 (현재가·손익은 T-S1)

**stub instrument 정합**: position INSERT 시에도 instrument FK 의무 — `KisBalanceAdapter` 결과 매핑 시 instrument stub upsert 의무 (Watchlist와 동일 로직).

---

## §6. Watchlist UI 계약 LOCK

### 6-1. 라우트 + 컴포넌트 책임

위치: `frontend/src/pages/Watchlist/` (신규 디렉토리)

| Path | 컴포넌트 | 책임 |
|---|---|---|
| `/watchlist` | `WatchlistPage.tsx` | 메인 화면 — list + 등록 폼 + 빈 상태 |
| (sub component) | `WatchlistAddForm.tsx` | 종목 코드 입력 + 등록 버튼 + clientside validation |
| (sub component) | `WatchlistRow.tsx` | row 1건 — code + priority + 등록일 + 휴지통 버튼 |
| (sub component) | `WatchlistEmpty.tsx` | 빈 상태 — "관심종목 등록하기" CTA |

**App.tsx 라우팅 추가**:
- 기존 PR-5b·PR-6 라우터에 `/watchlist` 라우트 추가
- `/` (Dashboard placeholder) 메뉴 또는 사이드바에서 `/watchlist` 진입 가능
- ADR 0040 §2-1 — `/watchlist` 1순위 (Dashboard보다 우선) → **architect 결정: 1차 PR-7은 Dashboard와 Watchlist 양쪽 메뉴 노출, default 라우트는 `/`(Dashboard) 그대로**. 사유 — Dashboard가 PR-5b placeholder라 우선순위 의미 미약. T-S1·T-O 진입 시 Dashboard 본격 화면 + 라우트 우선순위 재검토.

### 6-2. ticker 입력 검증 (clientside)

```typescript
// frontend/src/pages/Watchlist/WatchlistAddForm.tsx
const TICKER_PATTERN = /^\d{6}$/  // KIS 종목 코드 6자리 숫자

function validateTicker(input: string): string | null {
  if (!input.trim()) return "종목 코드를 입력하세요"
  if (!TICKER_PATTERN.test(input)) return "6자리 숫자만 가능합니다 (예: 005930)"
  return null
}
```

**왜 6자리 숫자만**:
- KOSPI·KOSDAQ·ETF·ETN 모두 6자리 숫자 (선두 0 패딩 의무)
- 영문 ticker (NYSE 등) 거부 — magicJar는 1차 한국 주식만
- 검증은 §7 종목 검색 정책 옵션 C 정합 (raw code 저장)

### 6-3. 등록 + 삭제 흐름 (TanStack Query)

```typescript
// frontend/src/api/watchlist.ts (신규)
export const watchlistApi = {
  list: () => axiosClient.get<WatchlistEntryDto[]>('/watchlists').then(r => r.data),
  add: (req: WatchlistAddRequest) => axiosClient.post<WatchlistEntryDto>('/watchlists', req).then(r => r.data),
  remove: (code: string) => axiosClient.delete(`/watchlists/${code}`),
}

// frontend/src/api/hooks/useWatchlist.ts (신규)
export function useWatchlist() {
  return useQuery({ queryKey: ['watchlist'], queryFn: watchlistApi.list, staleTime: 30_000 })
}

export function useAddWatchlist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: watchlistApi.add,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['watchlist'] }),
    // 낙관적 업데이트는 R2(T-S1·실시간)에서 도입 — 본 PR-7은 invalidate만
  })
}

export function useRemoveWatchlist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: watchlistApi.remove,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['watchlist'] }),
  })
}
```

### 6-4. 삭제 confirm 정책

**채택**: 휴지통 버튼 클릭 시 `window.confirm("관심종목 005930을(를) 삭제하시겠습니까?")` 동기 confirm. 사유:
- 단순 (modal 컴포넌트 추가 vs window.confirm trade-off)
- 본 PR-7 R1은 골격 — modal·UX 디테일은 R2 이후
- 잘못 삭제 시 재 등록 비용 ≤ 5초 (큰 손실 아님)

**대안 (R2)**: `<ConfirmDialog>` 컴포넌트 + Tailwind modal — UX 향상.

### 6-5. 정렬 + 검색 범위 결정

**정렬**:
- 1차: priority (낮은 값 = 상위)
- 2차: created_at desc (최근 등록 우선)
- 사용자 드래그 정렬 (priority 변경) = **본 PR-7 비범위** (PATCH endpoint 미구현). T-S1 또는 별도 트랙.

**검색**:
- 본 PR-7은 list 전체 표시 (사용자당 ≤ 30종목 — 검색 불필요)
- T-IM1 진입 시 `<InstrumentSearch>` 자동완성 도입 — Watchlist 등록 시 종목명·산업·시총 검색 가능
- 본 PR-7 입력 폼은 6자리 코드 직접 입력만

### 6-6. 빈 상태 UI

```tsx
// WatchlistEmpty.tsx
<div className="text-center py-16">
  <h2>관심종목이 없습니다</h2>
  <p>종목 코드를 입력해 등록하세요. 모든 데이터 수집·차트·시그널은 관심종목 풀 기준입니다.</p>
  <button onClick={() => focusInput()}>관심종목 등록하기</button>
</div>
```

ADR 0040 §2-2 정합 — "비어있으면 '관심종목 등록하기' CTA".

### 6-7. 등록·삭제 시 사용자 피드백

| 이벤트 | 피드백 |
|---|---|
| 등록 성공 | toast 또는 inline 메시지 "005930 등록 완료" — 1초 후 자동 사라짐 |
| 등록 실패 (409 중복) | inline error "이미 등록된 종목입니다" + 입력 필드 highlight |
| 등록 실패 (400 형식) | inline error "6자리 숫자만 가능합니다" |
| 삭제 성공 | row fade-out (200ms) + list refresh |
| 삭제 실패 (404) | toast "종목을 찾을 수 없습니다 — 새로고침 후 재시도" |
| 네트워크 실패 | toast "네트워크 오류 — 잠시 후 재시도" |

**toast 라이브러리**: 본 PR-7은 단순 inline 메시지로 시작 (라이브러리 추가 없음). T-S1 이후 `react-hot-toast` 등 도입 검토.

### 6-8. 메뉴·사이드바 통합 (PR-5b 라우터 갱신)

**기존 PR-5b·PR-6**: App.tsx에 `/setup`·`/`(Dashboard placeholder) 2개 라우트.
**본 PR-7 추가**: `/watchlist` 라우트 + 사이드바 메뉴 1개.

**메뉴 구조 1차**:
```
- 대시보드 (/) — placeholder
- 관심종목 (/watchlist) — 본 PR-7 활성
```

**구현 노트**:
- `frontend/src/components/layout/AppLayout.tsx` 또는 `AppHeader.tsx` 신규 (PR-5b에 없으면 추가)
- React Router `<NavLink>` 사용 — 활성 메뉴 highlight
- 사이드바 vs top nav — **architect 결정: top nav** (1차 단순화, 메뉴 ≤ 5개)

### 6-9. user_id 화면 표시 정책

- 화면 어디에도 KIS 계좌번호 8자리 평문 표시 금지
- `maskedAccount` 컬럼 (PR-6 V2 GENERATED) 사용 — "7468****"
- 본 PR-7 Watchlist 화면은 user_id 표시 자체가 불필요 — single-tenant라 누구나 본인 자기 row

---

## §7. 종목 검색 정책 (옵션 A/B/C 결정)

T-IM1 (Instrument Master) 미진입 상태에서 Watchlist 등록 시 ticker 검증 어떻게?

### 7-1. 옵션 비교

| 옵션 | 동작 | 장점 | 단점 |
|---|---|---|---|
| **A** 자유 입력 + 백엔드 KIS 1회 ping | 등록 시 KIS REST `/uapi/domestic-stock/v1/quotations/inquire-price` 1회 호출 → 200이면 종목 존재 → 등록 | 정확도 높음 (KIS가 알면 OK) | KIS Rate Limit 부담 + 등록 latency 200~500ms 증가 + KIS 다운 시 등록 불가 |
| **B** ADR 0042 KIS 잔고 워밍 결과 기반 universe 캐시 | 부팅 시 KIS 잔고 종목만 등록 가능 | KIS 호출 0 | universe ≤ 보유 종목 — 신규 종목 등록 불가 (사용자 의도 차단) |
| **C** 검증 없이 raw code 저장 + T-IM1에서 보강 | clientside 6자리 숫자만 검증 + DB INSERT (instrument stub) | 단순 + KIS 호출 0 + 사용자 의도 자유 | 잘못된 코드 등록 가능 (예: "999999") — T-W2 시세 수집 시 KIS가 거부 → frontend "데이터 없음" 표시 |

### 7-2. architect 결정 — **옵션 C**

**채택**: **옵션 C — clientside 6자리 숫자 검증 + raw code 저장 + T-IM1에서 보강**.

**사유**:
1. **T-W1 단일 책임 (룰 2)** — 본 PR-7은 CRUD·UI·Bootstrap. KIS 종목 검증은 T-IM1 책임.
2. **사용자 의도 우선** — 사용자가 "999999"를 등록하려는 의도는 거의 없음 (잘못 입력했어도 재 등록 비용 ≤ 5초). KIS 호출 비용 vs 사용자 자유 trade-off.
3. **KIS Rate Limit 보호** — ADR 0037 (초당 10건) 한도. 등록마다 KIS ping은 부담.
4. **오프라인 동작 (master §A 정합)** — 옵션 A는 KIS 다운 시 등록 차단. 옵션 C는 오프라인 OK.
5. **T-W2 시점 자연 검증** — 잘못된 코드는 KIS WS subscribe 시 KIS가 reject → frontend "데이터 없음" 명시 표시 (사용자 메모리 `feedback_no_mock_data.md` 정합).
6. **stub instrument 자동 INSERT** (§3-2-A) — 옵션 C 구현은 단순.

**옵션 A로 후속 전환 가능 여부**: T-IM1 진입 시 `<InstrumentSearch>` 자동완성 도입 → 사용자가 typo 입력 자체가 차단됨. **옵션 A는 T-IM1로 자연스럽게 전환** — 본 PR-7에서 옵션 A 선택할 가치 < T-IM1 진입 우선.

### 7-3. 옵션 C 구현 룰

| 구현 항목 | 룰 |
|---|---|
| clientside validation | `^\d{6}$` regex |
| backend validation | `@Pattern("^\\d{6}$")` |
| instrument stub INSERT | `{code, name=code, market='UNKNOWN', type='UNKNOWN', active=true}` |
| T-IM1 진입 시 정합 갱신 | KIS 마스터 동기화 → stub row UPDATE (name·market·type·sector·listed_at) |
| 잘못된 코드 (예: "999999") | T-W2 시세 수집 시 KIS reject → frontend "데이터 없음" 표시 |

---

## §8. 비범위 (post-MVP / 후속 트랙)

본 PR-7 (T-W1)에 명시적으로 포함하지 않는 항목:

| 항목 | 다음 PR/트랙 | 사유 |
|---|---|---|
| KIS WS subscribe | **T-W2** | T-W2가 ingestion worker 본격 도입 |
| Yahoo cold-start fetch | **T-W2** | 동 |
| KIS 당일 분봉 fetch | **T-W2** | 동 |
| WS Recovery (`KisWsReconnectedEvent` listener) | **T-W2** | WS 자체가 T-W2 진입 |
| `inquire-daily-ccld` (TTTC0081R) — gap 보정 | **T-W2** | Recovery용 |
| `position:lastUpdatedAt:{userId}` Redis key | **T-W2** | Recovery에서 사용 |
| UserOnboardedEvent listener (consumer) | **T-W2** | Bootstrap 자동 트리거 (현재는 부팅 시 1회) |
| Symbol 상세 화면 (`/symbol/{code}`) | **T-S1** | 현재가·차트는 T-S1 |
| LWC 차트 통합 | **T-S1** | 동 |
| Instrument 자동완성 (`<InstrumentSearch>`) | **T-IM1** | KIS 마스터 동기화 |
| Strategy Builder → Watchlist 등록 | **T-SB** | DSL 발굴 결과 → 등록 흐름 |
| Watchlist priority 변경 (PATCH endpoint + 드래그 정렬) | **T-S1 이후** | UX 디테일 |
| Order 매매 통합 | **T-O (W3)** | Watchlist 종목 매매 |
| BalanceReconcileJob (cron 5분) | **T-RECON (W3)** | Bootstrap은 부팅 1회만, drift 검출은 cron 별도 |
| KillSwitch UI 통합 | **T-O (W3)** | 화면 표시 + 토글 버튼 |
| Multi-user 인증 (Spring Security) | post-MVP | 1차 single-tenant |
| KIS 토큰 백그라운드 갱신 | post-MVP | 1차 cache miss 시 발급 |
| Notification (Slack 통지) | **T-NOTIFY (W3)** | KillSwitch ON 시 통지 |
| 차트 우측 panel — 보유 손익 | **T-S1** | position·current price 합쳐서 표시 |

---

## §9. 테스트 시나리오 골격 (R3 일괄 — 룰 4 정합)

본 PR-7 sub-agent 본인 PR에는 smoke test 1~2건만 (룰 4 정합 — 단순 단위 테스트 OK). R3 일괄 테스트는 **T-S1 진입 시 또는 별도 묶음**.

### 9-1. backend smoke (PR-7a 본인 PR)

| # | 시나리오 | 검증 |
|---|---|---|
| 1 | WatchlistController CRUD round-trip | `POST /api/watchlists -d '{"code":"005930"}'` → 201 → `GET /api/watchlists` → 1건 → `DELETE /api/watchlists/005930` → 204 → `GET` → 0건 (Spring Boot Test + Testcontainers PostgreSQL) |
| 2 | PositionBootstrapRunner — 활성 user 0명 | run() → log "활성 사용자 0명 idle" + DB position 0건 (단순 unit test) |

위치: `api/src/test/kotlin/.../WatchlistControllerTest.kt` + `consumer/src/test/kotlin/.../PositionBootstrapRunnerTest.kt`

**KIS REST mock — Testcontainers KIS server 또는 WireMock**: backend-core 자율 결정 (분량 부담 시 unit test 1건만으로도 OK).

### 9-2. frontend smoke (PR-7b 본인 PR)

| # | 시나리오 | 검증 |
|---|---|---|
| 1 | WatchlistPage 빈 상태 → 등록 → list 표시 | `WatchlistEmpty` 표시 → "005930" 입력 + 등록 버튼 → list 1건 표시 (vitest + react-testing-library + msw) |

위치: `frontend/src/__tests__/watchlist-flow.test.tsx`

### 9-3. R3 일괄 시나리오 골격 (T-S1 진입 시 또는 별도 묶음에 통합)

데이터 흐름·사용자 시퀀스 기반 통합 시나리오 — backend·frontend·DB·docker compose 통합:

| # | 시나리오 | 검증 |
|---|---|---|
| 1 | 셋업 완료 → /watchlist 진입 → 종목 등록 → DB+Kafka 정합 | wizard 완료 → `/watchlist` → "005930" 등록 → `psql watchlist` 1건 + Kafka topic 1 message |
| 2 | 부팅 잔고 워밍 → DB·Redis 정합 | consumer 부팅 → KIS REST 호출 (mock) → `psql position` N건 + Redis key 1건 |
| 3 | KIS 키 무효 → KillSwitch auto-ON | 잘못된 키 mock → `kill_switch_state.enabled=true` + reason="POSITION_BOOTSTRAP_FAILED" |
| 4 | 활성 user 0명 부팅 idle | 빈 DB → consumer 부팅 → log "idle" + EXIT 0 아님 |
| 5 | 종목 삭제 → CASCADE | watchlist 등록 후 user_account DELETE → watchlist 0건 |
| 6 | OnboardingGuardFilter 통합 | 셋업 미완 상태에서 `GET /api/watchlists` → 401 |

**룰 4 정합**: 본 PR-7 양쪽 PR은 코드 우선 + smoke 1~2건만. 통합 테스트는 T-S1 또는 별도 묶음.

---

## §10. 트레이드오프·결정 요약

| # | 결정 항목 | 채택 | 폐기/후속 | 사유 |
|---|---|---|---|---|
| 1 | DB 변경 — V3 1개 vs V3~V5 분리 | **V3 1개** | 분리 | 모두 PR-7 정합 (Watchlist + Position 한 묶음). backend-core 작업 단순화 |
| 2 | watchlist FK 처리 — instrument FK 유지 + stub upsert | ✅ | FK DROP 후 T-IM1에서 ADD | FK CASCADE 의도 보존 + T-IM1에서 자연 정합 |
| 3 | instrument.market·type CHECK — 'UNKNOWN' 추가 | ✅ | NULL 허용 ALTER | CHECK 보존 + stub 식별 가능 |
| 4 | Position Bootstrap 위치 — consumer ApplicationRunner | ✅ | api ApplicationRunner | ADR 0042 §2-1 명시 + KIS 본격 호출 진입점은 consumer (T-W2 정합) |
| 5 | KisBalanceAdapter 위치 — consumer 모듈 | ✅ | api 모듈 (검증용) | 운영 KIS 호출 — ADR 0042 §3-2 명시 |
| 6 | KisAuthAdapter 운영용 — 본 PR-7에서 신규 | ✅ | T-W2까지 미룸 | Bootstrap부터 KIS 운영 호출 시작 → 토큰 cache 의무 (ADR 0038) |
| 7 | 종목 검색 정책 — 옵션 C (raw code 저장) | ✅ | A (KIS ping) / B (universe 캐시) | T-W1 단일 책임 + KIS Rate Limit 보호 + 오프라인 정합 + T-IM1 자연 전환 |
| 8 | Position Bootstrap 디버그 endpoint | **선택적 추가** | 미추가 (T-W2까지 미룸) | 운영 디버그 편의 + T-RECON 본격 진입 시 base. sub-agent 분량 부담 시 빼도 OK |
| 9 | user_id 처리 — 자동 resolve (Spring Security 미도입) | ✅ | OAuth2 + JWT | 1차 single-tenant 단순화 (PR-6 정합) |
| 10 | 검색·드래그 정렬 — 본 PR-7 비범위 | ✅ | 본 PR-7에 통합 | T-S1 또는 별도 트랙 (R1 골격 우선) |
| 11 | toast 라이브러리 | **inline 메시지만** | react-hot-toast 도입 | 1차 단순화. T-S1 이후 검토 |
| 12 | UI 라우트 default — `/` (Dashboard) 그대로 | ✅ | `/watchlist` 1순위 (ADR 0040 §2-1) | Dashboard placeholder라 1순위 의미 미약. T-S1 진입 시 재검토 |
| 13 | KillSwitch 통합 — 부팅 실패 시 auto-ON | ✅ | 통지만 + 사용자 manual ON | ADR 0042 §2-5 + ADR 0043 정합. 사용자 안전 우선 |
| 14 | Kafka watchlist.changed.v1 — producer만 (본 PR-7) | ✅ | producer + consumer 통합 | consumer 측 listener는 T-W2 본격 ingestion 진입 |
| 15 | UserOnboardedEvent listener — T-W2로 미룸 | ✅ | 본 PR-7 통합 | 본 PR-7은 부팅 시 1회 + 디버그 endpoint로 시작. UserOnboardedEvent listener는 T-W2 단일 책임 |

---

## §11. PR-7 검증 게이트 (sub-agent 머지 전)

### 11-1. PR-7a (backend-core) 머지 게이트

| # | 게이트 | 명령 | GREEN 조건 |
|---|---|---|---|
| 1 | Gradle 빌드 | `./gradlew :api:check :consumer:check :domain:check` | BUILD SUCCESSFUL |
| 2 | bootJar | `./gradlew :api:bootJar :consumer:bootJar` | jar 생성 |
| 3 | ktlint | `./gradlew ktlintCheck` | violation 0 |
| 4 | V3 마이그레이션 적용 | `docker compose up -d api-app` 후 `psql -c "\d instrument"` | market CHECK에 'UNKNOWN' 포함 |
| 5 | Watchlist CRUD | `curl POST /api/watchlists` + `curl GET` + `curl DELETE` | 201 → 200 → 204 round-trip |
| 6 | OnboardingGuardFilter 통합 | 셋업 완료 사용자 → `curl GET /api/watchlists` | 200 (401 아님) |
| 7 | watchlist UQ 위반 | 같은 code 재 POST | 409 + WATCHLIST_DUPLICATE |
| 8 | Kafka publish 검증 | 등록 후 `kafka-console-consumer --topic watchlist.changed.v1` | 1 message |
| 9 | PositionBootstrapRunner 부팅 | consumer 부팅 후 `docker logs magicjar-consumer-app | grep PositionBootstrap` | "활성 사용자 N명" log |
| 10 | DB position upsert | KIS mock fetch 후 `psql position` | row 적재 |
| 11 | Redis position cache | `redis-cli GET position:current:<userId>` | JSON + TTL ≤ 24h |
| 12 | KillSwitch auto-ON (KIS 키 무효 mock) | invalid credential → `psql kill_switch_state` | enabled=true + reason="POSITION_BOOTSTRAP_FAILED" |
| 13 | 활성 user 0명 idle | 빈 DB consumer 부팅 | log "idle" + 정상 부팅 |
| 14 | LLM provider import 0 | `grep -rE "spring-ai\|anthropic\|openai" {domain,api,batch,consumer}/src/main` | 0건 |
| 15 | smoke test 2건 | `:api:test --tests "*Watchlist*"` + `:consumer:test --tests "*PositionBootstrap*"` | PASS |

### 11-2. PR-7b (frontend) 머지 게이트

| # | 게이트 | 명령 | GREEN 조건 |
|---|---|---|---|
| 1 | TS 타입체크 | `cd frontend && pnpm tsc --noEmit` | 0 error |
| 2 | Vite 빌드 | `pnpm build` | dist/ 생성 |
| 3 | smoke test | `pnpm test` (vitest) | 1 PASS |
| 4 | Watchlist 라우트 | dev server → `localhost:3000/watchlist` | WatchlistPage 컴포넌트 표시 |
| 5 | 빈 상태 + CTA | 빈 DB → `/watchlist` | WatchlistEmpty 컴포넌트 |
| 6 | 등록 + 즉시 표시 | "005930" 입력 + 등록 → list 1건 | DOM에 005930 row |
| 7 | 중복 등록 inline error | 같은 code 재 등록 | "이미 등록된 종목입니다" inline |
| 8 | 형식 위반 inline error | "ABC" 입력 | "6자리 숫자만 가능합니다" inline |
| 9 | 삭제 confirm + 삭제 | 휴지통 클릭 → confirm → 확인 | row 사라짐 |

### 11-3. PR-7 통합 게이트 (양쪽 머지 후)

| # | 게이트 | 명령 | GREEN 조건 |
|---|---|---|---|
| 1 | 전체 통합 기동 | `scripts/build-and-deploy.sh` | 7 컨테이너 healthy |
| 2 | 셋업 완료 → /watchlist 진입 | wizard 완료 후 `/watchlist` 접속 | WatchlistPage 표시 |
| 3 | 종목 등록 → DB+Kafka 정합 | "005930" 등록 → `psql watchlist` + Kafka 1 message | 양쪽 정합 |
| 4 | 부팅 잔고 워밍 → DB·Redis 정합 | consumer 부팅 → `psql position` + `redis-cli GET` | 정합 |
| 5 | 종목 삭제 → list 즉시 갱신 | 휴지통 → confirm → 확인 | 1초 내 제거 |
| 6 | OnboardingGuardFilter 회귀 검증 | 셋업 미완 상태 (DB clear 후) → `curl GET /api/watchlists` | 401 ONBOARDING_REQUIRED |

---

## §12. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-7 T-W1 설계 가이드 v1 발행 (architect, opus 4.7 1M). ADR 0042·0028·0040 정합 — V3 마이그레이션(instrument CHECK 완화 + 인덱스 2건) + REST 4 endpoint(Watchlist CRUD) + 디버그 endpoint(`/api/positions/bootstrap` 선택) + PositionBootstrapRunner(consumer ApplicationRunner) + KisBalanceAdapter + KisAuthAdapter(운영용 신규) + KisBalancePort(domain 신규) + RedisPositionCacheAdapter + WatchlistPage(frontend) + 종목 검색 정책 옵션 C(raw code 저장) + KillSwitch 통합. PR-7a (backend-core) + PR-7b (frontend) 병렬 가능 (분배 명세 + 충돌 행렬은 별도 문서). |
