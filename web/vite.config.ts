import path from "path";
import vue from "@vitejs/plugin-vue";
import ui from "@nuxt/ui/vite";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [
    vue(),
    ui({
      ui: {
        colors: {
          primary: "sztu",
          secondary: "sztu",
          info: "sztu",
          neutral: "slate",
        },
      },
    }),
  ],
  base: "./",
  resolve: {
    alias: { "@": path.resolve(__dirname, "./src") },
  },
  build: {
    outDir: path.resolve(__dirname, "../app/src/main/assets/www"),
    emptyOutDir: true,
    target: "es2020",
    cssCodeSplit: false,
    modulePreload: false,
  },
});
