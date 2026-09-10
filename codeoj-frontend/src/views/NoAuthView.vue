<template>
  <div id="noAuthView">
    <div class="noauth-card">
      <div class="icon-wrap">
        <icon-lock class="icon" />
      </div>
      <h2 class="title">无权限访问</h2>
      <p class="desc">抱歉，你没有权限访问该页面，请登录后重试或联系管理员。</p>
      <a-button type="primary" long @click="router.push('/')">
        <template #icon><icon-home /></template>
        返回首页
      </a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from "vue-router";

const router = useRouter();
</script>

<style scoped>
#noAuthView {
  min-height: calc(100vh - 140px);
  display: flex;
  align-items: center;
  justify-content: center;
}

.noauth-card {
  width: 360px;
  max-width: calc(100vw - 40px);
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: 40px 32px;
  text-align: center;
}

.icon-wrap {
  width: 72px;
  height: 72px;
  margin: 0 auto 20px;
  border-radius: 50%;
  background: var(--brand-1);
  display: flex;
  align-items: center;
  justify-content: center;
}

.icon {
  font-size: 34px;
  color: var(--brand-6);
}

.title {
  font-size: 20px;
  font-weight: 600;
  color: var(--ink-0);
  margin: 0 0 10px;
}

.desc {
  font-size: 14px;
  line-height: 1.7;
  color: var(--ink-4);
  margin: 0 0 24px;
}
</style>
