package kr.spdaewoon.magicjar.web.backtest

import kr.spdaewoon.magicjar.core.domain.BacktestRequest
import kr.spdaewoon.magicjar.core.domain.BacktestResult

/**
 * 백테스트 엔진 트리거용 web 로컬 인터페이스. 실제 구현은 strategy 모듈이 공급할 예정이며,
 * 그때까지는 `NotImplementedBacktestService` 가 501 의미로 예외를 던진다.
 *
 * 포트로 강등하지 않는 이유: 백테스트 엔진의 입력/출력 경계는 core에 이미 존재하지만
 * (`BacktestRequest`, `BacktestResult`), 그 실행 트리거 책임은 strategy 모듈 내부에 남긴다.
 */
interface BacktestService {
    fun run(request: BacktestRequest): BacktestResult
}
