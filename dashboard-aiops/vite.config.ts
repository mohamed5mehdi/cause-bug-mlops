import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// En developpement, Vite joue le role de nginx (ADR-014) : meme origine,
// /api/* est relaye vers aiops-engine sans le prefixe /api.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8000',
        changeOrigin: true,
        rewrite: (chemin) => chemin.replace(/^\/api/, ''),
      },
    },
  },
});
