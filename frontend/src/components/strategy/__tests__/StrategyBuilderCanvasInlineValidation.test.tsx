/**
 * StrategyBuilderCanvas 인라인 검증 테스트 — 빌더 고도화 B
 *
 * 검증:
 * - 빈 슬롯 있는 트리 → 경고 배너 + 빈 슬롯 개수 표시
 * - 빈 슬롯 있으면 검증/저장 버튼 disabled
 * - 완성 트리 → "조건 완성" 상태 + 버튼 활성
 */
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StrategyBuilderCanvas } from '../StrategyBuilderCanvas'
import type { BlockTree } from '../../../lib/dsl/blockTree'

const noop = () => {}

const incompleteTree: BlockTree = {
  entry: {
    kind: 'comparison',
    op: '>',
    left: { kind: 'candle', field: 'close' },
    // right 슬롯 빔
  },
}

const completeTree: BlockTree = {
  entry: {
    kind: 'comparison',
    op: '>',
    left: { kind: 'candle', field: 'close' },
    right: { kind: 'indicator', id: 'ma', params: { p0: 20 } },
  },
}

describe('StrategyBuilderCanvas — 인라인 검증 (B)', () => {
  it('빈 슬롯 트리 — 경고 배너 + 빈 슬롯 개수', () => {
    render(<StrategyBuilderCanvas tree={incompleteTree} onTreeChange={noop} />)
    expect(screen.getByRole('alert')).toHaveTextContent(/비어 있는 슬롯이 1개/)
    expect(screen.getByRole('status')).toHaveTextContent(/빈 슬롯 1개/)
  })

  it('빈 슬롯 있으면 검증·저장 버튼 disabled', () => {
    render(
      <StrategyBuilderCanvas tree={incompleteTree} onTreeChange={noop} onValidate={noop} onSave={noop} />,
    )
    expect(screen.getByText('검증')).toBeDisabled()
    expect(screen.getByText('저장')).toBeDisabled()
  })

  it('완성 트리 — "조건 완성" 상태 + 버튼 활성', () => {
    render(
      <StrategyBuilderCanvas tree={completeTree} onTreeChange={noop} onValidate={noop} onSave={noop} />,
    )
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByText(/조건 완성/)).toBeInTheDocument()
    expect(screen.getByText('검증')).not.toBeDisabled()
    expect(screen.getByText('저장')).not.toBeDisabled()
  })

  it('DSL 미리보기에 매수/매도 라벨 표시', () => {
    render(<StrategyBuilderCanvas tree={completeTree} onTreeChange={noop} />)
    expect(screen.getByText(/매수 Entry/)).toBeInTheDocument()
    fireEvent.click(screen.getByText('매도 조건 (Exit)'))
    expect(screen.getByText(/매도 Exit/)).toBeInTheDocument()
  })
})
