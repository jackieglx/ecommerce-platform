import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { ApiError } from '../../shared/api'
import { getAvailableInventory, reserveSku, succeedPayment, type Payment, type Reservation } from '../checkout/api'
import { getOrderPollingInterval, isTerminalOrderStatus } from '../checkout/orderStatus'
import { checkoutSchema, type CheckoutInput } from '../checkout/schema'
import { getOrder, type Order } from '../orders/api'
import { getConfiguredSkus } from './api'
import { ProductCard } from './ProductCard'

const DEMO_USER_ID = 'demo-user-001'

export function CatalogPage() {
  const catalogQuery = useQuery({ queryKey: ['catalog', 'configured-skus'], queryFn: getConfiguredSkus })
  const catalogSkuIds = catalogQuery.data?.map((sku) => sku.skuId) ?? []
  const inventoryQuery = useQuery({
    queryKey: ['inventory', 'demo-availability', catalogSkuIds],
    queryFn: async () => Object.fromEntries(await Promise.all(catalogSkuIds.map(async (skuId) => [skuId, await getAvailableInventory(skuId)] as const))),
    enabled: catalogSkuIds.length > 0
  })
  const form = useForm<CheckoutInput>({
    resolver: zodResolver(checkoutSchema),
    defaultValues: { skuId: '', qty: 1, userId: DEMO_USER_ID }
  })
  const [selectedSkuId, setSelectedSkuId] = useState<string | null>(null)
  const [pollStartedAt, setPollStartedAt] = useState<number | null>(null)
  const reservation = useMutation({
    mutationFn: (data: CheckoutInput) => reserveSku(data.skuId, data.qty, data.userId),
    onSuccess: (result) => setPollStartedAt(result.orderId ? Date.now() : null)
  })
  const latestReservation = reservation.data
  const orderId = latestReservation?.orderId ?? null
  const orderQuery = useQuery({
    queryKey: ['orders', orderId],
    queryFn: () => getOrder(orderId!),
    enabled: Boolean(orderId),
    retry: false,
    refetchInterval: (query) => getOrderPollingInterval(query.state.data?.status, pollStartedAt)
  })
  const payment = useMutation({
    mutationFn: ({ orderId, userId }: { orderId: string; userId: string }) => succeedPayment(orderId, userId),
    onSuccess: () => void orderQuery.refetch()
  })

  function submitReservation(data: CheckoutInput) {
    setPollStartedAt(null)
    reservation.reset()
    payment.reset()
    reservation.mutate(data)
  }

  function buyNow(skuId: string) {
    setSelectedSkuId(skuId)
    form.setValue('skuId', skuId, { shouldValidate: true })
    form.setValue('userId', DEMO_USER_ID)
    void form.handleSubmit(submitReservation)()
  }

  function submitPayment() {
    if (latestReservation?.orderId) payment.mutate({ orderId: latestReservation.orderId, userId: DEMO_USER_ID })
  }

  const pollingTimedOut = orderId !== null
    && getOrderPollingInterval(orderQuery.data?.status, pollStartedAt) === false
    && !isTerminalOrderStatus(orderQuery.data?.status ?? '')

  return <main>
    <header className="hero">
      <p className="eyebrow">LOCAL FLASH SALE</p>
      <h1>本地秒杀演示商城</h1>
      <p>商品名称和价格来自 Catalog，抢购库存由 Inventory 的真实数据提供。</p>
    </header>

    <section aria-labelledby="catalog-title">
      <div className="section-heading">
        <div><h2 id="catalog-title">演示商品</h2><p>点击卡片即可使用对应 SKU 发起真实库存预留。</p></div>
        <label className="quantity-control">数量
          <input type="number" min="1" aria-label="抢购数量" {...form.register('qty')} />
        </label>
      </div>
      {form.formState.errors.qty && <p role="alert">{form.formState.errors.qty.message}</p>}
      {catalogQuery.isLoading && <p>正在从 Catalog 加载商品…</p>}
      {catalogQuery.isError && <p role="alert">无法加载 Catalog：{catalogQuery.error.message}</p>}
      {catalogQuery.data?.length === 0 && <p>未找到演示商品。请先运行 <code>scripts/seed-demo-data.ps1</code> 或 <code>scripts/seed-demo-data.sh</code>。</p>}
      <div className="product-grid">
        {catalogQuery.data?.map((sku) => <ProductCard
          key={sku.skuId}
          sku={sku}
          availableInventory={inventoryQuery.data?.[sku.skuId]}
          inventoryError={inventoryQuery.isError}
          busy={reservation.isPending && selectedSkuId === sku.skuId}
          disabled={reservation.isPending}
          onBuy={buyNow}
        />)}
      </div>
    </section>

    {(selectedSkuId || reservation.isError || latestReservation) && <section aria-labelledby="checkout-title">
      <h2 id="checkout-title">抢购进度</h2>
      {reservation.isPending && <p>正在预留真实库存并创建异步订单…</p>}
      {reservation.isError && <p role="alert">预留失败：{reservation.error.message}</p>}
      {latestReservation && <ReservationResult
        skuId={selectedSkuId}
        reservation={latestReservation}
        order={orderQuery.data}
        orderLoading={orderQuery.isLoading}
        orderError={orderQuery.isError ? orderQuery.error : undefined}
        pollingTimedOut={pollingTimedOut}
        onPay={submitPayment}
        paying={payment.isPending}
        payment={payment.data}
        paymentError={payment.isError ? payment.error.message : undefined}
      />}
    </section>}
  </main>
}

function ReservationResult({ skuId, reservation, order, orderLoading, orderError, pollingTimedOut, onPay, paying, payment, paymentError }: { skuId: string | null; reservation: Reservation; order?: Order; orderLoading: boolean; orderError?: Error; pollingTimedOut: boolean; onPay: () => void; paying: boolean; payment?: Payment; paymentError?: string }) {
  return <div className="result">
    <p className="status-line">当前订单状态：<strong>{order?.status ?? (orderLoading ? '正在创建' : '尚未创建')}</strong></p>
    {orderError instanceof ApiError && orderError.status === 404 && <p>订单正在由异步事件创建，将继续查询…</p>}
    {orderError && !(orderError instanceof ApiError && orderError.status === 404) && <p role="alert">订单状态请求失败：{orderError.message}</p>}
    {pollingTimedOut && <p role="alert">60 秒内未获得订单终态，轮询已停止。</p>}
    {reservation.status === 'RESERVED' && reservation.orderId && !isTerminalOrderStatus(order?.status ?? '') && <button type="button" onClick={onPay} disabled={paying || order?.status !== 'PENDING_PAYMENT'}>{paying ? '正在提交支付…' : order?.status === 'PENDING_PAYMENT' ? '确认支付' : '等待订单创建'}</button>}
    {payment && <p>Payment 服务响应：<strong>{payment.status}</strong>。最终结果仍以 Order 状态为准。</p>}
    {paymentError && <p role="alert">支付失败：{paymentError}</p>}
    <details className="technical-details">
      <summary>技术详情</summary>
      <dl>
        <div><dt>用户 ID</dt><dd>{DEMO_USER_ID}</dd></div>
        <div><dt>SKU ID</dt><dd>{skuId ?? '—'}</dd></div>
        <div><dt>Order ID</dt><dd>{reservation.orderId ?? '—'}</dd></div>
        <div><dt>Inventory 预留状态</dt><dd>{reservation.status}</dd></div>
        <div><dt>Order 当前状态</dt><dd>{order?.status ?? '等待查询'}</dd></div>
        <div><dt>Payment 响应状态</dt><dd>{payment?.status ?? '尚未支付'}</dd></div>
      </dl>
      <p className="notice">页面每 2 秒查询真实 Order API；只有后端返回 <code>PAID</code>、<code>CANCELLED</code> 或 <code>FAILED</code> 才停止轮询。</p>
    </details>
  </div>
}
