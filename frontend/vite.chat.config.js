import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const gateway = process.env.VITE_GATEWAY_URL || 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  plugins: [react()],
  server: {
    port: 5174,
    host: '0.0.0.0',
    proxy: {
      '/app': gateway,
      '/v1': gateway,
      '/health': gateway,
    },
  },
})
