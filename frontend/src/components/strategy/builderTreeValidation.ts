/**
 * 빌더 조건 트리 인라인 검증 — 빌더 고도화 B
 *
 * 검증 버튼(서버 validate)을 누르기 전에도 빈 슬롯·미완성 노드를 사용자에게 즉시 시각화한다.
 * serializeBlockTree 결과(직렬화 시 빈 슬롯이 '_'로 남는 문제)를 사전 진단한다.
 *
 * - comparison/logical/arithmetic: left·right 둘 다 있어야 완성
 * - not: operand 있어야 완성
 * - 잎(indicator/candle/pattern/literal): 항상 완성
 *
 * 순수 계산만 — mock 합성 0건. 룰 5: LLM provider import 0건
 */
import type { BlockNode, BlockTree } from '../../lib/dsl/blockTree'

export interface TreeIssue {
  /** 사람이 읽는 경고 메시지 */
  message: string
}

export interface TreeValidationResult {
  /** 루트 자체가 없음(블록 미추가) */
  empty: boolean
  /** 빈 슬롯·미완성 노드 개수 */
  emptySlotCount: number
  /** 완성 여부 (empty=false && emptySlotCount=0) */
  complete: boolean
  /** 경고 목록 (없으면 빈 배열) */
  issues: TreeIssue[]
}

function countEmptySlots(node: BlockNode | undefined): number {
  if (!node) return 1
  switch (node.kind) {
    case 'comparison':
    case 'logical':
    case 'arithmetic':
      return (node.left ? countEmptySlots(node.left) : 1) + (node.right ? countEmptySlots(node.right) : 1)
    case 'not':
      return node.operand ? countEmptySlots(node.operand) : 1
    default:
      return 0
  }
}

/** 단일 루트(entry 또는 exit) 검증 */
export function validateTreeRoot(root: BlockNode | undefined, label: string): TreeValidationResult {
  if (!root) {
    return { empty: true, emptySlotCount: 0, complete: false, issues: [] }
  }
  const emptySlotCount = countEmptySlots(root)
  const issues: TreeIssue[] = []
  if (emptySlotCount > 0) {
    issues.push({ message: `${label} 조건에 비어 있는 슬롯이 ${emptySlotCount}개 있습니다. 모든 칸을 채워야 검증·저장할 수 있습니다.` })
  }
  return {
    empty: false,
    emptySlotCount,
    complete: emptySlotCount === 0,
    issues,
  }
}

/** 빌더 현재 탭(entry/exit)에 대한 인라인 검증 */
export function validateBuilderTree(tree: BlockTree, tab: 'entry' | 'exit'): TreeValidationResult {
  const label = tab === 'entry' ? '매수' : '매도'
  return validateTreeRoot(tab === 'entry' ? tree.entry : tree.exit, label)
}
