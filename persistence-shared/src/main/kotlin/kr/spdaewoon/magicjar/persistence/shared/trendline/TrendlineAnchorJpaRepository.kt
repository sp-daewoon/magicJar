package kr.spdaewoon.magicjar.persistence.shared.trendline

import org.springframework.data.jpa.repository.JpaRepository

/**
 * `trendline` anchor 조회 전용 Spring Data 리포지터리 (ADR 0140 §3-2 · R2).
 *
 * api·consumer 양쪽 `JpaScanConfiguration` 의 `@EnableJpaRepositories` basePackages
 * (`kr.spdaewoon.magicjar.persistence.shared`)에 포함 → 양쪽 ApplicationContext 에서 발견.
 *
 * 헌법 §6 (ADR 0051): interface 내부 body 가진 fun 0건 — findById(built-in)만 사용, 변환은 어댑터 private fun.
 */
interface TrendlineAnchorJpaRepository : JpaRepository<TrendlineAnchorRow, Long>
