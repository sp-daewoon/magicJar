---
description: 현재 세션 시점까지의 작업을 개발일지 HTML로 정리해서 _workspace/devlog/YYYY-MM-DD[-N].html에 저장하고 index.html을 갱신
---

# /devlog — 개발일지 HTML 작성

호출 시점까지의 세션 작업을 정리해서 **`_workspace/devlog/YYYY-MM-DD[-N].html`** 파일로 작성하고 **`_workspace/devlog/index.html`** 을 갱신하라.

## 동작 원칙

1. **호출 시점 기준** — `TZ='Asia/Seoul' date +%Y-%m-%d` 한국 시간 기준 날짜 결정
2. **호출할 때마다 신규 파일 생성 — 덮어쓰기 금지**
   - 같은 날 첫 호출: `YYYY-MM-DD.html`
   - 같은 날 두 번째 호출부터: `YYYY-MM-DD-2.html`, `YYYY-MM-DD-3.html`, … (시퀀스 +1)
   - 시퀀스 산출: 해당 날짜로 시작하는 기존 파일 중 가장 큰 번호 + 1 (없으면 base 그대로, base만 있으면 `-2`부터 시작)
   - 기존 파일은 절대 수정하지 않는다 — 매 호출은 그 시점 스냅샷
3. **여러 번 호출 가능** — 세션 중간에 호출해도 OK. 호출 시점까지 작업 누적 정리. 직전 회차와 중복돼도 그대로 남겨 이력 추적
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

- **위치**: `_workspace/devlog/YYYY-MM-DD[-N].html` (KST 기준, 신규 파일)
- **시퀀스 산출 예시**:
  ```bash
  DATE=$(TZ='Asia/Seoul' date +%Y-%m-%d)
  DIR=_workspace/devlog
  if [ ! -e "$DIR/$DATE.html" ]; then
    OUT="$DIR/$DATE.html"
  else
    N=$(ls "$DIR" | sed -n "s/^${DATE}-\\([0-9]\\+\\)\\.html$/\\1/p" | sort -n | tail -1)
    NEXT=$(( ${N:-1} + 1 ))
    OUT="$DIR/${DATE}-${NEXT}.html"
  fi
  ```
- **디자인**:
  - inline CSS (외부 의존 0)
  - SF Pro / Pretendard 폰트
  - 표 위주
  - badge 색상: `b-ok` 초록 / `b-warn` 노랑 / `b-pending` 파랑 / `b-todo` 분홍 / `b-mute` 회색
  - `.grid` auto-fit minmax(280px, 1fr)
- **참조 표준**: `_workspace/devlog/2026-05-03.html`
- **회차 표시**: HTML `<h1>` 또는 헤더 영역에 회차 번호와 작성 시각(`HH:mm KST`) 표기. 같은 날 N회차임을 한눈에 확인 가능하도록.

## index.html 갱신

`_workspace/devlog/index.html`의 `<ul>` 안에 새 `<li>` 추가 (최신 우선, 같은 날 회차도 별도 항목으로).
**같은 날 항목이라도 갱신이 아니라 신규 `<li>` 추가** — 회차마다 고유 링크 보존.

```html
<li>
  <a href="YYYY-MM-DD[-N].html">YYYY-MM-DD (요일) #N</a>
  <span class="summary">{한 줄 요약} <span class="badge">{상태 badge}</span></span>
</li>
```

- 첫 회차는 `#1` 생략 가능 (`YYYY-MM-DD (요일)`).
- 두 번째 회차부터는 `#2`, `#3` 식으로 명시.

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
## 개발일지 작성 완료 (#N회차)
- 파일: _workspace/devlog/YYYY-MM-DD[-N].html
- 머지 PR: NN건
- 신규 Flyway: V19 등 NN개
- sub-agent 호출: NN건
- 다음 세션 일정: ...

브라우저: open _workspace/devlog/YYYY-MM-DD[-N].html
```

## 주의

- 토큰 효율 위해 inline CSS 짧게 (변수 활용)
- 표 hover·transition 같은 micro-interaction은 선택
- 커멘드 호출 시점의 git status·worktree 상태 짧게 확인 후 작성
- 메모리 `feedback_devlog_html.md` 정책 참조
