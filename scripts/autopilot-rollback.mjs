#!/usr/bin/env node
// autopilot-rollback.mjs — magicjar-autopilot "v3.14.0 복귀" 안내 (spec 2026-06-22 §3 · Phase 0 Task 4)
//
// ⚠ 본 스크립트는 **안내만** 한다. 파괴적 git 명령(reset --hard)·DB 정리·돈 청산을 **직접 실행하지 않는다**.
//    실돈 자율매매 롤백은 코드·DB·돈 3면이 얽혀 있어, 사용자 확인을 경유하는 단계별 안내가 안전하다.
//
// 사용: node scripts/autopilot-rollback.mjs
//
// 롤백 3면(spec §3 + §3-bis 2-tier):
//   1. 코드  — git reset --hard v3.14.0 + scripts/build-and-deploy.sh (Tier A 폐기, Tier B 보존은 DISCARD-MANIFEST 참조)
//   2. 돈    — owner=AUTOPILOT 포지션 시장가 청산은 **POST /api/autopilot/rollback** API 경유(KIS 실주문 — 본 스크립트 X)
//   3. DB    — owner=AUTOPILOT 전략 PAUSE→삭제 + autopilot_state/kill_journal 정리(스냅샷 복원)

import { execSync } from 'node:child_process';

function safe(cmd, fallback = 'unknown') {
  try {
    return execSync(cmd, { encoding: 'utf8' }).trim();
  } catch {
    return fallback;
  }
}

const currentSha = safe('git rev-parse HEAD');
const currentDescribe = safe('git describe --tags --always 2>/dev/null');
const anchorExists = safe('git rev-parse --verify v3.14.0 2>/dev/null', '');

const line = '─'.repeat(78);
console.log(line);
console.log('  magicjar-autopilot 롤백 안내 — "v3.14.0 복귀" (실행은 사용자 확인 경유)');
console.log(line);
console.log(`  현재 HEAD     : ${currentDescribe} (${currentSha.slice(0, 12)})`);
console.log(`  롤백 앵커     : v3.14.0 ${anchorExists ? '(tag 존재 ✓)' : '(⚠ tag 미발견 — 확인 필요)'}`);
console.log('');
console.log('  ⚠ 본 스크립트는 안내만 합니다. 아래 명령을 직접 실행하지 않습니다.');
console.log('');
console.log('  [1] 돈(포지션) 먼저 — owner=AUTOPILOT 시장가 청산 (KIS 실주문):');
console.log('      curl -X POST http://localhost:8080/api/autopilot/rollback \\');
console.log('           -H "Content-Type: application/json" \\');
console.log('           -d \'{"reason":"v3.14.0 롤백 — owner=AUTOPILOT 전량 청산"}\'');
console.log('      → consumer 가 owner=AUTOPILOT 보유만 청산 + 전략 PAUSE + kill 해제(격리 LOCK — 사용자 전략·후성 비대상).');
console.log('      ⚠ REAL 체결로 이미 지출된 자본은 되돌릴 수 없습니다(하드 플로어가 최대 손실을 bound).');
console.log('');
console.log('  [2] 스냅샷 확인 — 되감기 기준점:');
console.log('      node scripts/autopilot-snapshot.mjs --stdout   # 현재 상태 재확인');
console.log('      ls _workspace/autopilot/snapshots/             # 루프 시작 전 스냅샷');
console.log('');
console.log('  [3] 코드 되감기 (Tier A 폐기 / Tier B 보존):');
console.log('      # ⚠ 2-tier 선택적 롤백(spec §3-bis): 검증된 개선(Tier B)은 살린다.');
console.log('      cat _workspace/autopilot/DISCARD-MANIFEST.md   # 폐기/보존 권위 매니페스트 먼저 확인');
console.log('      # 전량 리셋이 맞다면(사용자 확인 후):');
console.log('      git reset --hard v3.14.0');
console.log('      scripts/build-and-deploy.sh                    # 재배포');
console.log('');
console.log('  [4] DB 정리 (autopilot 전용 데이터 — 사용자 전략·후성 보존):');
console.log('      # owner=AUTOPILOT 전략 데이터만 정리(격리):');
console.log('      docker exec magicjar-postgres psql -U magicjar -d magicjar \\');
console.log('        -c "DELETE FROM strategy WHERE owner = \'AUTOPILOT\';"');
console.log('      # autopilot 테이블 폐기는 V91/V92 ROLLBACK 노트 참조:');
console.log('      #   DROP TABLE IF EXISTS autopilot_kill_journal;');
console.log('      #   DROP TABLE IF EXISTS autopilot_state;');
console.log('');
console.log(line);
console.log('  순서 권장: [1] 돈 청산 → [2] 스냅샷 확인 → [3] 코드 → [4] DB. 각 단계 사용자 확인.');
console.log(line);
