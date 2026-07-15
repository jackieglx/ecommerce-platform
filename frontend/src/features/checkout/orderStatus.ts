export const terminalOrderStatuses = new Set(['PAID', 'CANCELLED', 'FAILED'])
export function isTerminalOrderStatus(status: string): boolean { return terminalOrderStatuses.has(status) }

export const ORDER_POLL_INTERVAL_MS = 2_000
export const ORDER_POLL_TIMEOUT_MS = 60_000

export function getOrderPollingInterval(status: string | undefined, startedAt: number | null, now = Date.now()): number | false {
  if (!startedAt || now - startedAt >= ORDER_POLL_TIMEOUT_MS || (status !== undefined && isTerminalOrderStatus(status))) return false
  return ORDER_POLL_INTERVAL_MS
}
