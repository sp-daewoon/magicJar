package kr.spdaewoon.magicjar.consumer.autopilot

import org.springframework.data.jpa.repository.JpaRepository

/**
 * `autopilot_state` Spring Data JPA Repository (consumer 모듈 · spec 2026-06-22 Task 3).
 *
 * 헌법 §6 — JpaRepository 상속분(`findById`/`save`)만 사용. body 가진 default fun 0건
 * (query inference 함정 회피). account-wide 단일 행(id=1)이므로 `findById(1L)` 로 충분하다.
 */
interface AutopilotStateJpaRepository : JpaRepository<AutopilotStateJpaEntity, Long>
