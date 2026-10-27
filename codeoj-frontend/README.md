# CodeOJ 前端

Vue 3 + TypeScript + Arco Design + Monaco，包含题目练习、用户管理、比赛管理、报名、比赛提交与榜单。源码导读见[提交与页面查询链路](../docs/learning/02-提交与判题链路.md)，构建及浏览器验证状态见[项目状态](../docs/status.md)。

## 运行

启动完整可演示环境，优先从仓库根目录使用[本机启动入口](../docs/contest/local-startup.md)。只开发前端时，在本目录执行：

```bash
npm ci
npm run serve
npm run build
```

运行前确认 API 地址与后端端口。仓库已有 npm 锁文件与历史 Yarn 锁文件，本机启动入口使用 `npm ci`，不要混用包管理器或在无关修改中重新生成锁文件。

## 目录

| 位置 | 用途 |
| --- | --- |
| `src/router/`、`src/access/`、`src/store/` | 页面路由、交互权限和登录用户状态 |
| `src/views/question/`、`src/views/user/` | 题目、提交、登录和用户管理 |
| `src/views/contest/` | 比赛列表、管理和参赛 |
| `src/api/contest.ts` | 赛事接口、稳定请求 key 与状态类型 |
| `src/components/` | 编辑器、榜单及报名复核组件 |
| `generated/` | 已生成的 API 请求代码和类型，页面直接依赖 |

## 请求与结果

[OpenAPI.ts](generated/core/OpenAPI.ts) 配置地址并携带凭证。完整启动构建时设置 `VUE_APP_API_BASE_URL`，通过同源代理访问随机端口 Gateway；独立前端开发需要匹配实际后端配置。

请求受理、报名确认与答案 AC 是三个不同结果。提交响应未知时沿用原 key 和请求内容；PROCESSING 不当作参赛资格。页面显示“已确认人数 / 总名额”，不把数据库余量当作可抢库存。

重新生成 `generated/` 前，先确认实际接口文档地址，输出到临时目录检查 diff，再验证引用和核心页面；旧网关聚合 URL 不能当作现成可用命令。

`package.json` 没有前端测试脚本。`lint`、构建、模板夹具渲染、真实浏览器是不同检查层次，结论按状态页读取。
