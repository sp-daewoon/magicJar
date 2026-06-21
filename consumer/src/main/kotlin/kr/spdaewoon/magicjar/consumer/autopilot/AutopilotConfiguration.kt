package kr.spdaewoon.magicjar.consumer.autopilot

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * magicjar-autopilot 안전 하니스 config 활성 (spec 2026-06-22 §2-0-A · Phase 0 Task 3).
 *
 * [MagicjarAutopilotProperties](`magicjar.autopilot.*` — DRY 단일 출처)를 바인딩한다. 본 config 가 등록돼도
 * `enabled=false`(default)면 Guard·kill·손실누적 컴포넌트는 게이트에서 무조건 NotApplicable/no-op 이므로
 * 기존 매매 경로에 영향 0(zero-regression). autopilot 패키지 격리 — 폐기 시 본 패키지째 제거(spec §3-bis Tier A).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MagicjarAutopilotProperties::class)
class AutopilotConfiguration
