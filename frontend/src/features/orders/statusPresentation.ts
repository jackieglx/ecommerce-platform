const statusPresentation: Record<string, { label: string; description: string }> = {
  PENDING_PAYMENT: { label: '待支付', description: '库存已预留，请完成模拟支付。' },
  PAID: { label: '支付成功', description: '订单已由后端支付事件推进为已支付。' },
  CANCELLED: { label: '已取消', description: '订单已取消，不会继续处理。' },
  FAILED: { label: '处理失败', description: '订单处理失败，请返回商品列表重试。' }
}

export function presentOrderStatus(status: string | undefined) {
  if (!status) return { label: '正在创建订单', description: '库存已预留，正在等待异步订单事件。' }
  return statusPresentation[status] ?? { label: '处理中', description: `后端订单状态：${status}` }
}
