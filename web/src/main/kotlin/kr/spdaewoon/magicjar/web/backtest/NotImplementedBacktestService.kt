package kr.spdaewoon.magicjar.web.backtest

import kr.spdaewoon.magicjar.core.domain.BacktestRequest
import kr.spdaewoon.magicjar.core.domain.BacktestResult
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * strategy 모듈의 실제 백테스트 서비스가 머지되기 전까지 사용하는 기본 구현.
 * 호출 시 HTTP 501 + 안내 메시지를 던진다.
 */
@Service
@ConditionalOnMissingBean(BacktestService::class)
class NotImplementedBacktestService : BacktestService {
    override fun run(request: BacktestRequest): BacktestResult =
        throw ResponseStatusException(
            HttpStatus.NOT_IMPLEMENTED,
            "백테스트 엔진(strategy 모듈)이 아직 조립되지 않았습니다. Phase B-2 후속 머지 필요.",
        )
}
