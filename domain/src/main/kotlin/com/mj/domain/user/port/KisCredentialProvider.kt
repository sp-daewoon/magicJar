package com.mj.domain.user.port

import com.mj.domain.user.TradingMode
import java.util.UUID

/**
 * 사용자 KIS 자격증명 해석. spec §6.2, §8.3.
 *
 * 1차 구현(consumer.infrastructure.secret.DbKisCredentialProvider): user_trading_config DB row
 * + SecretCipher.decrypt. 인증 도입 시(ISSUE-006 후속 트랙) SecurityContext 기반 구현으로 교체.
 */
interface KisCredentialProvider {
    fun forUser(userId: UUID): KisCredential
}

/**
 * KIS 호출에 필요한 자격증명. 평문 키는 메모리 only — 절대 로그·응답에 노출되지 않는다.
 * `toString()` override로 마스킹 보장.
 */
data class KisCredential(
    val tradingMode: TradingMode,
    val accountNo: String,
    val accountProductCode: String,
    val appKey: String,
    val appSecret: String,
    val htsId: String,
) {
    override fun toString(): String =
        "KisCredential(mode=$tradingMode, account=$accountNo, prdt=$accountProductCode, " +
            "appKey=${appKey.take(4)}***, appSecret=***, htsId=$htsId)"
}
