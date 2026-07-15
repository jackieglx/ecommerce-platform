import { describe, expect, it } from 'vitest'
import { presentOrderStatus } from './statusPresentation'

describe('presentOrderStatus', () => {
  it.each([
    ['PENDING_PAYMENT', '待支付'],
    ['PAID', '支付成功'],
    ['CANCELLED', '已取消'],
    ['FAILED', '处理失败']
  ])('presents %s for users', (status, label) => {
    expect(presentOrderStatus(status).label).toBe(label)
  })
})
