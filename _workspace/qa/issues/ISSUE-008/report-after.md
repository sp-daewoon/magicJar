# ISSUE-008 — Hotfix Report (after)

- 일시: 2026-05-02
- 작업자: architect (메인 세션)
- 브랜치: `agent/architect/issue-008-presigned-host`

## 채택 해결안

(a) **MinIO client 2개 분리**.

- internal client: `endpoint=minio:9000` — `put_object` / `bucket_exists` / `make_bucket` 전용 (실 네트워크 호출, 컨테이너 사설망 빠름)
- public client: `endpoint=localhost:9000` — `presigned_get_object` 전용 (네트워크 호출 0, 서명 문자열만 생성)

이전 구현(`_rewrite_host`로 발급된 URL의 호스트 사후 치환)은 AWS4-HMAC-SHA256 서명에 호스트가 포함되므로 사용자 GET 시 `localhost:9000`으로 재서명 검증 → 불일치(`SignatureDoesNotMatch`).
public 클라이언트가 처음부터 `localhost:9000` endpoint로 서명을 만들면 호스트 일치 → OK.

## 변경 파일

| 파일 | 변경 |
|------|------|
| `pyworker/app/screenshot/storage.py` | `MinioStorage`에 `public_endpoint` 인자 추가, `_internal`/`_public` 두 minio-py 클라이언트 보유. `_rewrite_host` 함수 + `urllib.parse` import 제거. |
| `pyworker/app/main.py` | `MinioStorage(...)` 호출 사이트 `public_host=` → `public_endpoint=` |
| `pyworker/tests/test_screenshot_storage.py` | TDD — `_FakeMinio` fake로 두 클라이언트 분리 검증 (2 신규 테스트) |
| `_workspace/arch/adr/0011-qa-screenshot-gate.md` | 변경 이력에 2026-05-02 hotfix 기록 |

## 검증

- pyworker pytest: **25 PASS, 0 fail, 회귀 0**
  - 신규: `test_minio_storage_uses_public_endpoint_for_presign`, `test_minio_storage_ensure_bucket_uses_internal_client`
  - 기존 InMemoryStorage 테스트 2건 영향 없음

## 채택하지 않은 안

- (b) `MINIO_DOMAIN` — MinIO 서버 옵션 변경 필요, 운영 부담 증가, 단일 endpoint 환경에선 효과 동일하지만 multi-tenant 시 부작용
- (c) `extra_hosts` + endpoint 통일 — 컨테이너 자체에 9000 포트 listen 필요(아키텍처 변경), 호스트-컨테이너 양방향 도달 가정 깨지면 실패

(a)는 minio-py 클라이언트 인스턴스 1개 추가만으로 끝나고, 환경변수·docker compose 변경 0.

## 동작 검증 (실 docker 미실행)

신규 테스트가 두 클라이언트의 endpoint 분리와 presign 호출 라우팅을 검증한다.
실 docker compose에서의 사용자 브라우저 GET 정상 응답은 사용자 검증 todo로 남긴다 (`docker compose up -d pyworker minio` 후 새 ISSUE 생성·`scripts/qa-run.mjs scenario` 실행 → presigned URL 클릭).
