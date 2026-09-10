<template>
  <div id="userLayout">
    <!-- 装饰性背景光斑 -->
    <div class="blob blob-1"></div>
    <div class="blob blob-2"></div>
    <div class="blob blob-3"></div>

    <div class="auth-wrap">
      <div class="auth-brand" @click="router.push('/')">
        <img src="../assets/oj-logo.svg" class="logo" alt="logo" />
        <span class="brand-name">CodeOJ</span>
      </div>
      <div class="auth-card">
        <router-view />
      </div>
      <div class="auth-footer">CodeOJ · 简约现代的在线判题系统</div>
    </div>
  </div>
</template>

<style scoped>
#userLayout {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  background: #f6f7f9;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: left;
}

/* 柔和背景光斑 */
.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(70px);
  opacity: 0.55;
  pointer-events: none;
}
.blob-1 {
  width: 420px;
  height: 420px;
  background: #c7d6ff;
  top: -120px;
  left: -80px;
}
.blob-2 {
  width: 360px;
  height: 360px;
  background: #dbe4ff;
  bottom: -100px;
  right: -60px;
}
.blob-3 {
  width: 240px;
  height: 240px;
  background: #e7edff;
  top: 45%;
  left: 60%;
}

.auth-wrap {
  position: relative;
  width: 400px;
  max-width: calc(100vw - 40px);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
  z-index: 1;
}

.auth-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}

.logo {
  width: 30px;
  height: 38px;
}

.brand-name {
  font-size: 22px;
  font-weight: 700;
  color: var(--ink-0);
  letter-spacing: 0.5px;
}

.auth-card {
  width: 100%;
  background: #fff;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: 32px;
}

.auth-footer {
  font-size: 12px;
  color: var(--ink-4);
}
</style>
<script setup lang="ts">
import { useRouter } from "vue-router";
const router = useRouter();
</script>
