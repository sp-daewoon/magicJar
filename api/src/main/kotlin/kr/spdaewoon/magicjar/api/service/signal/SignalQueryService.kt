package kr.spdaewoon.magicjar.api.service.signal

import kr.spdaewoon.magicjar.api.persistence.signal.SignalJpaAdapter
import kr.spdaewoon.magicjar.domain.signal.Signal
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 시그널 조회 서비스 (ADR 0066 §5-3 + §7-2 단계 5).
 *
 * SignalController에서 사용. SignalJpaAdapter 직접 의존.
 * 포트를 통한 간접 접근은 page 조회가 domain port 범위를 벗어나므로 adapter 직접 사용.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Service
class SignalQueryService(
    private val signalJpaAdapter: SignalJpaAdapter,
) {
    private val log = LoggerFactory.getLogger(SignalQueryService::class.java)

    /**
     * 사용자 시계열 시그널 페이징 조회.
     *
     * @param userId 사용자 ID
     * @param from 시작 시각 (UTC Instant)
     * @param to 종료 시각 (UTC Instant)
     * @param page 페이지 번호 (0-based)
     * @param size 페이지 크기
     * @return 시그널 페이지 (ts DESC)
     */
    @Transactional(readOnly = true)
    fun list(
        userId: String,
        from: Instant,
        to: Instant,
        page: Int,
        size: Int,
    ): Page<Signal> {
        log.debug("시그널 목록 조회: userId={} from={} to={} page={} size={}", userId, from, to, page, size)
        return signalJpaAdapter.findByUserAndRange(userId, from, to, page, size)
    }

    /**
     * 시그널 단건 조회.
     *
     * @param userId 소유자 검증용
     * @param id 시그널 ID
     * @return 시그널 (userId 불일치 시 404)
     */
    @Transactional(readOnly = true)
    fun get(
        userId: String,
        id: Long,
    ): Signal {
        val signal =
            signalJpaAdapter.findById(id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "시그널 없음: id=$id")
        if (signal.userId != userId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "시그널 없음: id=$id")
        }
        return signal
    }
}
