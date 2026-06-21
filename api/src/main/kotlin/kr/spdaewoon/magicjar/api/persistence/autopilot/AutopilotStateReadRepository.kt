package kr.spdaewoon.magicjar.api.persistence.autopilot

import org.springframework.data.jpa.repository.JpaRepository

/**
 * `autopilot_state` read-only Spring Data JPA Repository (api 모듈 · spec 2026-06-22 Task 4).
 *
 * 헌법 §6 — JpaRepository 상속분(`findById`)만 사용. body 가진 default fun 0건(query inference 함정 회피).
 * account-wide 단일 행(id=1)이므로 `findById(1L)` 로 충분. **읽기 전용** — api 는 autopilot_state 를 쓰지 않는다.
 */
interface AutopilotStateReadRepository : JpaRepository<AutopilotStateReadEntity, Long>
