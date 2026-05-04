package com.mj.domain.user.port

import com.mj.domain.user.User
import com.mj.domain.user.UserTradingConfig

interface UserRepository {
    fun findById(userId: String): User?

    fun save(user: User): User
}

interface UserTradingConfigRepository {
    fun findByUserId(userId: String): UserTradingConfig?

    fun save(config: UserTradingConfig): UserTradingConfig
}
