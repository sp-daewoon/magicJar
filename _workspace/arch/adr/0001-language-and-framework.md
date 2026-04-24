# ADR 0001: 언어·프레임워크·빌드 시스템 선택

- 상태: Accepted
- 작성일: 2026-04-25 (동일 세션 내 개정: Java 21 → Kotlin 2.0)
- 결정자: architect (협의: backend-core, 사용자 선호)

## 맥락

magicJar는 Spring AI를 활용한 한국 주식 자동매매 시스템이다. 사용자 요구로 **Spring AI 사용**이 명시되어 있어 JVM 스택이 전제된다. 초기안은 Java 21이었으나, 사용자가 명시적으로 **Kotlin** 채택을 지시 — 코드베이스 전체를 Kotlin으로 통일한다.

## 검토한 대안

| 대안 | 장점 | 단점 |
|------|------|------|
| **Kotlin 2.0 + Spring Boot 3.3 + Gradle Kotlin DSL** (결정) | null-safety, data class, 확장 함수, DSL 친화, 코루틴 옵션, Spring Boot 공식 Kotlin 지원 | JPA/Hibernate 사용 시 `all-open`·`no-arg` 컴파일러 플러그인 필요, Lombok 불가(그러나 불필요) |
| Java 21 + Spring Boot 3.3 | 보수적 선택, record/pattern matching 가능 | Kotlin 대비 boilerplate 많음, 사용자 비선호 |
| Kotlin 1.9 + Boot 3.3 | 안정 K1 컴파일러 | K2 컴파일러(2.0) 성능·기능 이득 놓침 |

## 결정

**Kotlin 2.0.x + Spring Boot 3.3.x + JDK 21(Temurin) + Gradle Kotlin DSL(멀티모듈).**

세부:
- 애플리케이션 소스 **100% Kotlin**. Java 혼용 금지(서드파티 라이브러리 통합 외).
- **JVM 타깃 = 21**. virtual thread 사용 가능.
- Kotlin 컴파일러 플러그인: `kotlin-spring`(all-open), `kotlin-jpa`(no-arg, JPA 모듈에서만), `kotlin-kapt`(Querydsl 등 필요 시에만).
- **코루틴**은 초기 MVP에 기본 도입하지 않음. Spring MVC + JVM virtual thread(JDK 21) 조합으로 충분. WebFlux/코루틴 전환은 후속 ADR에서 성능 필요 시 검토.
- 테스트 라이브러리:
  - **JUnit 5** + **kotest-assertions** 또는 **AssertJ-Kotlin**
  - **MockK** (Mockito 대신 Kotlin-native, final class 친화)
  - **Testcontainers** (PostgreSQL/Redis)
- JSON: Jackson + **jackson-module-kotlin** 필수.
- Lombok·MapStruct 제거. Kotlin data class + 확장 함수로 대체.
- Kotlin 코드 스타일: `ktlint` 플러그인 적용, 기본 규칙 + 회사 커스텀 없음.

## 결과

- 긍정: 도메인 모델(record → data class)로 코드 양 30~40% 감소, null 안전성, DSL 표현력(백테스트 조건식 빌더·하네스 상태 JSON 등 Kotlin DSL로 간결화)
- 부정: 팀 내 Java 익숙 개발자 전환 비용, 빌드 속도 Java보다 소폭 느림, `open`/`no-arg` 플러그인 구성 필요
- 후속:
  - backend-core가 `kotlin-spring` + `kotlin-jpa` 플러그인 설정 완료
  - 도메인 모델 초안(`_workspace/backend/domain-model-draft.md`)은 Kotlin data class로 재작성됨
  - 라이브러리 선택 재검토: Lombok/MapStruct 삭제, MockK·kotest 추가 (본 ADR로 확정)
