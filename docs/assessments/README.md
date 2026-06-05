# magicJar 평가(Assessment) 인덱스

> **권위**: ADR 0108 — 주기 평가 문서의 단일 위치·네이밍·템플릿·인덱스 규약.
> 신규 평가 발행 시 본 표에 row **prepend** 의무. 같은 slug 반복 발행 = 시계열 (직전 평가 대비 스코어 diff를 본문 §9에 기록).

## 네이밍

`YYYY-MM-DD-<assessment-slug>.html` — 템플릿 9 섹션은 ADR 0108 본문 참조.

## slug 레지스트리

| slug | 평가 대상 |
|------|-----------|
| `autotrading-readiness` | 자동매매 준비도 (종합 / MOCK 구현도 / REAL 안전 운영) |

## 발행 이력

| 날짜 | slug | 종합 판정 | 핵심 스코어 | 파일 |
|------|------|-----------|-------------|------|
| 2026-06-05 | autotrading-readiness | MOCK 안정 운영 가능 · REAL canary 게이트 대기 | 종합 87(+16) · MOCK 95(+13) · REAL 78(+14) | [2026-06-05-autotrading-readiness.html](2026-06-05-autotrading-readiness.html) |
| 2026-06-04 | autotrading-readiness | 조건부 정상 | 종합 71 · MOCK 82 · REAL 64 | [2026-06-04-autotrading-readiness.html](2026-06-04-autotrading-readiness.html) |
