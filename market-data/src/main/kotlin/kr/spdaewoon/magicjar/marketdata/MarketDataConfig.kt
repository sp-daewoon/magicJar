package kr.spdaewoon.magicjar.marketdata

import kr.spdaewoon.magicjar.marketdata.dart.DartProperties
import kr.spdaewoon.magicjar.marketdata.kis.KisProperties
import kr.spdaewoon.magicjar.marketdata.news.NewsProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * market-data 모듈 자동 구성. app 모듈에서 컴포넌트 스캔 범위에 포함되면 자동 활성화.
 *
 * JPA `@EntityScan`/`@EnableJpaRepositories`는 app 모듈의 통합 Config(`JpaScanConfig`)가
 * 모든 어댑터 모듈(market-data, strategy)을 한 번에 등록 — 단일 모듈로 좁히면 다른 모듈
 * 엔티티/리포지토리가 누락되어 빈 주입 실패.
 */
@Configuration
@EnableConfigurationProperties(KisProperties::class, DartProperties::class, NewsProperties::class)
@EnableScheduling
class MarketDataConfig
