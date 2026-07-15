import { requestJson } from '../../shared/api'

export type Reservation = { status: 'RESERVED' | 'DUPLICATE' | 'SOLD_OUT' | 'FAILED'; orderId: string | null; reservationExpiresAt: string | null }
export type Payment = { paymentId: string; orderId: string; status: string; amountCents: number; currency: string; createdAt: string }

export function reserveSku(skuId: string, qty: number, userId: string): Promise<Reservation> {
  return requestJson('/inventory-api/api/v1/flashsale/reservations', { method: 'POST', headers: { 'X-User-Id': userId, 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ skuId, qty }) })
}

export function succeedPayment(orderId: string, userId: string): Promise<Payment> {
  return requestJson('/payment-api/api/v1/payments/succeed', { method: 'POST', headers: { 'X-User-Id': userId }, body: JSON.stringify({ orderId }) })
}

export function getAvailableInventory(skuId: string): Promise<number> {
  return requestJson(`/inventory-api/internal/inventory/${encodeURIComponent(skuId)}`)
}
