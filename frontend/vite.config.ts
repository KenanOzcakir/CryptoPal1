import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // envDir points at the project root so the frontend reads the same .env the backend and
  // Docker Compose use. One file, one place to edit.
  //
  // This does not leak the backend's secrets into the browser. Vite only bundles variables
  // prefixed with VITE_, so GEMINI_API_KEY and POSTGRES_PASSWORD are readable here in the
  // config (which runs in Node at build time) and are simply absent from anything shipped
  // to a client. That prefix rule is what makes sharing the file safe.
  const env = loadEnv(mode, '..', '')

  return {
    envDir: '..',
    plugins: [react(), tailwindcss()],
    server: {
      port: 5173,
      proxy: {
        // The browser talks to the dev server only, and the dev server forwards /api to
        // Spring Boot. That makes the app same-origin in development, which is worth more
        // than it looks: it means there is no CORS configuration anywhere in this project,
        // and it is the same shape nginx serves in production (SPA on port 80, /api
        // reverse-proxied to the backend). Development and production behave alike, so a
        // CORS problem cannot appear for the first time on the deployed VM.
        '/api': {
          target: env.VITE_API_BASE_URL || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
  }
})
