package kr.spdaewoon.magicjar.domain.user.port

import kr.spdaewoon.magicjar.domain.user.AccountType
import kr.spdaewoon.magicjar.domain.user.UserKisCredential

/**
 * KIS 자격증명 영속 포트 (ADR 0046 §2-2).
 */
interface UserKisCredentialRepository {
    fun findByUserIdAndType(
        userId: String,
        accountType: AccountType,
    ): UserKisCredential?

    fun upsert(credential: UserKisCredential): UserKisCredential

    fun deleteByUserId(userId: String)
}
