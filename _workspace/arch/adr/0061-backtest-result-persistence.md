# ADR 0061 — Backtest 결과 영속 (3 테이블 분리 + REST 5 endpoint + KPI 계산 룰)

- 상태: Accepted
- 작성일: 2026-05-12
- 결정자: backend-core (사용자 결정 2026-05-12 직접 채택 · architect spec PR #330 권장안 모두 채택)
- 관계:
  - ADR 0056 §2-6 supersede (1차 stateless 결정 → 본 ADR 영속 결정으로 대체)
  - ADR 0056 §3-4 supersede §1 (backlog §27-2 → 본 ADR PR로 처리)
  - ADR 0047 정합 (strategy-eval-pool 공유 + timeout 1000ms — 영향 0)
  - ADR 0056 §2-7 정합 (성과 지표 계산식 LOCK — 본 ADR이 계승)

---

## 1. 컨텍스트

ADR 0056 §2-6은 1차 stateless REST run을 결정하고 결과 영속을 backlog §27-2로 미루었다.
PR-50 plan(2026-05-12)이 fitness gap을 분석:

- **사용자 가치 미달**: 매번 재실행해야 결과 확인 가능 — "지난 결과 비교" 불가
- **운영 정합 미달**: T-SIG 활성화 사전 게이트("최근 N일 내 백테스트 통과") 구현 불가 — DB 영속 필수
- **UI 부재**: POST /api/backtest/run만 존재, 목록·조회·삭제 0건

사용자가 2026-05-12 architect spec PR #330 권장안 6건을 모두 채택:
- **A-1**: 본 사이클 R2 영속 (T-SIG 활성화 게이트 준비)
- **B-1**: 3 테이블 분리 (`backtest_run` · `backtest_trade` · `backtest_position`)
- **C-1**: 5 endpoint (POST run + GET list + GET detail + GET result + DELETE)
- **D-1**: `/strategy/backtest` 단일 페이지 (R3 frontend — 본 PR 범외)
- **E-1**: 모델 확장 본 사이클 0 (세금·수수료·슬리피지 모델 현행 유지)
- **F-1**: R2 → R3 직렬 (본 PR이 R2, R3 frontend는 본 PR 머지 후 디스패치)

---

## 2. 결정

### 2-1. 테이블 구조 (B-1: 3 테이블 분리)

| 테이블 | 역할 | Flyway |
|---|---|---|
| `backtest_run` | 실행 메타 + KPI 집계 | V12 |
| `backtest_trade` | 매매 기록 시계열 (N 관계) | V13 |
| `backtest_position` | 포지션 기록 시계열 (N 관계) | V13 |

CASCADE DELETE: `backtest_run` 삭제 시 `backtest_trade` · `backtest_position` 일괄 삭제.

**B-2(JSONB blob 단일 테이블) 폐기 사유**: 시계열 JOIN·조회 불가 + 후속 T-J(Trading Journal) 패턴 회귀.

### 2-2. backtest_run 주요 컬럼

```
id              BIGSERIAL PRIMARY KEY
user_id         VARCHAR(40)    -- 실행 사용자 ID
code            VARCHAR(8)     -- 종목코드 (복수 시 쉼표 구분)
interval        VARCHAR(8)     -- 캔들 인터벌
rule_dsl        TEXT           -- 규칙 요약 (strategyId + universe + 모델)
range_from      TIMESTAMPTZ    -- 시뮬레이션 시작일
range_to        TIMESTAMPTZ    -- 시뮬레이션 종료일
status          VARCHAR(20)    -- PENDING / RUNNING / SUCCEEDED / FAILED
started_at      TIMESTAMPTZ    -- 실행 시작 시각
completed_at    TIMESTAMPTZ    -- 실행 완료 시각
total_return    NUMERIC(15,4)  -- 총수익률
win_rate        NUMERIC(7,4)   -- 승률
mdd             NUMERIC(7,4)   -- 최대 낙폭 MDD
sharpe          NUMERIC(7,4)   -- Sharpe 비율
sortino         NUMERIC(7,4)   -- Sortino 비율 (post-MVP §6-2)
trade_count     INT            -- 총 매매 건수
position_count  INT            -- 총 포지션 건수
detail_json     JSONB          -- 전체 성과 지표 JSON
```

### 2-3. REST API 5 endpoint (C-1)

| 메서드 | 경로 | 기능 |
|---|---|---|
| `POST` | `/api/backtests` | 실행 + 즉시 영속 (PENDING→RUNNING→SUCCEEDED/FAILED) |
| `GET` | `/api/backtests` | 사용자 목록 (페이징 `?page=0&size=20`) |
| `GET` | `/api/backtests/{id}` | 단건 메타 + KPI |
| `GET` | `/api/backtests/{id}/result` | 거래·포지션 시계열 (페이징) |
| `DELETE` | `/api/backtests/{id}` | 삭제 (CASCADE) |

기존 `POST /api/backtest/run` (ADR 0056 §5-1, BacktestController)은 **유지** — stateless run 경로 별도 보존.
신규 영속 API는 `/api/backtests` (복수형) 별도 컨트롤러(`BacktestsController`).

### 2-4. 영속 흐름

```
POST /api/backtests
  → BacktestService.runBacktest()          // 기존 로직 재사용
  → BacktestPersister.persist(userId, result)   // 신규 — 트랜잭션 영속
    → backtest_run INSERT (status=SUCCEEDED/FAILED)
    → backtest_trade saveAll
    → backtest_position saveAll
  → 응답: BacktestRunPersistResponse { runId, status, trades, positions, performance, ... }
```

NO_CANDLE_DATA(Aborted) 시에도 status=FAILED로 저장 — feedback_no_mock_data.md 정합.

### 2-5. KPI 계산 룰 (ADR 0056 §2-7 계승)

| 지표 | 계산식 | ADR 0056 원문 |
|---|---|---|
| 총수익률 | `(finalCapital - initialCapital) / initialCapital` | §2-7 |
| CAGR | `(finalCapital / initialCapital)^(1/years) - 1`, years=days/365.25 | §2-7 |
| MDD | `max((peak_at_t - equity[t]) / peak_at_t for all t)` | §2-7 |
| Sharpe | `(mean - riskFree/252) / stdev * sqrt(252)`, n<30 시 null | §2-7 |
| 승률 | `winning_trades / closed_trades` | §2-7 |
| Profit Factor | `sum(win) / sum(abs(loss))`, sum(loss)==0 시 null | §2-7 |
| Sortino | post-MVP — `null` 저장 (§6-2 후속 예약) | — |

### 2-6. 수수료·세금 정합 (ADR 0056 §2-3-A 계승)

| 항목 | default | 출처 |
|---|---|---|
| 증권거래세 | 0.18% (매도 시 KOSPI·KOSDAQ) | 국세청 · KRX https://www.krx.co.kr |
| 농어촌특별세 | 0.15% (매도 시 KOSPI 한정) | 국세청 https://www.nts.go.kr |
| 위탁 수수료 | 0.015% | KIS https://securities.koreainvestment.com |
| 유관기관 수수료 | 0.0036% | KRX·KSD·증선위 합산 |
| 슬리피지 | ±0.05% (매수 +, 매도 −) | magicJar own |

`backtest_trade.fee` 컬럼: commission + tax 합산 저장 (detail은 `backtest_run.detail_json` 참조).

### 2-7. 사용자 소유권 게이팅

모든 조회·삭제 endpoint에서 `userId` 검증 의무:
- `BacktestRunJpaRepository.findByIdAndUserId()` — 타 사용자 조회 차단
- `BacktestRunJpaRepository.existsByIdAndUserId()` — 소유권 확인
- 소유권 불일치 시 404 반환 (403 아님 — 존재 여부 노출 금지)

---

## 3. 트레이드오프

| 검토안 | 채택 여부 | 사유 |
|---|---|---|
| **A-1 본 사이클 R2 영속** | ✅ | T-SIG 활성화 게이트 준비 + 사용자 가치 즉시 |
| A-2 post-MVP | 폐기 | T-SIG 활성화 사전 게이트 부재 — fitness gap |
| A-3 Redis cache 24h TTL | 폐기 | 결과 소실 — 운영 가치 미달 |
| **B-1 3 테이블 분리** | ✅ | 시계열 조회 + T-J 패턴 통일 |
| B-2 JSONB blob | 폐기 | 시계열 JOIN 불가 |
| **C-1 5 endpoint** | ✅ | 사용자 워크플로우 완비 |
| C-2 단일 GET 전체 blob | 폐기 | 응답 비대 + 페이징 불가 |
| **F-1 R2→R3 직렬** | ✅ | API 확정 전 frontend mock drift risk 방지 |

---

## 4. 영향

### 4-1. 컨테이너 영향

| 컨테이너 | 영향 |
|---|---|
| `api` | `BacktestsController` + `BacktestPersister` + `BacktestQueryService` + `persistence/backtest/` 6 파일 신규 |
| `domain` | 영향 0 (모델 확장 E-1 = 0) |
| `consumer` | 영향 0 |
| `batch` | 영향 0 |
| `frontend` | 영향 0 (R3 frontend는 본 PR 머지 후 backlog §38 disp.) |

### 4-2. Flyway 마이그레이션

| 파일 | 내용 |
|---|---|
| `V12__backtest_run.sql` | `backtest_run` 테이블 + `idx_backtest_run_user_code` 인덱스 |
| `V13__backtest_trade_position.sql` | `backtest_trade` + `backtest_position` 테이블 + 2 인덱스 |

### 4-3. 환경변수

신규 환경변수 0건 — 기존 `MAGICJAR_DSL_BACKTEST_TIMEOUT_MS=1000` 재사용.

### 4-4. 보안

- `userId` 기반 소유권 검증 — 타 사용자 결과 노출 0
- backtest_run.rule_dsl 컬럼: 전략 식별자 + 모델키 요약만 저장 (DSL 전체 텍스트 미포함)

---

## 5. 검증 항목

| 항목 | 방법 |
|---|---|
| Flyway 마이그레이션 자동 실행 | `docker exec magicjar-postgres psql -c "\d backtest_run"` 확인 |
| `:api:check` GREEN + ktlint | `./gradlew :api:check` |
| 헌법 §6 Repository body fun 0건 | `grep -r "fun.*{" api/src/main/kotlin/.../backtest/*Repository*.kt` 0건 |
| POST /api/backtests smoke | curl + runId 반환 확인 |
| GET /api/backtests smoke | curl + 목록 반환 확인 |
| DELETE /api/backtests/{id} smoke | curl 204 확인 |

---

## 6. 후속 ADR 후보

| 번호 | 내용 |
|---|---|
| 0065+ | T-BT R3 frontend `/strategy/backtest` 단일 페이지 (backlog §38 발동 시) |
| 0066+ | Sortino 비율 계산 추가 (sortino 컬럼 현재 null — post-MVP) |
| 0067+ | backtest_run 기반 T-SIG 활성화 사전 게이트 ("최근 7일 내 백테스트 통과 의무") |

---

## 7. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-12 | ADR 0061 신규 발행 — 사용자 결정 A-1·B-1·C-1·D-1·E-1·F-1 채택 (architect spec PR #330 권장안 모두 채택). ADR 0056 §2-6 supersede + §3-4 supersede §1 (backlog §27-2 → 본 PR 처리). Flyway V12·V13, JPA 3쌍, BacktestPersister·BacktestQueryService, BacktestsController 5 endpoint. (backend-core) |
