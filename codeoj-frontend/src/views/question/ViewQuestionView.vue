<template>
  <div id="viewQuestionView" class="list-page">
    <div class="page-head">
      <h2 class="page-title">在线做题</h2>
      <a-button @click="router.push('/questions')">
        <template #icon><icon-left /></template>
        返回题库
      </a-button>
    </div>

    <a-row :gutter="20">
      <!-- 左侧：题目区域 -->
      <a-col :md="11" :xs="24">
        <div class="app-card panel question-panel">
          <div class="panel-header">
            <h3 class="panel-title">{{ question?.title ?? "加载中..." }}</h3>
            <a-space wrap>
              <a-tag
                v-for="(tag, index) in question?.tags || []"
                :key="index"
                class="tag"
                >{{ tag }}</a-tag
              >
            </a-space>
          </div>

          <div class="judge-meta">
            <span
              ><icon-clock-circle /> 时间
              {{ question?.judgeConfig?.timeLimit ?? 0 }}ms</span
            >
            <span
              ><icon-book /> 内存
              {{ question?.judgeConfig?.memoryLimit ?? 0 }}MB</span
            >
            <span
              ><icon-layers /> 堆栈
              {{ question?.judgeConfig?.stackLimit ?? 0 }}KB</span
            >
          </div>

          <a-divider class="panel-divider" />

          <div class="content-body">
            <MdViewer :value="question?.content || ''" />
          </div>
        </div>
      </a-col>

      <!-- 右侧：编辑器区域 -->
      <a-col :md="13" :xs="24">
        <div class="app-card panel editor-panel">
          <div class="editor-toolbar">
            <span class="editor-label">代码</span>
            <a-select v-model="form.language" style="width: 180px" size="small">
              <a-option>java</a-option>
              <a-option>cpp</a-option>
              <a-option>go</a-option>
            </a-select>
          </div>
          <CodeEditor
            :value="form.code as string"
            :language="form.language"
            :handle-change="changeCode"
            class="editor-body"
          />
          <div class="editor-actions">
            <a-button
              type="primary"
              long
              :loading="submitting"
              @click="doSubmit"
            >
              <template #icon><icon-send /></template>
              提交代码
            </a-button>
          </div>
        </div>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { ref, watchEffect, withDefaults, defineProps } from "vue";
import { Message } from "@arco-design/web-vue";
import { useRouter } from "vue-router";
import CodeEditor from "@/components/CodeEditor.vue";
import MdViewer from "@/components/MdViewer.vue";
import {
  QuestionControllerService,
  QuestionSubmitAddRequest,
  QuestionVO,
} from "../../../generated";

interface Props {
  id: string;
}

const props = withDefaults(defineProps<Props>(), {
  id: () => "",
});

const router = useRouter();

const question = ref<QuestionVO>();
const submitting = ref(false);

const loadData = async () => {
  const res = await QuestionControllerService.getQuestionVoByIdUsingGet(
    props.id as any
  );
  if (res.code === 0) {
    question.value = res.data;
  } else {
    Message.error("加载失败，" + res.message);
  }
};

const form = ref<QuestionSubmitAddRequest>({
  language: "java",
  code: "",
});

const doSubmit = async () => {
  if (!question.value?.id) {
    return;
  }
  submitting.value = true;
  try {
    const res = await QuestionControllerService.doQuestionSubmitUsingPost({
      ...form.value,
      questionId: question.value.id,
    });
    if (res.code === 0) {
      Message.success("提交成功，正在判题");
      router.push("/question_submit");
    } else {
      Message.error("提交失败，" + res.message);
    }
  } finally {
    submitting.value = false;
  }
};

watchEffect(() => {
  loadData();
});

const changeCode = (value: string) => {
  form.value.code = value;
};
</script>

<style scoped>
.panel {
  padding: 24px;
  height: 100%;
}

/* 题目面板 */
.question-panel {
  display: flex;
  flex-direction: column;
}

.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.panel-title {
  margin: 0;
  font-size: 18px;
}

.tag {
  background: var(--brand-1);
  color: var(--brand-6);
  border: none;
  border-radius: 6px;
}

.judge-meta {
  display: flex;
  gap: 18px;
  margin-top: 14px;
  font-size: 12px;
  color: var(--ink-4);
}

.judge-meta span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.panel-divider {
  margin: 16px 0;
}

.content-body {
  flex: 1;
  overflow: auto;
  color: var(--ink-2);
  line-height: 1.7;
  font-size: 14px;
}

/* 编辑器面板 */
.editor-panel {
  display: flex;
  flex-direction: column;
  padding: 16px;
}

.editor-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.editor-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-2);
}

.editor-body {
  flex: 1;
  min-height: 480px;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.editor-actions {
  margin-top: 14px;
}
</style>
