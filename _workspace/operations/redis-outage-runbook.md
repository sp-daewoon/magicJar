# Redis 장애 운영 runbook (KIS 토큰 fallback)

## 개요

R3-5c-3 PR5 — KIS 토큰 단일 store + Redis 분산 single-flight 활성화 후, Redis 다운 시 어떻게 작동하고 운영자가 어떻게 대응해야 하는지 정리한다.

## 증상 진단

### 1. ERROR 로그 패턴

api·batch·consumer 컨테이너 로그에 아래 prefix가 등장한다:

```
ERROR [kis-auth][redis-down] fallback=in-memory race-risk-high scope={scope} op={read|write|invalidate|lock|lock-acquire|lock-init|lock-timeout} reason={ConnectionException|Timeout}
```

**탐지 명령:**
```bash
docker logs magicjar-api 2>&1 | grep '\[kis-auth\]\[redis-down\]' | tail -20
docker logs magicjar-batch 2>&1 | grep '\[kis-auth\]\[redis-down\]' | tail -20
docker logs magicjar-consumer 2>&1 | grep '\[kis-auth\]\[redis-down\]' | tail -20
```

### 2. HealthIndicator (api 모듈)

```bash
curl http://localhost:8080/actuator/health/kisAuth
```

DEGRADED 응답 시:
```json
{
  "status": "DEGRADED",
  "details": {
    "redisUnavailableLast5m": 12,
    "fallbackUsedLast5m": 247,
    "lastDownAt": "2026-05-05T16:42:11+09:00",
    "raceRisk": "high",
    "advice": "redis 컨테이너 즉시 복구 — 다중 JVM이 KIS API에 동시 발급 중 (EGW00133 한도 초과 위험)"
  }
}
```

### 3. Prometheus 카운터

```bash
curl http://localhost:8080/actuator/metrics/kis_token_redis_unavailable_total
curl http://localhost:8080/actuator/metrics/kis_token_fallback_used_total
```

5분 윈도우 증분 0 이상 → P0 알람 트리거 (별도 운영 트랙 — Slack/PagerDuty 통합은 후속 사이클).

## 즉시 조치 (P0)

### 1. Redis 컨테이너 상태 확인

```bash
docker compose -f docker/docker-compose.yml ps redis
docker logs magicjar-redis | tail -50
```

### 2. Redis 재시작

```bash
docker compose -f docker/docker-compose.yml restart redis
```

healthy 도달 확인 (10~30초):
```bash
docker compose -f docker/docker-compose.yml ps redis
# STATUS: Up X seconds (healthy)
```

### 3. 자동 회복 검증

Redis 복구 후 어댑터들은 자동으로 Redis path 재사용. 다음 호출에서 `[kis-auth][redis-down]` 로그가 더 이상 발행되지 않으면 회복 완료.

```bash
docker logs magicjar-api 2>&1 | grep '\[kis-auth\]' | tail -10
# fallback=in-memory 라인이 더 이상 나오지 않아야 함
```

## 사후 분석

### 1. 실 race 발생 여부 확인

Redis 다운 중 multi-JVM이 같은 KIS 키로 동시 발급한 경우 EGW00133 폭주 가능:

```bash
docker logs magicjar-api 2>&1 | grep -E 'EGW00133|kis_token_issuance_total.*in-memory' | tail -20
docker logs magicjar-batch 2>&1 | grep -E 'EGW00133|in-memory' | tail -20
```

### 2. 누적 in-memory 발급 카운트

```bash
curl -s http://localhost:8080/actuator/metrics/kis_token_issuance_total | jq '.measurements'
# tag source=in-memory 가 0보다 크면 race 위험 노출됨
```

## 비상 차단 (catastrophic)

EGW00133 폭주로 사용자 path 전체 차단 시:

```bash
# 1. Redis 어댑터 비활성화 (in-memory path 회복)
echo "MAGICJAR_KIS_REDIS_TOKEN_ENABLED=false" >> .env
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml up -d --force-recreate api-app batch-app consumer-app
```

복구 후 Redis 정상화하고 `MAGICJAR_KIS_REDIS_TOKEN_ENABLED=true` 재설정.

## 예방

- **Redis Sentinel/Cluster 도입** — 후속 운영 트랙 (master-slave failover).
- **모니터링 통합** — Prometheus rule + Slack/PagerDuty webhook (`increase(kis_token_redis_unavailable_total[5m]) > 0`).
- **부하 테스트** — 본격 multi-user 진입 전 RLock 동시성 시뮬레이션 (testcontainers IT 후속).

## 참조

- spec: `docs/superpowers/specs/2026-05-05-track-r3-5c-3-kis-token-unification-design.md` §10
- ADR: 0050 R3-5c-3
- 외부 API 정책: `docs/architecture/external-api-policy.md`
