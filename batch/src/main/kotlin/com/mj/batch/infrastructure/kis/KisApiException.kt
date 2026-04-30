package com.mj.batch.infrastructure.kis

/**
 * KIS REST API 응답에서 rt_cd != "0" 이거나 HTTP 오류가 발생한 경우 던지는 예외.
 *
 * @param message 사람이 읽을 수 있는 설명
 * @param msgCd   KIS 응답 본문의 msg_cd 값 (예: "EGW00123"). HTTP 오류 시 null.
 * @param rtCd    KIS 응답 본문의 rt_cd 값 (예: "1"). HTTP 오류 시 null.
 */
class KisApiException(
    message: String,
    val msgCd: String? = null,
    val rtCd: String? = null,
) : RuntimeException(message)
