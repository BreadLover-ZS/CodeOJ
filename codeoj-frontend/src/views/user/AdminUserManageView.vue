<template>
  <div id="manageUserView">
    <h2>用户管理</h2>
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
        <a-tag :color="roleColorMap[record.userRole] ?? 'gray'">
          {{ roleMap[record.userRole] ?? record.userRole }}
        </a-tag>
      </template>
      <template #createTime="{ record }">
        {{ moment(record.createTime).format("YYYY-MM-DD HH:mm") }}
      </template>
      <template #optional="{ record }">
        <a-space>
          <a-button
            v-if="record.userRole !== ACCESS_ENUM.ADMIN"
            size="small"
            type="outline"
            status="success"
            @click="changeRole(record, ACCESS_ENUM.ADMIN, '设为管理员')"
          >
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
            删除
          </a-button>
        </a-space>
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { ref, watchEffect } from "vue";
import { UserControllerService } from "../../../generated";
import { UserUpdateRequest, UserVO } from "../../../generated";
import message from "@arco-design/web-vue/es/message";
import { Modal } from "@arco-design/web-vue";
import moment from "moment";
import ACCESS_ENUM from "@/access/accessEnum";

const dataList = ref<UserVO[]>([]);
const total = ref(0);
const searchParams = ref({
  current: 1,
  pageSize: 10,
});

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
    message.error("加载失败，" + res.message);
  }
};

/**
 * 监听 searchParams 变量，改变时触发页面的重新加载
 */
watchEffect(() => {
  loadData();
});

const onPageChange = (page: number) => {
  searchParams.value = {
    ...searchParams.value,
    current: page,
  };
};

/**
 * 用户角色显示映射（与后端 UserRoleEnum 对应）
 */
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
  {
    title: "id",
    dataIndex: "id",
  },
  {
    title: "用户昵称",
    dataIndex: "userName",
  },
  {
    title: "角色",
    slotName: "userRole",
  },
  {
    title: "简介",
    dataIndex: "userProfile",
  },
  {
    title: "创建时间",
    slotName: "createTime",
  },
  {
    title: "操作",
    slotName: "optional",
  },
];

/**
 * 修改用户角色（设为管理员/封禁/解封）
 * @param record 用户
 * @param userRole 目标角色
 * @param action 操作名（用于提示）
 */
const changeRole = async (record: UserVO, userRole: string, action: string) => {
  const updateRequest: UserUpdateRequest = {
    id: record.id,
    userRole,
  };
  const res = await UserControllerService.updateUserUsingPost(updateRequest);
  if (res.code === 0) {
    message.success(`${action}成功`);
    loadData();
  } else {
    message.error(`${action}失败，` + res.message);
  }
};

/**
 * 删除用户
 */
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
        message.success("删除成功");
        loadData();
      } else {
        message.error("删除失败，" + res.message);
      }
    },
  });
};
</script>

<style scoped></style>
