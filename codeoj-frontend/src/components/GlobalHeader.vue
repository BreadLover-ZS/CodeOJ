<template>
  <div class="header">
    <div class="brand" @click="router.push('/')">
      <img class="brand-logo" src="../assets/oj-logo.svg" alt="logo" />
      <span class="brand-name">CodeOJ</span>
      <span class="brand-badge">OJ</span>
    </div>

    <nav class="nav">
      <a
        v-for="item in visibleRoutes"
        :key="item.path"
        class="nav-item"
        :class="{ active: isActive(item.path) }"
        @click="router.push(item.path)"
      >
        {{ item.name }}
      </a>
    </nav>

    <div class="user-area">
      <template
        v-if="
          store.state.user?.loginUser?.userRole !== ACCESS_ENUM.NOT_LOGIN &&
          store.state.user?.loginUser?.userRole !== undefined
        "
      >
        <a-dropdown @select="onUserAction">
          <div class="user-chip">
            <div class="avatar">
              {{ avatarText }}
            </div>
            <span class="user-name">{{ displayName }}</span>
            <icon-down :size="12" class="caret" />
          </div>
          <template #content>
            <a-doption value="logout">
              <template #icon><icon-export /></template>
              退出登录
            </a-doption>
          </template>
        </a-dropdown>
      </template>
      <template v-else>
        <span class="auth-link login" @click="router.push('/user/login')"
          >登录</span
        >
        <span class="auth-divider" />
        <span class="auth-link" @click="router.push('/user/register')"
          >注册</span
        >
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useStore } from "vuex";
import { Message } from "@arco-design/web-vue";
import { routes } from "@/router/routes";
import checkAccess from "@/access/checkAccess";
import ACCESS_ENUM from "@/access/accessEnum";
import { UserControllerService } from "../../generated";

const router = useRouter();
const route = useRoute();
const store = useStore();

// 有权限展示在导航栏的路由（排除隐藏项）
const visibleRoutes = computed(() =>
  routes.filter(
    (item) =>
      !item.meta?.hideInMenu &&
      checkAccess(store.state.user.loginUser, item.meta?.access as string)
  )
);

const loginUser = computed(() => store.state.user.loginUser);

const displayName = computed(() => loginUser.value?.userName || "未登录");

// 头像文本：取昵称首字符（中文）或前两位（英文）
const avatarText = computed(() => {
  const name = displayName.value;
  if (!name || name === "未登录") return "U";
  const first = name.slice(0, 1);
  return /[\u4e00-\u9fa5]/.test(first) ? first : name.slice(0, 2).toUpperCase();
});

const isActive = (path: string) =>
  route.path === path || (path !== "/" && route.path.startsWith(path));

const onUserAction = async (value: string | number) => {
  if (value === "logout") {
    const res = await UserControllerService.userLogoutUsingPost();
    if (res.code === 0) {
      await store.dispatch("user/getLoginUser");
      Message.success("已退出登录");
      router.push("/");
    } else {
      Message.error("退出失败，" + res.message);
    }
  }
};
</script>

<style scoped>
.header {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 24px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  user-select: none;
}

.brand-logo {
  width: 26px;
  height: 32px;
}

.brand-name {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.5px;
  color: var(--ink-0);
}

.brand-badge {
  font-size: 10px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-6);
  padding: 2px 7px;
  border-radius: 20px;
  letter-spacing: 0.5px;
}

.nav {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 4px;
}

.nav-item {
  position: relative;
  padding: 6px 14px;
  font-size: 14px;
  color: var(--ink-3);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.nav-item:hover {
  color: var(--brand-6);
  background: var(--brand-1);
}

.nav-item.active {
  color: var(--brand-6);
  font-weight: 600;
}

.nav-item.active::after {
  content: "";
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: 0;
  height: 2px;
  border-radius: 2px;
  background: var(--brand-6);
}

.user-area {
  display: flex;
  align-items: center;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 6px 4px 4px;
  border-radius: 24px;
  cursor: pointer;
  transition: background 0.2s ease;
}

.user-chip:hover {
  background: var(--ink-8);
}

.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--brand-5), var(--brand-7));
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}

.user-name {
  font-size: 13px;
  color: var(--ink-2);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.caret {
  color: var(--ink-4);
}

.auth-link {
  font-size: 14px;
  color: var(--ink-3);
  cursor: pointer;
  transition: color 0.2s ease;
}

.auth-link:hover {
  color: var(--brand-6);
}

.auth-link.login {
  color: var(--brand-6);
  font-weight: 600;
}

.auth-divider {
  width: 1px;
  height: 14px;
  background: var(--ink-6);
  margin: 0 12px;
}
</style>
