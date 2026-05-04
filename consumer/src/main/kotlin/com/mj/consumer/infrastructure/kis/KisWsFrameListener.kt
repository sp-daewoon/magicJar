package com.mj.consumer.infrastructure.kis

/**
 * KIS WebSocket 프레임 리스너 (SAM 인터페이스).
 *
 * KisWsRouter가 trId × trKey 단위로 dispatch할 때 호출된다.
 *
 * @param trId  KIS TR ID (e.g. "H0UNCNT0", "H0STCNI0")
 * @param trKey 종목코드(시세) 또는 HTS ID(체결통보)
 * @param columns `^` 분리된 원시 컬럼 리스트
 */
fun interface KisWsFrameListener {
    fun onFrame(
        trId: String,
        trKey: String,
        columns: List<String>,
    )
}
