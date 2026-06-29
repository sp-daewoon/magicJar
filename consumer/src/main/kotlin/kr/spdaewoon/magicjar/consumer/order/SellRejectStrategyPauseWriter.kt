package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * SELL 거부 누적 자동정지의 **쓰기 트랜잭션 경계** — 2026-06-29 국순당 043650 -9% 오버나잇 사고 D3 fix.
 *
 * ## 왜 별도 bean 인가 (read-only/무트랜잭션 함정 fix)
 * [SellRejectAutoPauseTracker] 는 [OrderRouterKafkaListener] (Kafka 리스너 스레드, `@Transactional` 없음)에서
 * 동기 호출된다. 거기서 [ConsumerStrategyJpaRepository.autoDisable] (`@Modifying` UPDATE)를 직접 부르면
 * Hibernate 가 **활성 쓰기 트랜잭션이 없다**며 `Executing an update/delete query` 예외를 던진다(라이브 evidence —
 * `autoDisable 실패: strategyId=104 err=Executing an update/delete query`). 그 결과 자동정지가 작동하지 않아
 * 거부 무한 재시도 루프가 멈추지 않았다.
 *
 * tracker 안에서 `@Transactional` 메서드를 만들어도 **자기호출(self-invocation)** 은 Spring AOP 프록시를 타지
 * 않아 트랜잭션이 안 열린다. 따라서 쓰기 책임을 **별도 bean** 으로 분리해 프록시 경계를 확보한다
 * ([kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyAutoDisableCallback] 와 동일 패턴 — DRY).
 *
 * AI 호출 0 — 룰 기반 자본 흐름 운영 가드 (ADR 0023 §2-4)
 */
@Component
class SellRejectStrategyPauseWriter(
    private val strategyRepository: ConsumerStrategyJpaRepository,
) {
    private val log = LoggerFactory.getLogger(SellRejectStrategyPauseWriter::class.java)

    /**
     * 전략을 DISABLED 로 자동정지(쓰기 트랜잭션). 반환 = 갱신 행 수(0=대상 없음/이미 DELETED).
     *
     * `@Transactional`(쓰기) 로 [ConsumerStrategyJpaRepository.autoDisable] (`@Modifying`)을 감싼다.
     * 별도 bean 메서드라 caller([SellRejectAutoPauseTracker]) 의 호출이 프록시를 거쳐 트랜잭션이 열린다.
     */
    @Transactional
    fun autoDisable(
        strategyId: Long,
        reason: String,
    ): Int = strategyRepository.autoDisable(strategyId, reason)
}
