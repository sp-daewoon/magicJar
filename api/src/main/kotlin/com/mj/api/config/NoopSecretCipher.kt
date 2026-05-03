package com.mj.api.config

import com.mj.domain.secret.SecretCipher

/**
 * mock profile용 no-op SecretCipher.
 * 암호화 없이 plain 저장 — mock 환경(개발/테스트)에서 master key 없이 AuthService를 활성화하기 위함.
 * 실제 운영(dev·prd)에서는 사용되지 않는다.
 */
internal class NoopSecretCipher : SecretCipher {
    override fun encrypt(plain: String): String = plain

    override fun decrypt(encoded: String): String = encoded
}
