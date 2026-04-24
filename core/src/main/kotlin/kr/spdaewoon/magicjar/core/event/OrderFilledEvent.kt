package kr.spdaewoon.magicjar.core.event

import kr.spdaewoon.magicjar.core.domain.Mode
import kr.spdaewoon.magicjar.core.domain.OrderResult

data class OrderFilledEvent(
    val result: OrderResult,
    val mode: Mode,
)
