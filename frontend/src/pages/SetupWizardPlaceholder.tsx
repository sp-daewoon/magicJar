/**
 * 셋업 마법사 placeholder.
 *
 * 실 구현: PR-6 (T-ONBOARD)
 * KIS 키 입력 → DB AES 암호화 저장 → 토큰 검증 → 완료의 7단계 흐름
 *
 * 참고 ADR: 0046 (Onboarding & Credential)
 */
export function SetupWizardPlaceholder() {
  const steps = [
    { num: 1, title: 'KIS 실전 계좌 정보 입력', desc: '계좌번호 · 앱 키 · 앱 시크릿' },
    { num: 2, title: '모의 계좌 정보 입력 (선택)', desc: '테스트용 모의투자 계좌' },
    { num: 3, title: 'KIS 토큰 발급 검증', desc: 'OAuth 2.0 토큰 유효성 확인' },
    { num: 4, title: '잔고 조회 검증', desc: '초기 포지션 스냅샷 가져오기' },
    { num: 5, title: '시스템 설정', desc: 'KillSwitch · 알림 · Slack webhook' },
    { num: 6, title: '전략 초기 설정', desc: '기본 리스크 파라미터 설정' },
    { num: 7, title: '완료', desc: '대시보드로 이동' },
  ]

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-gray-950 px-4">
      {/* 헤더 */}
      <div className="mb-8 text-center">
        <div className="mb-2 text-4xl font-bold tracking-tight text-white">
          magic<span className="text-blue-400">Jar</span>
        </div>
        <p className="text-sm text-gray-400">초기 설정 마법사</p>
      </div>

      {/* 메인 카드 */}
      <div className="w-full max-w-lg rounded-2xl border border-gray-800 bg-gray-900 p-8 shadow-xl">
        <div className="mb-6">
          <h1 className="mb-1 text-xl font-semibold text-white">셋업 마법사</h1>
          <p className="text-sm text-gray-400">
            이 화면은 <span className="font-medium text-blue-400">PR-6 (T-ONBOARD)</span>에서 구현됩니다.
          </p>
        </div>

        {/* 단계 목록 */}
        <ol className="mb-6 space-y-3">
          {steps.map((step) => (
            <li key={step.num} className="flex items-start gap-3">
              <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-gray-800 text-xs font-medium text-gray-500">
                {step.num}
              </span>
              <div>
                <p className="text-sm font-medium text-gray-300">{step.title}</p>
                <p className="text-xs text-gray-600">{step.desc}</p>
              </div>
            </li>
          ))}
        </ol>

        {/* 안내 메시지 */}
        <div className="mb-6 rounded-lg border border-amber-800/30 bg-amber-950/30 p-3">
          <p className="text-xs leading-relaxed text-amber-400/80">
            KIS 키는 화면에서 직접 입력 후 서버에 AES-256-GCM으로 암호화 저장됩니다.
            평문이 로그에 노출되지 않습니다. (ADR 0046 정합)
          </p>
        </div>

        {/* 돌아가기 */}
        <a
          href="/"
          className="block w-full rounded-xl border border-gray-700 px-4 py-3 text-center text-sm font-medium text-gray-300 transition-colors hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-gray-500 focus:ring-offset-2 focus:ring-offset-gray-900"
        >
          ← 대시보드로 돌아가기
        </a>
      </div>

      <p className="mt-6 text-xs text-gray-700">v3.0.1 · PR-5b 스캐폴드</p>
    </div>
  )
}
