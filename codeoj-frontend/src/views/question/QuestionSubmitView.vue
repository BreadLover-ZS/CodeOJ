<template>
  <div id="questionSubmitView" class="list-page">
    <div class="page-head">
      <h2 class="page-title">提交记录</h2>
    </div>

    <div class="app-card search-card">
      <a-form :model="searchParams" layout="inline">
        <a-form-item field="questionId" label="题号">
          <a-input
            v-model="searchParams.questionId"
            placeholder="输入题号"
            class="search-input"
          />
        </a-form-item>
        <a-form-item field="language" label="语言">
          <a-select
            v-model="searchParams.language"
            placeholder="全部语言"
            class="search-select"
          >
            <a-option>java</a-option>
            <a-option>cpp</a-option>
            <a-option>go</a-option>
          </a-select>
        </a-form-item>
        <a-form-item>
          <a-button type="primary" @click="doSubmit">
            <template #icon><icon-search /></template>
            搜索
          </a-button>
        </a-form-item>
      </a-form>
    </div>

    <div class="app-card table-card">
      <a-table
        :ref="tableRef"
        :columns="columns"
        :data="dataList"
        :pagination="{
          showTotal: true,
          pageSize: searchParams.pageSize,
          current: searchParams.current,
          total,
          showJumper: true,
        }"
        @page-change="onPageChange"
      >
        <template #judgeInfo="{ record }">
          <span v-if="record.judgeInfo?.message" class="judge-msg">
            {{ record.judgeInfo.message }}
            <span v-if="record.judgeInfo.time != null" class="judge-detail">
              · {{ record.judgeInfo.time }}ms
            </span>
            <span v-if="record.judgeInfo.memory != null" class="judge-detail">
              · {{ record.judgeInfo.memory }}KiB
            </span>
            <span v-else class="judge-detail"> · 内存未知</span>
          </span>
          <span v-else>-</span>
        </template>
        <template #status="{ record }">
          <a-tag :color="submissionStatus(record).color" class="status-tag">
            <span
              class="status-dot"
              :style="{
                background: submissionStatus(record).dot,
              }"
            ></span>
            {{ submissionStatus(record).text }}
          </a-tag>
        </template>
        <template #createTime="{ record }">
          {{
            moment(record.acceptedAt ?? record.createTime).format("MM-DD HH:mm")
          }}
        </template>
      </a-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watchEffect } from "vue";
import { QuestionControllerService } from "../../../generated";
import { Message } from "@arco-design/web-vue";
import moment from "moment";

const tableRef = ref();

const dataList = ref([]);
const total = ref(0);
const searchParams = ref<any>({
  questionId: undefined,
  language: undefined,
  pageSize: 10,
  current: 1,
});

const loadData = async () => {
  const res = await QuestionControllerService.listQuestionSubmitByPageUsingPost(
    {
      ...searchParams.value,
      sortField: "createTime",
      sortOrder: "descend",
    }
  );
  if (res.code === 0) {
    dataList.value = res.data.records;
    total.value = res.data.total;
    refreshJudgingStatus();
  } else {
    Message.error("加载失败，" + res.message);
  }
};

// 判题轮询：存在待判题(0)/判题中(1)的记录时，每 3 秒自动刷新直至全部结束
let pollTimer: number | undefined;

const refreshJudgingStatus = () => {
  const records = dataList.value ?? [];
  const hasPending = records.some(
    (record: any) => record.status === 0 || record.status === 1
  );
  if (hasPending && !pollTimer) {
    pollTimer = window.setInterval(() => {
      loadData();
    }, 3000);
  } else if (!hasPending && pollTimer) {
    clearInterval(pollTimer);
    pollTimer = undefined;
  }
};

onBeforeUnmount(() => {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = undefined;
  }
});

watchEffect(() => {
  loadData();
});

const columns = [
  { title: "提交号", dataIndex: "id" },
  { title: "题号", dataIndex: "questionId" },
  { title: "语言", dataIndex: "language" },
  { title: "判题信息", slotName: "judgeInfo" },
  { title: "判题状态", slotName: "status" },
  { title: "提交者", dataIndex: "userId" },
  { title: "时间", slotName: "createTime" },
];

const statusMap: Record<number, { text: string; color: string; dot: string }> =
  {
    0: { text: "待判题", color: "orange", dot: "#ffa600" },
    1: { text: "判题中", color: "arcoblue", dot: "#165dff" },
    2: { text: "判题完成", color: "green", dot: "#00b42a" },
    3: { text: "平台判题异常", color: "red", dot: "#f53f3f" },
  };

const executionStatusMap: Record<
  string,
  { text: string; color: string; dot: string }
> = {
  QUEUED: { text: "等待判题", color: "orange", dot: "#ffa600" },
  RUNNING: { text: "判题中", color: "arcoblue", dot: "#165dff" },
  RETRY_WAIT: { text: "等待重试", color: "orange", dot: "#ffa600" },
  COMPLETED: { text: "判题完成", color: "green", dot: "#00b42a" },
  NEEDS_ATTENTION: { text: "需管理员处理", color: "red", dot: "#f53f3f" },
};

const submissionStatus = (record: any) =>
  executionStatusMap[record.executionStatus] ??
  statusMap[record.status] ?? { text: "未知", color: "gray", dot: "#c9cdd4" };

const onPageChange = (page: number) => {
  searchParams.value = { ...searchParams.value, current: page };
};

const doSubmit = () => {
  searchParams.value = { ...searchParams.value, current: 1 };
};
</script>

<style scoped>
.search-card {
  padding: 20px;
  margin-bottom: 20px;
}

.search-input {
  width: 200px;
}
.search-select {
  width: 160px;
}

.table-card {
  padding: 8px 16px;
}

.judge-msg {
  color: var(--ink-2);
}
.judge-detail {
  color: var(--ink-4);
  font-size: 12px;
}

.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
}
</style>
