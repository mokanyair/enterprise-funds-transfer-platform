/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    env: { VITE_API_URL: "http://api.test/api/v1", VITE_AUTH_MODE: "dev", VITE_USE_MOCKS: "false" },
  },
});
