package com.mj.domain.user.port

import com.mj.domain.user.User
import com.mj.domain.user.UserTradingConfig
import java.util.UUID

interface UserRepository {
    fun findById(userId: UUID): User?

    fun save(user: User): User
}

interface UserTradingConfigRepository {
    fun findByUserId(userId: UUID): UserTradingConfig?

    fun save(config: UserTradingConfig): UserTradingConfig
}
