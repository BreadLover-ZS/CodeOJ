<template>
  <div id="questionsView" class="list-page">
    <div class="page-head">
      <h2 class="page-title">题库</h2>
      <span class="total-hint">共 {{ total }} 道题目</span>
    </div>

    <!-- 搜索栏 -->
    <div class="app-card search-card">
      <a-form :model="searchParams" layout="inline" class="search-form">
        <a-form-item field="title">
          <a-input
            v-model="searchParams.title"
            placeholder="搜索题目名称"
            allow-clear
            class="search-input"
          >
            <template #prefix><icon-search /></template>
          </a-input>
        </a-form-item>
        <a-form-item field="tags">
          <a-input-tag
            v-model="searchParams.tags"
            placeholder="按标签筛选"
            class="search-input"
          />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" @click="doSubmit">
            <template #icon><icon-search /></template>
            搜索
          </a-button>
          <a-button @click="doReset">重置</a-button>
        </a-form-item>
      </a-form>
    </div>

    <!-- 题目卡片网格 -->
    <div v-if="dataList.length" class="question-grid">
      <div
        v-for="q in dataList"
        :key="q.id"
        class="question-card"
        @click="toQuestionPage(q)"
      >
        <div class="card-top">
          <span class="card-index">#{{ String(q.id).slice(-5) }}</span>
          <a-tag
            v-if="q.submitNum"
            :color="acceptedRate(q) >= 50 ? 'green' : 'orange'"
            size="small"
            class="rate-tag"
          >
            {{ acceptedRate(q).toFixed(1) }}%
          </a-tag>
        </div>
        <h3 class="card-title">{{ q.title }}</h3>
        <div class="card-tags" v-if="q.tags?.length">
          <a-tag v-for="(tag, i) in q.tags" :key="i" size="small" class="tag">{{
            tag
          }}</a-tag>
        </div>
        <div class="card-meta">
          <span><icon-thunderbolt :size="14" /> 通过 {{ q.acceptedNum }}</span>
          <span><icon-edit :size="14" /> 提交 {{ q.submitNum }}</span>
          <span class="go"><icon-right :size="14" /></span>
        </div>
      </div>
    </div>

    <a-empty v-else description="暂无题目" class="empty-box" />

    <!-- 分页 -->
    <div class="pagination-wrap">
      <a-pagination
        :total="total"
        :current="searchParams.current"
        :page-size="searchParams.pageSize"
        show-total
        show-page-size
        :page-size-options="[8, 12, 20, 50]"
        @change="onPageChange"
        @page-size-change="onPageSizeChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watchEffect } from "vue";
import {
  Question,
  QuestionControllerService,
  QuestionQueryRequest,
} from "../../../generated";
import { Message } from "@arco-design/web-vue";
import { useRouter } from "vue-router";

const router = useRouter();

const dataList = ref<Question[]>([]);
const total = ref(0);
const searchParams = ref<QuestionQueryRequest>({
  title: "",
  tags: [],
  pageSize: 12,
  current: 1,
});

const loadData = async () => {
  const res = await QuestionControllerService.listQuestionVoByPageUsingPost(
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

const acceptedRate = (q: Question): number =>
  q.submitNum ? (q.acceptedNum / q.submitNum) * 100 : 0;

const toQuestionPage = (question: Question) => {
  router.push(`/view/question/${question.id}`);
};

const doSubmit = () => {
  searchParams.value = {
    ...searchParams.value,
    current: 1,
  };
};

const doReset = () => {
  searchParams.value = {
    title: "",
    tags: [],
    pageSize: searchParams.value.pageSize,
    current: 1,
  };
};

const onPageChange = (page: number) => {
  searchParams.value = { ...searchParams.value, current: page };
};

const onPageSizeChange = (size: number) => {
  searchParams.value = { ...searchParams.value, pageSize: size, current: 1 };
};
</script>

<style scoped>
.total-hint {
  font-size: 13px;
  color: var(--ink-4);
}

/* 搜索卡 */
.search-card {
  padding: 20px;
  margin-bottom: 24px;
}

.search-form {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.search-input {
  width: 260px;
}
.search-input :deep(.arco-input) {
  border-radius: 10px;
}

/* 卡片网格 */
.question-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(270px, 1fr));
  gap: 20px;
}

.question-card {
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: 22px;
  cursor: pointer;
  transition: all 0.25s ease;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.question-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--shadow-pop);
  border-color: var(--brand-4);
}

.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-index {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-4);
}

.rate-tag {
  border: none;
}

.card-title {
  margin: 0;
  font-size: 17px;
  color: var(--ink-0);
  transition: color 0.2s ease;
}

.question-card:hover .card-title {
  color: var(--brand-6);
}

.card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag {
  background: var(--brand-1);
  color: var(--brand-6);
  border: none;
  border-radius: 6px;
}

.card-meta {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 16px;
  font-size: 12px;
  color: var(--ink-4);
}

.card-meta span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.card-meta .go {
  margin-left: auto;
  color: var(--brand-6);
  opacity: 0;
  transition: opacity 0.2s ease;
}

.question-card:hover .go {
  opacity: 1;
}

.empty-box {
  padding: 60px 0;
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  margin-top: 32px;
}
</style>
