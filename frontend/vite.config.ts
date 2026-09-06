import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  server: {
    // Backend arrives in M2; the proxy target is fixed now so the seam exists.
    proxy: { '/api': 'http://127.0.0.1:8080' },
  },
})
