# ADR 0004: 영속 저장소와 캐시 전략

> **STATUS: SUPERSEDED by ADR 0025 (2026-05-03 v2 베이스라인)** — PostgreSQL 단일 인스턴스 + 스키마 분리로 본격 결정됨. 본 ADR은 참조용 보존.

- 상태: Superseded by 0025
- 작성일: 2026-04-25
- 결정자: architect (협의: backend-core, market-data, strategy-engine)

## 맥락

magicJar는 (a) 주식 캔들 시계열(고밀도) (b) 뉴스/공시 텍스트 (c) 매매일지 (d) 실시간 시세 캐시 — 4가지 데이터 특성을 다룬다. 저장소를 너무 많이 끌어들이면 MVP 부담이 크고, 반대로 단일 DB만 쓰면 스케일 한계가 빨리 온다.

## 검토한 대안

| 대안 | 특성 | 평가 |
|------|------|------|
| A. 단일 PostgreSQL | 최단 셋업, 캔들/뉴스/일지 모두 가능 | 대량 캔들 파티셔닝·실시간 pub/sub 부재 |
| B. PostgreSQL + Redis | Redis로 KIS 토큰·실시간 시세 캐시 + PUB/SUB, PG로 영속 | MVP에 적정, 운영 부담 제한적 |
| C. PostgreSQL + TimescaleDB 확장 + Redis | 캔들 대용량 효율, 다운샘플 지원 | 확장 설치·운영 복잡도↑, MVP 과함 |
| D. InfluxDB + PG + Redis | 시계열 전용 성능 | 이중 스토어·학습곡선 |

## 결정

**B안: PostgreSQL 15 + Redis 7.**

- **PostgreSQL**: 모든 영속 데이터(캔들·뉴스·매매일지·전략 설정·사용자 프로필). 마이그레이션은 **Flyway**. 캔들 테이블은 `(code, interval, open_time)` PK + 월 단위 RANGE 파티션 (네이티브 선언적 파티셔닝).
- **Redis**:
  - KIS OAuth 토큰 캐시 (`kis:token:{mode}`, TTL=토큰 만료 −1h)
  - 실시간 시세 Pub/Sub (`quotes:{code}` 채널)
  - AI 응답 캐시 (`ai:sentiment:{newsId}`, TTL=24h)
  - 레이트리미터 상태(Bucket4j 분산 버킷)
- **TimescaleDB는 후속 ADR에서 필요성 입증 시 도입.** MVP에선 Flyway 선언적 파티션으로 충분.

로컬 개발 시: Docker Compose(`postgres:15`, `redis:7`). CI/프로덕션 결정은 후속.

## 결과

- 긍정: 두 개의 검증된 오픈소스로 모든 요구 커버, 로컬 셋업 단순
- 부정: 초고밀도 1분봉 수년치 누적 시 PG 단독 한계 → 후속 ADR 트리거 포인트(사용량 지표로 감시)
- 후속: 
  - 실시간 시세를 Redis Stream vs Pub/Sub 중 선택 → ADR 0010 예정
  - 멀티 인스턴스 배포 시점에 Redis는 필수 공용, PG는 공용 DB 또는 매니지드
