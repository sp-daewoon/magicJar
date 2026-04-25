package kr.spdaewoon.magicjar.marketdata

import kr.spdaewoon.magicjar.marketdata.dart.DartProperties
import kr.spdaewoon.magicjar.marketdata.kis.KisProperties
import kr.spdaewoon.magicjar.marketdata.news.NewsProperties
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * market-data 모듈 자동 구성. app 모듈에서 컴포넌트 스캔 범위에 포함되면 자동 활성화.
 *
 * - JPA: market-data 패키지 안 엔티티/리포지토리만 명시적으로 등록(다른 모듈 충돌 방지).
 * - 설정 바인딩: KIS / DART / NEWS.
 * - @EnableScheduling: 뉴스 수집 스케줄러 동작.
 */
@Configuration
@EnableConfigurationProperties(KisProperties::class, DartProperties::class, NewsProperties::class)
@EntityScan(basePackages = ["kr.spdaewoon.magicjar.marketdata.persistence"])
@EnableJpaRepositories(basePackages = ["kr.spdaewoon.magicjar.marketdata.persistence"])
@EnableScheduling
class MarketDataConfig
