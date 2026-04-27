package com.mj.domain.common.event

import com.mj.domain.common.Mode
import com.mj.domain.common.OrderResult

data class OrderFilledEvent(
    val result: OrderResult,
    val mode: Mode,
)
