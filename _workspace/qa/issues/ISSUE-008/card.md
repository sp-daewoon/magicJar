---
id: ISSUE-008
status: DEFERRED
reportedAt: 2026-05-01T13:41:58.460Z
reportedBy: user
symptom: dogfooding — pyworker /screenshot/scenario 인프라 동작 검증 + presigned URL SignatureDoesNotMatch (후속 hotfix)
target: /symbol/005930
relatedAdr: [0011, 0022, 0026, 0030]
relatedPr: null
fixedAt: null
---

## 증상
- 인프라 동작 OK: ScenarioRunner → RealPlaywrightRunner → MinIO 업로드 → presigned URL 발급 흐름 모두 동작
- 그러나 사용자가 presigned URL 클릭 시 **MinIO `SignatureDoesNotMatch`** 에러

## 인프라 검증 결과 (2026-05-01)
- pyworker `POST /screenshot/scenario`: 200, 단계 3개 캡처
- MinIO 업로드: 객체 3건 저장 OK (`magicjar-screenshots/issues/ISSUE-008/before/2026-05-01T13-42-22Z/`)
- presigned URL 발급: 24h TTL, 호스트 `localhost:9000`로 치환 OK
- 사용자 브라우저 접근: ❌ SignatureDoesNotMatch

## 원인 추정
pyworker의 `_rewrite_host`가 presigned URL 발급 후 호스트만 갈아끼웠으나, AWS4 서명에 호스트(원래 `minio:9000`)가 포함되어 있어 **사용자 측 GET 시 `localhost:9000`으로 다시 서명 검증 → 불일치**.

## 해결 방향 (다음 사이클 hotfix)
- (a) MinIO client를 두 개 운영: 업로드용(`minio:9000`) + presign용(`localhost:9000`)
- (b) S3 SigV4 헤더 호스트 차이 무시 — MinIO 서버 옵션(`MINIO_DOMAIN`) 설정
- (c) 단순 — `s3_endpoint`를 `localhost:9000`로 통일하고 docker network 내부에서도 `extra_hosts`로 매핑

## 단계별 캡처
- `before/2026-05-01T13-42-22Z/01-after-load.png`
- `before/2026-05-01T13-42-22Z/02-chart-rendered.png`
- `before/2026-05-01T13-42-22Z/03-no-d-button.png`

(MinIO admin 콘솔이나 hotfix 후 재발급 시 확인 가능)

## 시나리오 코드 (인용)
`scenario-before.py` 참조.

## 상태
**DEFERRED** — 인프라(scenario endpoint·CLI·디렉토리 양식·MinIO 업로드)는 모두 정상 동작 검증됨. presigned URL 호스트 치환 이슈만 다음 사이클로 미룸. 본 PR은 인프라 골격을 main에 들이는 데 충분.
