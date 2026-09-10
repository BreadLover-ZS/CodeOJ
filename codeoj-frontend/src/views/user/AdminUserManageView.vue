<template>
  <div id="manageUserView" class="list-page">
    <div class="page-head">
      <h2 class="page-title">用户管理</h2>
      <span class="total-hint">共 {{ total }} 位用户</span>
    </div>

    <div class="app-card table-card">
      <a-table
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
        <template #userRole="{ record }">
          <a-tag
            :color="roleColorMap[record.userRole] ?? 'gray'"
            class="role-tag"
          >
            {{ roleMap[record.userRole] ?? record.userRole }}
          </a-tag>
        </template>
        <template #createTime="{ record }">
          {{ moment(record.createTime).format("YYYY-MM-DD HH:mm") }}
        </template>
        <template #optional="{ record }">
          <a-space wrap>
            <a-button
              v-if="record.userRole !== ACCESS_ENUM.ADMIN"
              size="small"
              type="outline"
              status="success"
              @click="changeRole(record, ACCESS_ENUM.ADMIN, '设为管理员')"
            >
              <template #icon><icon-user-add /></template>
              设为管理员
            </a-button>
            <a-button
              v-if="
                record.userRole !== ACCESS_ENUM.BAN &&
                record.userRole !== ACCESS_ENUM.ADMIN
              "
              size="small"
              type="outline"
              status="warning"
              @click="changeRole(record, ACCESS_ENUM.BAN, '封禁')"
            >
              <template #icon><icon-user-delete /></template>
              封禁
            </a-button>
            <a-button
              v-if="record.userRole === ACCESS_ENUM.BAN"
              size="small"
              type="outline"
              @click="changeRole(record, ACCESS_ENUM.USER, '解封')"
            >
              解封
            </a-button>
            <a-button
              v-if="record.userRole !== ACCESS_ENUM.ADMIN"
              size="small"
              status="danger"
              @click="doDelete(record)"
            >
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
import { ref, watchEffect } from "vue";
import { UserControllerService } from "../../../generated";
import { UserUpdateRequest, UserVO } from "../../../generated";
import { Message, Modal } from "@arco-design/web-vue";
import moment from "moment";
import ACCESS_ENUM from "@/access/accessEnum";

const dataList = ref<UserVO[]>([]);
const total = ref(0);
const searchParams = ref({ current: 1, pageSize: 10 });

const loadData = async () => {
  const res = await UserControllerService.listUserVoByPageUsingPost({
    ...searchParams.value,
    sortField: "createTime",
    sortOrder: "descend",
  });
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

const onPageChange = (page: number) => {
  searchParams.value = { ...searchParams.value, current: page };
};

const roleMap: Record<string, string> = {
  user: "普通用户",
  admin: "管理员",
  ban: "已封禁",
};

const roleColorMap: Record<string, string> = {
  user: "arcoblue",
  admin: "red",
  ban: "gray",
};

const columns = [
  { title: "ID", dataIndex: "id", width: 100 },
  { title: "用户昵称", dataIndex: "userName" },
  { title: "角色", slotName: "userRole", width: 110 },
  { title: "简介", dataIndex: "userProfile" },
  { title: "创建时间", slotName: "createTime", width: 150 },
  { title: "操作", slotName: "optional", width: 300 },
];

const changeRole = async (record: UserVO, userRole: string, action: string) => {
  const updateRequest: UserUpdateRequest = { id: record.id, userRole };
  const res = await UserControllerService.updateUserUsingPost(updateRequest);
  if (res.code === 0) {
    Message.success(`${action}成功`);
    loadData();
  } else {
    Message.error(`${action}失败，` + res.message);
  }
};

const doDelete = (record: UserVO) => {
  Modal.confirm({
    title: `确认删除用户「${record.userName}」？`,
    content: "删除后不可恢复，请谨慎操作",
    okText: "删除",
    okButtonProps: { status: "danger" },
    onOk: async () => {
      const res = await UserControllerService.deleteUserUsingPost({
        id: record.id,
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
.total-hint {
  font-size: 13px;
  color: var(--ink-4);
}

.table-card {
  padding: 8px 16px;
}

.role-tag {
  border: none;
}
</style>
