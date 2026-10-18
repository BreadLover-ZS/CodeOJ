<template>
  <section class="registration-operations">
    <a-space
      ><h3>报名确认与对账</h3>
      <a-button :loading="busy" @click="load">刷新记录</a-button></a-space
    >
    <p>
      当前通道：{{ modeLabel[settings?.mode ?? mode] ?? mode }}；版本
      {{ settings?.revision ?? version }}
    </p>
    <a-alert v-if="settings?.issue" type="warning"
      >入口已暂停：{{ settings.issue }}。请核查请求和发布证据后再恢复。</a-alert
    >
    <a-alert v-if="error" type="error">{{ error }}</a-alert>
    <a-form :model="operation" layout="vertical">
      <a-form-item label="操作原因"
        ><a-input
          v-model="operation.reason"
          :max-length="500"
          placeholder="必填，将保留在操作记录中"
      /></a-form-item>
      <a-button
        v-if="(settings?.mode ?? mode) === 'DB_SYNC'"
        :loading="busy"
        @click="changeGate('prepare')"
        >关闭普通报名，准备秒杀通道</a-button
      >
      <a-button v-else :loading="busy" @click="changeGate('reconcile')"
        >关闭入口核对账本，核对通过后恢复</a-button
      >
      <p class="muted">
        账本缺失或容量不一致时保持暂停，已有确认资格不会被释放。
      </p>
      <a-space>
        <a-form-item label="复核后追加发布次数（0 表示不追加）"
          ><a-input-number
            v-model="operation.additionalPublishAttempts"
            :min="0"
            :max="20"
        /></a-form-item>
        <a-form-item label="复核后延长恢复期限（秒，0 表示不延长）"
          ><a-input-number
            v-model="operation.extendRecoverySeconds"
            :min="0"
            :max="3600"
        /></a-form-item>
      </a-space>
    </a-form>
    <a-table :data="requests" :pagination="false" row-key="requestId">
      <template #columns>
        <a-table-column title="报名请求"
          ><template #cell="{ record }"
            ><div>{{ record.requestId }}</div>
            <span class="muted"
              >用户 {{ record.userId }} · 版本 {{ record.stateVersion }}</span
            ></template
          ></a-table-column
        >
        <a-table-column title="业务状态"
          ><template #cell="{ record }"
            ><div>{{ statusLabel[record.status] ?? record.status }}</div>
            <span class="muted">{{ record.reclaimState }}</span>
            <div v-if="record.lastError">{{ record.lastError }}</div></template
          ></a-table-column
        >
        <a-table-column title="发布证据"
          ><template #cell="{ record }"
            ><span v-if="!record.attempts.length">尚无发送记录</span>
            <div v-for="attempt in record.attempts" :key="attempt.attemptNo">
              第 {{ attempt.attemptNo }} 次：{{
                confirmationLabel[attempt.confirmState] ?? attempt.confirmState
              }}{{ attempt.returned ? "，未路由" : "" }}
            </div></template
          ></a-table-column
        >
        <a-table-column title="复核操作"
          ><template #cell="{ record }"
            ><a-space direction="vertical"
              ><a-button
                v-if="record.status === 'MANUAL_REVIEW'"
                :loading="busy"
                @click="review(record, 'CONTINUE')"
                >继续确认原请求</a-button
              ><a-popconfirm
                v-if="['PROCESSING', 'MANUAL_REVIEW'].includes(record.status)"
                content="终止后此用户不能在本场重新报名，确认终止？"
                @ok="review(record, 'FAIL')"
                ><a-button :loading="busy" status="danger"
                  >终止并安排回收</a-button
                ></a-popconfirm
              ></a-space
            ></template
          ></a-table-column
        >
      </template>
    </a-table>
    <a-space
      ><a-button
        :disabled="page === 0 || busy"
        @click="
          page--;
          load();
        "
        >上一页</a-button
      ><span>第 {{ page + 1 }} 页</span
      ><a-button
        :disabled="requests.length < 20 || busy"
        @click="
          page++;
          load();
        "
        >下一页</a-button
      ></a-space
    >
  </section>
</template>
<script setup lang="ts">
import { defineProps, defineEmits, onMounted, reactive, ref, watch } from "vue";
import { Message } from "@arco-design/web-vue";
import { contestRequest, errorText } from "@/api/contest";
const props = defineProps<{
  contestId: string;
  version: number;
  mode: string;
}>();
const emit = defineEmits<{ (event: "changed"): void }>();
interface Settings {
  revision: number;
  mode: string;
  issue?: string;
}
interface RequestRecord {
  requestId: string;
  userId: string;
  status: string;
  stateVersion: number;
  reclaimState: string;
  lastError?: string;
  attempts: Array<{
    attemptNo: number;
    confirmState: string;
    returned: boolean;
  }>;
}
const settings = ref<Settings>(),
  requests = ref<RequestRecord[]>([]),
  busy = ref(false),
  error = ref(""),
  page = ref(0);
const operation = reactive({
  reason: "",
  additionalPublishAttempts: 0,
  extendRecoverySeconds: 0,
});
const modeLabel: Record<string, string> = {
  DB_SYNC: "普通数据库报名",
  REDIS_PAUSED: "秒杀入口暂停",
  REDIS_RECONCILING: "正在对账",
  REDIS_READY: "秒杀报名",
};
const statusLabel: Record<string, string> = {
  PROCESSING: "确认中",
  CONFIRMED: "已确认",
  MANUAL_REVIEW: "待人工复核",
  FAILED: "已终止",
  CANCELLED: "比赛已取消",
};
const confirmationLabel: Record<string, string> = {
  UNKNOWN: "结果未知，名额继续保留",
  ACK: "Broker 已确认",
  NACK: "Broker 未确认",
};
async function load() {
  try {
    const data = await contestRequest<{
      settings: Settings;
      requests: RequestRecord[];
    }>(`/${props.contestId}/registration-admin?page=${page.value}`);
    settings.value = data.settings;
    requests.value = data.requests;
    error.value = "";
  } catch (e) {
    error.value = errorText(e);
  }
}
function validReason() {
  if (!operation.reason.trim()) {
    error.value = "请填写操作原因";
    return false;
  }
  return true;
}
async function changeGate(action: string) {
  if (!validReason()) return;
  busy.value = true;
  try {
    await contestRequest(
      `/${props.contestId}/registration-admin/${action}`,
      "POST",
      {
        expectedVersion: settings.value?.revision ?? props.version,
        reason: operation.reason,
      }
    );
    Message.success("报名通道状态已更新");
    await load();
    emit("changed");
  } catch (e) {
    error.value = errorText(e);
    emit("changed");
  } finally {
    busy.value = false;
  }
}
async function review(row: RequestRecord, decision: string) {
  if (!validReason()) return;
  busy.value = true;
  try {
    await contestRequest(
      `/${props.contestId}/registration-admin/requests/${row.requestId}/review`,
      "POST",
      { ...operation, decision, expectedVersion: row.stateVersion }
    );
    await load();
    Message.success("复核决定已保存");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
watch(() => [props.contestId, props.version], load);
onMounted(load);
</script>
<style scoped>
.registration-operations {
  border-top: 1px solid var(--color-border-2);
  margin-top: 24px;
  padding-top: 20px;
}
.arco-alert {
  margin: 12px 0;
}
.muted {
  color: var(--color-text-3);
}
.arco-table {
  margin: 16px 0;
}
</style>
