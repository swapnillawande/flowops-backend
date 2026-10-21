import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// Browser requests stay same-origin. Only the local dev/preview server forwards API calls.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: { "/api": process.env.API_TARGET || "http://127.0.0.1:8080" },
  },
  preview: {
    proxy: { "/api": process.env.API_TARGET || "http://127.0.0.1:8080" },
  },
  test: { environment: "jsdom", setupFiles: "./src/test-setup.ts", css: false },
});
