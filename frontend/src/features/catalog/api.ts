import { requestJson } from '../../shared/api'

export type Sku = { skuId: string; productId: string; title: string; status: string; brand: string; priceCents: number; currency: string }

const configuredSkuIds = String(import.meta.env.VITE_CATALOG_SKU_IDS ?? '').split(',').map((id: string) => id.trim()).filter(Boolean)

// Catalog exposes batch lookup, not a collection/list endpoint.
export function getConfiguredSkus(): Promise<Sku[]> {
  return requestJson<Sku[]>('/catalog-api/api/v1/skus/batch', { method: 'POST', body: JSON.stringify(configuredSkuIds) })
}

export function getSku(skuId: string): Promise<Sku> {
  return requestJson<Sku>(`/catalog-api/api/v1/skus/${encodeURIComponent(skuId)}`)
}
