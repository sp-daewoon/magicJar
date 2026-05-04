package com.mj.domain.user

import java.time.OffsetDateTime

enum class TradingMode { MOCK_KIS, REAL_KIS }

data class User(
    val userId: String,
    val username: String,
    val displayName: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class UserTradingConfig(
    val userId: String,
    val tradingMode: TradingMode,
    val accountNo: String,
    val accountProductCode: String,
    val kisAppKeyEncrypted: String,
    val kisAppSecretEncrypted: String,
    val htsId: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)
