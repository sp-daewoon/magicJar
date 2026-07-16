package kr.spdaewoon.magicjar.consumer.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KisWsTransportProperties::class)
class KisWsTransportConfiguration
