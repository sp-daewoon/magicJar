# 확정 기술 스택 — magicJar MVP

Phase B 구현 시 이 파일을 기준으로 `build.gradle.kts`의 `dependencyManagement` 블록을 구성한다.

## 런타임

| 범주 | 선택 | 버전 기준 | 관련 ADR |
|------|------|----------|---------|
| 언어 | **Kotlin** | **2.0.x (K2 컴파일러)** | 0001 |
| JVM | JDK 21 (Temurin) | LTS, virtual thread 가능 | 0001 |
| 프레임워크 | Spring Boot | 3.3.x 최신 | 0001 |
| AI 추상 | Spring AI | 1.0.x (Anthropic starter 포함) | 0005 |
| 빌드 | Gradle Kotlin DSL | 8.x | 0001, 0002 |
| Kotlin 컴파일러 플러그인 | `kotlin-spring`(all-open), `kotlin-jpa`(no-arg) | JPA 모듈에 한해 kotlin-jpa 적용 | 0001 |

## 데이터 계층

| 구성요소 | 기술 | 비고 | ADR |
|---------|------|------|-----|
| RDB | PostgreSQL 15 | 선언적 파티셔닝 | 0004 |
| 마이그레이션 | Flyway | `V{N}__{desc}.sql` | 0004 |
| ORM | Spring Data JPA + Hibernate | 읽기 쿼리는 JPQL 우선, 복잡건은 `jOOQ` 도입 재검토 | 0004 |
| 캐시·실시간 | Redis 7 | Lettuce 클라이언트 | 0004 |
| 로컬 환경 | Docker Compose (postgres + redis) | - | 0004 |

## 외부 API

| 용도 | 공급자 | ADR |
|------|-------|-----|
| 증권사 시세·주문 | KIS OpenAPI (REST + WS) | 0003 |
| 전자공시 | DART Open API | - |
| 뉴스 | 네이버 금융(공식 RSS 우선) | - |
| LLM | Anthropic Claude (기본) / Ollama (폴백) | 0005 |

## 안정성/관측

- **Resilience4j**: CircuitBreaker, Retry, TimeLimiter (주문 경로)
- **Bucket4j**: 요청 단위 레이트리미터 (KIS 호출)
- **Micrometer + Prometheus** registry (MVP에선 프로메테우스 서버 미기동, 메트릭만 수집)
- **Logback**: JSON 인코더, 비밀정보 필터(appkey/appsecret 마스킹)

## 개발 보조

- **Lombok / MapStruct 미사용** — Kotlin data class + 확장 함수로 대체
- **jackson-module-kotlin** 필수 (Kotlin data class 역직렬화)
- **SpringDoc OpenAPI** — REST 문서
- **Testcontainers** — PostgreSQL/Redis 통합 테스트
- **JUnit 5 + kotest-assertions** (또는 AssertJ) + **MockK** (Mockito-Kotlin 대체, final class 친화)
- **ktlint** Gradle 플러그인 — 기본 규칙 적용

## 프런트

- **Thymeleaf** + **HTMX 1.9.x** + **Alpine.js 3.x** (선택)
- **Chart.js 4.x** (캔들 플러그인: `chartjs-chart-financial`)
- **Tailwind CSS 3.x** — 초기 CDN, 프로덕션 전 CLI 빌드 전환
- **한국어 로케일** 기본값, KST 타임존 고정

## 환경변수 (MVP)

```
SPRING_PROFILES_ACTIVE=mock
MAGICJAR_MODE=MOCK                 # BACKTEST | MOCK | REAL
MAGICJAR_ALLOW_REAL=false
MAGICJAR_HARNESS_ENABLED=true

KIS_MOCK_APP_KEY=...
KIS_MOCK_APP_SECRET=...
KIS_APP_KEY=...                    # REAL 모드에서만
KIS_APP_SECRET=...
KIS_ACCOUNT_CANO=...
KIS_ACCOUNT_PRDT=01

ANTHROPIC_API_KEY=...              # 미설정 시 AI 기능 비활성 부팅
DART_API_KEY=...                   # 미설정 시 공시 수집 비활성

SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/magicjar
SPRING_DATASOURCE_USERNAME=magicjar
SPRING_DATASOURCE_PASSWORD=...

SPRING_REDIS_HOST=localhost
SPRING_REDIS_PORT=6379
```

## 미도입(후속 재검토)

- Kafka / RabbitMQ (이벤트 버스 확대 시)
- TimescaleDB (캔들 볼륨 임계치 초과 시)
- Kubernetes / 컨테이너 오케스트레이션 (단일 인스턴스 MVP 동안 불필요)
