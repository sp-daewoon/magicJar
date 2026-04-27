"""Kafka publishing layer.

pyworker는 PostgreSQL 직접 접근 금지 (ADR 0023). 결과물은 Kafka로 발행하거나
S3/MinIO에 저장 후 url을 Kafka 메시지에 포함한다.

Kotlin domain event 호환성:
- field name·type을 Kotlin data class와 정확히 매칭 (camelCase 그대로)
- UUID는 string, Instant는 ISO-8601 UTC
- Spring Kafka JsonDeserializer가 `__TypeId__` 헤더를 보고 타깃 클래스를 찾으므로
  publish 시 헤더에 Kotlin 클래스 FQN을 명시한다.
"""
