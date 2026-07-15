import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OrderPage } from './OrderPage'

const order = {
  orderId: 'o-refresh-123', skuId: 'demo-phone-001', userId: 'demo-user-001', quantity: 2,
  status: 'PENDING_PAYMENT', createdAt: '2026-07-15T00:00:00Z', updatedAt: '2026-07-15T00:00:01Z'
}
const sku = {
  skuId: 'demo-phone-001', productId: 'demo-product-phone-001', title: '星云 Pro 智能手机',
  status: 'ACTIVE', brand: 'NovaTech', priceCents: 699900, currency: 'USD'
}

afterEach(() => vi.unstubAllGlobals())

describe('OrderPage', () => {
  it('restores order and product state from the URL and real APIs', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/order-api/api/v1/orders/o-refresh-123')) return jsonResponse(order)
      if (url.includes('/catalog-api/api/v1/skus/demo-phone-001')) return jsonResponse(sku)
      return jsonResponse({}, 404)
    })
    vi.stubGlobal('fetch', fetchMock)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(<QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/orders/o-refresh-123']}>
        <Routes><Route path="/orders/:orderId" element={<OrderPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>)

    expect(await screen.findByRole('heading', { name: '待支付' })).toBeInTheDocument()
    expect(await screen.findByText('星云 Pro 智能手机')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '模拟支付' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/order-api/api/v1/orders/o-refresh-123', expect.anything())
  })

})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
