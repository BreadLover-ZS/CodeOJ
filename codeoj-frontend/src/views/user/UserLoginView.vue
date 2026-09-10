<template>
  <div id="userLoginView">
    <h2 class="auth-title">欢迎回来</h2>
    <p class="auth-sub">登录 CodeOJ，继续你的刷题之旅</p>

    <a-form :model="form" layout="vertical" hide-label @submit="handleSubmit">
      <a-form-item
        field="userAccount"
        :rules="[{ required: true, message: '请输入账号' }]"
      >
        <a-input v-model="form.userAccount" placeholder="账号" size="large">
          <template #prefix><icon-user /></template>
        </a-input>
      </a-form-item>
      <a-form-item
        field="userPassword"
        :rules="[{ required: true, message: '请输入密码' }]"
      >
        <a-input-password
          v-model="form.userPassword"
          placeholder="密码"
          size="large"
        >
          <template #prefix><icon-lock /></template>
        </a-input-password>
      </a-form-item>
      <a-form-item>
        <a-button
          type="primary"
          html-type="submit"
          size="large"
          class="auth-btn"
        >
          登录
        </a-button>
      </a-form-item>
    </a-form>

    <div class="auth-switch">
      还没有账号？
      <a class="switch-link" @click="router.push('/user/register')">立即注册</a>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive } from "vue";
import { UserControllerService, UserLoginRequest } from "../../../generated";
import { Message } from "@arco-design/web-vue";
import { useRouter } from "vue-router";
import { useStore } from "vuex";

const form = reactive({
  userAccount: "",
  userPassword: "",
} as UserLoginRequest);

const router = useRouter();
const store = useStore();

const handleSubmit = async () => {
  const res = await UserControllerService.userLoginUsingPost(form);
  if (res.code === 0) {
    await store.dispatch("user/getLoginUser");
    Message.success("登录成功");
    router.push({ path: "/", replace: true });
  } else {
    Message.error("登录失败，" + res.message);
  }
};
</script>

<style scoped>
.auth-title {
  font-size: 22px;
  margin: 0 0 6px;
}

.auth-sub {
  color: var(--ink-4);
  font-size: 13px;
  margin: 0 0 24px;
}

.auth-btn {
  width: 100%;
}

.auth-switch {
  text-align: center;
  margin-top: 20px;
  font-size: 13px;
  color: var(--ink-4);
}

.switch-link {
  color: var(--brand-6);
  cursor: pointer;
  font-weight: 500;
}
</style>
