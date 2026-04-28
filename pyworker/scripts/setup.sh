#!/usr/bin/env bash
# pyworker 호스트(IntelliJ) 실행 환경 셋업 — 1회 실행, 재현 가능.
#   $ cd pyworker && bash scripts/setup.sh
#
# 산출물:
#   - pyworker/.venv/        (uv가 uv.lock 기반으로 생성한 가상환경)
#   - playwright chromium    (.cache/ms-playwright/, ~150MB)
#
# IntelliJ 측 후속 작업:
#   1. Settings → Project → Python interpreter → "Add Local Interpreter" → Existing
#      → /Users/.../magic_ch1/pyworker/.venv/bin/python
#   2. .run/Pyworker.run.xml 의 SDK_HOME($PROJECT_DIR$/pyworker/.venv/bin/python) 자동 매칭

set -euo pipefail

cd "$(dirname "$0")/.."  # pyworker/ 진입

# 1) uv 설치 확인
if ! command -v uv >/dev/null 2>&1; then
  cat <<'EOF' >&2
[setup] uv가 설치되어 있지 않습니다. 다음 중 하나로 설치 후 다시 실행하세요:

  brew install uv
  # 또는
  curl -LsSf https://astral.sh/uv/install.sh | sh

EOF
  exit 1
fi

echo "[setup] uv $(uv --version | awk '{print $2}') 확인 OK"

# 2) uv.lock 기반 가상환경 + 의존성 동기화
echo "[setup] uv sync (.venv 생성 + 의존성 설치, 약 1~2분)"
uv sync

# 3) Playwright 브라우저 (Chromium만; ~150MB)
echo "[setup] playwright chromium 설치 (없으면 다운로드, 있으면 skip)"
uv run playwright install chromium

cat <<EOF

[setup] ✅ 완료.

다음으로:
  1. IntelliJ 재시작 또는 .idea 새로고침
  2. Project Structure → SDK → Python interpreter:
     $(pwd)/.venv/bin/python
  3. 인프라 띄우기: 우상단 Run 드롭다운 → "Infra-Up" ▶
  4. pyworker 띄우기: Run 드롭다운 → "Pyworker" ▶
     ⇒ http://localhost:8000/docs (Swagger)
EOF
