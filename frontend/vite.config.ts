import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Port 3000 matches the default invite link the backend puts in emails.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: { "/api": "http://localhost:8080" },
  },
});
