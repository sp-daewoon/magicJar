import { describe, expect, it } from 'vitest'
import { serializeBlockTree } from '../serializer'
import { SMART_MONEY_STRATEGY_PRESETS } from '../smartMoneyPresets'

describe('SMART_MONEY_STRATEGY_PRESETS', () => {
  it('FVG/OB 후보 프리셋을 DSL alias로 직렬화한다', () => {
    const byId = new Map(SMART_MONEY_STRATEGY_PRESETS.map((preset) => [preset.id, preset]))

    expect(serializeBlockTree(byId.get('fvg-bullish-reclaim')!.tree)).toEqual({
      entry: '(fvg_bullish_active(50) == 1) AND (close > fvg_bullish_mid(50))',
      exit: 'close < fvg_bullish_bottom(50)',
    })
    expect(serializeBlockTree(byId.get('order-block-bullish-retest')!.tree).entry).toBe(
      '(ob_bullish_active(20, 20, 50) == 1) AND (close > ob_bullish_mid(20, 20, 50))',
    )
  })

  it('모든 프리셋은 추천 기준봉과 entry/exit를 가진다', () => {
    for (const preset of SMART_MONEY_STRATEGY_PRESETS) {
      const dsl = serializeBlockTree(preset.tree)
      expect(preset.suggestedInterval).toBeTruthy()
      expect(dsl.entry).toBeTruthy()
      expect(dsl.exit).toBeTruthy()
    }
  })
})
