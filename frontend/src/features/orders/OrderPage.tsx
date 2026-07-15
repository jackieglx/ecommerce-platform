import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError } from '../../shared/api'
import { succeedPayment } from '../checkout/api'
import { getOrderPollingInterval, isOrderNotFoundTimedOut, isTerminalOrderStatus } from '../checkout/orderStatus'
import { getSku } from '../catalog/api'
import { formatPrice } from '../catalog/display'
import { getOrder } from './api'
import { presentOrderStatus } from './statusPresentation'

export function OrderPage() {
  const { orderId = '' } = useParams()
  const [pollStartedAt] = useState(() => Date.now())
  const orderQuery = useQuery({
    queryKey: ['orders', orderId],
    queryFn: () => getOrder(orderId),
    enabled: orderId.length > 0,
    retry: false,
    refetchInterval: (query) => getOrderPollingInterval(
      query.state.data?.status,
      pollStartedAt,
      Date.now(),
      query.state.error instanceof ApiError && query.state.error.status === 404
    )
  })
  const productQuery = useQuery({
    queryKey: ['catalog', 'sku', orderQuery.data?.skuId],
    queryFn: () => getSku(orderQuery.data!.skuId),
    enabled: Boolean(orderQuery.data?.skuId)
  })
  const payment = useMutation({
    mutationFn: () => succeedPayment(orderId, orderQuery.data!.userId),
    onSuccess: () => void orderQuery.refetch()
  })

  const order = orderQuery.data
  const isNotFound = orderQuery.error instanceof ApiError && orderQuery.error.status === 404
  const notFoundTimedOut = isNotFound && isOrderNotFoundTimedOut(pollStartedAt)
  const status = notFoundTimedOut
    ? { label: '订单未创建/订单不存在', description: '后端在短暂重试后仍未找到这笔订单，请返回商品列表。' }
    : presentOrderStatus(order?.status)
  const statusClass = notFoundTimedOut ? 'not-found' : (order?.status ?? 'creating').toLowerCase()
  const pollingTimedOut = !notFoundTimedOut && getOrderPollingInterval(order?.status, pollStartedAt) === false
    && !isTerminalOrderStatus(order?.status ?? '')

  return <main className="order-page">
    <header className="order-header">
      <p className="eyebrow">ORDER STATUS</p>
      <h1>订单详情</h1>
      <p>本页每 2 秒读取真实 Order API；持续 404 重试 10 秒，其他非终态最长轮询 60 秒。</p>
    </header>

    <section aria-labelledby="order-status-title">
      <div className={`order-status order-status-${statusClass}`}>
        <span>当前状态</span>
        <h2 id="order-status-title">{status.label}</h2>
        <p>{status.description}</p>
      </div>

      {isNotFound && !notFoundTimedOut && <p>订单正在由异步事件创建，将短暂重试…</p>}
      {notFoundTimedOut && <p role="alert">订单未创建或订单不存在。请返回商品列表重新抢购或选择其他商品。</p>}
      {orderQuery.isError && !isNotFound && <p role="alert">订单状态请求失败：{orderQuery.error.message}</p>}
      {pollingTimedOut && <p role="alert">60 秒内未获得订单状态，自动轮询已停止。你可以手动重试。</p>}

      <div className="order-summary">
        <div><span>商品</span><strong>{productQuery.data?.title ?? (order ? order.skuId : '正在读取订单…')}</strong></div>
        {productQuery.data && <div><span>价格</span><strong>{formatPrice(productQuery.data.priceCents, productQuery.data.currency)}</strong></div>}
        <div><span>数量</span><strong>{order?.quantity ?? '—'}</strong></div>
        <div><span>订单号</span><strong className="mono">{orderId}</strong></div>
      </div>

      <div className="order-actions">
        {order?.status === 'PENDING_PAYMENT' && <button type="button" onClick={() => payment.mutate()} disabled={payment.isPending}>
          {payment.isPending ? '正在提交支付…' : '模拟支付'}
        </button>}
        {(pollingTimedOut || notFoundTimedOut) && <button type="button" className="secondary-button" onClick={() => void orderQuery.refetch()}>重新查询</button>}
        <Link className="secondary-button" to="/">返回商品列表</Link>
      </div>

      {payment.data && <p className="notice">Payment 已受理请求（{payment.data.status}），最终结果仍以 Order 状态为准。</p>}
      {payment.isError && <p role="alert">支付请求失败：{payment.error.message}</p>}

      <details className="technical-details">
        <summary>技术详情</summary>
        <dl>
          <div><dt>Order ID</dt><dd>{orderId}</dd></div>
          <div><dt>SKU ID</dt><dd>{order?.skuId ?? '等待查询'}</dd></div>
          <div><dt>User ID</dt><dd>{order?.userId ?? '等待查询'}</dd></div>
          <div><dt>Order 原始状态</dt><dd>{order?.status ?? '等待查询'}</dd></div>
          <div><dt>创建时间</dt><dd>{order?.createdAt ?? '—'}</dd></div>
          <div><dt>更新时间</dt><dd>{order?.updatedAt ?? '—'}</dd></div>
          <div><dt>Payment 响应</dt><dd>{payment.data?.status ?? '尚未支付'}</dd></div>
        </dl>
      </details>
    </section>
  </main>
}
