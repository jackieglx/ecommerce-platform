import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/catalog-api': { target: 'http://localhost:8080', changeOrigin: true, rewrite: (path) => path.replace(/^\/catalog-api/, '') },
      '/inventory-api': { target: 'http://localhost:8082', changeOrigin: true, rewrite: (path) => path.replace(/^\/inventory-api/, '') },
      '/order-api': { target: 'http://localhost:8083', changeOrigin: true, rewrite: (path) => path.replace(/^\/order-api/, '') },
      '/payment-api': { target: 'http://localhost:8084', changeOrigin: true, rewrite: (path) => path.replace(/^\/payment-api/, '') }
    }
  }
})
