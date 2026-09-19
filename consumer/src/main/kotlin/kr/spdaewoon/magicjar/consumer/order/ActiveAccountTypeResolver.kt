package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.ActiveTradingModePort
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 활성 계좌 타입 해소기 (감사 2026-09-19 §8 GAP-2 교정).
 *
 * 체결/잔고/포지션/reconcile 경로가 accountType을 정할 때 쓰던 `RuntimeModeParser.accountTypeOrMock(env)`는
 * env `MAGICJAR_MODE`만 봤다. PR #1214로 활성 주문 모드가 DB(system_config.active_trading_mode)로
 * 이관된 뒤, env=REAL 상한 + DB활성=MOCK으로 MOCK 주문을 낸 경우 이 경로들이 여전히 REAL 계좌를 조회해
 * MOCK 체결이 backfill/sync되지 않는 불일치가 생긴다.
 *
 * 본 해소기는 TradingModeGate와 **동일한 min(env 상한, DB 활성모드)** 논리로 활성 계좌 타입을 제공한다:
 *  - env `MAGICJAR_MODE` != REAL (상한 MOCK) → **무조건 MOCK** (상한이 절대, 자본 사고 방지)
 *  - env 상한 REAL + DB 활성모드 = REAL → REAL
 *  - env 상한 REAL + DB 활성모드 = MOCK → MOCK (재기동 없는 전환 존중)
 *  - 포트 미합류/조회 실패 → 어댑터가 MOCK fail-closed 반환
 *
 * 주문 게이트(TradingModeGate)와 달리 consent/ALLOW_REAL은 보지 않는다 — 이미 발생한 체결의 조회 대상
 * 계좌를 정하는 용도이므로. 단 env 상한은 동일하게 존중해 REAL 계좌를 상한 밖에서 조회하지 않는다.
 *
 * AI 호출 0 — T-O 트랙 정책.
 */
@Component
class ActiveAccountTypeResolver(
    private val activeTradingModePortProvider: ObjectProvider<ActiveTradingModePort>,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
) {
    /**
     * 현재 활성 계좌 타입.
     * env 상한이 REAL이 아니면 무조건 MOCK. REAL 상한이면 DB 활성모드를 따른다(REAL이어야만 REAL).
     */
    fun activeAccountType(): AccountType {
        // env 상한 MOCK → 무조건 MOCK (DB 무관, 상한이 절대)
        if (!RuntimeModeParser.isRealMode(magicjarMode)) return AccountType.MOCK

        // env 상한 REAL — DB 활성모드가 최종 결정 (미합류/실패는 MOCK fail-closed)
        val port = activeTradingModePortProvider.getIfAvailable() ?: return AccountType.MOCK
        return if (port.activeMode() == TradingMode.REAL) AccountType.REAL else AccountType.MOCK
    }
}
