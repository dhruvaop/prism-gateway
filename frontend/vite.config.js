import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectDir = fileURLToPath(new URL('.', import.meta.url))
const gateway = process.env.VITE_GATEWAY_URL || 'http://localhost:8080'
export default defineConfig({
  plugins: [react()],
  build: { rollupOptions: { input: { admin: resolve(projectDir, 'index.html'), chat: resolve(projectDir, 'chat.html') } } },
  server: {
    port: 5173,
    host: '0.0.0.0',
    proxy: {
      '/auth': gateway,
      '/admin': gateway,
      '/v1': gateway,
      '/health': gateway,
      '/app': gateway,
    },
  },
})
