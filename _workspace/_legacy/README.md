# _legacy/

P1에서 임시 백업한 코드. P2~P3에서 신규 위치로 이전 후 본 디렉토리는 정리.

| 디렉토리 | 출처 | 신규 위치 (예정) | Phase |
|---------|------|----------------|-------|
| `application.yml.bak` | `app/src/main/resources/application.yml` | `api/src/main/resources/application.yml` 등 4개로 분할 | P2 |
| `market-kis/` | `market-data/src/main/kotlin/.../marketdata/kis/` | api 또는 consumer의 `infrastructure/kis/` (Spring 의존 클라이언트는 도메인 외부) | P2 |
| `market-persistence/` | `market-data/src/main/kotlin/.../marketdata/persistence/` | api 또는 batch의 `infrastructure/persistence/` (Spring Data JPA 어댑터) | P2 |
| `market-kis-test/` | 위 kis 테스트 | 위 kis와 동일 위치 | P2 |
| `market-persistence-test/` | 위 persistence 테스트 | 위 persistence와 동일 위치 | P2 |

**도메인 결정 (P1 중 발견)**: domain 모듈은 java-library + Spring AI core만. `@Service`/`@Repository`/`@Transactional`/`JpaRepository` 의존하는 코드는 domain에 둘 수 없음. spec §6의 "market-data/kis-rest → domain/market" 매핑은 **port(인터페이스)만 domain**, **구현체는 앱 infrastructure**로 정정 — hexagonal 정석.
