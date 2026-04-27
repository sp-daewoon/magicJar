# ADR 0024: Docker 표준 — 모듈별 Dockerfile + compose 통합 기동

- 상태: Accepted
- 작성일: 2026-04-27
- 결정자: architect (협의: backend-core)
- 관계: ADR 0019(MSA 재구성), ADR 0021(3 JVM 앱), ADR 0023(pyworker)

## 맥락

ADR 0019로 5 모듈 + pyworker + frontend 구조가 됐다. 운영·로컬 개발의 패키징 단위·기동 방식을 정해야 한다.

핵심 질문: **"MSA가 기본인가, compose가 기본인가?"**

자동매매를 실서비스로 운영할 때는 서비스별 독립 배포(k8s/ECS/Fly)가 자연스럽지만, MVP·로컬 개발·시연·통합 검증에선 compose가 압도적으로 단순하다.

## 결정

**MSA(서비스별 독립 컨테이너)가 기본 운영 모델이다. docker-compose는 로컬 개발·CI 통합·시연을 위한 도우미다.**

| 축 | MSA (운영) | compose (로컬·CI 통합) |
|----|-----------|---------------------|
| 빌드 단위 | 모듈별 독립 Dockerfile | 동일 Dockerfile 재사용 |
| 배포 | `ghcr.io/sp-daewoon/magicjar/<service>:<sha>` → k8s/ECS/Fly | `docker compose up` |
| 스케일 | 서비스별 독립 | single replica |
| 라이프사이클 | 서비스별 독립 PR·릴리스 | 통합 검증용 |
| 헬스체크 | `/actuator/health` (JVM) · `/health` (pyworker) · nginx default (frontend) | compose `healthcheck` |
| 로깅 | JSON stdout → 외부 수집 | 콘솔 |

### Dockerfile 원칙 (모든 모듈 공통)

- **멀티 스테이지 빌드** 필수
- **비루트 유저** (`USER 1000`)
- **distroless 또는 alpine 베이스** (이미지 경량)
- JVM: `eclipse-temurin:21-jre-alpine`
- Python: `python:3.12-slim` + Playwright chromium
- Frontend: `node:20-alpine` (build) → `nginx:alpine` (serve)
- 헬스체크: 컨테이너 레벨 `HEALTHCHECK` 또는 compose `healthcheck`

### docker-compose 토폴로지

```
docker/
├─ docker-compose.yml          # 전체 스택 (api·batch·consumer·pyworker·frontend + 인프라)
├─ docker-compose.dev.yml      # 핫 리로드·볼륨 마운트 오버레이
├─ docker-compose.infra.yml    # 인프라만 (kafka·postgres·redis·minio) — IDE에서 앱 실행 시
├─ kafka/
├─ postgres/
└─ minio/
```

### 서비스 매트릭스

| 서비스 | 이미지 | 포트 | 역할 |
|--------|--------|------|------|
| `kafka` | `bitnami/kafka:3.7` (KRaft) | 9092 | 메시지 브로커 (ADR 0020) |
| `postgres` | `postgres:16-alpine` | 5432 | 도메인 DB (ADR 0025) |
| `redis` | `redis:7-alpine` | 6379 | 세션·캐시·STOMP fan-out 옵션 |
| `minio` | `minio/minio:latest` | 9000, 9001 | 객체 저장소 (ADR 0026) |
| `api-app` | local | 8080 | REST + STOMP |
| `batch-app` | local | 8081 | Spring Batch |
| `consumer-app` | local | 8082 | Kafka 컨슈머 |
| `pyworker` | local | 8000 | FastAPI |
| `frontend` | local | 3000 | nginx |

### 모드별 명령

```bash
# 1) 인프라만 (IDE에서 앱 실행)
docker compose -f docker/docker-compose.infra.yml up

# 2) 전체 스택 (통합 검증·시연)
docker compose -f docker/docker-compose.yml up

# 3) 핫 리로드·볼륨 마운트
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml up
```

### 컨테이너 레지스트리·태깅

- **Registry**: `ghcr.io/sp-daewoon/magicjar/<service>` (api·batch·consumer·pyworker·frontend)
- **태그**: `:<short-sha>` 항상 + `:latest` (main 브랜치만)
- **빌드**: GitHub Actions matrix (5 서비스 병렬 빌드) — 별도 ADR(미래)에서 CI 정식화

### 네트워크·볼륨

- 단일 compose 네트워크 `magicjar` — 서비스명을 호스트로 사용 (`kafka:9092`, `postgres:5432`).
- 볼륨: `pgdata`, `kafkadata`, `miniodata` — 로컬 영속.

## 결과

- 긍정: 운영(독립 배포)과 로컬(통합 기동) 모두 자연스러움. 모듈별 Dockerfile이 라이프사이클 단위와 일치.
- 부정: 로컬 메모리 부담 (전체 스택 ~3~4GB). 개발자는 보통 `infra.yml`만 띄우고 IDE로 앱 실행 권장.
- 후속:
  - 각 모듈에 `Dockerfile` 추가 (backend-core·frontend·ai-engineer 분담)
  - `docker/` 디렉토리 신설 + 3개 compose 파일 작성
  - GitHub Actions matrix 빌드는 별도 ADR(CI 정식화) 후속

## Links

- spec: `docs/superpowers/specs/2026-04-27-msa-restructure-design.md` §3, §8, §10
- 관련 ADR: 0019, 0020, 0021, 0023, 0025, 0026
