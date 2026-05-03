package com.mj.api.application.auth

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import com.mj.domain.secret.SecretCipher
import com.mj.domain.user.TradingMode
import com.mj.domain.user.UserTradingConfig
import com.mj.domain.user.port.UserTradingConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

/**
 * KIS 인증(자격증명 등록·조회·모드 전환) 애플리케이션 서비스.
 *
 * spec §6.2, §8.2, ADR 0043.
 *
 * - 비밀키(appKey·appSecret)는 AES-256-GCM 암호화 후 user_trading_config에 저장.
 * - 응답·로그에는 절대 secret 평문을 포함하지 않는다.
 * - 멀티테넌트: 모든 메서드에 userId 명시.
 */
@Service
class AuthService(
    private val configRepo: UserTradingConfigRepository,
    private val cipher: SecretCipher,
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)

    /** 인증 상태 조회. */
    fun status(userId: UUID): AuthStatusResult {
        val cfg = configRepo.findByUserId(userId)
        if (cfg == null) {
            return AuthStatusResult(
                authenticated = false,
                mode = null,
                accountNo = null,
                hasAppKey = false,
                hasAppSecret = false,
                lastUpdatedAt = null,
            )
        }
        return AuthStatusResult(
            authenticated = cfg.kisAppKeyEncrypted.isNotBlank() && cfg.kisAppSecretEncrypted.isNotBlank(),
            mode = cfg.tradingMode.toApiMode(),
            accountNo = cfg.accountNo,
            hasAppKey = cfg.kisAppKeyEncrypted.isNotBlank(),
            hasAppSecret = cfg.kisAppSecretEncrypted.isNotBlank(),
            lastUpdatedAt = cfg.updatedAt,
        )
    }

    /**
     * 자격증명 저장 + 모드 설정 (로그인).
     *
     * 기존 config row가 있으면 upsert. 없으면 신규 생성.
     * appKey·appSecret은 AES-256-GCM 암호화 후 저장.
     */
    fun login(
        userId: UUID,
        request: LoginRequest,
    ): LoginResult {
        // secret 마스킹 로그
        log.info(
            "[auth-login] userId={} mode={} accountNo={} appKey={}*** accountProductCode={}",
            userId,
            request.mode,
            request.accountNo,
            request.appKey.take(4),
            request.accountProductCode,
        )

        val tradingMode = request.mode.toDomain()
        val encryptedKey = cipher.encrypt(request.appKey)
        val encryptedSecret = cipher.encrypt(request.appSecret)
        val now = OffsetDateTime.now()

        val existing = configRepo.findByUserId(userId)
        val config =
            if (existing != null) {
                existing.copy(
                    tradingMode = tradingMode,
                    accountNo = request.accountNo,
                    accountProductCode = request.accountProductCode,
                    kisAppKeyEncrypted = encryptedKey,
                    kisAppSecretEncrypted = encryptedSecret,
                    htsId = request.htsId ?: existing.htsId,
                    updatedAt = now,
                )
            } else {
                UserTradingConfig(
                    userId = userId,
                    tradingMode = tradingMode,
                    accountNo = request.accountNo,
                    accountProductCode = request.accountProductCode,
                    kisAppKeyEncrypted = encryptedKey,
                    kisAppSecretEncrypted = encryptedSecret,
                    htsId = request.htsId ?: "",
                    createdAt = now,
                    updatedAt = now,
                )
            }

        configRepo.save(config)
        log.info("[auth-login] saved userId={} mode={}", userId, tradingMode)

        return LoginResult(
            authenticated = true,
            mode = request.mode,
        )
    }

    /**
     * 모드 전환 (vps ↔ prod).
     *
     * 자격증명이 없는 경우(자격증명 미설정 상태) reject.
     */
    fun switchMode(
        userId: UUID,
        request: ModeChangeRequest,
    ): ModeChangeResult {
        val cfg =
            configRepo.findByUserId(userId)
                ?: return ModeChangeResult(success = false, reason = "자격증명이 설정되지 않았습니다. 먼저 /api/auth/login을 호출하세요.")

        if (cfg.kisAppKeyEncrypted.isBlank() || cfg.kisAppSecretEncrypted.isBlank()) {
            return ModeChangeResult(success = false, reason = "appKey·appSecret이 설정되지 않았습니다. 먼저 /api/auth/login을 호출하세요.")
        }

        val newMode = request.mode.toDomain()
        val updated = cfg.copy(tradingMode = newMode, updatedAt = OffsetDateTime.now())
        configRepo.save(updated)

        log.info("[auth-mode] userId={} mode changed → {}", userId, newMode)
        return ModeChangeResult(success = true, mode = request.mode)
    }
}

// ─── DTOs (application layer) ─────────────────────────────────────────────────

/**
 * API 레벨 모드 (frontend 친화적).
 *
 * @JsonValue/@JsonCreator를 통해 JSON 직렬화 시 소문자 "vps"/"prod"로 직렬화.
 * enum entry 자체는 ktlint 요구에 따라 UpperCamelCase 사용.
 */
enum class KisMode(
    @get:JsonValue val value: String,
) {
    Vps("vps"),
    Prod("prod"),
    ;

    companion object {
        @JsonCreator
        @JvmStatic
        fun fromValue(value: String): KisMode =
            entries.find { it.value == value.lowercase() }
                ?: throw IllegalArgumentException("Unknown KisMode: $value. Allowed: vps, prod")
    }
}

data class LoginRequest(
    val mode: KisMode,
    val appKey: String,
    val appSecret: String,
    val accountNo: String,
    val accountProductCode: String = "01",
    val htsId: String? = null,
)

data class LoginResult(
    val authenticated: Boolean,
    val mode: KisMode,
)

data class ModeChangeRequest(
    val mode: KisMode,
)

data class ModeChangeResult(
    val success: Boolean,
    val mode: KisMode? = null,
    val reason: String? = null,
)

data class AuthStatusResult(
    val authenticated: Boolean,
    val mode: KisMode?,
    val accountNo: String?,
    val hasAppKey: Boolean,
    val hasAppSecret: Boolean,
    val lastUpdatedAt: OffsetDateTime?,
)

// ─── 변환 helpers ─────────────────────────────────────────────────────────────

private fun TradingMode.toApiMode(): KisMode =
    when (this) {
        TradingMode.MOCK_KIS -> KisMode.Vps
        TradingMode.REAL_KIS -> KisMode.Prod
    }

private fun KisMode.toDomain(): TradingMode =
    when (this) {
        KisMode.Vps -> TradingMode.MOCK_KIS
        KisMode.Prod -> TradingMode.REAL_KIS
    }
