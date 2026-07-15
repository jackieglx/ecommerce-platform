import { requestJson } from '../../shared/api'

export type Order = {
  orderId: string
  skuId: string
  userId: string
  quantity: number
  status: string
  createdAt: string
  updatedAt: string
}

export function getOrder(orderId: string): Promise<Order> {
  return requestJson(`/order-api/api/v1/orders/${encodeURIComponent(orderId)}`)
}
