---
name: KIS 키 관리 정책 (개발 단계)
description: 개발 중에는 화면 입력(설정 모달) + DB 암호화 저장. 본격 운영은 후속 논의.
type: project
originSessionId: 64638064-5610-474e-8f54-b9d51c8468ab
---
사용자 명시(2026-05-03): "키를 화면에서 입력해서 쓰는 방식으로 하자. 그렇게 하면 개발할때 유용할거같고 나중에 개발 다 끝나고 키 관리는 다시 논의했으면 좋겠네"

**Why:** `.env` + 컨테이너 재기동 흐름이 sub-agent 병렬 작업·dev/mock profile 전환 시 충돌 야기. 화면(SettingsModal AuthSection) 입력 + DB 암호화 저장(`secrets` 테이블 AES-256-GCM)이 단일화·실시간 갱신 가능.

**How to apply:**
- 개발 단계: T18a `/api/auth/login` + T18b SettingsModal 흐름 활용. 사용자가 ⚙️ 클릭 → App Key·App Secret·계좌번호 입력 → DB 저장 → 즉시 반영
- `.env`의 KIS_MOCK_APP_KEY/SECRET 등은 fallback 또는 deprecated. 우선순위: DB 저장값 → .env (마이그레이션 path)
- IP 화이트리스트는 KIS 콘솔 직접 작업 (사용자 책임)
- **본격 운영 진입 시 재논의**: secret manager(Vault·AWS Secrets Manager 등), JWT/OAuth2 인증 게이트, 멀티유저 키 격리, 키 회전 정책. 현재 비범위 (post-MVP).
