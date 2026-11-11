import "./assets/main.css";
// 语义令牌与应用样式：必须排在 main.css（tailwind / @nuxt/ui）之后，
// 保证设计令牌与组件样式覆盖框架默认值。
import "./styles/tokens.css";
import "./styles/app.css";
import { createApp } from "vue";
import ui from "@nuxt/ui/vue-plugin";
import App from "./App.vue";
import { initTheme } from "./lib/theme";

// 主题在挂载前定下来，避免首屏闪一下旧颜色
initTheme();

createApp(App).use(ui).mount("#app");
