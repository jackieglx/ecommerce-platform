export function formatPrice(priceCents: number, currency: string): string {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency }).format(priceCents / 100)
}
