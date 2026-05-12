/**
 * RiskConfigPage — /risk-config (T-RISK Phase 3 — ADR 0067)
 *
 * 리스크 설정 페이지:
 * - RiskConfigForm 렌더링
 * - 빈 config → default seed 표시 + 사용자 갱신 유도 (이미 form 내부에서 처리)
 */
import { RiskConfigForm } from '../../components/risk/RiskConfigForm'

export function RiskConfigPage() {
  return (
    <div className="mx-auto max-w-3xl px-4 py-8">
      {/* 페이지 헤더 */}
      <div className="mb-8">
        <h1 className="text-2xl font-bold text-white">리스크 설정</h1>
        <p className="mt-2 text-sm text-gray-500">
          자동매매 전략의 손실 한도·포지션 한도·거래 빈도를 설정합니다.
          설정값 초과 시 전략 실행이 차단되거나 자동 정지됩니다.
        </p>
      </div>

      {/* 설정 폼 */}
      <RiskConfigForm />
    </div>
  )
}
