import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ordersApi, type OrderRequest, type Side, type OrderType } from '@/api/orders'

/**
 * 수동 주문 트리거 패널 — Dashboard에서 사용. 1번 트랙 dogfooding 입구.
 * Form → POST /api/orders → Kafka order.intent.v1 publish.
 */
export default function ManualOrderPanel() {
  const [code, setCode] = useState('005930')
  const [side, setSide] = useState<Side>('BUY')
  const [qty, setQty] = useState(1)
  const [price, setPrice] = useState<number | ''>('')
  const [orderType, setOrderType] = useState<OrderType>('LIMIT')
  const [feedback, setFeedback] = useState<string>('')

  const queryClient = useQueryClient()
  const trigger = useMutation({
    mutationFn: (req: OrderRequest) => ordersApi.trigger(req),
    onSuccess: (res) => {
      setFeedback(`✅ 발행 — intentId=${res.intentId.slice(0, 8)}`)
      queryClient.invalidateQueries({ queryKey: ['orders'] })
    },
    onError: (e) => {
      setFeedback(`❌ 실패 — ${(e as Error).message}`)
    },
  })

  function submit(e: React.FormEvent) {
    e.preventDefault()
    setFeedback('')
    trigger.mutate({
      code,
      side,
      qty,
      price: orderType === 'LIMIT' ? Number(price) : null,
      orderType,
    })
  }

  return (
    <form
      onSubmit={submit}
      className="rounded border p-4 space-y-3 bg-card"
    >
      <h3 className="font-semibold">수동 주문</h3>
      <div className="grid grid-cols-2 gap-2 text-sm">
        <label className="space-y-1">
          <span>종목코드</span>
          <input
            value={code}
            onChange={(e) => setCode(e.target.value)}
            className="w-full rounded border px-2 py-1 font-mono"
            maxLength={7}
          />
        </label>
        <label className="space-y-1">
          <span>매수/매도</span>
          <select
            value={side}
            onChange={(e) => setSide(e.target.value as Side)}
            className="w-full rounded border px-2 py-1"
          >
            <option value="BUY">매수</option>
            <option value="SELL">매도</option>
          </select>
        </label>
        <label className="space-y-1">
          <span>유형</span>
          <select
            value={orderType}
            onChange={(e) => setOrderType(e.target.value as OrderType)}
            className="w-full rounded border px-2 py-1"
          >
            <option value="LIMIT">지정가</option>
            <option value="MARKET">시장가</option>
          </select>
        </label>
        <label className="space-y-1">
          <span>수량</span>
          <input
            type="number"
            value={qty}
            onChange={(e) => setQty(Number(e.target.value))}
            className="w-full rounded border px-2 py-1 text-right"
            min={1}
          />
        </label>
        {orderType === 'LIMIT' && (
          <label className="space-y-1 col-span-2">
            <span>가격 (원)</span>
            <input
              type="number"
              value={price}
              onChange={(e) =>
                setPrice(e.target.value === '' ? '' : Number(e.target.value))
              }
              className="w-full rounded border px-2 py-1 text-right"
              min={1}
              required
            />
          </label>
        )}
      </div>
      <button
        type="submit"
        disabled={trigger.isPending}
        className="w-full rounded bg-primary px-3 py-2 text-sm font-medium text-primary-foreground disabled:opacity-50"
      >
        {trigger.isPending ? '발행 중…' : '주문 보내기'}
      </button>
      {feedback && (
        <div className="text-xs text-muted-foreground">{feedback}</div>
      )}
    </form>
  )
}
