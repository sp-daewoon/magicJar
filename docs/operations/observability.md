# magicJar 관측 스택 가이드 (Prometheus + Grafana)

ADR 0094 P0-OPS-1·2 정합 · backend-core 단일 owner

## 1. 구성 매트릭스

| 레이어 | 컴포넌트 | 위치 |
|------|---------|------|
| 로그 | logback JSON encoder (LogstashEncoder) | `{api,batch,consumer}/src/main/resources/logback-spring.xml` |
| 메트릭 노출 | Spring Actuator + Micrometer Prometheus registry | `{api,batch,consumer}/src/main/resources/application.yml` |
| 메트릭 수집 | Prometheus 2.54 | `docker/observability/prometheus/prometheus.yml` |
| 메트릭 시각화 | Grafana 11.2 | `docker/observability/grafana/{provisioning,dashboards}/` |
| 통합 compose | docker-compose.observability.yml | `docker/docker-compose.observability.yml` |

## 2. 기동 방법

### 2.1 관측 스택만 (Prometheus + Grafana)
```bash
docker compose -f docker/docker-compose.observability.yml --env-file ./.env up -d
```

### 2.2 전체 통합 (인프라 3 + 앱 4 + 관측 2)
```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.observability.yml --env-file ./.env up -d
```

### 2.3 IDE bootRun + 관측 스택
```bash
# 인프라 + 관측만 컨테이너
docker compose -f docker/docker-compose.infra.yml -f docker/docker-compose.observability.yml --env-file ./.env up -d

# 호스트에서 3 모듈 직접 실행
./gradlew :api:bootRun
./gradlew :batch:bootRun
./gradlew :consumer:bootRun
```

Prometheus가 자동으로 `host.docker.internal:8080·8081·8082` scrape — IDE 디버깅 친화.

## 3. 접속 URL

| 서비스 | URL | 비고 |
|------|-----|------|
| Prometheus | http://localhost:9090 | scrape targets / metric explorer |
| Grafana | http://localhost:3001 | admin / admin (default — env `GRAFANA_ADMIN_PASSWORD`) |
| api `/actuator/prometheus` | http://localhost:8080/actuator/prometheus | raw metric scrape |
| batch `/actuator/prometheus` | http://localhost:8081/actuator/prometheus | raw metric scrape |
| consumer `/actuator/prometheus` | http://localhost:8082/actuator/prometheus | raw metric scrape |

포트 매트릭스 (frontend `:3000` 충돌 회피):
- `GRAFANA_HOST_PORT` (default `3001`) — `.env` override 가능
- `PROMETHEUS_HOST_PORT` (default `9090`)

## 4. 로그 매트릭스

### 4.1 profile 분기 (`logback-spring.xml`)
| 프로파일 | encoder | 사용처 |
|--------|--------|------|
| (default — no profile) | PatternLayout (사람 읽기) | 회귀 안전 |
| `dev` | PatternLayout | IDE 콘솔 친화 |
| `prd` | LogstashEncoder (한 줄 JSON) | 운영 검색 / Loki ingestion |

### 4.2 MDC 표준 5 키
- `traceId` — 분산 추적 (ADR 0084 P1-3 — TraceIdServletFilter + TraceIdKafkaRecordInterceptor)
- `spanId` — 단일 span
- `userId` — 인증 사용자
- `orderId` — 주문 dispatch
- `strategyId` — 전략 신호

JSON 출력 시 자동 부착, Plain 출력 시 `[%X{traceId:--}]` 패턴으로 표시.

### 4.3 JSON 로그 예시
```json
{"ts":"2026-05-21T13:24:01.234+09:00","msg":"order dispatched","logger":"kr.spdaewoon.magicjar.consumer.OrderDispatcher","thread":"kafka-consumer-1","level":"INFO","application":"magicjar-consumer","profiles":"prd","traceId":"a1b2c3","orderId":"O-12345"}
```

## 5. 메트릭 매트릭스 (Micrometer)

기본 Spring Boot 노출 + 본 작업 추가:
- `application` 라벨 (= spring.application.name)
- `environment` 라벨 (= SPRING_PROFILES_ACTIVE)

핵심 메트릭:
- `jvm_memory_used_bytes{area="heap"}` — heap 사용량
- `jvm_threads_live_threads` — live thread
- `process_cpu_usage` — process CPU
- `http_server_requests_seconds_count{uri,status}` — api 모듈 HTTP 요청 rate
- `kafka_consumer_*` — Kafka consumer lag·rate (consumer 모듈)

기본 dashboard `magicJar / magicJar — JVM Baseline` — 4 panel (JVM heap·HTTP rate·CPU·thread). Grafana 부팅 직후 자동 등록.

## 6. 검증 시나리오

```bash
# 1. Actuator prometheus 응답 확인 (3 모듈)
curl -s http://localhost:8080/actuator/prometheus | head -5
curl -s http://localhost:8081/actuator/prometheus | head -5
curl -s http://localhost:8082/actuator/prometheus | head -5

# 2. Prometheus targets UP 확인
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job: .labels.job, health: .health}'

# 3. Grafana 헬스
curl -s http://localhost:3001/api/health

# 4. JSON 로그 검증 (prd profile 기동 후)
docker logs magicjar-api 2>&1 | tail -5 | jq .
```

## 7. 운영 진입 후속 작업 (ADR 0094 P0-OPS-3·4·5·6)

- P0-OPS-3 — SLO/SLA 정의 + Slack/PagerDuty alertmanager routing
- P0-OPS-4 — DR/backup 자동화 (pg_dump cron + S3 업로드)
- P0-OPS-5 — runbook 5 시나리오 (Postgres down·Kafka lag·KIS rate limit·메모리 누수·디스크 가득)
- P0-OPS-6 — persistence-shared monitoring (Outbox drain lag · notification retry queue)
