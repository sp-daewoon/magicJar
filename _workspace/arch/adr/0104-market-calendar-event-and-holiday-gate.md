# ADR 0104: 시장 캘린더 이벤트 표면 + KRX 휴장일 배치 게이트

- 상태: **Accepted (2026-05-25 — 사용자 dogfooding 휴장일 오판 지적 + 웹 시장 캘린더 요청 반영)**
- 작성일: 2026-05-25
- 결정자: architect (협의: backend-core, market-data, frontend, qa)
- 영역: 시장 데이터·외부 API + 운영 UX + 하네스 정합
- 관련 ADR: 0013(Market Session FSM) · 0036(Task 파일 시스템) · 0053(Living Policy) · 0100(T-MARKET) · 0102(영속 자원 audit)

---

## 1. 맥락

2026-05-25 KST는 사용자가 지적한 대로 부처님오신날 대체휴무일이며, KRX 정규장이 아니다. 기존 `market_calendar`에는 `2026-05-25 / is_business_day=false / holiday_name=부처님오신날` row가 있었지만, 일부 배치 스케줄러는 weekday cron만 보고 실행 가능 상태로 남아 있었다.

동시에 T-MARKET 지수 화면은 `market_index_snapshot`, `sector_index_snapshot`, 지수 `candle(ONE_DAY)`가 모두 0건인 상태에서 `dataInsufficient=true`를 반환하고 있었다. 이 상태는 휴장일 때문에 "오늘치만 없는 것"이 아니라, 초기 지수 백필/적재 데이터가 아직 없기 때문에 투자 판단 지표로 사용할 수 없는 상태다.

사용자는 별도 웹 캘린더에서 KRX 휴장일을 확인하고, IPO 등 시장 중요 일정을 직접 넣어 확장 가능하게 관리하길 요청했다.

## 2. 결정

### 2-1. `market_calendar`는 KRX 영업/휴장 기준표로 유지한다

- `market_calendar.date`는 KRX 날짜별 영업일 여부의 단일 기준이다.
- batch 국내 경로는 weekday만 보지 않고 `BusinessDayCalendar.isBusinessDay(date)`를 호출한다.
- row가 없을 때만 주말/평일 fallback을 허용한다.
- 해외 지수 일봉 수집은 KRX 캘린더로 막지 않는다. 미국장 캘린더는 별도 후속 과제로 둔다.

### 2-2. 사용자 시장 일정은 별도 `market_calendar_event`에 저장한다

`market_calendar`에 IPO·실적·배당·FOMC 같은 이벤트를 섞지 않는다. 휴장일 기준표와 사용자 일정은 수명·출처·확장성이 다르기 때문이다.

채택 DDL 요약:

```sql
CREATE TABLE market_calendar_event (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(20) NOT NULL REFERENCES user_account(user_id) ON DELETE CASCADE,
    event_date DATE NOT NULL,
    market VARCHAR(16) NOT NULL DEFAULT 'KRX',
    event_type VARCHAR(32) NOT NULL,
    title VARCHAR(120) NOT NULL,
    description TEXT,
    instrument_code VARCHAR(16),
    source VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- `event_type`에는 CHECK 제약을 두지 않는다. IPO·EARNINGS·DIVIDEND·LOCKUP·MACRO·FOMC·CUSTOM 외 유형도 add-only로 확장한다.
- `source`는 1차 `MANUAL`, 후속 자동 수집 시 `KRX`·`DART`·`IMPORT` 등으로 확장한다.
- `payload JSONB`는 자동 수집 원문 보존 슬롯이다.

### 2-3. 웹 표면은 날짜 row + 이벤트를 묶어 반환한다

- `GET /api/market/calendar?from=YYYY-MM-DD&to=YYYY-MM-DD`
  - 날짜별 `isBusinessDay`, `isHoliday`, `holidayName`, `source`, `events[]` 반환
  - `market_calendar` row가 없는 날짜는 주말 기준 fallback
- `POST /api/market/calendar/events`
- `PUT /api/market/calendar/events/{id}`
- `DELETE /api/market/calendar/events/{id}`

프론트엔드는 `/market/calendar`를 별도 화면으로 제공한다. 월간 캘린더에서 휴장일과 이벤트 chip을 함께 표시하고, 우측 폼에서 일정 추가·수정·삭제를 수행한다.

### 2-4. 시장 지수 화면은 mock 합성을 하지 않는다

T-MARKET 지수/섹터 화면은 지수 일봉 또는 snapshot 데이터가 0건이면 `dataInsufficient=true`, `lastClose=null`, `ma={}`를 그대로 반환한다. 휴장일이라 새 국내 데이터를 수집하지 않는 것은 정상이나, 과거 데이터가 존재한다면 마지막 영업일 기준 추세를 표시해야 한다.

따라서 현재 투자 지표로 사용할 수 없는 직접 원인은 "휴장일"이 아니라 "초기 지수 백필/적재 0건"이다.

## 3. 구현 매트릭스

| 영역 | 파일 | 결정 정합 |
|------|------|----------|
| Flyway | `V55__market_calendar_event.sql` | 사용자 시장 이벤트 분리 영속 |
| API | `MarketCalendarController` | 캘린더 조회 + 이벤트 CRUD |
| API 영속 | `MarketCalendarEventJpaEntity` / Repository | user_id 자연 키 + 날짜 범위 조회 |
| Batch | `BatchBusinessDayCalendar` | `market_calendar` 기반 휴장일 판정 |
| Batch | `MarketIndexScheduler` | 국내 minute/daily close KRX 휴장일 skip |
| Batch | `InstrumentMasterSyncScheduler` | boot/cron 종목 마스터 sync 휴장일 skip |
| Frontend | `MarketCalendarPage` | 월간 캘린더 + 일정 CRUD |
| Frontend | `App` / `AppHeader` | `/market/calendar` route + 시장 메뉴 |

## 4. 검증 evidence

- `2026-05-25` DB row 확인: `is_business_day=false`, `is_holiday=true`, `holiday_name=부처님오신날`, `source=STATIC_SEED`
- `GET /api/market/calendar?from=2026-05-25&to=2026-05-25` 응답 확인: 휴장일 정상 반환
- 이벤트 실제 API smoke: POST → GET events=1 → DELETE 204
- batch 로그 확인:
  - `[instrument-sync] cron=boot skip — 휴장일 (2026-05-25)`
  - `[MarketIndexScheduler] cron=minute skip — KRX 휴장일 (2026-05-25)`
- Playwright 확인: `/market/calendar` 렌더, `부처님오신날` 표시, console error 0건
- 테스트:
  - `./gradlew :batch:test`
  - `./gradlew :api:test`
  - `./gradlew :market-trend-engine:test`
  - `pnpm typecheck`
  - `pnpm build`
  - market calendar frontend test

## 5. 운영 영향

### 5-1. SLO 영향

- 국내 휴장일 불필요 KIS 호출 감소: S-4(KIS rate limit) 안정성 향상.
- 시장 캘린더 조회는 read-heavy이고 월간 범위가 작아 S-3(account API p95) 등 자본 path SLO 영향 0.
- 신규 이벤트 CRUD는 사용자 수동 조작이며 주문/시그널 path와 분리된다.

### 5-2. Rollback

- API/UI rollback: `/market/calendar` route와 controller 제거 또는 revert.
- DB rollback: `market_calendar_event`는 휴장일 기준표와 분리되어 있어 drop해도 `market_calendar` 영향 0.
- batch rollback: `BusinessDayCalendar` 주입 제거 시 weekday-only로 돌아가지만, 휴장일 KIS 호출 재발 risk가 생긴다.

### 5-3. 관측 매트릭스

| 관측 | 기대 |
|------|------|
| `market_calendar` 당일 row | 휴장일이면 `is_business_day=false` |
| batch 휴장일 로그 | 국내 minute/daily/instrument sync skip |
| `market_calendar_event` row count | 사용자 이벤트 등록에만 증가 |
| `/api/market/index/snapshot` | 데이터 0건이면 `dataInsufficient=true` |
| `/market/calendar` Playwright | 휴장일/이벤트 렌더 + console error 0 |

## 6. 후속 과제

1. T-MARKET 지수 초기 백필: `0001`, `1001`, `.IXIC`, `.SPX` 일봉을 채워야 시장 지수/상세 추세가 투자 보조 지표로 의미를 가진다.
2. 미국장 캘린더 분리: 해외 지수 daily close는 KRX 휴장일과 독립된 NYSE/NASDAQ 캘린더가 필요하다.
3. IPO·공모·보호예수·FOMC 자동 수집: `source`와 `payload`를 활용해 manual 이벤트와 자동 이벤트를 병행한다.
4. 캘린더 UI 고도화: 월/주/list view, 이벤트 필터, 알림 임계, 종목 상세와 연결.
5. 하네스 정합: 본 작업처럼 코드가 먼저 진행된 경우에도 ADR/task를 사후 보정해야 했다. ADR 0102 절차를 코드 작업 전 lightweight gate로 끌어오는 후속 검토가 필요하다.

## 7. 변경 이력

| 일자 | 변경 |
|------|------|
| 2026-05-25 | 신규 발행. KRX 휴장일 배치 게이트, 웹 시장 캘린더, `market_calendar_event` 확장 영속, T-MARKET 데이터 부족 의미를 Accepted 결정으로 기록. |
