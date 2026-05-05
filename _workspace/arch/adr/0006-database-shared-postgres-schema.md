# ADR 0006: 데이터 저장소 — PostgreSQL 단일 인스턴스 + 스키마 분리

- 상태: Accepted
- 작성일: 2026-04-27
- 결정자: architect (협의: backend-core)
- 관계: ADR 0004(영속 저장소·캐시 전략)를 **확장** — Postgres 채택은 그대로, MSA 환경에서의 분할 정책을 추가

## 맥락

ADR 0004로 3 JVM 앱(`api`·`batch`·`consumer`)이 서로 다른 시점에 같은 도메인 DB를 읽고 쓴다. 마이크로서비스 정통 가이드는 "서비스마다 별도 DB(DB-per-service)"를 권하지만, 단일 운영자가 트랜잭션·일관성·운영 부담을 모두 감당하기엔 MVP에 과도하다.

또한 자동매매 도메인은:
- **trading 영역**(주문·포지션·매매일지)은 강한 일관성 필요 — 같은 트랜잭션 안에서 처리되는 게 자연스러움
- **ops 영역**(하네스 상태·QA 보고서 메타·작업 큐) — trading과 격리하고 싶지만, 별도 DB까지 띄울 필요는 없음

## 검토한 대안

| 대안 | 장점 | 단점 |
|------|------|------|
| A. 단일 DB·단일 스키마 | 가장 단순 | 영역 경계 약함, 마이그레이션 충돌 잦음 |
| B. **단일 PostgreSQL 인스턴스 + 스키마 분리** (본 결정) | 단순함 + 영역 격리 + 트랜잭션 자유 | 진짜 격리는 아님 (한 인스턴스 다운 = 전체 영향) |
| C. DB-per-service (api/batch/consumer 별 DB) | 완전 격리 | 운영 3배, 트랜잭션 분산, MVP 과도 |
| D. TimescaleDB | 시계열 최적 | Phase D에선 데이터 양 미흡, 도입 후보로만 보유 |

## 결정

**PostgreSQL 16 단일 인스턴스 + 스키마 2개 분리. DB-per-service는 Phase F+ 검토.**

### 인스턴스 구성

- 이미지: `postgres:16-alpine`
- 단일 데이터베이스: `magicjar`
- 스키마:
  | 스키마 | 용도 | 주요 테이블 |
  |--------|------|------------|
  | `trading` | 도메인 트랜잭션 | `candle`, `tick_1m`, `order_intent`, `order_executed`, `position`, `journal_entry`, `news_analyzed` |
  | `ops` | 하네스·운영 메타 | `qa_report`, `task_log`, `agent_run`, `phase_state` |

- 각 앱은 같은 connection URL을 쓰되, JPA `default_schema` 또는 SQL `SET search_path`로 영역 분리.

### 접근 정책

- **api-app**: trading + ops (읽기·쓰기)
- **batch-app**: trading (백테스트·EOD 결과 쓰기) + 읽기는 전체
- **consumer-app**: trading (실시간 이벤트 영속화)

### 마이그레이션

- 도구: **Flyway** (본 ADR 그대로 유지)
- 위치: `domain/` 모듈은 마이그레이션 파일을 갖지 않는다(라이브러리). 마이그레이션은 `api-app/src/main/resources/db/migration/{trading,ops}/V*.sql`로 두고, batch/consumer는 같은 경로를 공유 (Gradle 리소스 의존으로 끌어옴) — 실행은 api-app만.
- 다중 앱 동시 기동 시 충돌 방지: **api-app만 마이그레이션 실행**, batch/consumer는 `flyway.enabled=false`.

### 트랜잭션·일관성

- 같은 스키마 내에서는 일반 트랜잭션 사용 자유.
- 스키마 간 트랜잭션도 가능(같은 인스턴스이므로) — 단 일상적으로 하지 않음. ops가 trading 데이터를 참조해야 하면 read-only.
- 분산 트랜잭션·Saga는 MVP 미도입. 주문↔매매일지처럼 "거의 동시" 일관성이 필요한 흐름은 같은 트랜잭션(consumer-app 내부)에서 처리.

### Redis 역할 (변경 없음 — 본 ADR)

- 세션·rate-limit·STOMP fan-out 옵션. 도메인 데이터는 저장하지 않음.

### 시계열 데이터 처리

- 캔들 1분/일봉: `trading.candle` 일반 테이블 + 인덱스(`(symbol, ts)`)로 시작.
- 틱: 영속화 시 `trading.tick_1m`에 분 단위 집계로만 저장 (raw tick은 Kafka retention 24h로 보유).
- **TimescaleDB 도입 트리거**: 일봉 행수 ≥ 1억, 또는 틱 영속화 요구 발생 → 별도 ADR.

## 결과

- 긍정: 운영 단순(인스턴스 1개, 마이그레이션 1곳), 영역 격리는 스키마로 강제, 트랜잭션 자유.
- 부정: 진짜 물리 격리는 아님 — 인스턴스 다운 시 전체 영향. 스키마 분리 강제는 코드 리뷰 책임.
- 후속:
  - Flyway 마이그레이션 폴더 `db/migration/{trading,ops}/` 분리
  - JPA 엔티티 `@Table(schema = "trading")` 또는 `@Table(schema = "ops")` 명시
  - DB-per-service 트리거: 운영 부하 분산 필요 + 인력 ≥2명. 그 시점에 별도 ADR.

## Links

- spec: `docs/superpowers/specs/2026-04-27-msa-restructure-design.md` §9
- 관련 ADR: 0004(확장), 0019, 0021, 0023
