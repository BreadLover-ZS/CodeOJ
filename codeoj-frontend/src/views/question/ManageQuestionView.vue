<template>
  <div id="manageQuestionView" class="list-page">
    <div class="page-head">
      <h2 class="page-title">题目管理</h2>
      <a-button type="primary" @click="router.push('/add/question')">
        <template #icon><icon-plus /></template>
        新建题目
      </a-button>
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
        <template #title="{ record }">
          <a
            class="q-title"
            @click="router.push(`/view/question/${record.id}`)"
          >
            {{ record.title }}
          </a>
        </template>
        <template #tags="{ record }">
          <a-tag
            v-for="(tag, i) in record.tags"
            :key="i"
            size="small"
            class="tag"
          >
            {{ tag }}
          </a-tag>
        </template>
        <template #createTime="{ record }">
          {{ moment(record.createTime).format("YYYY-MM-DD") }}
        </template>
        <template #optional="{ record }">
          <a-space>
            <a-button size="small" @click="doUpdate(record)">
              <template #icon><icon-edit /></template>
              修改
            </a-button>
            <a-button size="small" status="danger" @click="doDelete(record)">
              <template #icon><icon-delete /></template>
              删除
            </a-button>
          </a-space>
        </template>
      </a-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watchEffect } from "vue";
import { Question, QuestionControllerService } from "../../../generated";
import { Message, Modal } from "@arco-design/web-vue";
import { useRouter } from "vue-router";
import moment from "moment";

const router = useRouter();
const tableRef = ref();

const dataList = ref([]);
const total = ref(0);
const searchParams = ref({ pageSize: 10, current: 1 });

const loadData = async () => {
  const res = await QuestionControllerService.listQuestionByPageUsingPost(
    searchParams.value
  );
  if (res.code === 0) {
    dataList.value = res.data.records;
    total.value = res.data.total;
  } else {
    Message.error("加载失败，" + res.message);
  }
};

watchEffect(() => {
  loadData();
});

onMounted(() => {
  loadData();
});

const columns = [
  { title: "ID", dataIndex: "id", width: 90 },
  { title: "标题", slotName: "title" },
  { title: "标签", slotName: "tags" },
  { title: "提交数", dataIndex: "submitNum", width: 90 },
  { title: "通过数", dataIndex: "acceptedNum", width: 90 },
  { title: "创建时间", slotName: "createTime", width: 120 },
  { title: "操作", slotName: "optional", width: 160 },
];

const onPageChange = (page: number) => {
  searchParams.value = { ...searchParams.value, current: page };
};

const doUpdate = (question: Question) => {
  router.push({ path: "/update/question", query: { id: question.id } });
};

const doDelete = (question: Question) => {
  Modal.confirm({
    title: `确认删除题目「${question.title}」？`,
    content: "删除后不可恢复，请谨慎操作",
    okText: "删除",
    okButtonProps: { status: "danger" },
    onOk: async () => {
      const res = await QuestionControllerService.deleteQuestionUsingPost({
        id: question.id,
      });
      if (res.code === 0) {
        Message.success("删除成功");
        loadData();
      } else {
        Message.error("删除失败，" + res.message);
      }
    },
  });
};
</script>

<style scoped>
.table-card {
  padding: 8px 16px;
}

.q-title {
  color: var(--ink-1);
  font-weight: 500;
}
.q-title:hover {
  color: var(--brand-6);
}

.tag {
  background: var(--brand-1);
  color: var(--brand-6);
  border: none;
  border-radius: 6px;
  margin-right: 4px;
}
</style>
