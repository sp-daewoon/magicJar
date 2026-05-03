---
description: 현재 세션 시점까지의 작업을 개발일지 HTML로 정리해서 _workspace/devlog/YYYY-MM-DD.html에 저장하고 index.html을 갱신
---

# /devlog — 개발일지 HTML 작성

호출 시점까지의 세션 작업을 정리해서 **`_workspace/devlog/YYYY-MM-DD.html`** 파일로 작성하고 **`_workspace/devlog/index.html`** 을 갱신하라.

## 동작 원칙

1. **호출 시점 기준** — `date +%Y-%m-%d` 한국 시간 기준 파일명 결정
2. **기존 파일이 있으면 갱신** (덮어쓰기 + index.html 항목 갱신)
3. **여러 번 호출 가능** — 세션 중간에 호출해도 OK. 그 시점까지 작업 누적 정리
4. **사용자가 명시 트리거** — 자동 작성 X. `/devlog` 호출 시에만

## 7 표준 섹션 (모든 일지 공통)

1. **머지 PR 매트릭스** — `gh pr list --state merged --limit 30 --search "merged:>=$(date -v-1d +%Y-%m-%d)"` 등으로 오늘 머지 PR 추출. 트랙·에이전트·모델 표
2. **PostgreSQL 테이블 변경** — 오늘 추가된 Flyway 버전 (V19+) 또는 schema 변경 컬럼
3. **Kafka 토픽·데이터 흐름 변경** — 신규 토픽·consumer·producer 변경
4. **Redis 사용처 변경** — 신규 key pattern·TTL·LRU
5. **sub-agent 디스패치 내역** — 호출 횟수·이슈/복구 (worktree leak·토큰 hit·conflict 등)
6. **sanity/dogfooding 결과** — 화면·데이터·9 컨테이너 상태
7. **다음 세션 일정** — 우선순위·사이즈·예정 트랙

## 파일 작성

- **위치**: `_workspace/devlog/YYYY-MM-DD.html` (KST 기준)
- **디자인**:
  - inline CSS (외부 의존 0)
  - SF Pro / Pretendard 폰트
  - 표 위주
  - badge 색상: `b-ok` 초록 / `b-warn` 노랑 / `b-pending` 파랑 / `b-todo` 분홍 / `b-mute` 회색
  - `.grid` auto-fit minmax(280px, 1fr)
- **참조 표준**: `_workspace/devlog/2026-05-03.html`

## index.html 갱신

`_workspace/devlog/index.html`의 `<ul>` 안에 새 `<li>` 추가 (최신 우선).
이미 같은 날짜 항목 있으면 summary 갱신.

```html
<li>
  <a href="YYYY-MM-DD.html">YYYY-MM-DD (요일)</a>
  <span class="summary">{한 줄 요약} <span class="badge">{상태 badge}</span></span>
</li>
```

## 데이터 수집 명령 참고

```bash
# 오늘 KST 날짜
TZ='Asia/Seoul' date +%Y-%m-%d

# 오늘 머지 PR (KST 기준)
gh pr list --state merged --search "merged:>=$(TZ='Asia/Seoul' date +%Y-%m-%d)" --limit 50 --json number,title,mergedAt,author,headRefName

# 오늘 commit (현재 main 기준)
git log --since="$(TZ='Asia/Seoul' date +%Y-%m-%d)T00:00+09:00" --pretty=format:"%h %s (%an)" main

# Flyway 새 버전
ls api/src/main/resources/db/migration/ | tail -5

# 컨테이너 상태
docker compose -f docker/docker-compose.yml ps --format "table {{.Service}}\t{{.Status}}"

# 최근 sub-agent 호출 (메인 세션 기록 기반 — 호출자 메모리에 의존)
```

## 응답 양식

작성 완료 후 한국어로 짧게 보고:

```
## 개발일지 작성 완료
- 파일: _workspace/devlog/YYYY-MM-DD.html
- 머지 PR: NN건
- 신규 Flyway: V19 등 NN개
- sub-agent 호출: NN건
- 다음 세션 일정: ...

브라우저: open _workspace/devlog/YYYY-MM-DD.html
```

## 주의

- 토큰 효율 위해 inline CSS 짧게 (변수 활용)
- 표 hover·transition 같은 micro-interaction은 선택
- 커멘드 호출 시점의 git status·worktree 상태 짧게 확인 후 작성
- 메모리 `feedback_devlog_html.md` 정책 참조
