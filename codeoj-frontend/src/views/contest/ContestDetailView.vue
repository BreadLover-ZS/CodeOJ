<template>
  <div class="contest-detail">
    <div class="app-card overview">
      <a-space
        ><router-link to="/contests">返回比赛</router-link
        ><a-button :loading="loading" @click="load">刷新状态</a-button></a-space
      >
      <a-alert v-if="error" type="error">{{ error }}</a-alert>
      <template v-if="contest">
        <h2>{{ contest.title }}</h2>
        <a-space
          ><a-tag>{{ phaseLabel[contest.phase] }}</a-tag
          ><span>{{
            contest.contestType === "PRIVATE"
              ? "比赛专用题目"
              : "公开练习题训练赛"
          }}</span
          ><span
            >剩余 {{ contest.remainingCapacity }} / {{ contest.capacity }}</span
          ></a-space
        >
        <p>
          报名：{{ localTime(contest.registrationOpenAt) }} 至
          {{ localTime(contest.registrationCloseAt) }}
        </p>
        <p>
          比赛：{{ localTime(contest.startAt) }} 至
          {{ localTime(contest.endAt) }}（本地时间）
        </p>
        <p>
          报名状态：{{
            registration
              ? registrationLabel[registration.status] ?? registration.status
              : "暂未确认，请刷新"
          }}；参赛资格：{{
            registration
              ? registration.qualificationValid
                ? "有效"
                : "当前无有效资格"
              : "暂不可判断"
          }}
        </p>
        <a-alert
          v-if="!contest.registrationReady && contest.status === 'PUBLISHED'"
          type="warning"
          >报名数据正在核查，暂不接受新提交。</a-alert
        >
        <a-button
          type="primary"
          :loading="registering"
          :disabled="!canRegister"
          @click="register"
          >报名</a-button
        >
        <p class="muted">
          按各题最早 AC
          提交的受理时间计分；解题数优先，达到该解题数的时间次之。目前榜单功能待接入。
        </p>
        <a-button v-if="pending" :loading="submitting" @click="sendPending"
          >重试上次未确认的提交</a-button
        >
        <p v-if="pending" class="muted">
          上次提交结果尚未确认。重试会发送当时的代码和标识，即使比赛已结束或取消也可查询原受理结果。
        </p>
      </template>
      <a-alert v-if="submissionError" type="warning">{{
        submissionError
      }}</a-alert>
      <div v-if="lastId">
        <p>提交 {{ lastId }}</p>
        <p>
          状态：{{ result?.executionStatus ?? "查询中" }}
          {{ result?.judgeInfo?.verdict ?? result?.judgeInfo?.message ?? "" }}
        </p>
        <p v-if="result?.acceptedAt">
          受理时间：{{ localTime(result.acceptedAt) }}
        </p>
      </div>
    </div>
    <a-row v-if="contest?.problems?.length" :gutter="20">
      <a-col :span="11"
        ><div class="app-card problem">
          <a-select v-model="selected"
            ><a-option v-for="p in contest.problems" :key="p.id" :value="p.id"
              >{{ p.problemCode }} · {{ p.title }}</a-option
            ></a-select
          >
          <template v-if="problem"
            ><h3>{{ problem.title }}</h3>
            <p>
              {{ problem.limits.timeLimit }} ms ·
              {{ problem.limits.memoryLimit }} KiB
            </p>
            <MdViewer :value="problem.content"
          /></template></div
      ></a-col>
      <a-col :span="13"
        ><div class="app-card problem">
          <p>Java</p>
          <CodeEditor
            v-if="problem"
            :key="problem.id"
            :value="codes[selected] ?? initialCode"
            language="java"
            :handle-change="changeCode"
          />
          <a-button
            type="primary"
            :loading="submitting"
            :disabled="!canSubmit"
            @click="submit"
            >提交当前题目</a-button
          >
        </div></a-col
      >
    </a-row>
    <a-empty
      v-else-if="contest"
      description="开赛后，已确认报名的用户可在这里查看赛题并提交。"
    />
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { useRoute } from "vue-router";
import { useStore } from "vuex";
import { Message } from "@arco-design/web-vue";
import CodeEditor from "@/components/CodeEditor.vue";
import MdViewer from "@/components/MdViewer.vue";
import {
  Contest,
  Registration,
  SubmissionStatus,
  contestRequest,
  submissionStatus,
  stableKey,
  clearKey,
  errorText,
  localTime,
  phaseLabel,
} from "@/api/contest";
const route = useRoute(),
  store = useStore();
const id = String(route.params.id),
  contest = ref<Contest>(),
  registration = ref<Registration>();
const loading = ref(false),
  registering = ref(false),
  submitting = ref(false),
  error = ref(""),
  submissionError = ref("");
const selected = ref(""),
  codes = ref<Record<string, string>>({}),
  lastId = ref(""),
  result = ref<SubmissionStatus>();
type Pending = {
  body: {
    problemSnapshotId: string;
    publishedVersion?: number;
    language: string;
    code: string;
  };
  key: string;
  storage: string;
};
const pending = ref<Pending>();
const initialCode =
  "public class Main {\n    public static void main(String[] args) {\n        \n    }\n}";
const registrationLabel: Record<string, string> = {
  NOT_REGISTERED: "未报名",
  PROCESSING: "确认中",
  CONFIRMED: "已确认",
  FAILED: "失败",
  MANUAL_REVIEW: "人工核查中",
  CANCELLED: "已取消",
};
const problem = computed(() =>
  contest.value?.problems?.find((p) => p.id === selected.value)
);
const canRegister = computed(
  () =>
    contest.value?.phase === "REGISTRATION_OPEN" &&
    contest.value?.registrationReady &&
    registration.value?.status === "NOT_REGISTERED"
);
const canSubmit = computed(
  () =>
    !pending.value &&
    !!problem.value &&
    contest.value?.phase === "RUNNING" &&
    registration.value?.qualificationValid &&
    contest.value?.registrationReady
);
const scope = (type: string) =>
  `codeoj:${type}:${store.state.user.loginUser.id}:${id}`;
async function load() {
  if (loading.value) return;
  loading.value = true;
  error.value = "";
  try {
    contest.value = await contestRequest<Contest>(`/${id}`);
    registration.value = await contestRequest<Registration>(
      `/${id}/registrations/me`
    );
    if (!contest.value.problems?.some((p) => p.id === selected.value))
      selected.value = contest.value.problems?.[0]?.id ?? "";
  } catch (e) {
    error.value = errorText(e);
    registration.value = undefined;
  } finally {
    loading.value = false;
  }
}
async function register() {
  registering.value = true;
  try {
    const key = await stableKey(scope("registration"), { contestId: id });
    registration.value = await contestRequest<Registration>(
      `/${id}/registrations`,
      "POST",
      undefined,
      key
    );
    Message.success("报名请求已处理，请查看确认状态");
    await load();
  } catch (e) {
    error.value = `${errorText(e)}；结果未确认时请用原请求重试或刷新报名状态。`;
  } finally {
    registering.value = false;
  }
}
function changeCode(value: string) {
  codes.value[selected.value] = value;
}
async function poll() {
  if (!lastId.value) return;
  try {
    result.value = await submissionStatus(lastId.value);
    submissionError.value = "";
  } catch (e) {
    submissionError.value = `判题状态暂不可用：${errorText(e)}`;
  }
}
async function submit() {
  if (!canSubmit.value || submitting.value) return;
  submitting.value = true;
  const storage = `${scope("contest-submit")}:${selected.value}`;
  try {
    const body = {
      problemSnapshotId: selected.value,
      publishedVersion: contest.value?.publishedVersion,
      language: "java",
      code: codes.value[selected.value] ?? initialCode,
    };
    const key = await stableKey(storage, body);
    pending.value = { body, key, storage };
    await sendPending();
  } catch (e) {
    submissionError.value = errorText(e);
  } finally {
    submitting.value = false;
  }
}
async function sendPending() {
  if (!pending.value) return;
  submitting.value = true;
  const original = pending.value;
  try {
    lastId.value = await contestRequest<string>(
      `/${id}/submissions`,
      "POST",
      original.body,
      original.key
    );
    clearKey(original.storage);
    pending.value = undefined;
    result.value = undefined;
    await poll();
    Message.success("提交已受理");
  } catch (e) {
    const status = (e as { status?: number }).status;
    if (status && status >= 400 && status < 500) pending.value = undefined;
    submissionError.value = `${errorText(
      e
    )}；请保留代码，网络重试会沿用当前提交标识。`;
  } finally {
    submitting.value = false;
  }
}
let refresh: ReturnType<typeof setInterval>,
  statusPoll: ReturnType<typeof setInterval>;
onMounted(() => {
  load();
  refresh = setInterval(load, 10000);
  statusPoll = setInterval(poll, 2000);
});
onUnmounted(() => {
  clearInterval(refresh);
  clearInterval(statusPoll);
});
</script>
<style scoped>
.overview,
.problem {
  padding: 24px;
  margin-bottom: 20px;
}
.muted {
  color: var(--color-text-3);
}
.arco-alert {
  margin: 12px 0;
}
</style>
