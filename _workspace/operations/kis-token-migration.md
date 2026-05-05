# KIS 토큰 단일 store 마이그레이션 가이드 (R3-5c-3)

## 개요

R3-5c-3 사이클(PR1~PR5) — KIS REST 토큰 발급을 11경로(REST 9 + WS 2)에서 단일 store + Redis 분산 single-flight로 통합. 사용자 외부 액션은 .env 변수명 정정 + docker rebuild 1회.

## 사용자 액션 단계별

### 1. .env 변수명 정정 (선택 — 신규 path 사용 시)

기존 `KIS_*` 변수는 그대로 작동(하위 호환 fallback 유지). 신규 권장 path로 이전하려면 아래 매핑 표 참조:

| Before (deprecated) | After (권장) | 의미 |
|---|---|---|
| `KIS_APP_KEY` | `MAGICJAR_KIS_APP_KEY` | 시스템 키 prod (데이터 조회) |
| `KIS_APP_SECRET` | `MAGICJAR_KIS_APP_SECRET` | 시스템 키 prod |
| `KIS_MOCK_APP_KEY` | `MAGICJAR_KIS_VPS_APP_KEY` | 모의 키 vps (주문) |
| `KIS_MOCK_APP_SECRET` | `MAGICJAR_KIS_VPS_APP_SECRET` | 모의 키 vps |
| `KIS_BASE_URL` | `MAGICJAR_KIS_BASE_URL_PROD` | 실전 base URL |
| `KIS_WS_URL` | `MAGICJAR_KIS_WS_URL` | WS 연결 URL |
| (신규) | `MAGICJAR_KIS_BASE_URL_VPS` | 모의 base URL (default 자동) |

**중요:** 기존 `KIS_*` 변수가 .env에 있으면 하위 호환 fallback으로 그대로 작동한다. 신규 path는 권장이지만 강제 아님.

`KIS_ACCOUNT_CANO`·`KIS_ACCOUNT_PRDT`·`KIS_HTS_ID`는 그대로 유지 (1번 트랙 산출물 — 변경 없음).

### 2. Redis 분산 single-flight 활성화 (선택)

현 단계에서 `MAGICJAR_KIS_REDIS_TOKEN_ENABLED=false` (default). InMemory 어댑터로 그대로 작동. multi-JVM race 위험은 그대로 노출되지만 회귀 0.

활성화 시:
```bash
# .env에 추가
MAGICJAR_KIS_REDIS_TOKEN_ENABLED=true
```

활성화 후:
- Redis HASH(`magicjar:kis:token:system:prod`) + Redisson RLock(`magicjar:kis:token:lock:system:prod`) + RTopic broadcast로 multi-JVM single-flight 보장
- KIS `/oauth2/tokenP` 호출 빈도 11회/JVM → 1회/JVM (11경로 합산 1회 + 23h 마진)
- Redis 다운 시 in-memory fallback (multi-JVM race 위험 명시 + ERROR 로그 + 카운터)

### 3. docker rebuild

```bash
# 1. 정지
docker compose -f docker/docker-compose.yml down

# 2. 신규 이미지 빌드 + 기동
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml up -d --build

# 3. 헬스 검증 (api·batch·consumer 모두 healthy 도달 ~30~60s)
docker compose -f docker/docker-compose.yml ps
```

### 4. 검증

#### 4-1. KIS 토큰 발급 동작 확인

```bash
# api 컨테이너 로그
docker logs magicjar-api 2>&1 | grep '\[kis-auth\]' | tail -10

# Redis 활성화 시:
# [kis-auth] issued scope=system:prod source=fresh appKey=PSdkR4fO…
# 또는 [kis-auth] issued scope=system:prod source=redis-hit  ← 다른 JVM이 그사이 발급 + 본 JVM L1 hit
```

#### 4-2. HealthIndicator 노출 확인 (Redis 활성화 시)

```bash
curl http://localhost:8080/actuator/health/kisAuth
# 정상: {"status":"UP","details":{"redisUnavailableLast5m":0, ...}}
```

#### 4-3. 사용자 차트 동작 확인

```bash
curl 'http://localhost:8080/api/instruments/005930/candles?interval=DAY&limit=10'
# 200 + 봉 데이터 응답
```

#### 4-4. Redis 다운 시뮬레이션 (선택 — staging만)

```bash
docker stop magicjar-redis
# 사용자 차트 호출 → ERROR 로그 + DEGRADED + in-memory path
docker start magicjar-redis
# 자동 회복
```

## 롤백

### Redis 활성화 후 회귀 발생 시

```bash
# .env에서 비활성화
sed -i '' 's/MAGICJAR_KIS_REDIS_TOKEN_ENABLED=true/MAGICJAR_KIS_REDIS_TOKEN_ENABLED=false/' .env
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml up -d --force-recreate api-app batch-app consumer-app
```

InMemory path로 즉시 복귀. 회귀 0.

### 변수명 정정 후 회귀 발생 시

기존 `KIS_*` 변수가 .env에 그대로 있으면 fallback로 자동 작동. 별도 롤백 불요.

## 알려진 제약

- **Redis 다운 fallback** — multi-JVM race 위험 노출 (D7 trade-off). 운영 알람 후속 사이클.
- **사용자 키 path 통합** — 본 사이클은 시스템 키만 Redis 분산 보호 적용. 사용자 키(KisHttpSession)는 InMemory ConcurrentHashMap 그대로 (multi-user 진입 시 후속 사이클).
- **Redis Sentinel/Cluster 미지원** — 단일 Redis 인스턴스. 운영 트랙 별도.

## 참조

- spec: `docs/superpowers/specs/2026-05-05-track-r3-5c-3-kis-token-unification-design.md`
- plan: `docs/superpowers/plans/2026-05-05-track-r3-5c-3-kis-token-unification.md`
- runbook: `_workspace/operations/redis-outage-runbook.md`
- ADR: 0050 R3-5c-3
- 외부 API 정책: `docs/architecture/external-api-policy.md`
