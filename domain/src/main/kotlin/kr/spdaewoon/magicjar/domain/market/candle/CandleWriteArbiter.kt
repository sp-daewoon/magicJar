package kr.spdaewoon.magicjar.domain.market.candle

/**
 * 캔들 쓰기 중재자 (P0-A — 감사 2026-09-17 §1-② · 설계 `_workspace/specs/2026-09-18-candle-write-arbiter-design.md`).
 *
 * ## 왜 필요한가
 * 같은 (code, interval, ts) 봉에 쓰는 경로가 8개이고 UPSERT 규칙(DO NOTHING vs DO UPDATE)이 혼재해
 * 최종값이 실행 순서에 의존한다(canon §2). 낮은 신뢰 source(예: polling fallback)가 높은 신뢰 확정봉
 * (공식 마감가)을 덮는 오염이 발생할 수 있다. 본 중재자는 **기존 봉 대비 신규 write의 반영 여부를
 * 결정론적으로 판정**하는 순수 함수로, 모든 writer가 공유하는 단일 precedence 근거다.
 *
 * ## 순수성 (LOCK — KrxTickSize·EntryPricePolicy 1:1)
 * Spring·JPA·Clock·IO 의존 0. 입력(기존 source·신규 source) → 출력([WriteVerdict]) 결정론.
 * 실제 DB 반영은 어댑터 책임 — 본 중재자는 "덮어도 되는가"만 답한다.
 *
 * ## precedence 규칙 (canon §1)
 * - 신규 source priority < 기존 source priority (더 신뢰 높음) → [WriteVerdict.APPLY] (덮기 허용).
 * - 신규 == 기존 (동급) → [WriteVerdict.APPLY] (최신 write 우선 — WS live 재갱신·확정봉 재검증 허용).
 * - 신규 > 기존 (더 신뢰 낮음) → [WriteVerdict.REJECT] (오염 차단 — 낮은 신뢰가 높은 신뢰 못 덮음).
 * - 기존 봉 없음(신규 삽입) → [WriteVerdict.APPLY].
 */
object CandleWriteArbiter {
    /**
     * 신규 write가 기존 봉을 반영/덮어도 되는지 판정.
     *
     * @param existingSource 기존 DB 봉의 출처(없으면 null = 신규 삽입).
     * @param incomingSource 신규 write 요청의 출처.
     */
    fun decide(
        existingSource: CandleSource?,
        incomingSource: CandleSource,
    ): WriteVerdict {
        if (existingSource == null) return WriteVerdict.APPLY
        // priority 값이 작을수록 신뢰 높음. 신규가 기존보다 신뢰 높거나 같으면 반영.
        return if (incomingSource.priority <= existingSource.priority) {
            WriteVerdict.APPLY
        } else {
            WriteVerdict.REJECT
        }
    }

    /** 문자열 라벨 편의 오버로드(기존 DB 자유 문자열 하위호환 — [CandleSource.fromLabel] 경유). */
    fun decide(
        existingSourceLabel: String?,
        incomingSource: CandleSource,
    ): WriteVerdict =
        decide(
            existingSource = existingSourceLabel?.let { CandleSource.fromLabel(it) },
            incomingSource = incomingSource,
        )
}

/** 캔들 쓰기 판정 결과. */
enum class WriteVerdict {
    /** 반영 — 신규 write가 기존 봉을 덮거나 신규 삽입. */
    APPLY,

    /** 거부 — 낮은 신뢰 source가 높은 신뢰 확정봉을 덮으려 함(오염 차단). */
    REJECT,
}
