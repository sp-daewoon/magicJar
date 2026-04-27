package com.mj.consumer.infrastructure.persistence.order

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OrderJpaRepository : JpaRepository<OrderJpa, UUID> {
    fun findByCode(code: String): List<OrderJpa>
}
