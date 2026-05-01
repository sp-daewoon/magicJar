package com.mj.consumer.infrastructure.secret

import com.mj.domain.secret.SecretCipher
import com.mj.domain.user.port.KisCredential
import com.mj.domain.user.port.KisCredentialProvider
import com.mj.domain.user.port.UserTradingConfigRepository
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * `KisCredentialProvider` DB 기반 구현. spec §6.2, §8.3.
 *
 * 1차 — single user (DEFAULT_USER_ID 시드). 인증 도입 시(ISSUE-006 후속) SecurityContext의
 * 현재 사용자 id를 받아 같은 흐름으로 작동 — 본 클래스 변경 0.
 */
@Component
@Profile("dev | prd")
class DbKisCredentialProvider(
    private val configRepo: UserTradingConfigRepository,
    private val cipher: SecretCipher,
) : KisCredentialProvider {
    override fun forUser(userId: UUID): KisCredential {
        val cfg =
            configRepo.findByUserId(userId)
                ?: error("UserTradingConfig not found for userId=$userId")
        return KisCredential(
            tradingMode = cfg.tradingMode,
            accountNo = cfg.accountNo,
            accountProductCode = cfg.accountProductCode,
            appKey = cipher.decrypt(cfg.kisAppKeyEncrypted),
            appSecret = cipher.decrypt(cfg.kisAppSecretEncrypted),
            htsId = cfg.htsId,
        )
    }
}
