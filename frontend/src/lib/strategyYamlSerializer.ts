/**
 * 전략 빌더 상태 ↔ YAML 문자열 양방향 변환 (pure function)
 * T7 DSL 스키마 머지 후 Jackson 호환 형식 정합 예정.
 * 자체 직렬화 — 외부 yaml 라이브러리 불요.
 */

import type {
  StrategyBuilderState,
  IndicatorInstance,
  SingleCondition,
  ConditionGroup,
  IndicatorType,
  IndicatorParams,
  SmaParams,
  EmaParams,
  RsiParams,
  MacdParams,
  BbParams,
  ConditionOperator,
  CompareToType,
} from '@/api/strategy'
import { createInitialState } from '@/api/strategy'

// ────────────────────────────────────────────────────────────────────────────
// serialize: state → yaml string
// ────────────────────────────────────────────────────────────────────────────

function indent(n: number): string {
  return '  '.repeat(n)
}

function yamlString(value: string): string {
  // 특수문자 포함 시 따옴표 처리
  if (value === '') return '""'
  if (/[:#\[\]{}|>&*!,?@`"'\\]/.test(value) || value.includes('\n')) {
    return `"${value.replace(/"/g, '\\"')}"`
  }
  return value
}

function serializeIndicatorParams(type: IndicatorType, params: IndicatorParams): string {
  const lines: string[] = []
  switch (type) {
    case 'sma':
    case 'ema': {
      const p = params as SmaParams | EmaParams
      lines.push(`${indent(3)}period: ${p.period}`)
      break
    }
    case 'rsi': {
      const p = params as RsiParams
      lines.push(`${indent(3)}period: ${p.period}`)
      break
    }
    case 'macd': {
      const p = params as MacdParams
      lines.push(`${indent(3)}fastPeriod: ${p.fastPeriod}`)
      lines.push(`${indent(3)}slowPeriod: ${p.slowPeriod}`)
      lines.push(`${indent(3)}signalPeriod: ${p.signalPeriod}`)
      break
    }
    case 'bb': {
      const p = params as BbParams
      lines.push(`${indent(3)}period: ${p.period}`)
      lines.push(`${indent(3)}multiplier: ${p.multiplier}`)
      break
    }
  }
  return lines.join('\n')
}

function serializeCondition(c: SingleCondition, indentLevel: number): string {
  const i = indent(indentLevel)
  const compareToValue =
    c.compareTo.type === 'number'
      ? c.compareTo.value
      : yamlString(c.compareTo.value)
  return [
    `${i}- left: ${yamlString(c.left)}`,
    `${i}  operator: ${c.operator}`,
    `${i}  compareTo:`,
    `${i}    type: ${c.compareTo.type}`,
    `${i}    value: ${compareToValue}`,
  ].join('\n')
}

function serializeConditionGroup(group: ConditionGroup, sectionName: string): string {
  if (group.conditions.length === 0) {
    return `${indent(1)}${sectionName}:\n${indent(2)}logic: ${group.logic}\n${indent(2)}conditions: []`
  }
  const condLines = group.conditions.map((c) => serializeCondition(c, 2)).join('\n')
  return `${indent(1)}${sectionName}:\n${indent(2)}logic: ${group.logic}\n${indent(2)}conditions:\n${condLines}`
}

export function serializeToYaml(state: StrategyBuilderState): string {
  const lines: string[] = []

  // header comment
  lines.push('# magicJar 전략 DSL (KIS 호환)')
  lines.push('# T7 머지 후 /api/strategies/validate 로 서버 검증 가능')
  lines.push('')

  // metadata
  lines.push('strategy:')
  lines.push(`${indent(1)}name: ${yamlString(state.metadata.name || '무제 전략')}`)
  lines.push(`${indent(1)}description: ${yamlString(state.metadata.description)}`)
  if (state.metadata.tags) {
    const tagList = state.metadata.tags.split(',').map((t) => t.trim()).filter(Boolean)
    lines.push(`${indent(1)}tags: [${tagList.map((t) => yamlString(t)).join(', ')}]`)
  } else {
    lines.push(`${indent(1)}tags: []`)
  }
  if (state.metadata.author) {
    lines.push(`${indent(1)}author: ${yamlString(state.metadata.author)}`)
  }
  lines.push('')

  // indicators
  lines.push(`${indent(1)}indicators:`)
  if (state.indicators.length === 0) {
    lines.push(`${indent(2)}[]`)
  } else {
    for (const ind of state.indicators) {
      lines.push(`${indent(2)}- type: ${ind.type}`)
      lines.push(`${indent(3)}alias: ${yamlString(ind.alias)}`)
      lines.push(`${indent(3)}params:`)
      lines.push(serializeIndicatorParams(ind.type, ind.params))
    }
  }
  lines.push('')

  // entry conditions
  lines.push(serializeConditionGroup(state.entryConditions, 'entry'))
  lines.push('')

  // exit conditions
  lines.push(serializeConditionGroup(state.exitConditions, 'exit'))
  lines.push('')

  // risk
  lines.push(`${indent(1)}risk:`)
  if (state.risk.stopLoss) {
    lines.push(`${indent(2)}stopLoss: -${state.risk.stopLoss}%`)
  }
  if (state.risk.takeProfit) {
    lines.push(`${indent(2)}takeProfit: +${state.risk.takeProfit}%`)
  }
  if (state.risk.trailingStop) {
    lines.push(`${indent(2)}trailingStop: ${state.risk.trailingStop}%`)
  }
  if (!state.risk.stopLoss && !state.risk.takeProfit && !state.risk.trailingStop) {
    lines.push(`${indent(2)}# 리스크 미설정`)
  }

  return lines.join('\n')
}

// ────────────────────────────────────────────────────────────────────────────
// deserialize: yaml string → state
// 단순 key:value 파싱 — 프로덕션용 완전 파서 아님 (T7 후 Jackson 정합 예정)
// ────────────────────────────────────────────────────────────────────────────

function trimQuotes(v: string): string {
  const t = v.trim()
  if ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith("'") && t.endsWith("'"))) {
    return t.slice(1, -1).replace(/\\"/g, '"')
  }
  return t
}

function parsePercentValue(v: string): string {
  // "+10%" → "10", "-5%" → "5", "3%" → "3"
  return v.replace(/[+\-%]/g, '').trim()
}

/** 최소한의 yaml 파싱 — 전략 DSL import용 */
export function deserializeFromYaml(yaml: string): StrategyBuilderState {
  const state = createInitialState()

  const lines = yaml.split('\n')
  let i = 0

  // 재귀 파서 대신 라인 기반 단순 파싱
  // indicators, entry, exit, risk, metadata 섹션 탐색

  // 간단히 regex로 주요 필드 추출
  for (; i < lines.length; i++) {
    const line = lines[i]
    const trimmed = line.trim()

    // name
    const nameMatch = trimmed.match(/^name:\s*(.+)$/)
    if (nameMatch && state.metadata.name === '') {
      state.metadata.name = trimQuotes(nameMatch[1])
    }

    // description
    const descMatch = trimmed.match(/^description:\s*(.+)$/)
    if (descMatch) {
      state.metadata.description = trimQuotes(descMatch[1])
    }

    // tags
    const tagsMatch = trimmed.match(/^tags:\s*\[(.+)\]$/)
    if (tagsMatch) {
      state.metadata.tags = tagsMatch[1]
        .split(',')
        .map((t) => trimQuotes(t).trim())
        .filter(Boolean)
        .join(',')
    }

    // author
    const authorMatch = trimmed.match(/^author:\s*(.+)$/)
    if (authorMatch) {
      state.metadata.author = trimQuotes(authorMatch[1])
    }

    // risk stopLoss
    const slMatch = trimmed.match(/^stopLoss:\s*(.+)$/)
    if (slMatch) {
      state.risk.stopLoss = parsePercentValue(slMatch[1])
    }

    // risk takeProfit
    const tpMatch = trimmed.match(/^takeProfit:\s*(.+)$/)
    if (tpMatch) {
      state.risk.takeProfit = parsePercentValue(tpMatch[1])
    }

    // risk trailingStop
    const tsMatch = trimmed.match(/^trailingStop:\s*(.+)$/)
    if (tsMatch) {
      state.risk.trailingStop = parsePercentValue(tsMatch[1])
    }
  }

  // indicators 파싱 (블록 추출)
  const indBlock = extractBlock(lines, /^\s{2}indicators:/)
  if (indBlock.length > 0) {
    state.indicators = parseIndicatorsBlock(indBlock)
  }

  // entry 조건 파싱
  const entryBlock = extractBlock(lines, /^\s{1,2}entry:/)
  if (entryBlock.length > 0) {
    const parsed = parseConditionGroupBlock(entryBlock)
    if (parsed) state.entryConditions = parsed
  }

  // exit 조건 파싱
  const exitBlock = extractBlock(lines, /^\s{1,2}exit:/)
  if (exitBlock.length > 0) {
    const parsed = parseConditionGroupBlock(exitBlock)
    if (parsed) state.exitConditions = parsed
  }

  return state
}

function extractBlock(lines: string[], startPattern: RegExp): string[] {
  const startIdx = lines.findIndex((l) => startPattern.test(l))
  if (startIdx === -1) return []

  const startIndent = lines[startIdx].match(/^(\s*)/)?.[1].length ?? 0
  const result: string[] = [lines[startIdx]]

  for (let j = startIdx + 1; j < lines.length; j++) {
    const l = lines[j]
    if (l.trim() === '') {
      result.push(l)
      continue
    }
    const lIndent = l.match(/^(\s*)/)?.[1].length ?? 0
    if (lIndent <= startIndent && l.trim() !== '') break
    result.push(l)
  }

  return result
}

function parseIndicatorsBlock(lines: string[]): IndicatorInstance[] {
  const result: IndicatorInstance[] = []
  let current: Partial<IndicatorInstance & { paramsRaw: Record<string, string> }> | null = null
  let inParams = false

  for (const line of lines) {
    const trimmed = line.trim()

    if (trimmed.startsWith('- type:')) {
      if (current && current.type) {
        result.push(finalizeIndicator(current))
      }
      current = {
        id: `imported-${Date.now()}-${result.length}`,
        type: trimmed.replace('- type:', '').trim() as IndicatorType,
        alias: '',
        params: { period: 20 } as SmaParams,
        paramsRaw: {},
      }
      inParams = false
      continue
    }

    if (!current) continue

    if (trimmed.match(/^alias:/)) {
      current.alias = trimQuotes(trimmed.replace('alias:', '').trim())
      continue
    }

    if (trimmed === 'params:') {
      inParams = true
      continue
    }

    if (inParams && current.paramsRaw) {
      const kv = trimmed.match(/^(\w+):\s*(.+)$/)
      if (kv) {
        current.paramsRaw[kv[1]] = kv[2]
      }
    }
  }

  if (current && current.type) {
    result.push(finalizeIndicator(current))
  }

  return result
}

function finalizeIndicator(
  raw: Partial<IndicatorInstance & { paramsRaw: Record<string, string> }>,
): IndicatorInstance {
  const type = raw.type!
  const paramsRaw = raw.paramsRaw ?? {}

  let params: IndicatorParams
  switch (type) {
    case 'sma':
    case 'ema':
    case 'rsi':
      params = { period: parseInt(paramsRaw['period'] ?? '20', 10) }
      break
    case 'macd':
      params = {
        fastPeriod: parseInt(paramsRaw['fastPeriod'] ?? '12', 10),
        slowPeriod: parseInt(paramsRaw['slowPeriod'] ?? '26', 10),
        signalPeriod: parseInt(paramsRaw['signalPeriod'] ?? '9', 10),
      }
      break
    case 'bb':
      params = {
        period: parseInt(paramsRaw['period'] ?? '20', 10),
        multiplier: parseFloat(paramsRaw['multiplier'] ?? '2'),
      }
      break
    default:
      params = { period: 20 }
  }

  return {
    id: raw.id ?? `imported-${Date.now()}`,
    type,
    alias: raw.alias || type,
    params,
  }
}

function parseConditionGroupBlock(lines: string[]): ConditionGroup | null {
  const conditions: SingleCondition[] = []
  let logic: 'and' = 'and'

  const logicLine = lines.find((l) => /logic:/.test(l))
  if (logicLine) {
    const v = logicLine.replace(/.*logic:\s*/, '').trim()
    if (v === 'and') logic = 'and'
  }

  // 조건 블록 찾기: - left: ... operator: ... compareTo: ...
  let j = 0
  while (j < lines.length) {
    const l = lines[j].trim()
    if (l.startsWith('- left:')) {
      const leftVal = trimQuotes(l.replace('- left:', '').trim())
      let operator: ConditionOperator = 'greater_than'
      let compareToType: CompareToType = 'number'
      let compareToValue = '0'

      // 다음 3줄 파싱
      if (j + 1 < lines.length) {
        const opLine = lines[j + 1].trim()
        const opMatch = opLine.match(/^operator:\s*(.+)$/)
        if (opMatch) operator = opMatch[1].trim() as ConditionOperator
      }
      if (j + 2 < lines.length && lines[j + 2].trim() === 'compareTo:') {
        if (j + 3 < lines.length) {
          const typeMatch = lines[j + 3].trim().match(/^type:\s*(.+)$/)
          if (typeMatch) compareToType = typeMatch[1].trim() as CompareToType
        }
        if (j + 4 < lines.length) {
          const valMatch = lines[j + 4].trim().match(/^value:\s*(.+)$/)
          if (valMatch) compareToValue = trimQuotes(valMatch[1].trim())
        }
        j += 5
      } else {
        j++
      }

      conditions.push({
        id: `imported-cond-${Date.now()}-${conditions.length}`,
        left: leftVal,
        operator,
        compareTo: { type: compareToType, value: compareToValue },
      })
      continue
    }
    j++
  }

  return { logic, conditions }
}
