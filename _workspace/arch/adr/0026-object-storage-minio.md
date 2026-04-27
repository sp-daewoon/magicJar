# ADR 0026: 객체 저장소 도입 — MinIO (S3 호환)

- 상태: Accepted
- 작성일: 2026-04-27
- 결정자: architect (협의: backend-core, qa, frontend)
- 관계: ADR 0011(QA 스크린샷 게이트, 개정), ADR 0023(pyworker), ADR 0007(매매일지)

## 맥락

다음 비정형/대용량 산출물이 늘어나는 중인데, 모두 PostgreSQL이나 git에 두는 건 부적절하다.

- **차트 스크린샷** — 매매일지 첨부, QA 게이트 (ADR 0011), 클라이언트(LWC takeScreenshot) + 서버(pyworker Playwright) 양 경로
- **백테스트 산출물** — 자세한 trade ledger CSV/parquet, equity curve PNG
- **로그 첨부** — 사고 분석 시 일부 시간대 로그 아카이브
- **AI 입력/출력 캐시** — 큰 프롬프트·응답을 DB에 두면 row 비대

## 검토한 대안

| 대안 | 장점 | 단점 |
|------|------|------|
| A. PostgreSQL bytea | DB 1개로 끝 | row 비대, 스트리밍 불편, 백업 폭증 |
| B. git-lfs | 아카이브성 | 운영 자산을 git에 두는 건 부적절, 용량 한계 |
| C. AWS S3 직접 | 표준 | 로컬 개발 시 외부 의존, 비용 |
| D. **MinIO 로컬 + S3 호환 SDK** (본 결정) | S3 SDK 그대로, 로컬 무료, 운영 시 AWS S3로 endpoint만 교체 | 컨테이너 +1 |

## 결정

**MinIO를 로컬·MVP 객체 저장소로 채택. SDK는 S3 호환 (`software.amazon.awssdk:s3` 또는 `minio-java`). 운영 진입 시 endpoint만 교체해 AWS S3로 이전.**

### 컨테이너 (compose, ADR 0024)

- 이미지: `minio/minio:latest`
- 포트: 9000(API), 9001(Console)
- 볼륨: `miniodata`
- 기본 자격증명은 compose `.env`에서 주입

### 버킷 정책

| 버킷 | 용도 | 보존 |
|------|------|------|
| `chart-snapshots` | 매매일지·QA 차트 캡처 (PNG) | 무제한 (사용자 자산) |
| `backtest-artifacts` | 백테스트 결과 (CSV·parquet·PNG) | 90일 (옵션 수동 연장) |
| `log-archive` | 사고 분석용 로그 묶음 | 30일 |
| `ai-cache` | 큰 프롬프트·응답 캐시 (선택) | 7일 |

### 키 명명 규약

```
chart-snapshots/{yyyy}/{MM}/{dd}/{journal_entry_id}/{nn}-{source}.png
                                    └─ source: client_lwc | server_browser
backtest-artifacts/{run_id}/{filename}
log-archive/{yyyy-MM-dd}/{service}/{filename}
```

### 접근 패턴

- **api-app**: 메타데이터 DB 기록 + presigned URL 발급 → 프런트에 전달
- **pyworker**: `/screenshot` 처리 후 `chart-snapshots/...`에 직접 PUT, key를 Kafka `screenshot.captured.v1`에 발행 → consumer가 DB 메타 기록
- **frontend**: presigned URL로 직접 GET (api 우회) — api 부하 감소
- **batch-app**: 백테스트 산출물을 `backtest-artifacts/{run_id}/`에 PUT

### SDK 선택

- JVM: `software.amazon.awssdk:s3` (AWS 공식, MinIO 호환). `minio-java`도 가능하나 표준성 우선.
- Python: `boto3` 또는 `minio` Python SDK. pyworker에선 `boto3` (S3 표준 흐름과 일치).

### 환경 변수 (12-factor)

```
S3_ENDPOINT=http://minio:9000     # 로컬
S3_REGION=us-east-1               # MinIO 무시 가능
S3_ACCESS_KEY=...
S3_SECRET_KEY=...
S3_FORCE_PATH_STYLE=true          # MinIO 필수, AWS는 false
```

운영 진입 시: `S3_ENDPOINT`를 AWS 리전 endpoint로, `S3_FORCE_PATH_STYLE=false`로 전환. 코드 변경 0.

### 보안

- 버킷은 기본 비공개. 외부 노출은 항상 presigned URL (TTL ≤ 15분).
- pyworker는 별도 IAM 사용자 — `chart-snapshots` PUT만 허용 (다른 버킷 접근 거부).
- 자격증명은 `.env` (Husky pre-commit이 시크릿 패턴 차단, ADR 0010).

## 결과

- 긍정: 비정형 자산을 DB·git에서 분리. S3 호환이라 운영 마이그레이션 단순.
- 부정: 컨테이너·자격증명 +1, 로컬 디스크 사용 +.
- 후속:
  - `docker/docker-compose.infra.yml`에 minio 서비스 추가
  - JVM 측 `S3Client` 빈 + presigned URL 헬퍼 (api-app, batch-app)
  - pyworker `screenshot/storage.py` (boto3) — ADR 0023과 연동
  - 운영 진입 시 라이프사이클 정책(자동 만료)을 AWS S3 native로

## Links

- spec: `docs/superpowers/specs/2026-04-27-msa-restructure-design.md` §9, §11-4
- 관련 ADR: 0011(개정), 0019, 0023, 0024, 0007
