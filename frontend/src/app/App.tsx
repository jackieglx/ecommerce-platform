import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { CatalogPage } from '../features/catalog/CatalogPage'

export function App() {
  return <BrowserRouter><Routes><Route path="/" element={<CatalogPage />} /><Route path="*" element={<CatalogPage />} /></Routes></BrowserRouter>
}
