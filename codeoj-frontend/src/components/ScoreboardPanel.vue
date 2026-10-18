<template>
  <section class="app-card scoreboard-panel">
    <a-space
      ><h3>比赛榜单</h3>
      <a-button :loading="busy" @click="load">刷新榜单</a-button></a-space
    >
    <a-alert v-if="error" type="warning">{{ error }}</a-alert>
    <template v-if="board">
      <p>
        {{
          board.final
            ? "最终榜单"
            : board.status === "CANCELLED"
            ? "比赛已取消，成绩仅供查看"
            : "临时榜单"
        }}
        · 投影版本 {{ board.projectionVersion }} · 规则 {{ board.ruleVersion }}
      </p>
      <p>
        数据时间 {{ localTime(board.asOf) }} · 待判定 {{ board.pendingCount }} ·
        待更新成绩 {{ board.projectionPendingCount }}
      </p>
      <p class="muted">
        按解题数降序、各题最早 AC
        受理时间中的最晚时间升序排列。同分同时间并列，零分并列；用户 ID
        只用于稳定展示。
      </p>
      <a-table :data="board.entries" :pagination="false" row-key="userId">
        <template #columns>
          <a-table-column title="名次" data-index="rank" />
          <a-table-column title="用户 ID" data-index="userId" />
          <a-table-column title="解题数" data-index="solvedCount" />
          <a-table-column title="达到当前成绩的受理时间"
            ><template #cell="{ record }">{{
              localTime(record.achievedAt)
            }}</template></a-table-column
          >
        </template>
      </a-table>
      <a-space class="page-controls">
        <a-button
          :disabled="page === 0 || busy"
          @click="
            page--;
            load();
          "
          >上一页榜单</a-button
        >
        <span>第 {{ page + 1 }} 页，共 {{ board.total }} 人</span>
        <a-button
          :disabled="(page + 1) * 20 >= board.total || busy"
          @click="
            page++;
            load();
          "
          >下一页榜单</a-button
        >
      </a-space>
      <a-space class="version-controls">
        <a-input
          v-model="selectedVersion"
          placeholder="历史投影版本（留空为当前版本）"
          style="width: 290px"
        />
        <a-button
          :disabled="busy"
          @click="
            page = 0;
            load();
          "
          >查看榜单版本</a-button
        >
      </a-space>
      <template v-if="admin">
        <h4>成绩核查与结算</h4>
        <a-alert
          >先解决待判定和报名异常，再重建校验并固定最终版本。旧榜单会保留。</a-alert
        >
        <a-input
          v-model="reason"
          :max-length="500"
          placeholder="成绩操作原因（必填，保留审计）"
        />
        <a-space class="page-controls">
          <a-button :disabled="busy || !current" @click="act('cache-clear')"
            >清除榜单缓存</a-button
          >
          <a-button
            :disabled="busy || !current || board.status !== 'FINALIZING'"
            @click="act('rebuild')"
            >从提交重建成绩</a-button
          >
          <a-popconfirm
            content="确认已核查成绩？校验通过后固定最终版本。"
            @ok="act('finish')"
            ><a-button
              :disabled="busy || !current || board.status !== 'FINALIZING'"
              >完成结算</a-button
            ></a-popconfirm
          >
          <a-popconfirm
            content="将从权威记录生成新的最终榜单版本，保留原最终榜单。"
            @ok="act('revise')"
            ><a-button
              :disabled="busy || !current || board.status !== 'FINISHED'"
              >修订最终榜单</a-button
            ></a-popconfirm
          >
        </a-space>
        <a-space
          ><span>追加发布次数（每事件）</span
          ><a-input-number
            v-model="additionalPublishAttempts"
            :min="0"
            :max="20"
          /><a-button
            :disabled="
              busy ||
              !current ||
              ['FINISHED', 'CANCELLED'].includes(board.status)
            "
            @click="act('replay')"
            >重放待更新成绩事件</a-button
          ></a-space
        >
        <p v-if="operationResult">{{ operationResult }}</p>
        <template v-if="operations">
          <h4>待判定提交</h4>
          <a-table
            :data="operations.pending"
            :pagination="false"
            row-key="submissionId"
            ><template #columns
              ><a-table-column
                title="提交 ID"
                data-index="submissionId" /><a-table-column
                title="状态"
                data-index="executionStatus" /><a-table-column
                title="诊断码"
                data-index="errorCode" /></template
          ></a-table>
          <h4>待更新成绩事件</h4>
          <a-table
            :data="operations.publications"
            :pagination="false"
            row-key="eventId"
            ><template #columns
              ><a-table-column
                title="提交 ID"
                data-index="submissionId" /><a-table-column
                title="发布状态"
                data-index="publishState" /><a-table-column
                title="发送次数"
                data-index="attempts" /><a-table-column
                title="诊断码"
                data-index="errorCode" /></template
          ></a-table>
          <a-space class="page-controls"
            ><a-button
              :disabled="operationsPage === 0 || busy"
              @click="
                operationsPage--;
                load();
              "
              >上一页待处理</a-button
            ><span>待处理第 {{ operationsPage + 1 }} 页</span
            ><a-button
              :disabled="
                busy ||
                (operations.pending.length < 20 &&
                  operations.publications.length < 20)
              "
              @click="
                operationsPage++;
                load();
              "
              >下一页待处理</a-button
            ></a-space
          >
        </template>
      </template>
    </template>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, ref, watch, defineProps, defineEmits } from "vue";
import { contestRequest, errorText, localTime } from "@/api/contest";
interface Board {
  contestVersion: number;
  projectionVersion: number;
  activeProjectionVersion: number;
  revision: number;
  ruleVersion: string;
  status: string;
  final: boolean;
  asOf: string;
  pendingCount: number;
  projectionPendingCount: number;
  total: number;
  entries: Array<{
    userId: string;
    rank: number;
    solvedCount: number;
    achievedAt: string | null;
  }>;
}
interface Operations {
  pending: Array<Record<string, unknown>>;
  publications: Array<Record<string, unknown>>;
}
const props = defineProps<{
  contestId: string;
  admin?: boolean;
  version?: number;
}>();
const emit = defineEmits<{ (event: "changed"): void }>();
const board = ref<Board>(),
  operations = ref<Operations>(),
  busy = ref(false),
  error = ref("");
const page = ref(0),
  operationsPage = ref(0),
  selectedVersion = ref(""),
  reason = ref(""),
  additionalPublishAttempts = ref(0),
  operationResult = ref("");
const current = computed(
  () => board.value?.projectionVersion === board.value?.activeProjectionVersion
);
const base = computed(
  () => `/${encodeURIComponent(props.contestId)}/scoreboard`
);
async function load() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    if (
      selectedVersion.value &&
      !/^[1-9][0-9]{0,8}$/.test(selectedVersion.value)
    )
      throw new Error("请输入有效的榜单版本号");
    board.value = await contestRequest<Board>(
      `${base.value}?page=${page.value}&size=20${
        selectedVersion.value ? `&version=${selectedVersion.value}` : ""
      }`
    );
    if (props.admin)
      operations.value = await contestRequest<Operations>(
        `${base.value}/operations?page=${operationsPage.value}`
      );
  } catch (e) {
    error.value = `榜单暂不可用：${errorText(e)}`;
    board.value = undefined;
    operations.value = undefined;
  } finally {
    busy.value = false;
  }
}
async function act(action: string) {
  if (busy.value || !board.value || !current.value) return;
  if (!reason.value.trim()) {
    error.value = "请填写成绩操作原因";
    return;
  }
  busy.value = true;
  error.value = "";
  operationResult.value = "";
  try {
    const result = await contestRequest<
      { matchedPrevious?: boolean } | number | boolean
    >(`${base.value}/${action}`, "POST", {
      expectedVersion: board.value.contestVersion,
      expectedProjectionVersion: board.value.projectionVersion,
      expectedRevision: board.value.revision,
      reason: reason.value,
      additionalPublishAttempts: additionalPublishAttempts.value,
    });
    operationResult.value =
      typeof result === "object" && result.matchedPrevious === false
        ? "已重建新版本，发现原投影与权威记录不一致，请核查后结算。"
        : "成绩操作已完成";
    emit("changed");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
    const failure = error.value;
    await load();
    if (failure) error.value = failure;
  }
}
watch(() => props.version, load);
onMounted(load);
</script>
<style scoped>
.scoreboard-panel {
  padding: 24px;
  margin-bottom: 20px;
}
.page-controls,
.version-controls {
  display: flex;
  margin: 16px 0;
}
.muted {
  color: var(--color-text-3);
}
.arco-alert,
.arco-input-wrapper {
  margin: 10px 0;
}
</style>
