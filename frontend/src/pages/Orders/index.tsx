import { useQuery } from '@tanstack/react-query'
import { ordersApi, type OrderResponse } from '@/api/orders'

export default function OrdersPage() {
  const { data: openOrders, isLoading: loadingOpen } = useQuery({
    queryKey: ['orders', 'open'],
    queryFn: ordersApi.listOpen,
    refetchInterval: 2000,
  })

  const { data: allOrders, isLoading: loadingAll } = useQuery({
    queryKey: ['orders', 'all'],
    queryFn: ordersApi.list,
    refetchInterval: 5000,
  })

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">주문</h1>

      <section>
        <h2 className="text-lg font-semibold mb-2">
          미체결{' '}
          <span className="text-sm text-muted-foreground">
            ({openOrders?.length ?? 0}건)
          </span>
        </h2>
        {loadingOpen ? (
          <div className="text-muted-foreground">불러오는 중…</div>
        ) : (
          <OrdersTable rows={openOrders ?? []} />
        )}
      </section>

      <section>
        <h2 className="text-lg font-semibold mb-2">
          최근 주문 100건
        </h2>
        {loadingAll ? (
          <div className="text-muted-foreground">불러오는 중…</div>
        ) : (
          <OrdersTable rows={allOrders ?? []} />
        )}
      </section>
    </div>
  )
}

function OrdersTable({ rows }: { rows: OrderResponse[] }) {
  if (rows.length === 0) {
    return <div className="text-muted-foreground">없음</div>
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm border-collapse">
        <thead>
          <tr className="border-b text-left">
            <th className="px-2 py-1">시각</th>
            <th className="px-2 py-1">종목</th>
            <th className="px-2 py-1">매수/매도</th>
            <th className="px-2 py-1">유형</th>
            <th className="px-2 py-1 text-right">수량</th>
            <th className="px-2 py-1 text-right">가격</th>
            <th className="px-2 py-1 text-right">체결</th>
            <th className="px-2 py-1">상태</th>
            <th className="px-2 py-1">KIS 주문번호</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.orderId} className="border-b">
              <td className="px-2 py-1">
                {new Date(row.createdAt).toLocaleString('ko-KR')}
              </td>
              <td className="px-2 py-1 font-mono">{row.code}</td>
              <td className="px-2 py-1">
                <span
                  className={
                    row.side === 'BUY' ? 'text-red-600' : 'text-blue-600'
                  }
                >
                  {row.side === 'BUY' ? '매수' : '매도'}
                </span>
              </td>
              <td className="px-2 py-1">{row.orderType}</td>
              <td className="px-2 py-1 text-right">{row.qty}</td>
              <td className="px-2 py-1 text-right">
                {row.price?.toLocaleString() ?? '-'}
              </td>
              <td className="px-2 py-1 text-right">{row.filledQty}</td>
              <td className="px-2 py-1">
                <StatusBadge status={row.status} />
              </td>
              <td className="px-2 py-1 font-mono text-xs">{row.kisOrderNo}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function StatusBadge({ status }: { status: OrderResponse['status'] }) {
  const cls: Record<OrderResponse['status'], string> = {
    PENDING: 'bg-yellow-100 text-yellow-800',
    PARTIALLY_FILLED: 'bg-blue-100 text-blue-800',
    FILLED: 'bg-green-100 text-green-800',
    CANCELED: 'bg-gray-100 text-gray-700',
    REJECTED: 'bg-red-100 text-red-800',
  }
  return (
    <span className={`rounded px-2 py-0.5 text-xs ${cls[status]}`}>
      {status}
    </span>
  )
}
