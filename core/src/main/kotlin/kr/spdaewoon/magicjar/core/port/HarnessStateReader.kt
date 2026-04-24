package kr.spdaewoon.magicjar.core.port

import kr.spdaewoon.magicjar.core.domain.HarnessState

/**
 * 하네스 대시보드용 상태 리더 (ADR 0008).
 * 구현체는 web 모듈이 제공하며, `_workspace/_harness-state.json` 등 저장소 파일을 읽어 조립한다.
 */
interface HarnessStateReader {
    fun read(): HarnessState
}
