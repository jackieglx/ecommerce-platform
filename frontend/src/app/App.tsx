import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { CatalogPage } from '../features/catalog/CatalogPage'
import { OrderPage } from '../features/orders/OrderPage'

export function App() {
  return <BrowserRouter><Routes>
    <Route path="/" element={<CatalogPage />} />
    <Route path="/orders/:orderId" element={<OrderPage />} />
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes></BrowserRouter>
}
