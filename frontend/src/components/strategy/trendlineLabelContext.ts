/**
 * TrendlineLabelContext — 빌더 트리에서 trendline(id) 노드에 사람이 읽는 라벨을 붙인다.
 *
 * 추세매매 Phase 2 (ADR 0140 §3 R3 item2). BlockNode 는 순수 재귀 컴포넌트라 fetch 를 하지 않는다.
 * 대신 StrategyBuilderPage 가 현재 종목의 저장된 추세선(useTrendlines)을 조회해 id→라벨 맵을 만들어
 * 이 컨텍스트로 공급하면, TrendlineNodeView 가 "추세선 #id (종목 · 라벨)" 수준으로 표시한다.
 *
 * 맵에 없는 id(다른 종목·삭제된 선)는 라벨 없이 "추세선 #id" 로만 표시 — 깨지지 않는다(graceful).
 */
import { createContext, useContext } from 'react'

export interface TrendlineLabelInfo {
  /** 사람이 읽는 라벨(사용자 지정 label 또는 지지/저항 성격) */
  label: string
  /** 종목코드(6자리) — 표시 보조 */
  code?: string
}

const EMPTY_LABELS: ReadonlyMap<number, TrendlineLabelInfo> = new Map()

export const TrendlineLabelContext =
  createContext<ReadonlyMap<number, TrendlineLabelInfo>>(EMPTY_LABELS)

export function useTrendlineLabel(id: number): TrendlineLabelInfo | undefined {
  return useContext(TrendlineLabelContext).get(id)
}
