package com.mj.domain.market.corp_action.port

import com.mj.domain.market.corp_action.CorpAction
import java.time.LocalDate

/**
 * Corp Action 영속 저장소 포트.
 *
 * - PostgreSQL corp_action 테이블 (V13)
 * - instrumentCode + type + exDate UNIQUE 조합으로 멱등 upsert
 */
interface CorpActionRepository {
    /** 단건 upsert (멱등). */
    fun upsert(corpAction: CorpAction)

    /** 다건 upsert (멱등). */
    fun upsertAll(corpActions: List<CorpAction>)

    /**
     * 특정 종목의 ex_date 범위 조회.
     * 순서: exDate ASC.
     */
    fun findByInstrumentCodeAndExDateBetween(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction>

    /**
     * ex_date <= targetDate인 모든 Corp Action 조회.
     * retroactive 보정 시 사용.
     * 순서: exDate ASC.
     */
    fun findByInstrumentCodeAndExDateLessThanEqual(
        instrumentCode: String,
        targetDate: LocalDate,
    ): List<CorpAction>
}
