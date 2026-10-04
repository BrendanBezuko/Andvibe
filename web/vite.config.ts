import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'

const here = (path: string) => fileURLToPath(new URL(path, import.meta.url))

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': here('./src') },
  },
  server: {
    // The license page reads ../LICENSE from the repo root.
    fs: { allow: ['..'] },
  },
  build: {
    rolldownOptions: {
      input: {
        main: here('./index.html'),
        license: here('./license/index.html'),
        terms: here('./terms/index.html'),
      },
    },
  },
})
