import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ProductCard } from './ProductCard'

const sku = {
  skuId: 'demo-phone-001', productId: 'demo-product-phone-001', title: '星云 Pro 智能手机',
  status: 'ACTIVE', brand: 'NovaTech', priceCents: 699900, currency: 'USD'
}

describe('ProductCard', () => {
  it('shows real Catalog fields and buys the card SKU without manual input', async () => {
    const onBuy = vi.fn()
    render(<ProductCard sku={sku} availableInventory={40} inventoryError={false} busy={false} disabled={false} onBuy={onBuy} />)

    expect(screen.getByText('星云 Pro 智能手机')).toBeInTheDocument()
    expect(screen.getByText('NovaTech')).toBeInTheDocument()
    expect(screen.getByText('可用库存：40')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '立即抢购' }))
    expect(onBuy).toHaveBeenCalledWith('demo-phone-001')
  })
})
