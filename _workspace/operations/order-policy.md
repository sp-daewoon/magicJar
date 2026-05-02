# magicJar 주문 정책·운영 가이드

> 1번 트랙 산출물. spec §10 / plan T18.
>
> 가드 정책 정밀화·실전 진입 절차 등 운영 결정을 풀어 둔다. 본 문서는 사용자(운영자) 첫 번째 참조점.

## 1. KillSwitch ON/OFF 절차

KillSwitch는 모든 placeOrder를 즉시 차단하는 비상 정지. **default ON으로 부팅** — 사용자가 매 운영 시작 시 명시적으로 OFF로 토글.

### ON으로 전환 (비상 정지)
```sql
UPDATE kill_switch_state
   SET is_active = TRUE,
       reason = '<사유>',
       toggled_by = 'sp-daewoon',
       toggled_at = NOW()
 WHERE scope = 'GLOBAL';
```

### OFF로 전환 (정상 운영 시작)
```sql
UPDATE kill_switch_state
   SET is_active = FALSE,
       reason = 'morning start ' || NOW()::text,
       toggled_by = 'sp-daewoon',
       toggled_at = NOW()
 WHERE scope = 'GLOBAL';
```

### 응급상황 대응 (순서)
1. **KillSwitch ON** — 위 SQL 즉시 실행
2. **미체결 일괄 취소** — `consumer` 컨테이너 로그에서 `[KIS_SENDER] PLACED ...` 검색해 kis_order_no 수집 → `POST /api/orders` 별도 endpoint(후속 트랙)로 cancelOrder 또는 KIS HTS에서 직접 취소
3. **잔고 확인** — `GET /api/balance` (T16 후속에서 endpoint 추가) 또는 `inquire-balance` 직접 호출
4. **원인 분석** — `order_events` 테이블 + `consumer` 컨테이너 로그
5. 정상 복구 후 KillSwitch OFF

## 2. DailyOrderLimit 한도값 조정

### default 값 (매우 느슨 — 정상 매매 차단 0)
- `magicjar.guard.daily-order-limit-count` = **1000건/일**
- `magicjar.guard.daily-order-limit-value-krw` = **1억 원/일**

### 단계적 조정 가이드
| 단계 | 권장 한도 (count / value) | 사유 |
|---|---|---|
| 관찰기 (모의·1차 운영) | 1000 / 1억 | dogfooding·고빈도 전략 검증 — 한도가 막히지 않게 |
| 모의 안정화기 | 500 / 5천만 | 폭주 사고 대비 + 현실 매매 빈도 반영 |
| 실전 준비기 | 100 / 1천만 | 1차 실전 실수 방지 |
| 실전 운영기 | 사용자 자체 결정 | 자본·전략 빈도 기반 |

### 조정 방법
1. `.env` 또는 `docker-compose.yml`의 environment에 추가:
   ```yaml
   MAGICJAR_GUARD_DAILY_ORDER_LIMIT_COUNT: "500"
   MAGICJAR_GUARD_DAILY_ORDER_LIMIT_VALUE_KRW: "50000000"
   ```
2. `consumer` 컨테이너 재시작.
3. 부팅 로그에서 적용 확인 — `DailyOrderLimitGuard` 초기화 시점 (DEBUG 로그 활성화 권고).

### 주의
- **한도가 정상 매매를 차단하면 안 됨**. 차단 보고 시 즉시 한도 상향. 가드는 폭주·실수 차단용 안전벨트, 전략 제약이 아님.
- 스켈핑·호가 펼치기·1초 정정 같은 고빈도 패턴은 default 한도 안에서 자유롭게 작동.

## 3. Profile 전환 매트릭스

| 시점 | profile | env 설정 | 효과 |
|---|---|---|---|
| 단위테스트·CI | `mock` | (default) | 외부 호출 0, MockOrderSender FILLED 즉시 |
| 내부 개발 검증 | `dev` | `SPRING_PROFILES_ACTIVE=dev` + KIS 모의 키 + `MAGICJAR_SECRET_MASTER_KEY` | KIS 모의서버 호출, 실 외부 통신 (모의계좌) |
| 운영 (모의) | `prd` + `trading_mode=MOCK_KIS` | `SPRING_PROFILES_ACTIVE=prd` + user_trading_config | 사용자 등록 키 기반 모의 매매 |
| 운영 (실전) | `prd` + `trading_mode=REAL_KIS` | 동일 + 실전 키 등록 | **실거래** |

profile 전환 절차:
1. 새 profile 활성: `docker-compose.yml` 또는 `docker-compose.dev.yml` 수정
2. 부팅 로그 `[KIS_MODE] active=...` 확인 — 의도와 일치하는지 검증
3. **KillSwitch는 새 profile에서 default ON** — 명시적 OFF로 전환

## 4. trading_mode 전환 (MOCK_KIS → REAL_KIS)

prd profile 안에서 사용자 등록 데이터의 `trading_mode`만 변경하면 됨.

### 전제 조건
- KIS 실전 키 발급 완료 (https://apiportal.koreainvestment.com 신청)
- IP 화이트리스트 등록 완료 (운영 서버 IP)
- 모의 안정화 N일 무사고 (DailyLimit·KillSwitch 작동 검증, 의도치 않은 거부 0)

### 전환 절차
1. 사용자가 실전 key·secret을 안전하게 보관 (절대 채팅·로그 노출 금지 — 사고 시 즉시 KIS 발급페이지에서 재발급)
2. `consumer` 컨테이너 안에 진입해 SQL 직접 실행:
   ```sql
   UPDATE user_trading_config
      SET trading_mode = 'REAL_KIS',
          kis_app_key_encrypted = pgp_sym_encrypt('<NEW_REAL_KEY>', '<MASTER_KEY>'),
          kis_app_secret_encrypted = pgp_sym_encrypt('<NEW_REAL_SECRET>', '<MASTER_KEY>'),
          updated_at = NOW()
    WHERE user_id = '00000000-0000-0000-0000-000000000001';
   ```
   ※ 본 SQL은 placeholder. 실제로는 `SecretCipher.encrypt()`로 직접 생성한 ciphertext를 INSERT — 운영 endpoint(후속 트랙) 또는 사용자 직접 KisCredentialProvider 호출 코드 작성.
3. consumer 재시작 → 부팅 로그 `[KIS_MODE] active=PRD trading_mode=REAL_KIS ⚠ LIVE_TRADING killSwitch=ON` 확인
4. KillSwitch OFF로 전환
5. **소액 1주 매수 테스트** → 체결 확인 → KIS HTS에서 동일 주문번호 확인 → 즉시 매도 → 잔고 0 확인
6. 본격 운영

## 5. KIS 토큰 갱신·재발급

- KIS access_token TTL은 24시간 (KIS 공식). KisHttpSession이 1분 마진으로 자동 재발급.
- 401 응답 발생 시 KisHttpSession.executeWithRetry가 1회 invalidate + retry.
- 강제 재발급이 필요하면 `consumer` 컨테이너 재시작 — 인메모리 캐시가 비워짐.
- WS 인증(approval_key)은 별도 — 후속 (T15에서 인프라 도입, 본 1차 사이클은 callback 단위).

## 6. KIS WS 연결 모니터링

- KisWsSession은 끊김 시 exponential backoff(2s·4s·8s···)로 자동 재접속.
- 모니터링: `/actuator/metrics/kis.ws.disconnects` (T17 카운터).
- WS 끊김 ≠ 데이터 손실 — OrderExecutionSweep(@Scheduled 30s)이 REST 보강. 멱등 dedup으로 중복 0.
- KIS 공식 — 30분 무활동 시 ping/pong 끊김 권고. 자동 재접속이 정상 동작.

## 7. 가드 정책 진화 계획

1번 트랙 1차는 **minimal real** 가드 (KillSwitch + DailyLimit + PriceSanity). 3번 트랙(Risk Guard)에서 정밀화 예정:

| 가드 | 1차 | 3번 트랙 후 |
|---|---|---|
| KillSwitch | GLOBAL 단일 toggle | user-scope 또는 strategy-scope 분리 |
| DailyOrderLimit | count + total_value_krw | 종목별 한도, 시간대별 한도, 변동성 기반 sizing |
| PriceSanity | D1 종가 ±30% | 호가창 대비, ATR 기반 동적 범위 |
| (신규) PositionLimit | 없음 | 종목 수·금액 한도 |
| (신규) DrawdownStop | 없음 | 일일 손실 한도 도달 시 매수 차단 |

진화 시점은 사용자가 dogfooding 결과 반영해 결정.

## 8. KIS API 호출 한도

- 모의 + 실전 각각 **5회/초** (KIS 공식).
- 1차 사이클은 단일 사용자라 한도 도달 가능성 낮음.
- 향후 멀티테넌트 진입 시 사용자별 KIS 키 + 호출 큐잉(또는 KIS Pro plan 고려).

## 9. master key rotation

`MAGICJAR_SECRET_MASTER_KEY`는 AES-256-GCM 암호화의 root. 노출·유출 의심 시 rotation 절차:

1. 새 master key 발급: `openssl rand -base64 32`
2. **모든 user_trading_config의 암호문을 재암호화** — 신규 운영 endpoint 또는 한 번만 실행하는 마이그 SQL:
   ```kotlin
   // pseudo-code
   val old = SecretCipher(oldKey)
   val new = SecretCipher(newKey)
   userTradingConfigRepo.findAll().forEach { cfg ->
       userTradingConfigRepo.save(cfg.copy(
           kisAppKeyEncrypted = new.encrypt(old.decrypt(cfg.kisAppKeyEncrypted)),
           kisAppSecretEncrypted = new.encrypt(old.decrypt(cfg.kisAppSecretEncrypted)),
       ))
   }
   ```
3. `.env`의 `MAGICJAR_SECRET_MASTER_KEY` 교체 → 재시작
4. 부팅 시 MasterKeyValidator 통과 확인.
5. 한 번 더 1주 매수 → 체결 확인으로 정합성 검증.

향후 트랙: HashiCorp Vault 도입 시 별도 절차.

## 10. 데이터 모델 빠른 참조

| 테이블 | 의미 | UPDATE 가능 여부 |
|---|---|---|
| `users` | 사용자 1명 (시드) → 인증 도입 시 다중 | mutable |
| `user_trading_config` | KIS 키 (암호화) + 계좌 + mode | mutable (rotation·전환 시) |
| `orders` | 주문 메타 projection | mutable (status·filled_qty·avg_fill_price) |
| `order_events` | 라이프사이클 audit | **append-only** — UPDATE 0건 |
| `daily_order_counter` | 가드용 일일 카운터 | mutable (UPSERT) |
| `kill_switch_state` | 비상 정지 상태 | mutable (toggle 시) |

`order_events` UNIQUE 키: `(kis_mode, kis_order_no, event_type, cumulative_qty)` — WS·REST sweep 멱등 dedup.

---

## 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-02 | 1번 트랙 1차 — 초기 작성 (T18) |
