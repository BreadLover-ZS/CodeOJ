# 项目文件与保留规则

文档入口见[导航](README.md)，当前完成情况见[状态页](status.md)。

| 路径 | 用途 | 保留规则 |
| --- | --- | --- |
| `codeoj-backend/` | 后端源码、测试、迁移及旧 Compose | 不因文档整理移动模块或修改业务开关 |
| `codeoj-frontend/src/`、`generated/` | 前端源码与已引用的 API 客户端 | generated 是构建输入，不当作 dist 删除 |
| `scripts/` | 可复用启动、演示与验证入口 | 用途见[脚本索引](../scripts/README.md) |
| `docs/learning/` | 项目全局入口、五条业务源码阅读链与面试复盘，共 7 篇 | 由 README 按顺序进入；正文提供源码范围与解释，操作和证据仍链接原路径 |
| `docs/contest/` | 技术专题、运行交接和阶段记录 | 当前说明与历史阶段按[赛事导航](contest/README.md)区分 |
| `docs/history/`、`docs/images/` | 历史索引、基础版图解和引用素材 | 旧图仍有引用，不随正文精简删除 |
| `outputs/validation/` | 各轮报告、日志、事实及源码指纹 | 新执行写新目录，不覆盖旧结果或重算旧清单 |
| `output/` | 本机个人材料 | Git 忽略，不随项目清理删除正文 |
| `target/`、`dist/`、Python 缓存 | 可再生成产物 | 无运行任务占用时可清理，下次先重建再使用 skip-build |
| `node_modules/`、`.idea/` | 本机依赖与 IDE 设置 | 不纳入 Git，不在无关任务中重装或删除 |

## 验收目录不都是纯日志

[run-final-acceptance.py](../scripts/local/run-final-acceptance.py) 只读加载 `final-acceptance-20261018/` 中的 Python 夹具；同目录的 [run_acceptance.py](../outputs/validation/final-acceptance-20261018/run_acceptance.py) 还依赖 AMQP 故障代理。复验需要这些源文件，不能只复制 scripts 后删除历史目录。

`overall-acceptance-20261023/` 的调度与继续执行脚本记录那次验收，包含已清理的临时路径，不是当前一键入口。重新验证应使用[运行交接](contest/handoff.md)中的可维护脚本和新证据目录。

## 清理范围

默认只清理本轮产生的临时脚本、预览、缓存和运行时；保留最终交付物、必需源码、锁文件和证据。删除前确认路径归属、没有运行进程占用，且没有跟踪文件被当作产物删除。

Finder 元数据、IDE 文件和 Python 缓存由 `.gitignore` 忽略；不要全局忽略 `.log`、`.json`，以免遗漏需要归档的运行证据。
