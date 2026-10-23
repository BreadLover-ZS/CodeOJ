# 项目文件与保留规则

本轮整理采用入口和索引归类，保留源码、脚本与历史验收目录路径。所有当前文档从[文档导航](README.md)进入，项目进度以[状态页](status.md)为准。

| 路径 | 用途 | 维护与清理规则 |
| --- | --- | --- |
| `README.md` | 项目概览、运行与文档入口 | 保持简洁，完整状态与历史说明分别链接到 docs |
| `codeoj-backend/` | Maven 多模块源码、测试、迁移及历史 Compose | 不在文档整理中移动模块、迁移或修改业务开关 |
| `codeoj-frontend/` | 前端源码、生成的 API 客户端与锁文件 | `generated/` 是构建输入，不能当作 dist 删除 |
| `scripts/` | 可复用演示、启动、验证与工具 | 按[脚本索引](../scripts/README.md)选入口；本轮保留原路径 |
| `docs/contest/` | 运行/实现说明与各阶段历史记录 | 当前入口指向最新证据；历史阶段的原结论按日期阅读 |
| `docs/learning/` | 学习路线、模块笔记、自测与面试追问 | 当前主线为 01→03→04；02 为基础模块补充 |
| `docs/history/` | 历史文档索引与 README 拆出的基础版流程 | 图解仍引用 `docs/images/`，原图片不复制、不移动 |
| `docs/images/` | 源码流程图等正文引用素材 | 有文档引用，继续保留 |
| `outputs/validation/` | 按日期保存的验收报告、事实、失败诊断及源码指纹 | 使用新目录执行新验收，禁止覆盖旧报告或重算旧清单掩盖差异 |
| `output/` | 本机个人材料 | 已忽略，不纳入项目提交；本轮只移除其中的 Finder 元数据，不改材料正文 |
| 各后端模块的 `target/`、前端 `dist/` | 可再生成的编译、测试与打包产物 | 已按用户选择清理；下次运行使用默认构建，产物重建后才可使用 `--skip-build` |
| 前端 `node_modules/` | 本机前端依赖 | 按用户选择保留，便于继续开发；不纳入 Git |
| `.idea/` | 本机 IDE 设置 | 已忽略，保留用户设置 |

## 历史路径中的可执行依赖

[run-final-acceptance.py](../scripts/local/run-final-acceptance.py) 会只读加载 `outputs/validation/final-acceptance-20261018/` 中的 Python 夹具；[run_acceptance.py](../outputs/validation/final-acceptance-20261018/run_acceptance.py) 还使用同目录的 AMQP 故障代理。不能把这些目录视为纯日志而移动或删除，也不能只复制 `scripts/local/` 就认定可以完整复验。

`outputs/validation/overall-acceptance-20261023/` 中的调度与继续执行脚本记录那次验收过程，包含已删除的临时环境路径和暂停进程号。它们是证据来源，不作为可再次执行的一键入口。复验应使用[运行交接](contest/handoff.md)中的可维护脚本，并创建新证据目录。

## 本轮清理

文档整理阶段移除 6 个未跟踪的 `.DS_Store`：仓库根目录、`docs/`、`docs/learning/`、`outputs/`、`output/`、`output/resume/` 各 1 个。这些文件只保存 Finder 显示元数据。

随后按用户选择，删除 8 个后端模块的 `target/` 与前端 `dist/`，共 9 个可再生成目录、542 个文件，逻辑大小 491478355 字节（约 469 MiB）。删除前确认这些目录被 Git 忽略、没有跟踪文件且未发现使用项目构建路径的运行进程；删除前后对 1291 个项目文件作摘要核对，内容与 Git 状态均未被清理操作改变。未发现额外 `.pyc`、`.pyo` 或临时备份文件需要删除。

源码、锁文件、生成的 API 客户端、已整理文档、历史失败/成功证据、个人材料和 `node_modules/` 均保留。恢复构建可运行 `./scripts/local-stack.sh verify`；该入口默认构建并执行启动验证，前提与清理行为见[启动说明](contest/local-startup.md)。本次清理不重新启动服务或生成产物。

根 `.gitignore` 继续忽略 Finder/IDE/个人材料，并补充 Python `__pycache__/`、`.pyc` 和 `.pyo`，防止运行工具产生缓存杂项。不会全局忽略 `.log` 或 `.json`，以免漏掉应归档的验证证据。
