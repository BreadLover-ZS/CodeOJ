import { createApp } from "vue";
import App from "./App.vue";
// Arco 组件由 unplugin-vue-components 按需注册（见 vue.config.js），
// 此处仅保留全量样式，避免命令式 API（message/Modal 等）样式缺失
import "@arco-design/web-vue/dist/arco.css";
import router from "./router";
import store from "./store";
import "@/plugins/axios";
import "@/access";
import "bytemd/dist/index.css";

createApp(App).use(store).use(router).mount("#app");
