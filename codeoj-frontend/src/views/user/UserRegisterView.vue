<template>
  <div id="userRegisterView">
    <h2 class="auth-title">创建账号</h2>
    <p class="auth-sub">加入 CodeOJ，开始你的编程之旅</p>

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
        :rules="[
          { required: true, message: '请输入密码' },
          { minLength: 8, message: '密码不少于 8 位' },
        ]"
      >
        <a-input-password
          v-model="form.userPassword"
          placeholder="密码（不少于 8 位）"
          size="large"
        >
          <template #prefix><icon-lock /></template>
        </a-input-password>
      </a-form-item>
      <a-form-item
        field="checkPassword"
        :rules="[
          { required: true, message: '请再次输入密码' },
          {
            validator: (value, callback) => {
              if (value !== form.userPassword) {
                callback('两次输入的密码不一致');
              } else {
                callback();
              }
            },
          },
        ]"
      >
        <a-input-password
          v-model="form.checkPassword"
          placeholder="确认密码"
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
          注册
        </a-button>
      </a-form-item>
    </a-form>

    <div class="auth-switch">
      已有账号？
      <a class="switch-link" @click="router.push('/user/login')">直接登录</a>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive } from "vue";
import { UserControllerService, UserRegisterRequest } from "../../../generated";
import { Message } from "@arco-design/web-vue";
import { useRouter } from "vue-router";

const form = reactive({
  userAccount: "",
  userPassword: "",
  checkPassword: "",
} as UserRegisterRequest);

const router = useRouter();

const handleSubmit = async () => {
  const res = await UserControllerService.userRegisterUsingPost(form);
  if (res.code === 0) {
    Message.success("注册成功，请登录");
    router.push({ path: "/user/login", replace: true });
  } else {
    Message.error("注册失败，" + res.message);
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
