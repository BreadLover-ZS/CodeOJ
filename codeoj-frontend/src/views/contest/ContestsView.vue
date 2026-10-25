<template>
  <div class="app-card contest-list">
    <a-space
      ><h2>比赛</h2>
      <a-button :loading="loading" @click="load">刷新</a-button></a-space
    >
    <a-alert v-if="error" type="error">{{ error }}</a-alert>
    <a-table :data="rows" :pagination="false" :loading="loading" row-key="id">
      <template #columns>
        <a-table-column title="比赛" data-index="title" />
        <a-table-column title="类型"
          ><template #cell="{ record }">{{
            record.contestType === "PRIVATE" ? "专用赛题" : "公开训练赛"
          }}</template></a-table-column
        >
        <a-table-column title="阶段"
          ><template #cell="{ record }">{{
            phaseLabel[record.phase]
          }}</template></a-table-column
        >
        <a-table-column title="开赛时间（本地）"
          ><template #cell="{ record }">{{
            localTime(record.startAt)
          }}</template></a-table-column
        >
        <a-table-column title="已确认人数 / 总名额"
          ><template #cell="{ record }"
            >{{ record.capacity - record.remainingCapacity }} /
            {{ record.capacity }}</template
          ></a-table-column
        >
        <a-table-column title="操作"
          ><template #cell="{ record }"
            ><router-link :to="`/contests/${record.id}`"
              >查看比赛</router-link
            ></template
          ></a-table-column
        >
      </template>
    </a-table>
    <a-space
      ><a-button
        :disabled="page === 0 || loading"
        @click="
          page--;
          load();
        "
        >上一页</a-button
      ><span>第 {{ page + 1 }} 页</span
      ><a-button
        :disabled="rows.length < 20 || loading"
        @click="
          page++;
          load();
        "
        >下一页</a-button
      ></a-space
    >
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from "vue";
import {
  Contest,
  contestRequest,
  errorText,
  localTime,
  phaseLabel,
} from "@/api/contest";
const rows = ref<Contest[]>([]),
  page = ref(0),
  loading = ref(false),
  error = ref("");
async function load() {
  loading.value = true;
  error.value = "";
  try {
    rows.value = await contestRequest<Contest[]>(`?page=${page.value}`);
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>
<style scoped>
.contest-list {
  padding: 24px;
}
h2 {
  margin: 0;
}
.arco-table {
  margin: 20px 0;
}
</style>
