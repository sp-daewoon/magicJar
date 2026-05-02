# ADR 0008: 하네스 운영 페이지(/harness) 노출 정책

> **STATUS: SUPERSEDED by ADR 0022 (2026-05-03 v2 베이스라인)** — Spring 직접 노출 정적 페이지 → React SPA(`frontend/`) 흡수로 재구현됨. `/harness` 라우트는 `frontend/src/pages/Harness/`에서 React로 제공. 본 ADR은 참조용 보존.

- 상태: Superseded by 0022
- 작성일: 2026-04-25
- 결정자: architect (협의: backend-core, frontend)

## 맥락

사용자가 요구한 **에이전트 업무상황을 웹에서 직접 볼 수 있는 페이지**는 `.claude/agents/`, `_workspace/`, `CLAUDE.md` 같은 **프로젝트 내부 메타데이터**를 노출한다. 이 정보는 외부에 공개될 이유가 없고, 일부는 경로·의사결정 이력 등 실수로 공개되면 OSINT 가치가 생길 수 있다.

## 결정

**읽기 전용 + 로컬/내부 네트워크 한정 + 프로파일 기반 비활성화 스위치.**

### 라우팅
- 경로: `/harness`, `/harness/api/**`
- 렌더 주체: `web` 모듈의 `HarnessController`. 데이터는 `backend-core`의 `HarnessStateReader`가 `_workspace/`·`.claude/agents/`·`CLAUDE.md`를 런타임에 읽어 제공.

### 보안 3중 방어
1. **프로파일 가드:** `magicjar.harness.enabled`(기본 true), 프로파일 `prod`에서는 `false` 기본값. 설정값이 `false`면 Spring Security 레벨에서 `/harness/**` 자체가 404.
2. **IP 화이트리스트:** 기본 `127.0.0.1/32`, `::1`만 허용. `magicjar.harness.allowed-cidr`로 내부 대역 추가 가능.
3. **민감 정보 마스킹:** 에이전트 파일·`CLAUDE.md`를 노출할 때 다음 패턴은 `****`로 치환:
   - `APP_KEY`, `APP_SECRET`, `API_KEY`, `PASSWORD`, `TOKEN` 같은 키=값 형태
   - `Bearer` 토큰 문자열
   - 계좌번호 형태(`\d{8,}-\d{2}`)

### 쓰기 금지
- 어떠한 POST/PUT/DELETE도 `/harness/**`에 허용하지 않는다.
- 에이전트 실행 트리거 UI 금지 (오케스트레이터만이 에이전트 호출 진입점).

### 성능
- 상태 조회 시 파일 시스템 스캔 결과를 **60초 캐시** (Caffeine). 실시간이 필요한 Phase 전환은 오케스트레이터가 별도 이벤트로 캐시 무효화.

## 결과

- 긍정: 사용자가 코드를 안 열어도 에이전트/Phase/Workspace/이슈를 확인 가능
- 부정: 로컬 한정이라 원격 협업 시 터널/VPN 필요 → 후속 ADR에서 인증(토큰 기반)으로 확장 가능
- 후속: 
  - backend-core가 `magicjar-harness-dashboard` 스킬로 백엔드 구현
  - frontend가 같은 스킬로 `/harness` 페이지 구현
  - QA는 경계면 테스트에 민감정보 마스킹 단위 테스트 포함
