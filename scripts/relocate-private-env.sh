#!/usr/bin/env bash
# relocate-private-env.sh — KIS 실계좌 키 평문 파일 레포 밖 이동 (2026-07-03 감사 §5-⑭)
#
# 왜 필요?
#   docs/operations/http-client.private.env.json 에 KIS real/vps appkey·appsecret 실값이
#   평문으로 존재한다. git 미추적(.gitignore 정상)이지만, 레포 zip/폴더 공유/AI 세션 노출 시
#   실계좌 키가 동반 유출된다. 레포 밖 ~/.config/magicjar/ 로 이동 + chmod 600.
#
# 사용:
#   scripts/relocate-private-env.sh            # 이동 실행
#   scripts/relocate-private-env.sh --dry-run  # 무엇을 할지 출력만
#
# 이동 후 사용자 후속 작업 (스크립트가 말미에 다시 안내):
#   1. IntelliJ HTTP Client 환경 파일 경로를 새 위치로 갱신
#      (Run/Debug 또는 kis.http 실행 시 "Environment file" 지정 — 舊 경로 참조 제거)
#   2. ★ 예방적 KIS 키 재발급 강력 권장 — 평문 파일이 레포 폴더에 머문 기간 동안의
#      노출 가능성은 소급 확인 불가. KIS 개발자센터에서 appkey/appsecret 재발급 후
#      SettingsModal(또는 .env)에 재입력.

set -Eeuo pipefail
cd "$(dirname "$0")/.."

SRC="docs/operations/http-client.private.env.json"
DEST_DIR="${MAGICJAR_PRIVATE_ENV_DIR:-$HOME/.config/magicjar}"
DEST="$DEST_DIR/http-client.private.env.json"
DRY_RUN=0

[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

log() { echo "[relocate-private-env] $*"; }

if [[ ! -f "$SRC" ]]; then
  log "원본 없음: $SRC — 이미 이동했거나 생성 전. 종료(정상)."
  [[ -f "$DEST" ]] && log "참고: 새 위치에 이미 존재 → $DEST"
  exit 0
fi

# git 추적 여부 안전 확인 (추적 중이면 이동만으로는 이력이 남음 — 즉시 중단·수동 조치)
if git ls-files --error-unmatch "$SRC" >/dev/null 2>&1; then
  log "★ 경고: $SRC 가 git 추적 중입니다 — 단순 이동으로 부족합니다." >&2
  log "  git 이력 세탁(filter-repo) + 키 즉시 재발급이 필요합니다. 중단." >&2
  exit 1
fi

if [[ -f "$DEST" ]]; then
  log "대상 위치에 파일이 이미 존재: $DEST" >&2
  log "  수동으로 내용 비교 후 정리하세요 (덮어쓰기 안 함 — 키 유실 방지). 중단." >&2
  exit 1
fi

if [[ $DRY_RUN -eq 1 ]]; then
  log "(dry-run) mkdir -p $DEST_DIR && chmod 700 $DEST_DIR"
  log "(dry-run) mv $SRC → $DEST && chmod 600 $DEST"
  exit 0
fi

mkdir -p "$DEST_DIR"
chmod 700 "$DEST_DIR"
mv "$SRC" "$DEST"
chmod 600 "$DEST"

log "이동 완료: $SRC → $DEST (chmod 600)"
echo
log "★ 후속 작업 (사용자 1회):"
log "  1. IntelliJ HTTP Client 환경 파일 경로 갱신 — kis.http 실행 시 private env 를"
log "     새 경로($DEST)로 지정 (舊 docs/operations/ 경로 참조 제거)."
log "  2. ★ KIS appkey/appsecret 예방적 재발급 강력 권장 — 평문 파일이 레포 폴더에"
log "     존재했던 기간의 노출 가능성은 소급 확인 불가 (감사 §5-⑭)."
log "  3. 재발급 후 SettingsModal(또는 .env)에서 키 갱신 + Redis 토큰 DEL (R-2 §3-1)."
exit 0
