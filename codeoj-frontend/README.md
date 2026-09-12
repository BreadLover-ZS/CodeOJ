# CodeOJ Frontend

Vue 3 + TypeScript 前端，包含用户登录注册、题目管理、题目列表、在线代码编辑和提交记录页面。UI 使用 Arco Design，编辑器使用 Monaco Editor，接口类型来自 `openapi-typescript-codegen` 生成目录。

> 状态边界：本说明来自静态源码审计，尚未在当前环境完成依赖安装、构建和浏览器联调。

## 目录速览

```text
src/
  components/     公共组件
  layouts/        页面布局
  router/         路由与权限元信息
  store/          Vuex 登录用户状态
  views/
    question/     题目列表、管理、编辑、提交
    user/         登录、注册、用户管理
generated/        已生成的 TypeScript API 客户端
```

`generated/` 已被页面直接引用，不能在未验证新接口文档前随意删除或整目录覆盖。

## 本地命令

仓库同时存在 `package-lock.json` 与 `yarn.lock`，说明包管理器尚未统一。二开时应只保留一种锁文件并在 CI 中固定。根文档暂以 npm 为例：

```bash
npm ci
npm run serve
npm run lint
npm run build
```

当前 `package.json` 没有前端测试脚本。`lint` 和 `build` 通过只能证明静态检查与打包成功，不能替代页面和接口测试。

## API 客户端

当前生成代码的默认地址位于 `generated/core/OpenAPI.ts`：

```text
http://localhost:8101
```

请求配置携带 credentials，用于跨域传递 Spring Session Cookie。

旧 README 曾使用：

```bash
openapi --input http://localhost:8101/api/v2/api-docs --output ./generated --client axios
```

该命令目前**不能当作已验证流程**：Gateway 只配置了 `/api/user/**`、`/api/question/**`、`/api/judge/**` 三类路由，没有证据表明 `/api/v2/api-docs` 能在网关聚合全部服务文档；现有 `generated/` 还包含当前微服务路由之外的旧接口。

重新生成前应先完成：

1. 启动目标后端服务，确认实际 OpenAPI 文档 URL 和版本；
2. 决定按服务分别生成，还是由网关聚合；
3. 将输出生成到临时目录并审查 diff；
4. 检查页面使用的 Service/Model 是否被删除或改名；
5. 通过 `lint`、`build` 和核心页面冒烟测试后再替换 `generated/`。

推荐把可复现命令固化为 `package.json` 脚本，例如 `generate:api`，但应在 URL 被实际验证后再添加。

## 浏览器联调清单

- 未登录访问受保护页面会跳转登录页；
- 登录响应写入 Cookie，刷新后能恢复用户状态；
- 普通用户不能进入管理员页面或调用管理员接口；
- 题目详情不暴露隐藏测试用例；
- 编辑器当前只允许选择真正能执行的语言；基线阶段应限制为 Java；
- Java AC、WA、编译错误、运行错误和超时都有可理解的终态；
- 提交列表只向本人或管理员展示源码；
- 页面轮询不会制造无界请求或在终态后继续轮询。

其中后端判题错误契约目前仍有缺陷，因此不能只从页面展示判断功能已完成，应同时核对 API、数据库状态和 MQ 行为。
