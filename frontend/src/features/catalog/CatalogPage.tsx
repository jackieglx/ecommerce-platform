import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router-dom'
import { getAvailableInventory, reserveSku } from '../checkout/api'
import { checkoutSchema, type CheckoutInput } from '../checkout/schema'
import { getConfiguredSkus } from './api'
import { ProductCard } from './ProductCard'

const DEMO_USER_ID = 'demo-user-001'

export function CatalogPage() {
  const navigate = useNavigate()
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
  const [reservationMessage, setReservationMessage] = useState<string | null>(null)
  const reservation = useMutation({
    mutationFn: (data: CheckoutInput) => reserveSku(data.skuId, data.qty, data.userId),
    onSuccess: (result) => {
      if (result.status === 'RESERVED' && result.orderId) {
        navigate(`/orders/${encodeURIComponent(result.orderId)}`)
        return
      }
      setReservationMessage(reservationMessageFor(result.status))
    }
  })

  function submitReservation(data: CheckoutInput) {
    setReservationMessage(null)
    reservation.mutate(data)
  }

  function buyNow(skuId: string) {
    setSelectedSkuId(skuId)
    setReservationMessage(null)
    form.setValue('skuId', skuId, { shouldValidate: true })
    form.setValue('userId', DEMO_USER_ID)
    void form.handleSubmit(submitReservation)()
  }

  return <main>
    <header className="hero">
      <p className="eyebrow">LOCAL FLASH SALE</p>
      <h1>本地秒杀演示商城</h1>
      <p>商品名称和价格来自 Catalog，抢购库存由 Inventory 的真实数据提供。</p>
    </header>

    <section aria-labelledby="catalog-title">
      <div className="section-heading">
        <div><h2 id="catalog-title">演示商品</h2><p>选择数量后，点击商品卡片发起真实库存预留。</p></div>
        <label className="quantity-control">数量
          <input type="number" min="1" aria-label="抢购数量" {...form.register('qty')} />
        </label>
      </div>
      {form.formState.errors.qty && <p role="alert">{form.formState.errors.qty.message}</p>}
      {reservation.isError && <p role="alert">预留失败：{reservation.error.message}</p>}
      {reservationMessage && <p role="status">{reservationMessage}</p>}
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
  </main>
}

function reservationMessageFor(status: string): string {
  if (status === 'DUPLICATE') return '你已预留过此商品，请查看原订单或选择其他商品。'
  if (status === 'SOLD_OUT') return '该商品库存不足，本次未创建订单。'
  return '未能创建订单，请稍后重试。'
}
