package com.mj.consumer.application.order

import com.mj.consumer.infrastructure.persistence.guard.KillSwitchJpaAdapter
import com.mj.domain.user.TradingMode
import com.mj.domain.user.port.KisCredentialProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * 부팅 시 active KIS mode를 강제 로그. spec §3 D6.
 *
 * - mock: `[KIS_MODE] active=MOCK (no external calls)`
 * - dev:  `[KIS_MODE] active=DEV (KIS 모의계좌 ${account})`
 * - prd:  `[KIS_MODE] active=PRD (trading_mode=...) account=... ⚠ LIVE_TRADING|MOCK_TRADING killSwitch=ON|OFF`
 *
 * 사용자가 매 시작마다 active mode를 컨테이너 로그에서 즉시 확인 가능.
 *
 * mock profile에선 KisCredentialProvider·KillSwitchJpaAdapter Bean이 없을 수 있어 ObjectProvider로 안전 inject.
 */
@Component
class KisModeBootLogger(
    private val env: Environment,
    private val credentialsProvider: ObjectProvider<KisCredentialProvider>,
    private val killSwitchProvider: ObjectProvider<KillSwitchJpaAdapter>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun logActiveMode() {
        val active = env.activeProfiles.firstOrNull() ?: "mock"
        when (active) {
            "mock" -> log.info("[KIS_MODE] active=MOCK (no external calls)")
            "dev" -> {
                val cred =
                    runCatching {
                        credentialsProvider.getIfAvailable()?.forUser(KisOrderSender.DEFAULT_USER_ID)
                    }.getOrNull()
                if (cred != null) {
                    log.info("[KIS_MODE] active=DEV (KIS 모의계좌 {})", cred.accountNo)
                } else {
                    log.warn("[KIS_MODE] active=DEV (KisCredentialProvider 미등록 또는 seed 부재)")
                }
            }
            "prd" -> {
                val cred =
                    runCatching {
                        credentialsProvider.getIfAvailable()?.forUser(KisOrderSender.DEFAULT_USER_ID)
                    }.getOrNull()
                val ks = killSwitchProvider.getIfAvailable()?.isActive("GLOBAL") ?: true
                val live = cred?.tradingMode == TradingMode.REAL_KIS
                log.warn(
                    "[KIS_MODE] active=PRD (trading_mode={}) account={} {} killSwitch={}",
                    cred?.tradingMode ?: "UNKNOWN",
                    cred?.accountNo ?: "?",
                    if (live) "⚠ LIVE_TRADING" else "MOCK_TRADING",
                    if (ks) "ON" else "OFF",
                )
            }
            else -> log.info("[KIS_MODE] active={} (unknown profile)", active)
        }
    }
}
