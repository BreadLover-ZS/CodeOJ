<template>
  <div class="manage-contests">
    <div class="app-card section">
      <a-space
        ><h2>比赛管理</h2>
        <a-button :loading="busy" @click="load">刷新</a-button
        ><a-button @click="newDraft">新建草稿</a-button></a-space
      >
      <a-alert v-if="error" type="error">{{ error }}</a-alert>
      <a-table :data="rows" :pagination="false" row-key="id">
        <template #columns>
          <a-table-column title="比赛" data-index="title" />
          <a-table-column title="状态"
            ><template #cell="{ record }">{{
              phaseLabel[record.phase]
            }}</template></a-table-column
          >
          <a-table-column title="版本" data-index="version" />
          <a-table-column title="操作"
            ><template #cell="{ record }"
              ><a-button @click="select(record.id)">管理</a-button></template
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
          :disabled="rows.length < 20 || busy"
          @click="
            page++;
            load();
          "
          >下一页</a-button
        ></a-space
      >
    </div>
    <div class="app-card section">
      <h3>
        {{
          active
            ? `${active.title} · ${phaseLabel[active.phase]}`
            : "新建比赛草稿"
        }}
      </h3>
      <template v-if="!active || active.status === 'DRAFT'">
        <a-form :model="form" layout="vertical">
          <a-form-item label="比赛名称"
            ><a-input v-model="form.title" :max-length="120"
          /></a-form-item>
          <a-space
            ><a-form-item label="题目类型"
              ><a-select
                v-model="form.contestType"
                style="width: 210px"
                @change="resetProblems"
                ><a-option value="PRIVATE">比赛专用题目</a-option
                ><a-option value="PUBLIC_TRAINING"
                  >公开练习题训练赛</a-option
                ></a-select
              ></a-form-item
            ><a-form-item label="名额"
              ><a-input-number
                v-model="form.capacity"
                :min="1"
                :max="1000000" /></a-form-item
          ></a-space>
          <a-alert v-if="form.contestType === 'PUBLIC_TRAINING'"
            >此类型使用已公开的练习题，不属于未见题比赛。</a-alert
          >
          <div class="times">
            <label v-for="field in timeFields" :key="field.key"
              >{{ field.label }}（本地时间）<input
                v-model="times[field.key]"
                type="datetime-local"
                step="1"
            /></label>
          </div>
          <div
            v-for="(p, index) in form.problems"
            :key="index"
            class="problem-form"
          >
            <a-space
              ><h4>题目 {{ index + 1 }}</h4>
              <a-button
                :disabled="form.problems.length === 1"
                @click="form.problems.splice(index, 1)"
                >移除</a-button
              ></a-space
            >
            <a-form-item label="题号（大写英文字母开头，最多 8 位）"
              ><a-input v-model="p.problemCode"
            /></a-form-item>
            <a-form-item
              v-if="form.contestType === 'PUBLIC_TRAINING'"
              label="公开练习题 ID"
              ><a-input v-model="p.sourceQuestionId"
            /></a-form-item>
            <template v-else>
              <a-form-item label="题目标题"
                ><a-input v-model="p.title" :max-length="120"
              /></a-form-item>
              <a-form-item label="题面（Markdown）"
                ><a-textarea
                  v-model="p.content"
                  :auto-size="{ minRows: 4, maxRows: 16 }"
              /></a-form-item>
              <a-space v-if="p.limits"
                ><a-form-item label="每用例时限（ms）"
                  ><a-input-number
                    v-model="p.limits.timeLimit"
                    :min="1" /></a-form-item
                ><a-form-item label="内存上限（KiB）"
                  ><a-input-number
                    v-model="p.limits.memoryLimit"
                    :min="1"
                    :max="1048576" /></a-form-item
              ></a-space>
              <p>隐藏判题用例（只供管理员配置）</p>
              <div v-for="(sample, i) in p.cases" :key="i" class="case-fields">
                <a-textarea
                  v-model="sample.input"
                  placeholder="输入"
                /><a-textarea
                  v-model="sample.output"
                  placeholder="预期输出"
                /><a-button
                  :disabled="p.cases?.length === 1"
                  @click="p.cases?.splice(i, 1)"
                  >删除</a-button
                >
              </div>
              <a-button
                :disabled="(p.cases?.length ?? 0) >= 20"
                @click="p.cases?.push({ input: '', output: '' })"
                >添加用例</a-button
              >
            </template>
          </div>
          <a-space
            ><a-button
              :disabled="form.problems.length >= 20"
              @click="addProblem"
              >添加题目</a-button
            ><a-button type="primary" :loading="busy" @click="save"
              >保存草稿</a-button
            ><a-button
              v-if="active"
              :disabled="draftDirty"
              :loading="busy"
              @click="act('publish')"
              >发布已保存的草稿</a-button
            ></a-space
          >
        </a-form>
      </template>
      <template v-else>
        <p>
          发布版本 {{ active.publishedVersion }} · {{ active.capacity }} 个名额
          · 报名资格数据{{ active.registrationReady ? "可用" : "暂停使用" }}
        </p>
        <p>
          报名 {{ localTime(active.registrationOpenAt) }} —
          {{ localTime(active.registrationCloseAt) }}
        </p>
        <p>
          比赛 {{ localTime(active.startAt) }} — {{ localTime(active.endAt) }}
        </p>
        <p>发布后的容量、时间、题目和赛制已固定。若需调整，请取消并新建。</p>
        <a-input
          v-model="reason"
          placeholder="操作原因（取消、暂停或恢复必填）"
          :max-length="500"
        />
        <a-space class="operations"
          ><a-button
            v-if="
              active.status === 'PUBLISHED' &&
              (active.registrationMode === 'DB_SYNC' ||
                active.registrationReady)
            "
            :loading="busy"
            @click="act('readiness')"
            >{{
              active.registrationReady ? "暂停资格数据使用" : "恢复资格数据使用"
            }}</a-button
          ><a-popconfirm
            v-if="!['CANCELLED', 'FINISHED'].includes(active.status)"
            content="取消后立即关闭报名和提交，历史记录会保留。确认取消？"
            @ok="act('cancel')"
            ><a-button status="danger" :loading="busy"
              >取消比赛</a-button
            ></a-popconfirm
          ><router-link :to="`/contests/${active.id}`"
            >打开参赛页面</router-link
          ></a-space
        >
        <RegistrationOperations
          :key="active.id"
          :contest-id="active.id"
          :version="active.version"
          :mode="active.registrationMode"
          @changed="refreshActive"
        />
      </template>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { Message } from "@arco-design/web-vue";
import RegistrationOperations from "@/components/RegistrationOperations.vue";
import {
  Contest,
  ContestDraft,
  DraftProblem,
  contestRequest,
  errorText,
  localTime,
  phaseLabel,
} from "@/api/contest";
const rows = ref<Contest[]>([]),
  active = ref<Contest>(),
  busy = ref(false),
  error = ref(""),
  page = ref(0),
  reason = ref("");
const timeFields = [
  { key: "registrationOpenAt", label: "报名开始" },
  { key: "registrationCloseAt", label: "报名截止" },
  { key: "startAt", label: "开赛" },
  { key: "endAt", label: "结束" },
];
const times = reactive<Record<string, string>>({});
const savedDraft = ref("");
const form = reactive<ContestDraft>({
  title: "",
  contestType: "PRIVATE",
  capacity: 50,
  registrationOpenAt: "",
  registrationCloseAt: "",
  startAt: "",
  endAt: "",
  problems: [],
});
const draftDirty = computed(
  () => JSON.stringify({ form, times }) !== savedDraft.value
);
function blankProblem(): DraftProblem {
  return form.contestType === "PRIVATE"
    ? {
        problemCode: String.fromCharCode(65 + form.problems.length),
        title: "",
        content: "",
        limits: { timeLimit: 1000, memoryLimit: 131072 },
        cases: [{ input: "", output: "" }],
      }
    : {
        problemCode: String.fromCharCode(65 + form.problems.length),
        sourceQuestionId: "",
      };
}
function addProblem() {
  form.problems.push(blankProblem());
}
function resetProblems() {
  form.problems = [];
  addProblem();
}
function newDraft() {
  active.value = undefined;
  form.title = "";
  form.contestType = "PRIVATE";
  form.capacity = 50;
  form.expectedVersion = undefined;
  resetProblems();
  timeFields.forEach((f) => (times[f.key] = ""));
  reason.value = "";
}
async function load() {
  try {
    rows.value = await contestRequest<Contest[]>(
      `?admin=true&page=${page.value}`
    );
  } catch (e) {
    error.value = errorText(e);
  }
}
async function select(id: string) {
  try {
    active.value = await contestRequest<Contest>(`/${id}?admin=true`);
    reason.value = "";
    if (active.value.status === "DRAFT" && active.value.draft) {
      Object.assign(form, active.value.draft);
      form.expectedVersion = active.value.version;
      for (const f of timeFields) {
        const date = new Date(
          (form as unknown as Record<string, string>)[f.key]
        );
        times[f.key] = new Date(
          date.getTime() - date.getTimezoneOffset() * 60000
        )
          .toISOString()
          .slice(0, 19);
      }
      savedDraft.value = JSON.stringify({ form, times });
    }
  } catch (e) {
    error.value = errorText(e);
  }
}
async function save() {
  busy.value = true;
  error.value = "";
  try {
    const body = { ...form };
    for (const f of timeFields) {
      if (!times[f.key]) throw new Error("请填写全部时间");
      (body as unknown as Record<string, string>)[f.key] = new Date(
        times[f.key]
      ).toISOString();
    }
    const saved = await contestRequest<Contest>(
      active.value ? `/${active.value.id}` : "",
      active.value ? "PUT" : "POST",
      { ...body, expectedVersion: active.value?.version }
    );
    await select(saved.id);
    await load();
    Message.success("草稿已保存");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function act(action: string) {
  if (!active.value) return;
  if (action !== "publish" && !reason.value.trim()) {
    error.value = "请填写操作原因";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    const id = active.value.id;
    await contestRequest(`/${id}/${action}`, "POST", {
      expectedVersion: active.value.version,
      reason: reason.value,
      registrationReady: !active.value.registrationReady,
    });
    await select(id);
    await load();
    Message.success("操作已完成");
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function refreshActive() {
  if (active.value) await select(active.value.id);
  await load();
}
onMounted(() => {
  newDraft();
  load();
});
</script>
<style scoped>
.section {
  padding: 24px;
  margin-bottom: 20px;
}
h2 {
  margin: 0;
}
.times {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin: 20px 0;
}
.times label {
  display: grid;
  gap: 8px;
}
.times input {
  padding: 8px;
  border: 1px solid var(--color-border-2);
  border-radius: 4px;
}
.problem-form {
  border-top: 1px solid var(--color-border-2);
  padding: 16px 0;
}
.case-fields {
  display: flex;
  gap: 12px;
  margin: 12px 0;
}
.operations {
  margin-top: 16px;
}
.arco-table {
  margin: 20px 0;
}
.arco-alert {
  margin: 12px 0;
}
</style>
