import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

// API backend (Query Service Javalin). Dev proxy /api → :7070 để tránh CORS.
const API_TARGET = process.env.VITE_API_TARGET || "http://localhost:7070";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { "@": path.resolve(__dirname, "./src") },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": { target: API_TARGET, changeOrigin: true },
    },
  },
});
