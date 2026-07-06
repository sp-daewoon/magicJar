/**
 * TrendlineDeleteGuardModal — 추세선 삭제 참조 가드 (추세매매 Phase 2, ADR 0140 §3-3)
 *
 * 선을 참조하는 전략이 있으면 백엔드가 삭제를 409 로 차단한다(사유에 전략 이름·id 포함).
 * 이 모달은 그 사유를 노출하고 사용자에게 두 갈래를 준다:
 *   [취소]        — 삭제하지 않음(전략을 먼저 정리하도록 유도, 자본 기준 고아화 방지)
 *   [강제 삭제]   — force=true 로 재요청 → 참조 전략 자동 일시정지 후 삭제
 *
 * mock 없음 — message 는 백엔드 409 응답 그대로. 강제 삭제는 자본 흐름을 멈추므로 명시 확인을 강제한다.
 */
import { useModalBehavior } from '../../hooks/useModalBehavior'

interface TrendlineDeleteGuardModalProps {
  /** 백엔드 409 사유 (참조 전략 개수·이름·id 포함) */
  message: string
  /** 강제 삭제 진행 중 여부 */
  isDeleting: boolean
  onCancel: () => void
  onForceDelete: () => void
}

export function TrendlineDeleteGuardModal({
  message,
  isDeleting,
  onCancel,
  onForceDelete,
}: TrendlineDeleteGuardModalProps) {
  useModalBehavior({ open: true, onClose: onCancel })

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
      <div
        className="w-full max-w-md rounded-2xl border border-gray-700 bg-gray-900 p-6 shadow-2xl"
        role="alertdialog"
        aria-modal="true"
        aria-label="추세선 삭제 참조 가드"
      >
        <h2 className="text-base font-bold text-gray-100">추세선을 삭제할 수 없습니다</h2>

        <p className="mt-3 whitespace-pre-line text-sm leading-relaxed text-gray-300">
          {message}
        </p>

        <div className="mt-4 rounded-lg border border-amber-800/60 bg-amber-950/30 px-3 py-2 text-xs leading-relaxed text-amber-200">
          강제 삭제하면 이 선을 참조하는 전략이 <span className="font-semibold">자동으로 일시정지</span>됩니다.
          매매 기준(선)이 사라진 상태로 자동매매가 지속되지 않도록 하는 안전 조치입니다.
          전략을 계속 쓰려면 <span className="font-semibold">취소</span> 후 전략에서 이 선 참조를 먼저 정리하세요.
        </div>

        <div className="mt-6 flex items-center justify-end gap-3">
          <button
            type="button"
            onClick={onCancel}
            disabled={isDeleting}
            className="rounded-lg border border-gray-600 px-4 py-2 text-sm font-medium text-gray-200 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-50"
          >
            취소
          </button>
          <button
            type="button"
            onClick={onForceDelete}
            disabled={isDeleting}
            className="rounded-lg bg-red-600 px-4 py-2 text-sm font-semibold text-white hover:bg-red-500 active:bg-red-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {isDeleting ? '삭제 중...' : '강제 삭제 (전략 일시정지)'}
          </button>
        </div>
      </div>
    </div>
  )
}
