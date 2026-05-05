# ADR 0010: Multi-tenant 구조 (트랙1 완료 흡수)

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M) — 트랙1 완료(2026-05-02) 사실 흡수
- master §위치: `docs/architecture/master-architecture.md` v2 §1·§D.18·§6-2
- 트랙: T1 (DONE) + 모든 후속 트랙 정합 의무
- 선행: ADR 0036 (Git author + orchestrator-meta), ADR 0001

---

## 1. 맥락

1번 트랙 KIS 주문 어댑터(2026-05-02 완료)에서 멀티테넌트 확장 path를 처음부터 도입했다. user_id 컬럼이 모든 user-scoped 테이블에 박혔고, KisCredentialProvider 추상으로 인증 도입 시 침습 0이 보장된다.

v2에서는 사용자 비전(MCP 서버 + 외부 AI 클라이언트 호출 + 미래 다중 사용자)에 다라 본 구조를 정식 ADR로 박는다.

## 2. 결정

### 2-1. user_id 컬럼 의무 테이블

| 테이블 | user_id | 사유 |
|---|---|---|
| `users` | PK | tenant root |
| `user_trading_config` | FK NOT NULL | 사용자별 한도·trading_mode (MOCK_KIS/REAL_KIS) |
| `orders` | FK NOT NULL | 주문 |
| `order_events` | FK NOT NULL | audit |
| `order_reject_audit` | FK NOT NULL | 거부 audit |
| `daily_order_counter` | (user_id, date) | 한도 카운터 |
| `kill_switch_state` | (user_id, scope) | 사용자별 + global |
| `position` | FK NOT NULL | 포지션 |
| `pnl_daily` | FK NOT NULL | 손익 |
| `risk_event` | FK NOT NULL | risk 이벤트 |
| `trading_journal` | FK NOT NULL | 매매일지 |
| `chart_snapshot` | FK NOT NULL | 차트 스냅샷 |
| `strategy` | FK NOT NULL | 사용자 전략 |
| `screener_query`·`screener_result` | FK NOT NULL | 사용자 스크리너 |
| `signal` | FK NOT NULL | 사용자 시그널 |
| `ai_comment_log` (W4) | FK NOT NULL | AI 호출 audit |
| `mcp_audit` (W4) | FK NOT NULL | MCP 호출 audit |

### 2-2. user-scoped 아닌 테이블 (전역 universe)

| 테이블 | 사유 |
|---|---|
| `instrument` · `instrument_alias` | 종목 마스터 — 모든 사용자 공유 |
| `corp_action` | 종목 이벤트 — 모든 사용자 공유 |
| `candle` | 시세 데이터 — 모든 사용자 공유 |
| `market_calendar` | KRX 캘린더 — 모든 사용자 공유 |
| `news` · `news_sentiment` | 뉴스 — 모든 사용자 공유 |

### 2-3. 인증 추상화 (post-MVP 진입 path)

```kotlin
interface KisCredentialProvider {
    fun getCredential(userId: Long): KisCredential
}

// 1차 — 단일 사용자 .env 기반
class EnvKisCredentialProvider : KisCredentialProvider {
    override fun getCredential(userId: Long): KisCredential =
        KisCredential(
            appKey = env("KIS_APP_KEY"),
            appSecret = env("KIS_APP_SECRET"),
            accountCano = env("KIS_ACCOUNT_CANO"),
        )
}

// post-MVP — DB 기반 (master key 암호화)
class DbKisCredentialProvider(
    private val repo: UserKisCredentialRepository,
    private val cipher: SecretCipher,
) : KisCredentialProvider {
    override fun getCredential(userId: Long): KisCredential {
        val row = repo.findByUserId(userId) ?: error("No KIS credential for user $userId")
        return KisCredential(
            appKey = cipher.decrypt(row.encryptedAppKey),
            appSecret = cipher.decrypt(row.encryptedAppSecret),
            accountCano = cipher.decrypt(row.encryptedCano),
        )
    }
}
```

### 2-4. 인증 도입 시점

| 단계 | 인증 |
|---|---|
| MVP (현재) | 단일 사용자 (`sp-daewoon`) — user_id=1 hardcoded |
| post-MVP 1차 | OAuth2 (Google·GitHub) — JWT 토큰 |
| post-MVP 2차 | API key (외부 AI 클라이언트 — MCP server) |
| 실서비스 | 다중 사용자 + 권한 관리 (admin·trader·viewer) |

각 단계는 별도 ADR로 정식 발행.

## 3. 결과·영향

- 모든 트랙(T2~T17) 신설 테이블에 user_id 의무 — 누락 시 review reject.
- 인증 도입 시점 확장 비침습 — Provider port 교체만.
- MCP server (W4)는 client 식별을 user_id로 매핑 의무 — API key → user_id resolver.

## 4. 검토 대안

- **(a) Single-tenant 가정** (1차안 기본) — post-MVP 진입 시 schema 변경 침습 큼. 거부.
- **(b) 처음부터 OAuth2 도입** — MVP 단계 과도한 복잡도.
- **(c) user_id 컬럼 의무 + 인증 추상화 (채택)** — 트랙1 정합 + 비침습 확장.

## 5. References

- master-architecture.md §1, §D.18, §6-2
- ADR 0036 (Git author per agent — bot 사용자도 같은 schema)
- 트랙1 spec/plan `docs/superpowers/{specs,plans}/2026-05-02-track1-kis-order-*.md`
- `_workspace/operations/order-policy.md` (트랙1 운영 가이드)
