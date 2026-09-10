<template>
  <div id="addQuestionView" class="list-page">
    <div class="page-head">
      <h2 class="page-title">{{ updatePage ? "更新题目" : "创建题目" }}</h2>
    </div>

    <div class="app-card form-card">
      <a-form :model="form" label-align="left" class="question-form">
        <a-form-item field="title" label="标题">
          <a-input v-model="form.title" placeholder="请输入题目标题" />
        </a-form-item>
        <a-form-item field="tags" label="标签">
          <a-input-tag
            v-model="form.tags"
            placeholder="输入后回车添加标签"
            allow-clear
          />
        </a-form-item>

        <a-form-item field="content" label="题目内容">
          <MdEditor
            :value="form.content"
            :handle-change="onContentChange"
            class="md-editor"
          />
        </a-form-item>
        <a-form-item field="answer" label="答案">
          <MdEditor
            :value="form.answer"
            :handle-change="onAnswerChange"
            class="md-editor"
          />
        </a-form-item>

        <a-divider class="sec-divider" />

        <a-form-item label="判题配置">
          <a-space size="large" wrap class="config-space">
            <a-form-item field="judgeConfig.timeLimit" label="时间限制 (ms)">
              <a-input-number
                v-model="form.judgeConfig.timeLimit"
                :min="0"
                mode="button"
              />
            </a-form-item>
            <a-form-item field="judgeConfig.memoryLimit" label="内存限制 (MB)">
              <a-input-number
                v-model="form.judgeConfig.memoryLimit"
                :min="0"
                mode="button"
              />
            </a-form-item>
            <a-form-item field="judgeConfig.stackLimit" label="堆栈限制 (KB)">
              <a-input-number
                v-model="form.judgeConfig.stackLimit"
                :min="0"
                mode="button"
              />
            </a-form-item>
          </a-space>
        </a-form-item>

        <a-divider class="sec-divider" />

        <a-form-item label="测试用例">
          <div class="cases-wrap">
            <div
              v-for="(item, index) of form.judgeCase"
              :key="index"
              class="case-item"
            >
              <span class="case-label">用例 {{ index + 1 }}</span>
              <a-input
                v-model="item.input"
                placeholder="输入用例"
                class="case-input"
              />
              <a-input
                v-model="item.output"
                placeholder="输出用例"
                class="case-input"
              />
              <a-button
                status="danger"
                size="small"
                :disabled="form.judgeCase.length <= 1"
                @click="handleDelete(index)"
              >
                <template #icon><icon-delete /></template>
              </a-button>
            </div>
            <a-button type="outline" status="success" @click="handleAdd">
              <template #icon><icon-plus /></template>
              新增用例
            </a-button>
          </div>
        </a-form-item>

        <a-form-item>
          <a-button type="primary" long size="large" @click="doSubmit">
            {{ updatePage ? "保存修改" : "发布题目" }}
          </a-button>
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import MdEditor from "@/components/MdEditor.vue";
import { QuestionControllerService } from "../../../generated";
import { Message } from "@arco-design/web-vue";
import { useRoute } from "vue-router";

const route = useRoute();
const updatePage = route.path.includes("update");

let form = ref<any>({
  title: "",
  tags: [],
  answer: "",
  content: "",
  judgeConfig: { memoryLimit: 1000, stackLimit: 1000, timeLimit: 1000 },
  judgeCase: [{ input: "", output: "" }],
});

const loadData = async () => {
  const id = route.query.id;
  if (!id) return;
  const res = await QuestionControllerService.getQuestionByIdUsingGet(
    id as any
  );
  if (res.code === 0) {
    form.value = res.data;
    form.value.judgeCase = form.value.judgeCase
      ? JSON.parse(form.value.judgeCase)
      : [{ input: "", output: "" }];
    form.value.judgeConfig = form.value.judgeConfig
      ? JSON.parse(form.value.judgeConfig)
      : { memoryLimit: 1000, stackLimit: 1000, timeLimit: 1000 };
    form.value.tags = form.value.tags ? JSON.parse(form.value.tags) : [];
  } else {
    Message.error("加载失败，" + res.message);
  }
};

onMounted(() => {
  loadData();
});

const doSubmit = async () => {
  if (updatePage) {
    const res = await QuestionControllerService.editQuestionUsingPost(
      form.value
    );
    if (res.code === 0) {
      Message.success("更新成功");
    } else {
      Message.error("更新失败，" + res.message);
    }
  } else {
    const res = await QuestionControllerService.addQuestionUsingPost(
      form.value
    );
    if (res.code === 0) {
      Message.success("创建成功");
    } else {
      Message.error("创建失败，" + res.message);
    }
  }
};

const handleAdd = () => {
  form.value.judgeCase.push({ input: "", output: "" });
};

const handleDelete = (index: number) => {
  form.value.judgeCase.splice(index, 1);
};

const onContentChange = (value: string) => {
  form.value.content = value;
};

const onAnswerChange = (value: string) => {
  form.value.answer = value;
};
</script>

<style scoped>
.form-card {
  padding: 32px;
  max-width: 980px;
}

.question-form :deep(.arco-form-label-item-label) {
  font-weight: 600;
  color: var(--ink-2);
}

.md-editor {
  width: 100%;
}

.sec-divider {
  margin: 20px 0;
}

.config-space :deep(.arco-form-item) {
  margin-bottom: 0;
}

.cases-wrap {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.case-item {
  display: flex;
  align-items: center;
  gap: 10px;
}

.case-label {
  min-width: 56px;
  font-size: 13px;
  color: var(--ink-4);
}

.case-input {
  width: 220px;
}
</style>
