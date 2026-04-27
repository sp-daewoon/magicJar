package com.mj.consumer.infrastructure.persistence.order

import org.springframework.data.jpa.repository.JpaRepository

interface PositionJpaRepository : JpaRepository<PositionJpa, String>
