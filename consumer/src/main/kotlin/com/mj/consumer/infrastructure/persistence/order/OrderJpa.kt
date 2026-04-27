package com.mj.consumer.infrastructure.persistence.order

import com.mj.domain.common.Order
import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "trade_order",
    indexes = [
        Index(name = "idx_trade_order_code", columnList = "code"),
        Index(name = "idx_trade_order_created_at", columnList = "created_at"),
    ],
)
class OrderJpa(
    @Id @Column(name = "id") var id: UUID,
    @Column(name = "code", length = 12) var code: String,
    @Enumerated(EnumType.STRING) @Column(name = "side", length = 8) var side: Side,
    @Column(name = "qty") var qty: Int,
    @Column(name = "price", precision = 20, scale = 4) var price: BigDecimal?,
    @Enumerated(EnumType.STRING) @Column(name = "type", length = 16) var type: OrderType,
    @Enumerated(EnumType.STRING) @Column(name = "status", length = 16) var status: OrderStatus,
    @Column(name = "created_at") var createdAt: Instant,
)

fun Order.toJpa(): OrderJpa = OrderJpa(id, code, side, qty, price, type, status, createdAt)

fun OrderJpa.toDomain(): Order = Order(id, code, side, qty, price, type, status, createdAt)
