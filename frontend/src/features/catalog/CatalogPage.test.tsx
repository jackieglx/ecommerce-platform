import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CatalogPage } from './CatalogPage'

const sku = {
  skuId: 'demo-phone-001', productId: 'demo-product-phone-001', title: '星云 Pro 智能手机',
  status: 'ACTIVE', brand: 'NovaTech', priceCents: 699900, currency: 'USD'
}

function OrderRouteMarker() {
  const { orderId } = useParams()
  return <p>订单路由：{orderId}</p>
}

afterEach(() => vi.unstubAllGlobals())

describe('CatalogPage routing', () => {
  it('navigates to the real order id returned by Inventory', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/skus/batch')) return jsonResponse([sku])
      if (url.includes('/internal/inventory/')) return jsonResponse(20)
      if (url.includes('/flashsale/reservations')) return jsonResponse({ status: 'RESERVED', orderId: 'o-real-123', reservationExpiresAt: null })
      return jsonResponse({}, 404)
    })
    vi.stubGlobal('fetch', fetchMock)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(<QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<CatalogPage />} />
          <Route path="/orders/:orderId" element={<OrderRouteMarker />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>)

    await userEvent.click(await screen.findByRole('button', { name: '立即抢购' }))
    expect(await screen.findByText('订单路由：o-real-123')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/inventory-api/api/v1/flashsale/reservations', expect.anything())
  })

  it('stays on the catalog when Inventory reports a duplicate, even if a bad order id is present', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/skus/batch')) return jsonResponse([sku])
      if (url.includes('/internal/inventory/')) return jsonResponse(20)
      if (url.includes('/flashsale/reservations')) return jsonResponse({ status: 'DUPLICATE', orderId: 'o-never-created', reservationExpiresAt: null })
      return jsonResponse({}, 404)
    })
    vi.stubGlobal('fetch', fetchMock)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(<QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<CatalogPage />} />
          <Route path="/orders/:orderId" element={<OrderRouteMarker />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>)

    await userEvent.click(await screen.findByRole('button', { name: '立即抢购' }))
    expect(await screen.findByText('你已预留过此商品，请查看原订单或选择其他商品。')).toBeInTheDocument()
    expect(screen.queryByText('订单路由：o-never-created')).not.toBeInTheDocument()
  })
})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
