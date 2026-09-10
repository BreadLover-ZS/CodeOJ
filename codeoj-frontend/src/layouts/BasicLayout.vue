<template>
  <div id="basicLayout">
    <header class="layout-header">
      <div class="app-container header-inner">
        <GlobalHeader />
      </div>
    </header>
    <main class="app-container layout-main">
      <router-view />
    </main>
    <footer class="layout-footer">
      <span>CodeOJ · 一个简约现代的在线判题系统</span>
    </footer>
  </div>
</template>

<style scoped>
#basicLayout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--app-bg);
}

.layout-header {
  position: sticky;
  top: 0;
  z-index: 100;
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: saturate(180%) blur(12px);
  border-bottom: 1px solid var(--border-color);
}

.layout-main {
  flex: 1;
  padding-bottom: 48px;
}

.layout-footer {
  border-top: 1px solid var(--border-color);
  background: #fff;
  padding: 20px 0;
  text-align: center;
  font-size: 13px;
  color: var(--ink-4);
}
</style>
<script setup lang="ts">
import GlobalHeader from "@/components/GlobalHeader.vue";
</script>
