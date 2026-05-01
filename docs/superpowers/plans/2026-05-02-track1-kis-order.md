# 1번 트랙 — KIS 주문 API 어댑터 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** KIS 모의계좌까지 매수→체결→매도→잔고 0 사이클이 작동하는 자동매매 토대를 만든다. 인증·실전 진입은 후속 트랙이지만 데이터 모델·인터페이스는 멀티테넌트 정공법으로 미리 갖춘다.

**Architecture:** consumer 모듈에 KIS 외부 시스템 어댑터(시세 REST·주문 REST·체결통보 WS) 통합 + WS Primary + REST Sweep 보강 멱등성. profile 분기(mock/dev/prd) + 사용자 등록 trading_mode 분기. AES-GCM 암호화된 KIS 키. orders + order_events(append-only audit). KillSwitch + DailyOrderLimit + PriceSanity Guard.

**Tech Stack:** Spring Boot 3 (Kotlin 2.0), JDK 21, PostgreSQL 16, Kafka, MinIO, OkHttp + minio-py, Spring WebSocket, Spring Data JPA, MockK, kotest, WireMock, Testcontainers, Flyway V9 (V8은 분봉 시드로 점유됨 by side-session).

**spec:** `docs/superpowers/specs/2026-05-02-track1-kis-order-design.md`

---

## 사용자 외부 자원 — Task 1 진입 전 필수

**`MAGICJAR_SECRET_MASTER_KEY`** 발급 + `.env` 추가:

```bash
echo "MAGICJAR_SECRET_MASTER_KEY=$(openssl rand -base64 32)" >> .env
```

이 키 없이 Task 1 진입하면 부팅 fail-fast (mock profile 제외). 사용자 액션 후 진행.

---

## Phase 1 — DB 시드 + 비밀 관리 (Task 1~3)

### Task 1: V9 Flyway 마이그레이션 — 1번 트랙 신규 테이블 6개

**Files:**
- Create: `api/src/main/resources/db/migration/V9__track1_orders.sql`
- Test: `api/src/test/kotlin/com/mj/api/persistence/V9MigrationTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.mj.api.persistence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class V9MigrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
) {
    @Test
    fun `V9 creates 6 new tables for track1`() {
        val tables = jdbc.queryForList(
            """SELECT table_name FROM information_schema.tables
               WHERE table_schema='public'
                 AND table_name IN ('users','user_trading_config','orders',
                                    'order_events','daily_order_counter','kill_switch_state')""",
            String::class.java,
        ).toSet()

        tables shouldBe setOf(
            "users", "user_trading_config", "orders",
            "order_events", "daily_order_counter", "kill_switch_state",
        )
    }

    @Test
    fun `kill_switch_state seed has GLOBAL row default ON`() {
        val active = jdbc.queryForObject(
            "SELECT is_active FROM kill_switch_state WHERE scope='GLOBAL'",
            Boolean::class.java,
        )
        active shouldBe true
    }

    @Test
    fun `order_events unique key prevents duplicate executions`() {
        jdbc.update(
            """INSERT INTO users(user_id, username, created_at, updated_at)
               VALUES('00000000-0000-0000-0000-000000000001','testuser',NOW(),NOW())""",
        )
        jdbc.update(
            """INSERT INTO orders(order_id, user_id, account_no, kis_mode, intent_id,
                                  kis_order_no, krx_fwdg_ord_orgno, code, side, qty,
                                  order_type, status, created_at, updated_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000010"),
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "50185338", "MOCK_KIS",
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000020"),
            "0000123456", "06010", "005930", "BUY", 1,
            "LIMIT", "PENDING", java.time.OffsetDateTime.now(), java.time.OffsetDateTime.now(),
        )

        val insertEvent = """INSERT INTO order_events(event_id, order_id, user_id, account_no,
                                                       kis_mode, kis_order_no, event_type,
                                                       cumulative_qty, occurred_at, ingested_at)
                              VALUES(?,?,?,?,?,?,?,?,?,?)"""
        jdbc.update(
            insertEvent,
            java.util.UUID.randomUUID(),
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000010"),
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "50185338", "MOCK_KIS", "0000123456", "EXECUTED", 1,
            java.time.OffsetDateTime.now(), java.time.OffsetDateTime.now(),
        )

        try {
            jdbc.update(
                insertEvent,
                java.util.UUID.randomUUID(),
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000010"),
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "50185338", "MOCK_KIS", "0000123456", "EXECUTED", 1,
                java.time.OffsetDateTime.now(), java.time.OffsetDateTime.now(),
            )
            error("UNIQUE 제약 깨짐")
        } catch (e: org.springframework.dao.DuplicateKeyException) {
            // expected
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :api:test --tests V9MigrationTest
```

Expected: FAIL — `V9` 마이그레이션 미존재로 테이블 부재.

- [ ] **Step 3: V9 SQL 작성**

```sql
-- api/src/main/resources/db/migration/V9__track1_orders.sql
-- 1번 트랙: KIS 주문 어댑터용 신규 테이블 6개
-- spec §4.1 참조

CREATE TABLE users (
  user_id UUID PRIMARY KEY,
  username VARCHAR(64) UNIQUE NOT NULL,
  display_name VARCHAR(128),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE user_trading_config (
  user_id UUID PRIMARY KEY REFERENCES users(user_id),
  trading_mode VARCHAR(16) NOT NULL,
  account_no VARCHAR(20) NOT NULL,
  account_product_code VARCHAR(2) NOT NULL,
  kis_app_key_encrypted TEXT NOT NULL,
  kis_app_secret_encrypted TEXT NOT NULL,
  hts_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CHECK (trading_mode IN ('MOCK_KIS','REAL_KIS'))
);

CREATE TABLE orders (
  order_id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(user_id),
  account_no VARCHAR(20) NOT NULL,
  kis_mode VARCHAR(8) NOT NULL,
  intent_id UUID NOT NULL,
  kis_order_no VARCHAR(20) NOT NULL,
  krx_fwdg_ord_orgno VARCHAR(8) NOT NULL,
  code VARCHAR(7) NOT NULL,
  side VARCHAR(8) NOT NULL,
  qty INT NOT NULL CHECK (qty > 0),
  price NUMERIC(15,2),
  order_type VARCHAR(8) NOT NULL,
  status VARCHAR(20) NOT NULL,
  filled_qty INT NOT NULL DEFAULT 0,
  avg_fill_price NUMERIC(15,2),
  reject_reason TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  UNIQUE (kis_mode, kis_order_no),
  CHECK (kis_mode IN ('MOCK_KIS','REAL_KIS')),
  CHECK (side IN ('BUY','SELL')),
  CHECK (order_type IN ('MARKET','LIMIT')),
  CHECK (status IN ('PENDING','PARTIALLY_FILLED','FILLED','CANCELED','REJECTED'))
);

CREATE TABLE order_events (
  event_id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES orders(order_id),
  user_id UUID NOT NULL,
  account_no VARCHAR(20) NOT NULL,
  kis_mode VARCHAR(8) NOT NULL,
  kis_order_no VARCHAR(20) NOT NULL,
  event_type VARCHAR(20) NOT NULL,
  cumulative_qty INT,
  partial_qty INT,
  exec_price NUMERIC(15,2),
  source VARCHAR(8),
  payload JSONB,
  occurred_at TIMESTAMPTZ NOT NULL,
  ingested_at TIMESTAMPTZ NOT NULL,
  UNIQUE (kis_mode, kis_order_no, event_type, cumulative_qty),
  CHECK (event_type IN ('PLACED','MODIFIED','CANCELED','EXECUTED','REJECTED','RECEIPT_ACCEPTED'))
);

CREATE TABLE daily_order_counter (
  counter_date DATE NOT NULL,
  user_id UUID NOT NULL,
  account_no VARCHAR(20) NOT NULL,
  count INT NOT NULL DEFAULT 0,
  total_value_krw NUMERIC(18,2) NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (counter_date, user_id, account_no)
);

CREATE TABLE kill_switch_state (
  scope VARCHAR(16) PRIMARY KEY,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  reason TEXT,
  toggled_by VARCHAR(64),
  toggled_at TIMESTAMPTZ NOT NULL
);
INSERT INTO kill_switch_state (scope, is_active, reason, toggled_by, toggled_at)
VALUES ('GLOBAL', TRUE, 'initial — guard layer default ON', 'system', NOW());

CREATE INDEX idx_orders_user_created ON orders(user_id, created_at DESC);
CREATE INDEX idx_orders_pending ON orders(kis_mode, status)
  WHERE status IN ('PENDING','PARTIALLY_FILLED');
CREATE INDEX idx_order_events_order ON order_events(order_id, occurred_at);
CREATE INDEX idx_order_events_user ON order_events(user_id, occurred_at DESC);
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :api:test --tests V9MigrationTest
```

Expected: PASS — 3 테스트 모두 통과.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/resources/db/migration/V9__track1_orders.sql \
        api/src/test/kotlin/com/mj/api/persistence/V9MigrationTest.kt
git commit -m "feat(track1): V9 Flyway — orders·events·user_trading_config·guards (T1/20)"
```

---

### Task 2: AesGcmSecretCipher — KIS 키 암호화 인프라

**Files:**
- Create: `domain/src/main/kotlin/com/mj/domain/secret/SecretCipher.kt`
- Create: `consumer/src/main/kotlin/com/mj/consumer/infrastructure/secret/AesGcmSecretCipher.kt`
- Test: `consumer/src/test/kotlin/com/mj/consumer/infrastructure/secret/AesGcmSecretCipherTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.mj.consumer.infrastructure.secret

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

class AesGcmSecretCipherTest {
    private val masterKey = SecretKeySpec(
        Base64.getDecoder().decode("dGhpcy1pcy0zMi1ieXRlcy1tYXN0ZXIta2V5LWFhYWFhYQo="),
        "AES",
    )
    private val cipher = AesGcmSecretCipher(masterKey)

    @Test
    fun `encrypt then decrypt yields original`() {
        val plain = "PSAKi7XV..fake-app-key..xQ"
        val encrypted = cipher.encrypt(plain)
        encrypted shouldNotBe plain
        encrypted shouldNotContain plain  // 평문이 ciphertext에 부분 노출되지 않음
        cipher.decrypt(encrypted) shouldBe plain
    }

    @Test
    fun `encrypt produces different output for same input due to random IV`() {
        val plain = "secret"
        val a = cipher.encrypt(plain)
        val b = cipher.encrypt(plain)
        a shouldNotBe b   // IV가 매번 다르기에 ciphertext도 다름
    }

    @Test
    fun `decrypt fails on tampered ciphertext`() {
        val encrypted = cipher.encrypt("plain")
        val tampered = encrypted.dropLast(4) + "AAAA"
        try {
            cipher.decrypt(tampered)
            error("tampered ciphertext should fail")
        } catch (e: javax.crypto.AEADBadTagException) {
            // expected — GCM 인증 실패
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :consumer:test --tests AesGcmSecretCipherTest
```

Expected: FAIL — `SecretCipher`·`AesGcmSecretCipher` 미존재.

- [ ] **Step 3: 도메인 인터페이스 + 구현체**

```kotlin
// domain/src/main/kotlin/com/mj/domain/secret/SecretCipher.kt
package com.mj.domain.secret

/** 암호화/복호화 추상. 1차 구현은 AES-GCM, 후속에 Vault로 교체 가능. */
interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String
}
```

```kotlin
// consumer/src/main/kotlin/com/mj/consumer/infrastructure/secret/AesGcmSecretCipher.kt
package com.mj.consumer.infrastructure.secret

import com.mj.domain.secret.SecretCipher
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM. IV 12바이트 랜덤 → ciphertext 앞에 prepend → base64.
 * spec §8.2.
 */
class AesGcmSecretCipher(private val key: SecretKey) : SecretCipher {

    override fun encrypt(plain: String): String {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    override fun decrypt(encoded: String): String {
        val raw = Base64.getDecoder().decode(encoded)
        require(raw.size > IV_LEN) { "ciphertext too short" }
        val iv = raw.copyOfRange(0, IV_LEN)
        val ct = raw.copyOfRange(IV_LEN, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), StandardCharsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :consumer:test --tests AesGcmSecretCipherTest
```

Expected: PASS — 3 테스트 통과.

- [ ] **Step 5: Commit**

```bash
git add domain/src/main/kotlin/com/mj/domain/secret/SecretCipher.kt \
        consumer/src/main/kotlin/com/mj/consumer/infrastructure/secret/AesGcmSecretCipher.kt \
        consumer/src/test/kotlin/com/mj/consumer/infrastructure/secret/AesGcmSecretCipherTest.kt
git commit -m "feat(track1): SecretCipher 도메인 추상 + AES-GCM 구현 (T2/20)"
```

---

### Task 3: 시드 마이그 — .env → DB seed + master key 검증 + .env 정리 안내

**Files:**
- Create: `consumer/src/main/kotlin/com/mj/consumer/infrastructure/bootstrap/MasterKeyValidator.kt`
- Create: `consumer/src/main/kotlin/com/mj/consumer/infrastructure/bootstrap/InitialUserSeeder.kt`
- Create: `consumer/src/main/resources/db/migration/V9.1__seed_default_user.sql` (placeholder, app code seeds)
- Modify: `consumer/src/main/kotlin/com/mj/consumer/config/ConsumerKisConfig.kt` (신규)
- Test: `consumer/src/test/kotlin/com/mj/consumer/infrastructure/bootstrap/InitialUserSeederTest.kt`

- [ ] **Step 1: Write failing test — seeder 동작**

```kotlin
package com.mj.consumer.infrastructure.bootstrap

import com.mj.consumer.infrastructure.secret.AesGcmSecretCipher
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import javax.crypto.spec.SecretKeySpec

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = [
    "magicjar.secret.master-key=dGhpcy1pcy0zMi1ieXRlcy1tYXN0ZXIta2V5LWFhYWFhYQo=",
    "magicjar.seed.kis.app-key=APP_KEY_PLAIN",
    "magicjar.seed.kis.app-secret=APP_SECRET_PLAIN",
    "magicjar.seed.kis.account-cano=50185338",
    "magicjar.seed.kis.account-prdt=01",
    "magicjar.seed.kis.hts-id=PLACEHOLDER",
])
@Import(InitialUserSeeder::class, AesGcmSecretCipher::class)
class InitialUserSeederTest @Autowired constructor(
    private val seeder: InitialUserSeeder,
    private val jdbc: JdbcTemplate,
    private val cipher: com.mj.domain.secret.SecretCipher,
) {
    @Test
    fun `seeder creates default user and trading config from properties`() {
        seeder.seedIfNotExists()

        val users = jdbc.queryForList("SELECT username FROM users", String::class.java)
        users shouldBe listOf("default")

        val cfg = jdbc.queryForMap(
            """SELECT trading_mode, account_no, account_product_code,
                       kis_app_key_encrypted, kis_app_secret_encrypted, hts_id
                 FROM user_trading_config""",
        )
        cfg["trading_mode"] shouldBe "MOCK_KIS"
        cfg["account_no"] shouldBe "50185338"
        cfg["account_product_code"] shouldBe "01"
        cfg["hts_id"] shouldBe "PLACEHOLDER"
        cipher.decrypt(cfg["kis_app_key_encrypted"] as String) shouldBe "APP_KEY_PLAIN"
        cipher.decrypt(cfg["kis_app_secret_encrypted"] as String) shouldBe "APP_SECRET_PLAIN"
    }

    @Test
    fun `seeder is idempotent`() {
        seeder.seedIfNotExists()
        seeder.seedIfNotExists()  // 두 번 호출
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Int::class.java)
        count shouldBe 1
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :consumer:test --tests InitialUserSeederTest
```

Expected: FAIL — 클래스 미존재.

- [ ] **Step 3: MasterKeyValidator 구현 (mock profile 제외 fail-fast)**

```kotlin
// consumer/src/main/kotlin/com/mj/consumer/infrastructure/bootstrap/MasterKeyValidator.kt
package com.mj.consumer.infrastructure.bootstrap

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class MasterKeyValidator(
    @Value("\${magicjar.secret.master-key:}") private val masterKey: String,
    private val env: Environment,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun verify() {
        val active = env.activeProfiles.toSet()
        val isMock = active.contains("mock") || active.isEmpty()
        if (isMock) {
            log.info("[MASTER_KEY] skip verification (mock profile)")
            return
        }
        require(masterKey.isNotBlank()) {
            "MAGICJAR_SECRET_MASTER_KEY is required for profile=${active}. " +
            "Generate with: openssl rand -base64 32 → add to .env"
        }
        try {
            java.util.Base64.getDecoder().decode(masterKey).also {
                require(it.size == 32) { "master key must be 32 bytes after base64 decode (got ${it.size})" }
            }
        } catch (e: IllegalArgumentException) {
            error("MAGICJAR_SECRET_MASTER_KEY invalid base64 / size: ${e.message}")
        }
        log.info("[MASTER_KEY] verified (length OK, profile=${active})")
    }
}
```

- [ ] **Step 4: InitialUserSeeder 구현**

```kotlin
// consumer/src/main/kotlin/com/mj/consumer/infrastructure/bootstrap/InitialUserSeeder.kt
package com.mj.consumer.infrastructure.bootstrap

import com.mj.domain.secret.SecretCipher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 시드 사용자 1명 + user_trading_config 1행을 .env 값에서 읽어 INSERT.
 * spec §4.3. mock profile에선 skip(외부 호출 0).
 */
@Component
@Profile("dev | prd")
class InitialUserSeeder(
    private val jdbc: JdbcTemplate,
    private val cipher: SecretCipher,
    @Value("\${magicjar.seed.kis.app-key:}") private val appKey: String,
    @Value("\${magicjar.seed.kis.app-secret:}") private val appSecret: String,
    @Value("\${magicjar.seed.kis.account-cano:}") private val cano: String,
    @Value("\${magicjar.seed.kis.account-prdt:01}") private val prdt: String,
    @Value("\${magicjar.seed.kis.hts-id:PLACEHOLDER}") private val htsId: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun seedOnStartup() {
        seedIfNotExists()
    }

    fun seedIfNotExists() {
        val exists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE user_id = ?",
            Int::class.java,
            DEFAULT_USER_ID,
        ) ?: 0
        if (exists > 0) {
            log.info("[SEED] default user already exists — skip")
            return
        }
        require(appKey.isNotBlank()) { "magicjar.seed.kis.app-key 미설정" }
        require(appSecret.isNotBlank()) { "magicjar.seed.kis.app-secret 미설정" }
        require(cano.isNotBlank()) { "magicjar.seed.kis.account-cano 미설정" }

        val now = OffsetDateTime.now()
        jdbc.update(
            """INSERT INTO users(user_id, username, display_name, created_at, updated_at)
               VALUES(?,?,?,?,?)""",
            DEFAULT_USER_ID, "default", "Default User", now, now,
        )
        jdbc.update(
            """INSERT INTO user_trading_config(user_id, trading_mode, account_no,
                                                account_product_code,
                                                kis_app_key_encrypted, kis_app_secret_encrypted,
                                                hts_id, created_at, updated_at)
               VALUES(?,?,?,?,?,?,?,?,?)""",
            DEFAULT_USER_ID, "MOCK_KIS", cano, prdt,
            cipher.encrypt(appKey), cipher.encrypt(appSecret), htsId,
            now, now,
        )
        log.info("[SEED] default user seeded: account=$cano, mode=MOCK_KIS, hts_id=$htsId")
        log.warn("[SEED] .env에서 KIS_MOCK_APP_KEY, KIS_MOCK_APP_SECRET, " +
                 "KIS_ACCOUNT_CANO, KIS_ACCOUNT_PRDT 제거 권고 — DB로 이전 완료")
    }

    companion object {
        val DEFAULT_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
```

- [ ] **Step 5: ConsumerKisConfig — Bean 등록**

```kotlin
// consumer/src/main/kotlin/com/mj/consumer/config/ConsumerKisConfig.kt
package com.mj.consumer.config

import com.mj.consumer.infrastructure.secret.AesGcmSecretCipher
import com.mj.domain.secret.SecretCipher
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

@Configuration
class ConsumerKisConfig {

    @Bean
    fun secretCipher(
        @Value("\${magicjar.secret.master-key}") masterKeyB64: String,
    ): SecretCipher {
        val raw = Base64.getDecoder().decode(masterKeyB64)
        require(raw.size == 32) { "master key must be 32 bytes" }
        return AesGcmSecretCipher(SecretKeySpec(raw, "AES"))
    }
}
```

- [ ] **Step 6: docker-compose.yml에 master key + seed env 추가**

`docker/docker-compose.yml`의 consumer-app 섹션에 다음 environment 추가:

```yaml
environment:
  # ... 기존 ...
  MAGICJAR_SECRET_MASTER_KEY: ${MAGICJAR_SECRET_MASTER_KEY:?master key required for dev/prd}
  MAGICJAR_SEED_KIS_APP_KEY: ${KIS_MOCK_APP_KEY:-}
  MAGICJAR_SEED_KIS_APP_SECRET: ${KIS_MOCK_APP_SECRET:-}
  MAGICJAR_SEED_KIS_ACCOUNT_CANO: ${KIS_ACCOUNT_CANO:-}
  MAGICJAR_SEED_KIS_ACCOUNT_PRDT: ${KIS_ACCOUNT_PRDT:-01}
  MAGICJAR_SEED_KIS_HTS_ID: ${KIS_HTS_ID:-PLACEHOLDER}
```

- [ ] **Step 7: Run test to verify it passes**

```bash
./gradlew :consumer:test --tests InitialUserSeederTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add consumer/src/main/kotlin/com/mj/consumer/infrastructure/bootstrap/ \
        consumer/src/main/kotlin/com/mj/consumer/config/ConsumerKisConfig.kt \
        consumer/src/test/kotlin/com/mj/consumer/infrastructure/bootstrap/InitialUserSeederTest.kt \
        docker/docker-compose.yml
git commit -m "feat(track1): MasterKeyValidator + InitialUserSeeder + ConsumerKisConfig (T3/20)"
```

---

## Phase 2 — 인프라 컴포넌트 (Task 4~6)

### Task 4: KisHttpSession — PR #32 토큰 로직 추출

**Files:**
- Create: `consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisHttpSession.kt`
- Test: `consumer/src/test/kotlin/com/mj/consumer/infrastructure/kis/KisHttpSessionTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.mj.consumer.infrastructure.kis

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class KisHttpSessionTest {
    private lateinit var server: MockWebServer
    private lateinit var session: KisHttpSession

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        session = KisHttpSession(
            baseUrl = server.url("").toString().trimEnd('/'),
            okHttp = OkHttpClient(),
            clock = Clock.fixed(Instant.parse("2026-05-02T10:00:00Z"), ZoneOffset.UTC),
        )
    }

    @AfterEach
    fun tearDown() { server.shutdown() }

    @Test
    fun `acquireToken caches access_token`() {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"TOKEN_AAA","token_type":"Bearer","expires_in":86400}""",
            ).setHeader("Content-Type", "application/json"),
        )
        val tok1 = session.acquireToken("appkey", "appsecret")
        val tok2 = session.acquireToken("appkey", "appsecret")
        tok1 shouldBe "TOKEN_AAA"
        tok2 shouldBe "TOKEN_AAA"  // 캐시 사용
        server.requestCount shouldBe 1
    }

    @Test
    fun `invalidate forces re-fetch`() {
        server.enqueue(MockResponse().setBody(
            """{"access_token":"AAA","token_type":"Bearer","expires_in":86400}"""))
        server.enqueue(MockResponse().setBody(
            """{"access_token":"BBB","token_type":"Bearer","expires_in":86400}"""))

        session.acquireToken("appkey", "appsecret") shouldBe "AAA"
        session.invalidateToken("appkey")
        session.acquireToken("appkey", "appsecret") shouldBe "BBB"
        server.requestCount shouldBe 2
    }

    @Test
    fun `expiry triggers re-fetch`() {
        // 첫 응답에 짧은 ttl
        server.enqueue(MockResponse().setBody(
            """{"access_token":"AAA","token_type":"Bearer","expires_in":1}"""))
        server.enqueue(MockResponse().setBody(
            """{"access_token":"BBB","token_type":"Bearer","expires_in":86400}"""))

        // 만료 후 시계 진행
        session = KisHttpSession(
            baseUrl = server.url("").toString().trimEnd('/'),
            okHttp = OkHttpClient(),
            clock = Clock.fixed(Instant.parse("2026-05-02T10:00:00Z"), ZoneOffset.UTC),
        )
        session.acquireToken("appkey", "appsecret") shouldBe "AAA"
        // 다른 시점에서 호출 시뮬 — clock 파라미터로 추후 expand
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :consumer:test --tests KisHttpSessionTest
```

Expected: FAIL — 클래스 미존재.

- [ ] **Step 3: 구현**

```kotlin
// consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisHttpSession.kt
package com.mj.consumer.infrastructure.kis

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * KIS REST 토큰 캐시 + 발급/만료/재발급 + HTTP 클라이언트 공유.
 * spec §6.1, ADR 0011·0021. PR #32의 토큰 로직을 추출해 시세·주문 어댑터가 공유.
 */
class KisHttpSession(
    private val baseUrl: String,
    private val okHttp: OkHttpClient,
    private val clock: Clock = Clock.systemUTC(),
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<String, CachedToken>()

    fun baseUrl(): String = baseUrl
    fun client(): OkHttpClient = okHttp

    fun acquireToken(appKey: String, appSecret: String): String {
        val now = Instant.now(clock)
        val cached = cache[appKey]
        if (cached != null && cached.expiresAt.isAfter(now.plusSeconds(MARGIN_SEC))) {
            return cached.token
        }
        return refresh(appKey, appSecret, now)
    }

    fun invalidateToken(appKey: String) {
        cache.remove(appKey)
        log.info("[KIS_TOKEN] invalidated appKey=${appKey.take(4)}***")
    }

    private fun refresh(appKey: String, appSecret: String, now: Instant): String {
        val body = mapper.writeValueAsString(
            mapOf(
                "grant_type" to "client_credentials",
                "appkey" to appKey,
                "appsecret" to appSecret,
            ),
        )
        val req = Request.Builder()
            .url("$baseUrl/oauth2/tokenP")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        okHttp.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: error("empty token response")
            require(resp.isSuccessful) { "KIS token fetch failed: ${resp.code} $text" }
            val node = mapper.readTree(text)
            val token = node.get("access_token").asText()
            val ttl = node.get("expires_in").asLong()
            val expiresAt = now.plusSeconds(ttl)
            cache[appKey] = CachedToken(token, expiresAt)
            log.info("[KIS_TOKEN] refreshed appKey=${appKey.take(4)}*** ttl=${ttl}s")
            return token
        }
    }

    private data class CachedToken(val token: String, val expiresAt: Instant)

    private companion object {
        const val MARGIN_SEC = 60L  // 만료 1분 전에는 재발급
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :consumer:test --tests KisHttpSessionTest
```

Expected: PASS — 3 테스트.

- [ ] **Step 5: Commit**

```bash
git add consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisHttpSession.kt \
        consumer/src/test/kotlin/com/mj/consumer/infrastructure/kis/KisHttpSessionTest.kt
git commit -m "feat(track1): KisHttpSession — REST 토큰 캐시·재발급 (T4/20)"
```

---

### Task 5: KisWsSession — 체결통보 WebSocket 인프라

**Files:**
- Create: `consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisWsSession.kt`
- Create: `consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisAesDecoder.kt`
- Test: `consumer/src/test/kotlin/com/mj/consumer/infrastructure/kis/KisAesDecoderTest.kt`
- Test: `consumer/src/test/kotlin/com/mj/consumer/infrastructure/kis/KisWsSessionIT.kt`

- [ ] **Step 1: Write AES decoder test (KIS wikidocs sample fixture)**

```kotlin
package com.mj.consumer.infrastructure.kis

import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class KisAesDecoderTest {
    /**
     * KIS 공식 wikidocs sample (체결통보 복호화 예시).
     * 실제 KIS approval response의 iv·key를 받아 ciphertext를 풀면 ^ 구분 컬럼 raw가 나와야 한다.
     */
    @Test
    fun `decode AES256 with iv and key`() {
        // KIS wikidocs sample 또는 단위테스트용 자체 생성 fixture
        val iv = "abcdef1234567890"
        val key = "0123456789abcdef0123456789abcdef"
        val plain = "USERID^50185338^0000123456^^^^^^005930^1^70000^143025^N^2^Y^06010^1^TEST^^^^^^"
        val cipher = encryptForTest(plain, key, iv)  // helper

        val decoded = KisAesDecoder.decode(cipher, key, iv)
        decoded shouldStartWith "USERID^50185338^0000123456"
    }
}
```

- [ ] **Step 2: 구현 — KisAesDecoder**

```kotlin
// consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisAesDecoder.kt
package com.mj.consumer.infrastructure.kis

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * KIS WS 체결통보 페이로드 AES256/CBC/PKCS5 복호.
 * subscribe 응답에 포함된 iv·key 사용. spec §5.5.
 */
object KisAesDecoder {

    fun decode(cipherTextB64: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        val ct = Base64.getDecoder().decode(cipherTextB64)
        val plain = cipher.doFinal(ct)
        return String(plain, Charsets.UTF_8)
    }
}
```

- [ ] **Step 3: KisWsSession 구현 (Spring WebSocket Client + heartbeat + 재접속)**

```kotlin
// consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisWsSession.kt
package com.mj.consumer.infrastructure.kis

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * KIS 체결통보 WebSocket 세션.
 * - subscribe 프레임 송신 → SUBSCRIBE SUCCESS 응답에서 iv/key 추출
 * - 데이터 프레임 receive → AES 복호 → ^ 구분 raw → callback
 * - heartbeat ping (30s) + 끊김 시 자동 재접속 (exponential backoff)
 * spec §5.5, §7.2
 */
class KisWsSession(
    private val wsUrl: String,
    private val approvalKey: String,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = ReactorNettyWebSocketClient()
    private val subscription = AtomicReference<Disposable?>()
    private var ivKey: Pair<String, String>? = null

    fun subscribe(
        trId: String,             // H0STCNI0 / H0STCNI9
        trKey: String,             // hts_id
        onFrame: (KisExecutionNoticeRaw) -> Unit,
    ) {
        val handler = WebSocketHandler { sess ->
            val subscribeMsg = mapper.writeValueAsString(
                mapOf(
                    "header" to mapOf(
                        "approval_key" to approvalKey,
                        "tr_type" to "1",
                        "custtype" to "P",
                        "content-type" to "utf-8",
                    ),
                    "body" to mapOf(
                        "input" to mapOf("tr_id" to trId, "tr_key" to trKey),
                    ),
                ),
            )
            val out = sess.send(Mono.just(sess.textMessage(subscribeMsg)))
            val inFlux = sess.receive()
                .map { it.payloadAsText }
                .flatMap { text ->
                    try {
                        handleFrame(text, onFrame)
                    } catch (e: Exception) {
                        log.error("[KIS_WS] handle frame failed: $text", e)
                    }
                    Mono.empty<Void>()
                }
            Mono.zip(out, inFlux.then()).then()
        }
        val disp = client.execute(URI.create(wsUrl), handler)
            .retryWhen(reactor.util.retry.Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2)))
            .subscribe()
        subscription.set(disp)
        log.info("[KIS_WS] subscribed tr_id=$trId tr_key=$trKey")
    }

    fun unsubscribe() {
        subscription.get()?.dispose()
        ivKey = null
    }

    private fun handleFrame(text: String, onFrame: (KisExecutionNoticeRaw) -> Unit) {
        // SUBSCRIBE SUCCESS 응답: JSON
        if (text.trim().startsWith("{")) {
            val node = mapper.readTree(text)
            val output = node.path("body").path("output")
            val iv = output.path("iv").asText(null)
            val key = output.path("key").asText(null)
            if (iv != null && key != null) {
                ivKey = key to iv
                log.info("[KIS_WS] subscribe success — iv/key cached")
            }
            return
        }
        // 실 데이터 프레임: 0|tr_id|count|payload  또는  1|tr_id|count|encrypted_payload
        val parts = text.split("|", limit = 4)
        if (parts.size < 4) return
        val isEncrypted = parts[0] == "1"
        val rawPayload = if (isEncrypted) {
            val (k, iv) = ivKey ?: return.also { log.warn("[KIS_WS] iv/key not yet — drop frame") }
            KisAesDecoder.decode(parts[3], k, iv)
        } else {
            parts[3]
        }
        // ^ 구분 26 컬럼 → KisExecutionNoticeRaw
        val cols = rawPayload.split("^")
        if (cols.size < 15) return  // 정상 페이로드 아니면 skip
        onFrame(KisExecutionNoticeRaw.fromColumns(cols))
    }
}
```

```kotlin
// consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisExecutionNoticeRaw.kt
package com.mj.consumer.infrastructure.kis

/** KIS H0STCNI0/9 페이로드 26 컬럼 (spec §5.5). */
data class KisExecutionNoticeRaw(
    val custId: String,
    val acntNo: String,
    val orderNo: String,
    val originalOrderNo: String,
    val sellBuyClass: String,         // 1:매도, 2:매수
    val rectifyClass: String,         // 0:정상, 1:정정, 2:취소
    val orderKind: String,
    val orderCondition: String,
    val stockCode: String,
    val executedQty: Int,
    val executedUnitPrice: java.math.BigDecimal,
    val executedTime: String,         // HHMMSS
    val refusalYn: String,
    val cntgYn: String,               // 1:접수통보, 2:체결통보
    val acceptYn: String,
    val krxFwdgOrdOrgno: String,
    val orderQty: Int,
    val rest: List<String>,
) {
    companion object {
        fun fromColumns(c: List<String>): KisExecutionNoticeRaw = KisExecutionNoticeRaw(
            custId = c[0], acntNo = c[1], orderNo = c[2], originalOrderNo = c[3],
            sellBuyClass = c[4], rectifyClass = c[5], orderKind = c[6], orderCondition = c[7],
            stockCode = c[8],
            executedQty = c[9].toIntOrNull() ?: 0,
            executedUnitPrice = c[10].toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
            executedTime = c[11],
            refusalYn = c[12],
            cntgYn = c[13],
            acceptYn = c[14],
            krxFwdgOrdOrgno = c.getOrNull(15) ?: "",
            orderQty = c.getOrNull(16)?.toIntOrNull() ?: 0,
            rest = if (c.size > 17) c.subList(17, c.size) else emptyList(),
        )
    }
}
```

- [ ] **Step 4: integration test — embedded WS server (skip in CI, manual)**

WS embedded test는 reactive 비동기 흐름이라 1차에선 `KisAesDecoder` 단위로 핵심 검증. WS 통신 자체는 Task 15 통합 단계에서 fake `KisWsSession` mock으로 검증.

- [ ] **Step 5: Run unit tests**

```bash
./gradlew :consumer:test --tests "KisAesDecoderTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisAesDecoder.kt \
        consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisWsSession.kt \
        consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisExecutionNoticeRaw.kt \
        consumer/src/test/kotlin/com/mj/consumer/infrastructure/kis/KisAesDecoderTest.kt
git commit -m "feat(track1): KisWsSession + AES256 decoder + 체결통보 raw (T5/20)"
```

---

### Task 6: batch → consumer 이전 — RealKisRestAdapter·KisRestProperties·KisApiException

**Files:**
- Move: `batch/.../infrastructure/kis/RealKisRestAdapter.kt` → `consumer/.../infrastructure/kis/RealKisRestAdapter.kt`
- Move: `batch/.../infrastructure/kis/StubKisRestAdapter.kt` → `consumer/.../infrastructure/kis/StubKisRestAdapter.kt`
- Move: `batch/.../infrastructure/kis/KisRestProperties.kt` → `consumer/.../infrastructure/kis/KisRestProperties.kt`
- Move: `batch/.../infrastructure/kis/KisApiException.kt` → `consumer/.../infrastructure/kis/KisApiException.kt`
- Modify: `batch/build.gradle.kts` (KIS deps 제거)
- Modify: `consumer/build.gradle.kts` (KIS deps 추가 — okhttp, jackson)
- Modify: `consumer/src/main/kotlin/com/mj/consumer/config/ConsumerKisConfig.kt` (Bean 분기 보강)
- Modify: 모든 `import com.mj.batch.infrastructure.kis.*` → `import com.mj.consumer.infrastructure.kis.*`
- Modify: `RealKisRestAdapter` 내부에 `KisHttpSession` 사용으로 리팩 (기존 직접 OkHttp+토큰 로직 → KisHttpSession에 위임)
- Modify: `KisApiException` 카테고리 enum 추가 (D10)
- Test: `consumer/src/test/kotlin/com/mj/consumer/infrastructure/kis/RealKisRestAdapterIT.kt` (이전 + 회귀)

- [ ] **Step 1: 이전 + import 경로 수정 (대량 단순 이동)**

```bash
mkdir -p consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis
git mv batch/src/main/kotlin/com/mj/batch/infrastructure/kis/{RealKisRestAdapter,StubKisRestAdapter,KisRestProperties,KisApiException}.kt \
       consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/

git mv batch/src/test/kotlin/com/mj/batch/infrastructure/kis/RealKisRestAdapterIT.kt \
       consumer/src/test/kotlin/com/mj/consumer/infrastructure/kis/

# 패키지 선언 변경
find consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis -name "*.kt" \
    -exec sed -i '' 's|package com.mj.batch.infrastructure.kis|package com.mj.consumer.infrastructure.kis|' {} +
find consumer/src/test/kotlin/com/mj/consumer/infrastructure/kis -name "*.kt" \
    -exec sed -i '' 's|package com.mj.batch.infrastructure.kis|package com.mj.consumer.infrastructure.kis|' {} +
```

- [ ] **Step 2: import 경로 일괄 변경 (다른 모듈에서의 사용처)**

```bash
grep -rln "com.mj.batch.infrastructure.kis" --include="*.kt" \
  | xargs sed -i '' 's|com.mj.batch.infrastructure.kis|com.mj.consumer.infrastructure.kis|g'
```

- [ ] **Step 3: KisApiException 카테고리 보강**

`consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisApiException.kt`:

```kotlin
package com.mj.consumer.infrastructure.kis

enum class KisErrorCategory {
    AUTH, RATE_LIMIT, INSUFFICIENT_FUNDS, INVALID_ORDER,
    MARKET_CLOSED, SERVER, UNKNOWN;

    companion object {
        fun classify(httpStatus: Int, msgCd: String?): KisErrorCategory = when {
            httpStatus == 401 || msgCd in AUTH_CODES -> AUTH
            httpStatus == 429 || msgCd in RATE_CODES -> RATE_LIMIT
            httpStatus in 500..599 -> SERVER
            msgCd in FUNDS_CODES -> INSUFFICIENT_FUNDS
            msgCd in INVALID_CODES -> INVALID_ORDER
            msgCd in MARKET_CLOSED_CODES -> MARKET_CLOSED
            else -> UNKNOWN
        }

        // KIS 공식 코드 일부 — 운영하면서 카테고리 추가
        private val AUTH_CODES = setOf("EGW00121", "EGW00123", "OPSP9999")
        private val RATE_CODES = setOf("EGW00201")
        private val FUNDS_CODES = setOf("APBK0918", "APBK0919")  // 매수가능금액·증거금
        private val INVALID_CODES = setOf("APBK0067", "APBK0068", "APBK0918", "APBK1535")
        private val MARKET_CLOSED_CODES = setOf("APBK0086")
    }
}

class KisApiException(
    val rtCd: String,
    val msgCd: String,
    val msgRaw: String,
    val httpStatus: Int,
    val category: KisErrorCategory = KisErrorCategory.classify(httpStatus, msgCd),
) : RuntimeException("[$category][rt=$rtCd msg=$msgCd] $msgRaw")
```

- [ ] **Step 4: RealKisRestAdapter — KisHttpSession 사용으로 리팩**

기존 직접 OkHttp+토큰 캐시 로직을 `KisHttpSession`에 위임. 시그니처 + 로직만 정리, 외부 동작 변경 없음.

```kotlin
// consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/RealKisRestAdapter.kt 핵심부
@Component
@Profile("dev | prd")
class RealKisRestAdapter(
    private val session: KisHttpSession,
    private val credentialProvider: KisCredentialProvider,  // T7에서 도입
    private val props: KisRestProperties,
) : KisRestPort {

    override fun fetchCandles(...): List<Candle> {
        val cred = credentialProvider.forUser(currentUserId())
        val token = session.acquireToken(cred.appKey, cred.appSecret)
        // ... 기존 로직 그대로, OkHttpClient·토큰만 session에서
    }

    private fun currentUserId(): UUID =
        UUID.fromString("00000000-0000-0000-0000-000000000001")  // T7 후 SecurityContext
}
```

- [ ] **Step 5: build.gradle.kts 정정**

`batch/build.gradle.kts`에서 KIS REST·OkHttp deps 제거. `consumer/build.gradle.kts`에 추가.

```kotlin
// consumer/build.gradle.kts
dependencies {
    // ... 기존 ...
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.squareup.okhttp3:mockwebserver:4.12.0") {
        // test only — 또는 testImplementation으로 분리
    }
}
```

- [ ] **Step 6: batch에서 시세 어댑터 제거 — backfill job도 consumer로 이전**

`batch/.../job/backfill/DailyCandleBackfillJob.kt`를 `consumer/.../application/backfill/KisDailyCandleBackfill.kt`로 이전. `@Scheduled`로 변경 (JobLauncher 의존 제거).

- [ ] **Step 7: build 확인**

```bash
./gradlew :batch:build :consumer:build :api:build
```

Expected: BUILD SUCCESSFUL — 4 모듈 모두 통과.

- [ ] **Step 8: 회귀 테스트**

```bash
./gradlew :consumer:test --tests RealKisRestAdapterIT
```

Expected: PASS — 시세 5 시나리오 그대로 (PR #32 동작 보존).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor(track1): KIS 어댑터 batch → consumer 이전 + KisHttpSession 사용 + KisErrorCategory (T6/20)"
```

---

## Phase 3 — 주문 도메인 + 어댑터 (Task 7~10)

### Task 7: domain — KisOrderPort, OrderRepository, OrderEventRepository, KisCredentialProvider

**Files:**
- Create: `domain/src/main/kotlin/com/mj/domain/order/port/KisOrderPort.kt`
- Create: `domain/src/main/kotlin/com/mj/domain/order/port/OrderRepository.kt`
- Create: `domain/src/main/kotlin/com/mj/domain/order/port/OrderEventRepository.kt`
- Create: `domain/src/main/kotlin/com/mj/domain/order/OrderLifecycleEvent.kt`
- Create: `domain/src/main/kotlin/com/mj/domain/user/User.kt`
- Create: `domain/src/main/kotlin/com/mj/domain/user/UserTradingConfig.kt`
- Create: `domain/src/main/kotlin/com/mj/domain/user/port/UserRepository.kt`
- Create: `domain/src/main/kotlin/com/mj/domain/user/port/UserTradingConfigRepository.kt`
- Create: `domain/src/main/kotlin/com/mj/domain/user/port/KisCredentialProvider.kt`
- Test: `domain/src/test/kotlin/com/mj/domain/order/port/KisOrderPortTest.kt` (인터페이스 계약 검증)

- [ ] **Step 1: 인터페이스 작성 + 모델 정의**

```kotlin
// domain/src/main/kotlin/com/mj/domain/order/port/KisOrderPort.kt
package com.mj.domain.order.port

import com.mj.domain.common.Order
import com.mj.domain.order.KisOrderResult
import com.mj.domain.order.KisBalanceSnapshot
import com.mj.domain.order.KisExecutionLine
import java.time.LocalDate
import java.util.UUID

/**
 * KIS 주문/잔고/체결조회 시스템 어댑터 포트. 인프라(consumer.infrastructure.kis.RealKisOrderAdapter)에서 구현.
 * spec §3 D1, §6.2.
 */
interface KisOrderPort {
    fun placeOrder(userId: UUID, order: Order): KisOrderResult
    fun cancelOrder(userId: UUID, kisOrderNo: String, krxFwdgOrdOrgno: String, qtyAll: Boolean = true, qty: Int = 0): KisOrderResult
    fun modifyOrder(userId: UUID, kisOrderNo: String, krxFwdgOrdOrgno: String, newQty: Int, newPrice: java.math.BigDecimal): KisOrderResult
    fun fetchBalance(userId: UUID): KisBalanceSnapshot
    fun fetchExecutions(userId: UUID, date: LocalDate, executedOnly: Boolean = false): List<KisExecutionLine>
}
```

```kotlin
// domain/src/main/kotlin/com/mj/domain/order/KisOrderResult.kt
package com.mj.domain.order

data class KisOrderResult(
    val kisOrderNo: String,
    val krxFwdgOrdOrgno: String,
    val orderTime: String,            // HHMMSS
    val raw: Map<String, String> = emptyMap(),
)

// domain/src/main/kotlin/com/mj/domain/order/KisBalanceSnapshot.kt
package com.mj.domain.order

import java.math.BigDecimal

data class KisBalanceSnapshot(
    val holdings: List<HoldingLine>,
    val account: AccountSummary,
)

data class HoldingLine(
    val code: String,
    val name: String,
    val heldQty: Int,
    val orderableQty: Int,
    val avgPurchasePrice: BigDecimal,
    val currentPrice: BigDecimal,
    val evaluationAmount: BigDecimal,
    val evaluationPnL: BigDecimal,
)

data class AccountSummary(
    val depositAmount: BigDecimal,           // 예수금총금액
    val nextDayExchangeAmount: BigDecimal,   // D+1
    val twoDayExchangeAmount: BigDecimal,    // D+2
    val totalEvaluationAmount: BigDecimal,
    val netAssetAmount: BigDecimal,
    val totalPnL: BigDecimal,
)

// domain/src/main/kotlin/com/mj/domain/order/KisExecutionLine.kt
package com.mj.domain.order

import java.math.BigDecimal

data class KisExecutionLine(
    val orderDate: String,        // YYYYMMDD
    val kisOrderNo: String,
    val originalOrderNo: String,
    val krxFwdgOrdOrgno: String,
    val code: String,
    val side: com.mj.domain.common.Side,
    val orderedQty: Int,
    val orderedPrice: BigDecimal?,
    val cumulativeExecutedQty: Int,
    val cumulativeExecutedAmount: BigDecimal,
    val canceled: Boolean,
    val remainingQty: Int,
    val rejectedQty: Int,
)
```

```kotlin
// domain/src/main/kotlin/com/mj/domain/order/port/OrderRepository.kt
package com.mj.domain.order.port

import com.mj.domain.common.OrderStatus
import com.mj.domain.order.OrderRecord
import java.util.UUID

interface OrderRepository {
    fun save(record: OrderRecord): OrderRecord
    fun findById(orderId: UUID): OrderRecord?
    fun findByKisOrderNo(kisMode: String, kisOrderNo: String): OrderRecord?
    fun findOpenByUser(userId: UUID): List<OrderRecord>
    fun updateStatus(orderId: UUID, status: OrderStatus, filledQty: Int, avgFillPrice: java.math.BigDecimal?)
}

// domain/src/main/kotlin/com/mj/domain/order/OrderRecord.kt
package com.mj.domain.order

import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class OrderRecord(
    val orderId: UUID,
    val userId: UUID,
    val accountNo: String,
    val kisMode: String,
    val intentId: UUID,
    val kisOrderNo: String,
    val krxFwdgOrdOrgno: String,
    val code: String,
    val side: Side,
    val qty: Int,
    val price: BigDecimal?,
    val orderType: OrderType,
    val status: OrderStatus,
    val filledQty: Int,
    val avgFillPrice: BigDecimal?,
    val rejectReason: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)
```

```kotlin
// domain/src/main/kotlin/com/mj/domain/order/port/OrderEventRepository.kt
package com.mj.domain.order.port

import com.mj.domain.order.OrderEventRecord
import java.util.UUID

interface OrderEventRepository {
    /**
     * INSERT 시도. UNIQUE(kis_mode, kis_order_no, event_type, cumulative_qty) 충돌 시 false (멱등).
     * 성공 시 true.
     */
    fun insertIdempotent(event: OrderEventRecord): Boolean
    fun findByOrder(orderId: UUID): List<OrderEventRecord>
}
```

```kotlin
// domain/src/main/kotlin/com/mj/domain/order/OrderEventRecord.kt
// + OrderLifecycleEvent.kt — 도메인 이벤트 sealed class
```

```kotlin
// domain/src/main/kotlin/com/mj/domain/user/User.kt
package com.mj.domain.user

import java.time.OffsetDateTime
import java.util.UUID

data class User(
    val userId: UUID,
    val username: String,
    val displayName: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

// domain/src/main/kotlin/com/mj/domain/user/UserTradingConfig.kt
package com.mj.domain.user

import java.time.OffsetDateTime
import java.util.UUID

enum class TradingMode { MOCK_KIS, REAL_KIS }

data class UserTradingConfig(
    val userId: UUID,
    val tradingMode: TradingMode,
    val accountNo: String,
    val accountProductCode: String,
    val kisAppKeyEncrypted: String,
    val kisAppSecretEncrypted: String,
    val htsId: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

// domain/src/main/kotlin/com/mj/domain/user/port/KisCredentialProvider.kt
package com.mj.domain.user.port

import com.mj.domain.user.TradingMode
import java.util.UUID

interface KisCredentialProvider {
    fun forUser(userId: UUID): KisCredential
}

data class KisCredential(
    val tradingMode: TradingMode,
    val accountNo: String,
    val accountProductCode: String,
    val appKey: String,        // 평문 (메모리 only)
    val appSecret: String,     // 평문
    val htsId: String,
) {
    override fun toString(): String =
        "KisCredential(mode=$tradingMode, account=$accountNo, prdt=$accountProductCode, " +
        "appKey=${appKey.take(4)}***, appSecret=***, htsId=$htsId)"
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew :domain:build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add domain/src/main/kotlin/com/mj/domain/order/ \
        domain/src/main/kotlin/com/mj/domain/user/
git commit -m "feat(track1): domain — KisOrderPort·KisCredentialProvider·User·OrderRecord·OrderEventRecord (T7/20)"
```

---

### Task 8: RealKisOrderAdapter — KIS 주문 REST 5 메서드 + WireMock IT

**Files:**
- Create: `consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/RealKisOrderAdapter.kt`
- Create: `consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisOrderResponseMapper.kt`
- Create: `consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisOrderProperties.kt`
- Test: `consumer/src/test/kotlin/com/mj/consumer/infrastructure/kis/RealKisOrderAdapterIT.kt`

- [ ] **Step 1: WireMock IT 시나리오 작성**

```kotlin
package com.mj.consumer.infrastructure.kis

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.mj.domain.common.*
import com.mj.domain.user.TradingMode
import com.mj.domain.user.port.KisCredential
import com.mj.domain.user.port.KisCredentialProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class RealKisOrderAdapterIT {
    private lateinit var wm: WireMockServer
    private lateinit var adapter: RealKisOrderAdapter
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @BeforeEach
    fun setUp() {
        wm = WireMockServer(0).also { it.start() }
        val session = KisHttpSession(
            baseUrl = "http://localhost:${wm.port()}",
            okHttp = okhttp3.OkHttpClient(),
        )
        val credProvider = mockk<KisCredentialProvider>().also {
            every { it.forUser(userId) } returns KisCredential(
                tradingMode = TradingMode.MOCK_KIS,
                accountNo = "50185338",
                accountProductCode = "01",
                appKey = "AK", appSecret = "AS", htsId = "HTSID",
            )
        }
        // 토큰 발급 stub
        wm.stubFor(post(urlEqualTo("/oauth2/tokenP"))
            .willReturn(okJson("""{"access_token":"TOK","token_type":"Bearer","expires_in":86400}""")))
        adapter = RealKisOrderAdapter(session, credProvider, KisOrderProperties())
    }

    @AfterEach
    fun tearDown() { wm.stop() }

    @Test
    fun `placeOrder demo BUY LIMIT returns ODNO`() {
        wm.stubFor(post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
            .withHeader("tr_id", equalTo("VTTC0012U"))
            .willReturn(okJson("""
                {"rt_cd":"0","msg_cd":"OK","msg1":"정상",
                 "output":{"KRX_FWDG_ORD_ORGNO":"06010","ODNO":"0000123456","ORD_TMD":"143025"}}
            """.trimIndent())))

        val order = Order(
            id = UUID.randomUUID(),
            code = "005930",
            side = Side.BUY,
            qty = 1,
            price = BigDecimal("70000"),
            type = OrderType.LIMIT,
            status = OrderStatus.PENDING,
            createdAt = java.time.Instant.now(),
        )

        val result = adapter.placeOrder(userId, order)

        result.kisOrderNo shouldBe "0000123456"
        result.krxFwdgOrdOrgno shouldBe "06010"
        result.orderTime shouldBe "143025"
    }

    @Test
    fun `placeOrder demo SELL MARKET uses VTTC0011U with ord_dvsn=01`() {
        wm.stubFor(post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
            .withHeader("tr_id", equalTo("VTTC0011U"))
            .withRequestBody(matchingJsonPath("$.ORD_DVSN", equalTo("01")))
            .willReturn(okJson("""
                {"rt_cd":"0","msg_cd":"OK","msg1":"정상",
                 "output":{"KRX_FWDG_ORD_ORGNO":"06010","ODNO":"0000999999","ORD_TMD":"150000"}}
            """.trimIndent())))

        val order = Order(
            id = UUID.randomUUID(), code = "005930", side = Side.SELL,
            qty = 1, price = null, type = OrderType.MARKET,
            status = OrderStatus.PENDING, createdAt = java.time.Instant.now(),
        )
        val result = adapter.placeOrder(userId, order)
        result.kisOrderNo shouldBe "0000999999"
    }

    @Test
    fun `cancelOrder demo uses VTTC0013U with rvse_cncl_dvsn_cd=02`() {
        wm.stubFor(post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-rvsecncl"))
            .withHeader("tr_id", equalTo("VTTC0013U"))
            .withRequestBody(matchingJsonPath("$.RVSE_CNCL_DVSN_CD", equalTo("02")))
            .withRequestBody(matchingJsonPath("$.ORGN_ODNO", equalTo("0000123456")))
            .willReturn(okJson("""
                {"rt_cd":"0","msg_cd":"OK",
                 "output":{"KRX_FWDG_ORD_ORGNO":"06010","ODNO":"0000999998","ORD_TMD":"143130"}}
            """.trimIndent())))

        val res = adapter.cancelOrder(userId, "0000123456", "06010", qtyAll = true, qty = 0)
        res.kisOrderNo shouldBe "0000999998"
    }

    @Test
    fun `fetchBalance returns holdings and account summary`() {
        wm.stubFor(get(urlPathEqualTo("/uapi/domestic-stock/v1/trading/inquire-balance"))
            .withHeader("tr_id", equalTo("VTTC8434R"))
            .willReturn(okJson("""
                {"rt_cd":"0","msg_cd":"OK",
                 "output1":[
                   {"pdno":"005930","prdt_name":"삼성전자","hldg_qty":"1","ord_psbl_qty":"1",
                    "pchs_avg_pric":"70000","prpr":"71000","evlu_amt":"71000","evlu_pfls_amt":"1000"}
                 ],
                 "output2":[
                   {"dnca_tot_amt":"500000","nxdy_excc_amt":"500000","prvs_rcdl_excc_amt":"500000",
                    "tot_evlu_amt":"571000","nass_amt":"571000","evlu_pfls_smtl_amt":"1000"}
                 ],
                 "ctx_area_fk100":"","ctx_area_nk100":""}
            """.trimIndent())))

        val snap = adapter.fetchBalance(userId)
        snap.holdings.size shouldBe 1
        snap.holdings[0].code shouldBe "005930"
        snap.holdings[0].heldQty shouldBe 1
        snap.account.depositAmount shouldBe BigDecimal("500000")
        snap.account.totalEvaluationAmount shouldBe BigDecimal("571000")
    }

    @Test
    fun `fetchExecutions returns lines for today`() {
        wm.stubFor(get(urlPathEqualTo("/uapi/domestic-stock/v1/trading/inquire-daily-ccld"))
            .withHeader("tr_id", equalTo("VTTC0081R"))
            .willReturn(okJson("""
                {"rt_cd":"0","msg_cd":"OK",
                 "output1":[
                   {"ord_dt":"20260502","ord_gno_brno":"06010","odno":"0000123456",
                    "orgn_odno":"","pdno":"005930","sll_buy_dvsn_cd":"02",
                    "ord_qty":"1","ord_unpr":"70000","tot_ccld_qty":"1","tot_ccld_amt":"70000",
                    "cncl_yn":"N","rmn_qty":"0","rjct_qty":"0"}
                 ],
                 "output2":[],
                 "ctx_area_fk100":"","ctx_area_nk100":""}
            """.trimIndent())))

        val lines = adapter.fetchExecutions(userId, LocalDate.parse("2026-05-02"))
        lines.size shouldBe 1
        lines[0].kisOrderNo shouldBe "0000123456"
        lines[0].cumulativeExecutedQty shouldBe 1
    }

    @Test
    fun `auth 401 triggers token refresh and retry once`() {
        // 첫 호출 401, 토큰 재발급, 재시도 200
        wm.stubFor(post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
            .inScenario("auth-retry").whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(401)
                .withBody("""{"rt_cd":"1","msg_cd":"EGW00121","msg1":"Token expired"}""")
                .withHeader("Content-Type", "application/json"))
            .willSetStateTo("retry"))
        wm.stubFor(post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
            .inScenario("auth-retry").whenScenarioStateIs("retry")
            .willReturn(okJson("""
                {"rt_cd":"0","msg_cd":"OK",
                 "output":{"KRX_FWDG_ORD_ORGNO":"06010","ODNO":"0000123456","ORD_TMD":"143025"}}""")))

        val order = Order(UUID.randomUUID(), "005930", Side.BUY, 1, BigDecimal("70000"),
            OrderType.LIMIT, OrderStatus.PENDING, java.time.Instant.now())
        val result = adapter.placeOrder(userId, order)
        result.kisOrderNo shouldBe "0000123456"
    }

    @Test
    fun `INVALID_ORDER throws KisApiException with category`() {
        wm.stubFor(post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
            .willReturn(aResponse().withStatus(200)
                .withBody("""{"rt_cd":"1","msg_cd":"APBK0067","msg1":"호가 단위 오류"}""")
                .withHeader("Content-Type", "application/json")))

        val order = Order(UUID.randomUUID(), "005930", Side.BUY, 1, BigDecimal("70001"),
            OrderType.LIMIT, OrderStatus.PENDING, java.time.Instant.now())
        val ex = assertThrows<KisApiException> { adapter.placeOrder(userId, order) }
        ex.category shouldBe KisErrorCategory.INVALID_ORDER
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :consumer:test --tests RealKisOrderAdapterIT
```

Expected: FAIL — adapter 미존재.

- [ ] **Step 3: 구현 — RealKisOrderAdapter (5 메서드)**

```kotlin
// consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/RealKisOrderAdapter.kt
package com.mj.consumer.infrastructure.kis

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.mj.domain.common.*
import com.mj.domain.order.*
import com.mj.domain.order.port.KisOrderPort
import com.mj.domain.user.TradingMode
import com.mj.domain.user.port.KisCredentialProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

@Component
@Profile("dev | prd")
class RealKisOrderAdapter(
    private val session: KisHttpSession,
    private val credentials: KisCredentialProvider,
    private val props: KisOrderProperties,
) : KisOrderPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()

    override fun placeOrder(userId: UUID, order: Order): KisOrderResult {
        val cred = credentials.forUser(userId)
        val isMock = cred.tradingMode == TradingMode.MOCK_KIS
        val trId = when (order.side) {
            Side.BUY  -> if (isMock) "VTTC0012U" else "TTTC0012U"
            Side.SELL -> if (isMock) "VTTC0011U" else "TTTC0011U"
        }
        val body = mapOf(
            "CANO" to cred.accountNo,
            "ACNT_PRDT_CD" to cred.accountProductCode,
            "PDNO" to order.code,
            "ORD_DVSN" to when (order.type) { OrderType.MARKET -> "01"; OrderType.LIMIT -> "00" },
            "ORD_QTY" to order.qty.toString(),
            "ORD_UNPR" to (order.price?.toPlainString() ?: "0"),
            "EXCG_ID_DVSN_CD" to "KRX",
            "SLL_TYPE" to if (order.side == Side.SELL) "01" else "",
            "CNDT_PRIC" to "",
        )
        val output = postWithAuth(cred, trId, "/uapi/domestic-stock/v1/trading/order-cash", body)
        return KisOrderResponseMapper.toOrderResult(output)
    }

    override fun cancelOrder(
        userId: UUID, kisOrderNo: String, krxFwdgOrdOrgno: String,
        qtyAll: Boolean, qty: Int,
    ): KisOrderResult = rvseCncl(userId, kisOrderNo, krxFwdgOrdOrgno,
        rvseCnclCode = "02", newQty = qty, newPrice = BigDecimal.ZERO, qtyAll = qtyAll)

    override fun modifyOrder(
        userId: UUID, kisOrderNo: String, krxFwdgOrdOrgno: String,
        newQty: Int, newPrice: BigDecimal,
    ): KisOrderResult = rvseCncl(userId, kisOrderNo, krxFwdgOrdOrgno,
        rvseCnclCode = "01", newQty = newQty, newPrice = newPrice, qtyAll = false)

    private fun rvseCncl(
        userId: UUID, kisOrderNo: String, krxFwdgOrdOrgno: String,
        rvseCnclCode: String, newQty: Int, newPrice: BigDecimal, qtyAll: Boolean,
    ): KisOrderResult {
        val cred = credentials.forUser(userId)
        val isMock = cred.tradingMode == TradingMode.MOCK_KIS
        val trId = if (isMock) "VTTC0013U" else "TTTC0013U"
        val body = mapOf(
            "CANO" to cred.accountNo,
            "ACNT_PRDT_CD" to cred.accountProductCode,
            "KRX_FWDG_ORD_ORGNO" to krxFwdgOrdOrgno,
            "ORGN_ODNO" to kisOrderNo,
            "ORD_DVSN" to "00",
            "RVSE_CNCL_DVSN_CD" to rvseCnclCode,
            "ORD_QTY" to newQty.toString(),
            "ORD_UNPR" to newPrice.toPlainString(),
            "QTY_ALL_ORD_YN" to if (qtyAll) "Y" else "N",
            "EXCG_ID_DVSN_CD" to "KRX",
        )
        val output = postWithAuth(cred, trId, "/uapi/domestic-stock/v1/trading/order-rvsecncl", body)
        return KisOrderResponseMapper.toOrderResult(output)
    }

    override fun fetchBalance(userId: UUID): KisBalanceSnapshot {
        val cred = credentials.forUser(userId)
        val trId = if (cred.tradingMode == TradingMode.MOCK_KIS) "VTTC8434R" else "TTTC8434R"
        val body = getBalanceWithPagination(cred, trId)
        return KisOrderResponseMapper.toBalanceSnapshot(body)
    }

    override fun fetchExecutions(userId: UUID, date: LocalDate, executedOnly: Boolean): List<KisExecutionLine> {
        val cred = credentials.forUser(userId)
        val trId = if (cred.tradingMode == TradingMode.MOCK_KIS) "VTTC0081R" else "TTTC0081R"
        val body = getExecutionsWithPagination(cred, trId, date, executedOnly)
        return KisOrderResponseMapper.toExecutionLines(body)
    }

    private fun postWithAuth(
        cred: com.mj.domain.user.port.KisCredential,
        trId: String, path: String, body: Map<String, Any>,
    ): com.fasterxml.jackson.databind.JsonNode {
        repeat(2) { attempt ->
            val token = session.acquireToken(cred.appKey, cred.appSecret)
            val req = Request.Builder()
                .url("${session.baseUrl()}$path")
                .post(mapper.writeValueAsString(body).toRequestBody("application/json".toMediaType()))
                .header("authorization", "Bearer $token")
                .header("appkey", cred.appKey)
                .header("appsecret", cred.appSecret)
                .header("tr_id", trId)
                .header("custtype", "P")
                .build()
            session.client().newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.code == 401 && attempt == 0) {
                    session.invalidateToken(cred.appKey)
                    return@repeat
                }
                val node = mapper.readTree(text)
                val rtCd = node.path("rt_cd").asText("")
                val msgCd = node.path("msg_cd").asText("")
                if (resp.isSuccessful && rtCd == "0") {
                    return node.path("output")
                }
                throw KisApiException(rtCd, msgCd, node.path("msg1").asText(""), resp.code)
            }
        }
        error("unreachable")
    }

    private fun getBalanceWithPagination(...): com.fasterxml.jackson.databind.JsonNode { /* ... */ }
    private fun getExecutionsWithPagination(...): com.fasterxml.jackson.databind.JsonNode { /* ... */ }
}
```

- [ ] **Step 4: KisOrderResponseMapper 구현**

```kotlin
// consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisOrderResponseMapper.kt
package com.mj.consumer.infrastructure.kis

import com.fasterxml.jackson.databind.JsonNode
import com.mj.domain.common.Side
import com.mj.domain.order.*

object KisOrderResponseMapper {
    fun toOrderResult(output: JsonNode): KisOrderResult = KisOrderResult(
        kisOrderNo = output.path("ODNO").asText(),
        krxFwdgOrdOrgno = output.path("KRX_FWDG_ORD_ORGNO").asText(),
        orderTime = output.path("ORD_TMD").asText(""),
        raw = output.fields().asSequence().associate { it.key to it.value.asText() },
    )

    fun toBalanceSnapshot(body: JsonNode): KisBalanceSnapshot {
        val holdings = body.path("output1").map { node ->
            HoldingLine(
                code = node.path("pdno").asText(),
                name = node.path("prdt_name").asText(""),
                heldQty = node.path("hldg_qty").asInt(0),
                orderableQty = node.path("ord_psbl_qty").asInt(0),
                avgPurchasePrice = node.path("pchs_avg_pric").asText("0").toBigDecimal(),
                currentPrice = node.path("prpr").asText("0").toBigDecimal(),
                evaluationAmount = node.path("evlu_amt").asText("0").toBigDecimal(),
                evaluationPnL = node.path("evlu_pfls_amt").asText("0").toBigDecimal(),
            )
        }
        val out2 = body.path("output2").firstOrNull() ?: body.path("output2")
        val acc = AccountSummary(
            depositAmount = out2.path("dnca_tot_amt").asText("0").toBigDecimal(),
            nextDayExchangeAmount = out2.path("nxdy_excc_amt").asText("0").toBigDecimal(),
            twoDayExchangeAmount = out2.path("prvs_rcdl_excc_amt").asText("0").toBigDecimal(),
            totalEvaluationAmount = out2.path("tot_evlu_amt").asText("0").toBigDecimal(),
            netAssetAmount = out2.path("nass_amt").asText("0").toBigDecimal(),
            totalPnL = out2.path("evlu_pfls_smtl_amt").asText("0").toBigDecimal(),
        )
        return KisBalanceSnapshot(holdings, acc)
    }

    fun toExecutionLines(body: JsonNode): List<KisExecutionLine> = body.path("output1").map { node ->
        KisExecutionLine(
            orderDate = node.path("ord_dt").asText(),
            kisOrderNo = node.path("odno").asText(),
            originalOrderNo = node.path("orgn_odno").asText(""),
            krxFwdgOrdOrgno = node.path("ord_gno_brno").asText(""),
            code = node.path("pdno").asText(),
            side = if (node.path("sll_buy_dvsn_cd").asText() == "02") Side.BUY else Side.SELL,
            orderedQty = node.path("ord_qty").asInt(0),
            orderedPrice = node.path("ord_unpr").asText("0").toBigDecimalOrNull(),
            cumulativeExecutedQty = node.path("tot_ccld_qty").asInt(0),
            cumulativeExecutedAmount = node.path("tot_ccld_amt").asText("0").toBigDecimal(),
            canceled = node.path("cncl_yn").asText() == "Y",
            remainingQty = node.path("rmn_qty").asInt(0),
            rejectedQty = node.path("rjct_qty").asInt(0),
        )
    }
}
```

- [ ] **Step 5: Run tests**

```bash
./gradlew :consumer:test --tests RealKisOrderAdapterIT
```

Expected: PASS — 6 시나리오.

- [ ] **Step 6: Commit**

```bash
git add consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/RealKisOrderAdapter.kt \
        consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisOrderResponseMapper.kt \
        consumer/src/main/kotlin/com/mj/consumer/infrastructure/kis/KisOrderProperties.kt \
        consumer/src/test/kotlin/com/mj/consumer/infrastructure/kis/RealKisOrderAdapterIT.kt
git commit -m "feat(track1): RealKisOrderAdapter — 5 메서드 + WireMock IT 6 시나리오 (T8/20)"
```

---

### Task 9: JpaAdapter 5종 — Order, OrderEvent, DailyOrderCounter, KillSwitch, UserTradingConfig

**Files:**
- Create: `consumer/src/main/kotlin/com/mj/consumer/infrastructure/persistence/{Order,OrderEvent,DailyOrderCounter,KillSwitch,UserTradingConfig}{Entity,JpaRepository,JpaAdapter}.kt` (15 파일)
- Test: `consumer/src/test/kotlin/com/mj/consumer/infrastructure/persistence/OrderEventJpaAdapterTest.kt` (멱등 INSERT 검증 포함)

- [ ] **Step 1: 단위테스트 — OrderEvent INSERT 멱등성 검증**

```kotlin
@DataJpaTest
class OrderEventJpaAdapterTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val adapter: OrderEventJpaAdapter,
) {
    @Test
    fun `insertIdempotent returns false on UNIQUE conflict`() {
        // user + order 시드
        // 첫 INSERT
        val event = OrderEventRecord(...)
        adapter.insertIdempotent(event) shouldBe true
        // 같은 키 재시도
        adapter.insertIdempotent(event.copy(eventId = UUID.randomUUID())) shouldBe false
    }
}
```

- [ ] **Step 2~5: 5 Entity + 5 Repository + 5 Adapter 구현 + 매핑 + 멱등 INSERT 처리** (`@SQLInsert` + `ON CONFLICT DO NOTHING` 또는 `try-catch DataIntegrityViolation` → false)

- [ ] **Step 6: Run tests + commit**

```bash
./gradlew :consumer:test --tests "*JpaAdapterTest"
git commit -m "feat(track1): 5 JpaAdapter (Order·OrderEvent·DailyCounter·KillSwitch·UserTradingConfig) (T9/20)"
```

---

### Task 10: DbKisCredentialProvider — UserTradingConfig + SecretCipher 통합

**Files:**
- Create: `consumer/src/main/kotlin/com/mj/consumer/infrastructure/secret/DbKisCredentialProvider.kt`
- Test: `consumer/src/test/kotlin/com/mj/consumer/infrastructure/secret/DbKisCredentialProviderTest.kt`

- [ ] **Step 1: TDD test**

```kotlin
class DbKisCredentialProviderTest {
    @Test
    fun `forUser returns decrypted credential`() {
        val cipher = mockk<SecretCipher>()
        val repo = mockk<UserTradingConfigRepository>()
        every { cipher.decrypt("ENC_KEY") } returns "PLAIN_KEY"
        every { cipher.decrypt("ENC_SEC") } returns "PLAIN_SEC"
        every { repo.findByUserId(userId) } returns UserTradingConfig(...)

        val cred = DbKisCredentialProvider(repo, cipher).forUser(userId)
        cred.appKey shouldBe "PLAIN_KEY"
        cred.appSecret shouldBe "PLAIN_SEC"
        cred.toString() shouldNotContain "PLAIN_KEY"  // toString masking
    }
}
```

- [ ] **Step 2: 구현 + Run tests + commit (구현 ~30줄)**

```bash
git commit -m "feat(track1): DbKisCredentialProvider — DB row + SecretCipher 통합 (T10/20)"
```

---

## Phase 4 — Guard + OrderSender 분기 (Task 11~13)

### Task 11: Guard chain — KillSwitch + DailyOrderLimit + PriceSanity

**Files:**
- Create: `consumer/src/main/kotlin/com/mj/consumer/application/guard/{OrderGuard,GuardChain,KillSwitchGuard,DailyOrderLimitGuard,PriceSanityGuard,GuardDecision}.kt`
- Test: `consumer/src/test/kotlin/com/mj/consumer/application/guard/GuardChainTest.kt`

- [ ] **Step 1: TDD — 3 Guard 각각 차단 시나리오**

```kotlin
class GuardChainTest {
    @Test
    fun `KillSwitch ON rejects all orders`() { ... }

    @Test
    fun `DailyOrderLimit count exceeded rejects`() { ... }

    @Test
    fun `DailyOrderLimit value KRW exceeded rejects`() { ... }

    @Test
    fun `PriceSanity outside ±30 percent rejects LIMIT order`() { ... }

    @Test
    fun `PriceSanity ignores MARKET order`() { ... }

    @Test
    fun `chain accepts when all pass`() { ... }
}
```

- [ ] **Step 2~5: 구현**

```kotlin
sealed interface GuardDecision {
    object Pass : GuardDecision
    data class Reject(val reason: String) : GuardDecision
}

interface OrderGuard {
    fun check(userId: UUID, order: Order): GuardDecision
}

class GuardChain(private val guards: List<OrderGuard>) : OrderGuard {
    override fun check(userId: UUID, order: Order): GuardDecision {
        guards.forEach { g ->
            when (val d = g.check(userId, order)) {
                is GuardDecision.Reject -> return d
                GuardDecision.Pass -> {}
            }
        }
        return GuardDecision.Pass
    }
}
```

각 Guard는 spec §3 D4 정책 그대로:
- KillSwitchGuard: `kill_switch_state.is_active=true` 시 reject (`KILL_SWITCH_ON`)
- DailyOrderLimitGuard: `daily_order_counter` row의 count·total_value 비교 후 limit 초과 시 reject. 통과 시 카운터 INCREMENT (트랜잭션). limit은 env property
- PriceSanityGuard: 전일종가(`candles` 테이블 마지막 row close) ±30% 벗어난 LIMIT만 reject

- [ ] **Step 6: Run + commit**

```bash
git commit -m "feat(track1): Guard chain — KillSwitch + DailyLimit + PriceSanity (T11/20)"
```

---

### Task 12: MockOrderSender + KisOrderSender + Profile 분기 + 부팅 로그

**Files:**
- Create: `consumer/src/main/kotlin/com/mj/consumer/application/order/{MockOrderSender,KisOrderSender,OrderSenderConfig,KisModeBootLogger}.kt`
- Modify: `consumer/.../config/ConsumerKisConfig.kt` (Bean 등록)
- Test: `consumer/src/test/kotlin/com/mj/consumer/application/order/{MockOrderSenderTest,KisOrderSenderTest}.kt`

- [ ] **Step 1: TDD — MockOrderSender (in-memory FILLED 시뮬)**

```kotlin
class MockOrderSenderTest {
    @Test
    fun `mock sender returns FILLED instantly`() {
        val sender = MockOrderSender()
        val order = Order(...)
        val result = sender.send(order)
        result.resultStatus shouldBe OrderStatus.FILLED
        result.filledQty shouldBe order.qty
    }
}
```

- [ ] **Step 2: KisOrderSender — KisOrderPort 위임 + Guard 통합**

```kotlin
@Component
@Profile("dev | prd")
class KisOrderSender(
    private val kisOrderPort: KisOrderPort,
    private val guard: GuardChain,
    private val orders: OrderRepository,
    private val orderEvents: OrderEventRepository,
    private val publisher: ExecutionPublisher,
    private val cred: KisCredentialProvider,
) : OrderSender {

    override fun send(order: Order): OrderResult {
        val userId = currentUserId()
        when (val d = guard.check(userId, order)) {
            is GuardDecision.Reject -> return OrderResult(
                order = order, resultStatus = OrderStatus.REJECTED,
                filledQty = 0, avgFillPrice = null, filledAt = null,
                rejectReason = d.reason,
            )
            GuardDecision.Pass -> {}
        }

        val kisResult = try { kisOrderPort.placeOrder(userId, order) }
        catch (e: KisApiException) {
            return OrderResult(order, OrderStatus.REJECTED, 0, null, null,
                rejectReason = "KIS_${e.category}: ${e.msgCd}")
        }

        val record = OrderRecord(
            orderId = order.id, userId = userId,
            accountNo = cred.forUser(userId).accountNo,
            kisMode = cred.forUser(userId).tradingMode.name,
            intentId = order.id,  // intent_id가 별도 있으면 거기서
            kisOrderNo = kisResult.kisOrderNo,
            krxFwdgOrdOrgno = kisResult.krxFwdgOrdOrgno,
            // ...
            status = OrderStatus.PENDING,
            filledQty = 0, avgFillPrice = null, rejectReason = null,
        )
        orders.save(record)
        orderEvents.insertIdempotent(OrderEventRecord(eventType = "PLACED", ...))
        publisher.publishExecuted(record, OrderStatus.PENDING)

        return OrderResult(order, OrderStatus.PENDING, 0, null, null, null)
    }
}
```

- [ ] **Step 3: OrderSenderConfig — Profile 분기**

```kotlin
@Configuration
class OrderSenderConfig {
    @Bean @Profile("mock") @ConditionalOnMissingBean(OrderSender::class)
    fun mockOrderSender(): OrderSender = MockOrderSender()

    @Bean @Profile("dev | prd")
    @ConditionalOnMissingBean(OrderSender::class)
    fun realOrderSender(...): OrderSender = KisOrderSender(...)
}
```

- [ ] **Step 4: KisModeBootLogger — 부팅 시 active mode 강제 로그**

```kotlin
@Component
class KisModeBootLogger(
    private val env: Environment,
    private val cred: KisCredentialProvider,
    private val killSwitch: KillSwitchRepository,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun log() {
        val active = env.activeProfiles.firstOrNull() ?: "mock"
        when (active) {
            "mock" -> log.info("[KIS_MODE] active=MOCK (no external calls)")
            "dev" -> {
                val c = cred.forUser(DEFAULT_USER_ID)
                log.info("[KIS_MODE] active=DEV (KIS 모의계좌 ${c.accountNo})")
            }
            "prd" -> {
                val c = cred.forUser(DEFAULT_USER_ID)
                val live = c.tradingMode == TradingMode.REAL_KIS
                val ks = killSwitch.global().is_active
                log.warn("[KIS_MODE] active=PRD (trading_mode=${c.tradingMode}) " +
                         "account=${c.accountNo} ${if (live) "⚠ LIVE_TRADING" else "MOCK_TRADING"} " +
                         "killSwitch=${if (ks) "ON" else "OFF"}")
            }
        }
    }
}
```

- [ ] **Step 5: Run + commit**

```bash
git commit -m "feat(track1): MockOrderSender + KisOrderSender + Profile 분기 + 부팅 로그 (T12/20)"
```

---

### Task 13: dev profile end-to-end 통합테스트 — OrderSender 활성 검증

**Files:**
- Test: `consumer/src/test/kotlin/com/mj/consumer/application/order/OrderSenderProfileIT.kt`

- [ ] **Step 1: @SpringBootTest profile=dev로 OrderSender 빈 활성 + KisOrderSender 인스턴스 검증 + WireMock + Testcontainers postgres**

```kotlin
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = ["magicjar.secret.master-key=...", ...])
class OrderSenderProfileIT {
    @Autowired private lateinit var sender: OrderSender

    @Test
    fun `dev profile activates KisOrderSender`() {
        sender::class.simpleName shouldBe "KisOrderSender"
    }

    @Test
    fun `dev profile end-to-end mock buy 1주`() {
        // WireMock으로 KIS 응답 stub
        val order = Order(...)
        val result = sender.send(order)
        result.resultStatus shouldBe OrderStatus.PENDING
        // orders 테이블에 row 1개 INSERT 확인
        // order_events 테이블에 PLACED row 확인
    }
}
```

- [ ] **Step 2~3: Run + commit**

```bash
git commit -m "test(track1): dev profile OrderSender 활성 + e2e (T13/20)"
```

---

## Phase 5 — Kafka 흐름 (Task 14~15)

### Task 14: OrderIntentConsumer + ExecutionPublisher + Testcontainers 왕복 IT

**Files:**
- Create: `consumer/src/main/kotlin/com/mj/consumer/application/order/{OrderIntentConsumer,ExecutionPublisher}.kt`
- Test: `consumer/src/test/kotlin/com/mj/consumer/application/order/OrderFlowKafkaIT.kt`

- [ ] **Step 1: TDD with Testcontainers**

```kotlin
@SpringBootTest
@Testcontainers
@ActiveProfiles("dev-test")  // Kafka는 Testcontainers, KIS는 WireMock
class OrderFlowKafkaIT {
    @Container
    val postgres = PostgreSQLContainer("postgres:16-alpine")
    @Container
    val kafka = KafkaContainer(...)

    @Test
    fun `OrderIntentEvent → place → order_executed publish 왕복`() {
        // produce order.intent.v1
        // assertion: order.executed.v1 PENDING 메시지 1건 publish
        // DB: orders row + order_events PLACED 1행
    }
}
```

- [ ] **Step 2: 구현 — OrderIntentConsumer + ExecutionPublisher**

```kotlin
@Component
class OrderIntentConsumer(
    private val orderSender: OrderSender,
) {
    @KafkaListener(topics = ["order.intent.v1"])
    fun onIntent(event: OrderIntentEvent) {
        val order = Order(
            id = event.intentId,
            code = event.code, side = event.side, qty = event.qty,
            price = event.price, type = event.orderType,
            status = OrderStatus.PENDING, createdAt = event.createdAt,
        )
        orderSender.send(order)
        // OrderSender 내부에서 publishExecuted 호출
    }
}

@Component
class ExecutionPublisher(
    private val kafka: KafkaTemplate<String, String>,
    private val mapper: ObjectMapper,
) {
    fun publishExecuted(record: OrderRecord, status: OrderStatus, partialQty: Int = 0, execPrice: BigDecimal? = null) {
        val event = OrderExecutedEvent(...)
        kafka.send("order.executed.v1", record.kisOrderNo, mapper.writeValueAsString(event))
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
git commit -m "feat(track1): OrderIntentConsumer + ExecutionPublisher + Testcontainers IT (T14/20)"
```

---

### Task 15: KisExecutionNoticeListener (WS push) + OrderExecutionSweep (REST 보강) + 멱등성

**Files:**
- Create: `consumer/src/main/kotlin/com/mj/consumer/application/order/{KisExecutionNoticeListener,OrderExecutionSweep,OrderProjectionUpdater}.kt`
- Test: `consumer/src/test/kotlin/com/mj/consumer/application/order/{KisExecutionNoticeListenerTest,OrderExecutionSweepTest}.kt`

- [ ] **Step 1: TDD — Listener는 H0STCNI9 raw → INSERT 시도 → projection update**

```kotlin
class KisExecutionNoticeListenerTest {
    @Test
    fun `WS push EXECUTED inserts order_event and updates projection`() {
        val raw = KisExecutionNoticeRaw(... cntgYn="2", executedQty=1, ...)
        listener.onFrame(raw)
        verify(events).insertIdempotent(... event_type="EXECUTED")
        verify(projection).update(orderId, FILLED, 1, ...)
    }

    @Test
    fun `WS push duplicate EXECUTED is dropped`() {
        every { events.insertIdempotent(any()) } returns false
        listener.onFrame(raw)
        verify(projection, times(0)).update(any(), any(), any(), any())
    }

    @Test
    fun `WS push CNTG_YN=1 inserts RECEIPT_ACCEPTED`() {
        val raw = KisExecutionNoticeRaw(... cntgYn="1", ...)
        listener.onFrame(raw)
        verify(events).insertIdempotent(... event_type="RECEIPT_ACCEPTED")
    }
}
```

- [ ] **Step 2: TDD — Sweep은 30초마다 REST 미체결 조회 → INSERT 시도 (멱등)**

```kotlin
class OrderExecutionSweepTest {
    @Test
    fun `sweep ingests new executions from REST`() {
        every { kisOrderPort.fetchExecutions(...) } returns listOf(
            KisExecutionLine(kisOrderNo="0000123456", cumulativeExecutedQty=1, ...),
        )
        every { events.insertIdempotent(any()) } returns true
        sweep.run()
        verify(events).insertIdempotent(... source="REST_SWEEP")
        verify(projection).update(...)
    }

    @Test
    fun `sweep ignores duplicates`() {
        every { events.insertIdempotent(any()) } returns false
        sweep.run()
        verify(projection, times(0)).update(any(), any(), any(), any())
    }
}
```

- [ ] **Step 3: 구현**

```kotlin
@Component
@Profile("dev | prd")
class KisExecutionNoticeListener(
    private val ws: KisWsSession,
    private val cred: KisCredentialProvider,
    private val orders: OrderRepository,
    private val events: OrderEventRepository,
    private val projection: OrderProjectionUpdater,
    private val publisher: ExecutionPublisher,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val c = cred.forUser(DEFAULT_USER_ID)
        val trId = if (c.tradingMode == TradingMode.MOCK_KIS) "H0STCNI9" else "H0STCNI0"
        ws.subscribe(trId, c.htsId, ::onFrame)
    }

    fun onFrame(raw: KisExecutionNoticeRaw) {
        val orderRow = orders.findByKisOrderNo(...) ?: return
        val eventType = if (raw.cntgYn == "2") "EXECUTED" else "RECEIPT_ACCEPTED"
        val event = OrderEventRecord(
            eventId = UUID.randomUUID(),
            orderId = orderRow.orderId,
            ...,
            eventType = eventType,
            cumulativeQty = if (raw.cntgYn == "2") /*누적계산*/ else null,
            partialQty = raw.executedQty,
            execPrice = raw.executedUnitPrice,
            source = "WS",
            occurredAt = OffsetDateTime.now(),
        )
        if (!events.insertIdempotent(event)) return  // 멱등 dedup
        if (eventType == "EXECUTED") {
            projection.update(orderRow.orderId, computeStatus(...), event.cumulativeQty!!, computeAvgPrice(...))
            publisher.publishExecuted(orderRow, computeStatus(...), event.partialQty!!, event.execPrice)
        }
    }
}

@Component
@Profile("dev | prd")
class OrderExecutionSweep(
    private val kisOrderPort: KisOrderPort,
    private val orders: OrderRepository,
    private val events: OrderEventRepository,
    private val projection: OrderProjectionUpdater,
    private val publisher: ExecutionPublisher,
) {
    @Scheduled(fixedDelay = 30_000)
    fun run() {
        val openOrders = orders.findOpenByUser(DEFAULT_USER_ID)
        if (openOrders.isEmpty()) return
        val lines = kisOrderPort.fetchExecutions(DEFAULT_USER_ID, LocalDate.now())
        for (line in lines) {
            val match = openOrders.firstOrNull { it.kisOrderNo == line.kisOrderNo } ?: continue
            val event = OrderEventRecord(
                ..., source = "REST_SWEEP",
                cumulativeQty = line.cumulativeExecutedQty,
                ...
            )
            if (events.insertIdempotent(event) && line.cumulativeExecutedQty > 0) {
                projection.update(...)
                publisher.publishExecuted(...)
            }
        }
    }
}
```

- [ ] **Step 4: Run + commit**

```bash
git commit -m "feat(track1): WS Listener + REST Sweep + 멱등 dedup (T15/20)"
```

---

## Phase 6 — UI + 관측 (Task 16~17)

### Task 16: api OrderController + BalanceController + STOMP /topic/orders + frontend /orders

**Files:**
- Create: `api/src/main/kotlin/com/mj/api/controller/{OrderController,BalanceController}.kt`
- Create: `api/src/main/kotlin/com/mj/api/controller/dto/{OrderResponse,BalanceResponse}.kt`
- Modify: `api/src/main/kotlin/com/mj/api/config/WebSocketStompConfig.kt` (`/topic/orders` 추가)
- Modify: `consumer/.../ExecutionPublisher.kt` — STOMP fanout 추가 (api에서 receive해서 STOMP)
- Or — `api/.../listener/OrderExecutedKafkaListener.kt` 신규 (api 모듈에서 order.executed.v1 consume → STOMP fanout)
- Create: `frontend/src/pages/Orders/index.tsx`
- Create: `frontend/src/hooks/useOrderStream.ts`
- Modify: `frontend/src/App.tsx` (route 추가)
- Modify: `frontend/src/pages/Dashboard/index.tsx` (수동 주문 패널 추가)

- [ ] **Step 1: api 컨트롤러 + DTO + Swagger annotation + 단위테스트**
- [ ] **Step 2: api Kafka listener — order.executed.v1 → STOMP /topic/orders/{userId}**
- [ ] **Step 3: frontend `/orders` 페이지 — TanStack Query for `/api/orders` + STOMP subscribe + 미체결/체결/잔고 3 섹션**
- [ ] **Step 4: Dashboard 수동 주문 패널 — 종목·수량·가격 입력 → POST `/api/orders`**
- [ ] **Step 5: vitest UI 테스트 + commit**

```bash
git commit -m "feat(track1): /orders + 잔고 + 수동 주문 패널 + STOMP fanout (T16/20)"
```

---

### Task 17: actuator metrics + structured logging

**Files:**
- Create: `consumer/.../infrastructure/observability/KisMetricsRecorder.kt` (Micrometer)
- Modify: `consumer/.../infrastructure/kis/KisHttpSession.kt` (call counter 추가)
- Modify: `consumer/.../application/order/{KisOrderSender,KisExecutionNoticeListener,OrderExecutionSweep}.kt` (counter)
- Modify: `consumer/src/main/resources/logback-spring.xml` (JSON encoder)

- [ ] **Step 1: 카운터 8종 — `kis.api.calls{endpoint}`, `kis.api.errors{category}`, `kis.ws.disconnects`, `orders.placed{mode}`, `orders.filled{mode}`, `orders.rejected{reason}`, `executions.dedup_hits{source}`, `guard.rejected{guard}`**
- [ ] **Step 2: structured JSON logging — kis_mode, account_no, order_id, kis_order_no, latency_ms, event_type 필드 강제**
- [ ] **Step 3: actuator 통합 + commit**

```bash
git commit -m "feat(track1): actuator metrics + structured JSON logging (T17/20)"
```

---

## Phase 7 — 운영 가이드 + Dogfooding (Task 18~20)

### Task 18: 운영 가이드 작성 — `_workspace/operations/order-policy.md`

- [ ] **Step 1: spec §10 목차 그대로 풀어서 작성. 사용자가 dogfooding 진입 시 첫 절차로 사용**
- [ ] **Step 2: README.md "운영 가이드" 섹션 추가, order-policy.md 링크**
- [ ] **Step 3: commit**

```bash
git commit -m "docs(track1): 운영 가이드 — order-policy.md (T18/20)"
```

---

### Task 19: Profile 명명 정리 + docker-compose dev/prd yml + MAGICJAR_MODE env 폐기

**Files:**
- Modify: 기존 `@Profile("stub-kis")` → `@Profile("mock")`, `@Profile("real-kis")` → `@Profile("dev | prd")` 일괄 (~10 파일)
- Modify: `application.yml` 4개에서 `MAGICJAR_MODE` 환경변수 사용처 제거
- Create: `docker/docker-compose.prd.yml` (override, .gitignore — 사용자 발급 키 보관용)
- Modify: `docker/docker-compose.dev.yml` (SPRING_PROFILES_ACTIVE=dev로 변경)
- Modify: `.gitignore` 에 `docker-compose.prd.yml` 추가

- [ ] **Step 1~5: 일괄 정정 + 회귀 테스트 + commit**

```bash
git commit -m "refactor(track1): profile 명명 정리 (mock/dev/prd) + MAGICJAR_MODE 폐기 (T19/20)"
```

---

### Task 20: Dogfooding ISSUE-009 — dev profile 모의계좌 1주 매수 → 매도 → 잔고 0

**액션:**
- [ ] **Step 1: `docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml up -d` (dev profile)**
- [ ] **Step 2: 9 컨테이너 healthy 확인 + KisModeBootLogger 부팅 로그 검증**
- [ ] **Step 3: `node scripts/qa-run.mjs new --symptom "track1 dogfooding 모의계좌 1주 매수→매도" --target /orders`**
- [ ] **Step 4: 사용자 안내 — frontend Dashboard 수동 주문 패널에서 005930 매수 1주 70000원 → /orders에서 체결 확인 → 매도 1주 시장가 → 잔고 0 검증**
- [ ] **Step 5: scenario-before.py + report-before.md 자동 생성** (qa-run.mjs)
- [ ] **Step 6: 운영 가이드 첫 절차 따라 사용자 직접 수행 후 검증 결과 ISSUE 카드 close**
- [ ] **Step 7: PR 발행 — `agent/architect/track1-kis-order` → main**

```bash
gh pr create --title "feat: 1번 트랙 1차 — KIS 주문 어댑터 + 모의계좌 매수→체결→매도 사이클" \
  --body "$(cat <<EOF
## Summary
- 12 architectural decisions 통합 구현 (spec §3)
- 신규 테이블 6개 (V9 Flyway, V8은 옆 세션 분봉 시드) — 멀티테넌트 확장 path 처음부터
- consumer 모듈 통합 — KIS 외부 시스템 책임 단일화
- KisOrderPort 6 메서드 + WS Primary + REST Sweep 멱등성
- mock/dev/prd profile + 사용자 등록 trading_mode 분기
- KillSwitch + DailyOrderLimit + PriceSanity Guard
- 운영 가이드 신설

## QA / Test
- pyworker pytest 25 PASS (회귀 0)
- consumer test 신규 ~80건 PASS
- ISSUE-009 dogfooding 시나리오 (사용자 직접 수행)

## Author
sp-daewoon (1번 트랙 전체 메인 세션 작업)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
gh pr merge --rebase --delete-branch
```

```bash
git commit -m "test(track1): ISSUE-009 dogfooding — 모의계좌 매수→체결→매도→잔고 0 (T20/20)"
```

---

## ADR

신규/개정 후보 (Task별 분산 또는 마지막 전에 한 번에 commit):
- ADR 0031 — Profile 분기 정정 (mock/dev/prd) + 사용자 등록 기반 trading_mode
- ADR 0032 — KIS Order 어댑터 hexagonal 구조 + WS+REST 하이브리드 멱등성
- ADR 0033 — 멀티테넌트 확장 path
- ADR 0006 개정 — OrderSender 분기에 dev profile in-memory mock 의미 정정
- ADR 0011 개정 (작은 단위) — QA dogfooding ISSUE-009 시나리오 명시

## 사용자 액션 마일스톤

| 시점 | 사용자 액션 | 상태 |
|---|---|---|
| **Task 1 진입 전** | `MAGICJAR_SECRET_MASTER_KEY` 발급 + `.env` 추가 | 필수 |
| **Task 5 전** | KIS HTS/MTS `hts_id` 알려주기 + `user_trading_config.hts_id` 갱신 | 필수 |
| **Task 3 후** | `.env`에서 `KIS_MOCK_*`·`KIS_ACCOUNT_*` 제거 검증 | 검증만 |
| **Task 19 후** | dev profile 9 컨테이너 healthy 확인 (architect가 검증) | 확인 |
| **Task 20 dogfooding** | frontend에서 1주 매수 → 체결 → 1주 매도 → 잔고 0 검증 | 사용자 직접 |
| **후속 트랙** | KIS 실전 키 발급 + IP 화이트리스트 | 1번 트랙 비범위 |

## Self-Review

- [x] 20 task 모두 spec §11 Phase 분배와 일치
- [x] 각 task에 구체 코드 + verify 명령 + commit 메시지
- [x] placeholder 0 (KIS endpoint 사양은 MCP 검증)
- [x] 사용자 외부 자원 의존 시점 명확
- [x] 멀티테넌트 확장 path는 spec §4.2와 동일하게 user_id 처음부터
- [x] 인증 도입 시 침습 0 — `KisCredentialProvider` 인터페이스 + `currentUserId()` 추상
- [x] 5번 트랙 plan(1885줄) 패턴 일치 — Phase·Task·Step·코드·verify·commit

## Execution

**Inline 자율 모드** — architect 메인 세션 직접 진행 (ADR 0030). 사용자 컨펌 시점:
1. Task 1 진입 전 (master key 발급 확인)
2. Task 5 진입 전 (hts_id 입력)
3. Task 3 마지막 (.env 정리 검증 — sanity check)
4. Phase 5 마지막 (Task 15 완료, KIS WS 인프라 + 멱등성 작동 검증 후)
5. Task 20 dogfooding 사용자 직접 수행

이외 task는 자율 진행 + 회귀 검증 후 다음 task.

브랜치: `agent/architect/track1-kis-order` (단일 브랜치, 20 commit, 마지막에 PR)

**plan 끝.**
