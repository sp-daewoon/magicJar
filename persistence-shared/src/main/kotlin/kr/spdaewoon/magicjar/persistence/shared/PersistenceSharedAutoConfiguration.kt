package kr.spdaewoon.magicjar.persistence.shared

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * persistence-shared 모듈 auto-configuration.
 *
 * ## 발행 이유 (ADR 0088 후속 — v3.7.1 hotfix)
 *
 * Spring Boot의 `@EnableAutoConfiguration` JPA scan (`JpaRepositoriesAutoConfiguration` /
 * `HibernateJpaAutoConfiguration`)은 **main class 패키지를 root로 fallback** (`AutoConfigurationPackages`).
 * api/consumer 모듈 main class는 각자 `kr.spdaewoon.magicjar.api`·`kr.spdaewoon.magicjar.consumer` 패키지에
 * 위치하므로, 별도 라이브러리 모듈인 `persistence-shared`의 JPA 자산
 * (`kr.spdaewoon.magicjar.persistence.shared.{stomp, order.outbox, notification.outbox}.*`)을
 * 자동 발견하지 못함.
 *
 * v3.7.0 cut 후 사용자 데이터 초기화 dogfooding evidence에서 빈 DB 부팅 시 다음 실패가 실증됨:
 * ```
 * APPLICATION FAILED TO START
 * Parameter 0 of constructor in StompEventRepositoryAdapter required a bean of type
 * 'StompEventJpaRepository' that could not be found.
 * ```
 *
 * ## 해결 — 옵션 B (라이브러리 자체 auto-configuration)
 *
 * 옵션 A(main class에 `@EnableJpaRepositories` 직접 명시)는 `@WebMvcTest` 컨트롤러 테스트가
 * 강제로 JPA stack을 로드하게 만들어 `entityManagerFactory` Bean 부재로 80건 테스트 fail.
 *
 * `@AutoConfiguration` + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
 * 등록 방식은 표준 Spring Boot auto-config처럼 처리되어 `@WebMvcTest`의 auto-config 제외(JPA disable)와
 * 정상 호환된다.
 *
 * - api/consumer 양쪽 `implementation(project(":persistence-shared"))` 의존이라
 *   이 auto-config가 자동 활성화됨 (`@ConditionalOnClass(JpaRepository::class)`은 안전망).
 * - basePackages는 `persistence-shared` 패키지 root만 지정. 각 모듈 자체 JPA 자산은
 *   기존 Spring Boot default scan(`@EnableAutoConfiguration` + main class 패키지 fallback)이 그대로 처리.
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
@AutoConfiguration
@ConditionalOnClass(JpaRepository::class)
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackages = ["kr.spdaewoon.magicjar.persistence.shared"])
@EntityScan(basePackages = ["kr.spdaewoon.magicjar.persistence.shared"])
class PersistenceSharedAutoConfiguration
