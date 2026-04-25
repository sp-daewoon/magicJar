package kr.spdaewoon.magicjar.config

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * 모든 어댑터 모듈의 JPA 엔티티/리포지토리를 한 번에 등록한다.
 * 모듈 추가 시 이 목록에 패키지 한 줄 추가.
 */
@Configuration
@EntityScan(
    basePackages = [
        "kr.spdaewoon.magicjar.marketdata.persistence",
        "kr.spdaewoon.magicjar.strategy.journal",
    ],
)
@EnableJpaRepositories(
    basePackages = [
        "kr.spdaewoon.magicjar.marketdata.persistence",
        "kr.spdaewoon.magicjar.strategy.journal",
    ],
)
class JpaScanConfig
