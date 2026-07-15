import type { Sku } from './api'
import { formatPrice } from './display'

export function ProductCard({ sku, availableInventory, inventoryError, busy, disabled, onBuy }: { sku: Sku; availableInventory?: number; inventoryError: boolean; busy: boolean; disabled: boolean; onBuy: (skuId: string) => void }) {
  return <article className="product-card">
    <div className="product-badge">{sku.brand}</div>
    <h3>{sku.title}</h3>
    <p className="price">{formatPrice(sku.priceCents, sku.currency)}</p>
    <p className="inventory">{inventoryError ? '库存读取失败' : availableInventory === undefined ? '库存加载中…' : `可用库存：${availableInventory}`}</p>
    <button type="button" disabled={disabled || availableInventory === 0} onClick={() => onBuy(sku.skuId)}>{busy ? '抢购中…' : availableInventory === 0 ? '已售罄' : '立即抢购'}</button>
  </article>
}
