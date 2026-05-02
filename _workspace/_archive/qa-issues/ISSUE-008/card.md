---
id: ISSUE-008
status: FIXED
reportedAt: 2026-05-01T13:41:58.460Z
reportedBy: user
symptom: dogfooding — pyworker /screenshot/scenario 인프라 동작 검증 + presigned URL SignatureDoesNotMatch (hotfix 완료)
target: /symbol/005930
relatedAdr: [0011, 0022, 0026, 0030]
relatedPr: null
fixedAt: 2026-05-02T00:00:00Z
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
**FIXED (2026-05-02)** — 해결안 (a) 채택: MinIO 클라이언트를 internal(`minio:9000`, 업로드)·public(`localhost:9000`, presign) 둘로 분리. `_rewrite_host` 후처리 치환 폐기 → 서명 자체가 외부 endpoint 기준으로 만들어져 사용자 GET 시 호스트 일치. pyworker pytest 25 PASS, 회귀 0. ADR 0011 변경 이력 갱신. 환경변수 변화 0.

## 해결 후 검증 (after)
`report-after.md` 참조.
