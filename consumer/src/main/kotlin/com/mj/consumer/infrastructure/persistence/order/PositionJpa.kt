package com.mj.consumer.infrastructure.persistence.order

import com.mj.domain.common.Position
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "position")
class PositionJpa(
    @Id @Column(name = "code", length = 12) var code: String,
    @Column(name = "qty") var qty: Int,
    @Column(name = "avg_price", precision = 20, scale = 4) var avgPrice: BigDecimal,
    @Column(name = "opened_at") var openedAt: Instant,
)

fun Position.toJpa(): PositionJpa = PositionJpa(code, qty, avgPrice, openedAt)

fun PositionJpa.toDomain(): Position = Position(code, qty, avgPrice, openedAt)
