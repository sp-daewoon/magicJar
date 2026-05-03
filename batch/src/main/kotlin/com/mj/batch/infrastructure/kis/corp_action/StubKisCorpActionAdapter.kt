package com.mj.batch.infrastructure.kis.corp_action

import com.mj.domain.market.corp_action.CorpAction
import com.mj.domain.market.corp_action.CorpActionSource
import com.mj.domain.market.corp_action.port.KisCorpActionPort
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

/**
 * stub-kis 프로파일용 KisCorpActionPort 구현체.
 *
 * CI 환경·mock profile에서 KIS API 없이 고정 fixture를 반환한다.
 *
 * Fixture 종목: 005930 (삼성전자)
 * - 2024-04-26: 50:1 액면분할
 * - 2023-12-28: 배당 (현금 1444원/주)
 */
@Component
@Profile("stub-kis | mock")
class StubKisCorpActionAdapter : KisCorpActionPort {
    private val log = LoggerFactory.getLogger(StubKisCorpActionAdapter::class.java)

    companion object {
        // 삼성전자 2024-04-26 50:1 액면분할 픽스처
        val SAMSUNG_SPLIT =
            CorpAction.Split(
                instrumentCode = "005930",
                exDate = LocalDate.of(2024, 4, 26),
                source = CorpActionSource.KIS,
                ratio = BigDecimal("50"),
            )

        // 삼성전자 2023-12-28 배당 픽스처
        val SAMSUNG_DIVIDEND =
            CorpAction.Dividend(
                instrumentCode = "005930",
                exDate = LocalDate.of(2023, 12, 28),
                source = CorpActionSource.KIS,
                cashAmount = BigDecimal("1444"),
            )

        // SK하이닉스 2023-03-31 무상증자 픽스처
        val HYNIX_BONUS_ISSUE =
            CorpAction.BonusIssue(
                instrumentCode = "000660",
                exDate = LocalDate.of(2023, 3, 31),
                source = CorpActionSource.KIS,
                ratio = BigDecimal("0.05"),
            )
    }

    override fun fetchSplits(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction.Split> {
        log.debug("[stub-kis] fetchSplits code={} from={} to={}", instrumentCode, from, to)
        return when (instrumentCode) {
            "005930" -> listOf(SAMSUNG_SPLIT).filter { it.exDate in from..to }
            else -> emptyList()
        }
    }

    override fun fetchBonusIssues(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction.BonusIssue> {
        log.debug("[stub-kis] fetchBonusIssues code={} from={} to={}", instrumentCode, from, to)
        return when (instrumentCode) {
            "000660" -> listOf(HYNIX_BONUS_ISSUE).filter { it.exDate in from..to }
            else -> emptyList()
        }
    }

    override fun fetchRightsIssues(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction.RightsIssue> {
        log.debug("[stub-kis] fetchRightsIssues code={} from={} to={}", instrumentCode, from, to)
        return emptyList()
    }

    override fun fetchDividends(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction.Dividend> {
        log.debug("[stub-kis] fetchDividends code={} from={} to={}", instrumentCode, from, to)
        return when (instrumentCode) {
            "005930" -> listOf(SAMSUNG_DIVIDEND).filter { it.exDate in from..to }
            else -> emptyList()
        }
    }

    override fun fetchMergersAndSpinoffs(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction> {
        log.debug("[stub-kis] fetchMergersAndSpinoffs code={} from={} to={}", instrumentCode, from, to)
        return emptyList()
    }
}
