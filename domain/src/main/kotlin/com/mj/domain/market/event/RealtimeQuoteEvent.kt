package com.mj.domain.market.event

import com.mj.domain.common.Quote

/**
 * market-data 내부 흐름용 ApplicationEvent. 다른 모듈은 직접 import 금지(스펙 정책).
 * 다른 모듈은 ApplicationContext 의 이벤트 발행만 신뢰하고, Spring `@EventListener<RealtimeQuoteEvent>`
 * 형태로 받되 import 한정자는 일치해야 한다 — 정책상 web/strategy 는 별도의 listener-side 어댑터를 둔다.
 */
data class RealtimeQuoteEvent(
    val quote: Quote,
    val source: String,
)
