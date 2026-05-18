---
id: T-20260518-2333-hotfix-p0-4-kafka-consumer-factory
agent: backend-core
model: opus
branch: agent/backend-core/hotfix-p0-4-kafka-consumer-factory
status: RUNNING
startedAt: 2026-05-18T23:33:05.669+09:00
completedAt: null
pr: null
parentPhase: ?
---

# 목표
긴급 hotfix — PR #501 P0-4 KafkaErrorHandlerConfig ConsumerFactory DI 결함

## 결과물 체크리스트
- [x] root cause 분석 | fix 옵션 선택 (Spring Boot KafkaAutoConfiguration ObjectProvider pattern 모방) | KafkaErrorHandlerConfig 정정 | 단위 테스트 갱신 | gradle check GREEN | dogfooding consumer 부팅 evidence

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-18T23:33:05.677+09:00 — 작업 시작
2026-05-18T23:35:00.000+09:00 — root cause 분석 완료 — Spring Boot KafkaAutoConfiguration `@ConditionalOnMissingBean(ConsumerFactory.class)` 조건 false (WatchlistChangedKafkaConfig가 named ConsumerFactory bean 발행) → default 미등록 → autowire fail
2026-05-18T23:35:30.000+09:00 — fix 옵션 선택 — 옵션 A+C 하이브리드 (Spring Boot KafkaAnnotationDrivenConfiguration.kafkaListenerContainerFactory line 153~162 ObjectProvider pattern 모방)
2026-05-18T23:36:00.000+09:00 — KafkaErrorHandlerConfig 정정 — ObjectProvider<ConsumerFactory<Any, Any>> + ObjectProvider<SslBundles> + KafkaProperties fallback
2026-05-18T23:36:30.000+09:00 — 단위 테스트 갱신 — 신규 2 케이스 add-only (ConsumerFactory absent fallback path + present 위임 path)
2026-05-18T23:37:00.000+09:00 — gradle check GREEN — :consumer:check :api:check BUILD SUCCESSFUL + ktlintCheck GREEN
2026-05-18T23:39:49.981+09:00 — dogfooding consumer 부팅 evidence — `scripts/build-and-deploy.sh --no-gradle consumer-app` → `consumer-app Up 30 seconds (healthy)` + `Started ConsumerApplicationKt in 5.715 seconds` + ERROR·APPLICATION FAILED·NoSuchBeanDefinition 0건 + Kafka·JPA·WS subscribe 정상 진행 — v3.6.16 cut 차단 해소 확인

## 비고
