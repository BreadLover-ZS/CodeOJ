<template>
  <div id="questionSubmitView">
    <a-form :model="searchParams" layout="inline">
      <a-form-item field="questionId" label="题号" style="min-width: 240px">
        <a-input v-model="searchParams.questionId" placeholder="请输入" />
      </a-form-item>
      <a-form-item field="language" label="编程语言" style="min-width: 240px">
        <a-select
          v-model="searchParams.language"
          :style="{ width: '320px' }"
          placeholder="选择编程语言"
        >
          <a-option>java</a-option>
          <a-option>cpp</a-option>
          <a-option>go</a-option>
        </a-select>
      </a-form-item>
      <a-form-item>
        <a-button type="primary" @click="doSubmit">搜索</a-button>
      </a-form-item>
    </a-form>
    <a-divider size="0" />
    <a-table
      :ref="tableRef"
      :columns="columns"
      :data="dataList"
      :pagination="{
        showTotal: true,
        pageSize: searchParams.pageSize,
        current: searchParams.current,
        total,
      }"
      @page-change="onPageChange"
    >
      <template #judgeInfo="{ record }">
        <span v-if="record.judgeInfo?.message">
          {{ record.judgeInfo.message }}
          <span v-if="record.judgeInfo.time">
            · {{ record.judgeInfo.time }}ms</span
          >
          <span v-if="record.judgeInfo.memory">
            · {{ record.judgeInfo.memory }}KB</span
          >
        </span>
        <span v-else>-</span>
      </template>
      <template #status="{ record }">
        <a-tag :color="statusMap[record.status]?.color ?? 'gray'">
          {{ statusMap[record.status]?.text ?? record.status }}
        </a-tag>
      </template>
      <template #createTime="{ record }">
        {{ moment(record.createTime).format("YYYY-MM-DD HH:mm") }}
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watchEffect } from "vue";
import {
  Question,
  QuestionControllerService,
  QuestionSubmitQueryRequest,
} from "../../../generated";
import message from "@arco-design/web-vue/es/message";
import { useRouter } from "vue-router";
import moment from "moment";

const tableRef = ref();

const dataList = ref([]);
const total = ref(0);
const searchParams = ref<QuestionSubmitQueryRequest>({
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
    message.error("加载失败，" + res.message);
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

/**
 * 监听 searchParams 变量，改变时触发页面的重新加载
 */
watchEffect(() => {
  loadData();
});

const columns = [
  {
    title: "提交号",
    dataIndex: "id",
  },
  {
    title: "编程语言",
    dataIndex: "language",
  },
  {
    title: "判题信息",
    slotName: "judgeInfo",
  },
  {
    title: "判题状态",
    slotName: "status",
  },
  {
    title: "题目 id",
    dataIndex: "questionId",
  },
  {
    title: "提交者 id",
    dataIndex: "userId",
  },
  {
    title: "创建时间",
    slotName: "createTime",
  },
];

/**
 * 判题状态显示映射（与后端 QuestionSubmitStatusEnum 对应）
 */
const statusMap: Record<number, { text: string; color: string }> = {
  0: { text: "待判题", color: "orange" },
  1: { text: "判题中", color: "arcoblue" },
  2: { text: "判题完成", color: "green" },
  3: { text: "判题失败", color: "red" },
};

const onPageChange = (page: number) => {
  searchParams.value = {
    ...searchParams.value,
    current: page,
  };
};

const router = useRouter();

/**
 * 跳转到做题页面
 * @param question
 */
const toQuestionPage = (question: Question) => {
  router.push({
    path: `/view/question/${question.id}`,
  });
};

/**
 * 确认搜索，重新加载数据
 */
const doSubmit = () => {
  // 这里需要重置搜索页号
  searchParams.value = {
    ...searchParams.value,
    current: 1,
  };
};
</script>

<style scoped>
#questionSubmitView {
  max-width: 1280px;
  margin: 0 auto;
}
</style>
