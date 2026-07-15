import { describe, expect, it } from 'vitest'
import { getOrderPollingInterval, isOrderNotFoundTimedOut, isTerminalOrderStatus, ORDER_NOT_FOUND_TIMEOUT_MS, ORDER_POLL_INTERVAL_MS, ORDER_POLL_TIMEOUT_MS } from './orderStatus'

describe('isTerminalOrderStatus', () => {
  it.each(['PAID', 'CANCELLED', 'FAILED'])('recognizes %s as terminal', (status) => expect(isTerminalOrderStatus(status)).toBe(true))
  it('keeps pending states pollable', () => expect(isTerminalOrderStatus('PENDING_PAYMENT')).toBe(false))
})

describe('getOrderPollingInterval', () => {
  const startedAt = 1_000

  it('polls pending and not-yet-created orders at the configured interval', () => {
    expect(getOrderPollingInterval('PENDING_PAYMENT', startedAt, startedAt + 1)).toBe(ORDER_POLL_INTERVAL_MS)
    expect(getOrderPollingInterval(undefined, startedAt, startedAt + 1)).toBe(ORDER_POLL_INTERVAL_MS)
  })

  it.each(['PAID', 'CANCELLED', 'FAILED'])('stops when %s is terminal', (status) => {
    expect(getOrderPollingInterval(status, startedAt, startedAt + 1)).toBe(false)
  })

  it('stops after the polling timeout', () => {
    expect(getOrderPollingInterval('PENDING_PAYMENT', startedAt, startedAt + ORDER_POLL_TIMEOUT_MS)).toBe(false)
  })

  it('keeps polling through a short Order API 404', () => {
    expect(getOrderPollingInterval(undefined, startedAt, startedAt + ORDER_NOT_FOUND_TIMEOUT_MS - 1, true)).toBe(ORDER_POLL_INTERVAL_MS)
    expect(isOrderNotFoundTimedOut(startedAt, startedAt + ORDER_NOT_FOUND_TIMEOUT_MS - 1)).toBe(false)
  })

  it('stops polling when Order API keeps returning 404', () => {
    expect(getOrderPollingInterval(undefined, startedAt, startedAt + ORDER_NOT_FOUND_TIMEOUT_MS, true)).toBe(false)
    expect(isOrderNotFoundTimedOut(startedAt, startedAt + ORDER_NOT_FOUND_TIMEOUT_MS)).toBe(true)
  })
})
