# 项目文档导航

先用[当前状态](status.md)判断完成情况，再按目标选择下列入口。验收报告记录某一版本与环境的运行结果；开发计划、学习笔记和历史绿色记录均不能替代当前执行证据。

| 阅读目标 | 建议顺序 |
| --- | --- |
| 首次接手项目 | [项目 README](../README.md) → [当前状态](status.md) → [运行交接](contest/handoff.md) |
| 启动或演示 | [Mac 完整启动](contest/local-startup.md) → [脚本索引](../scripts/README.md) |
| 学习当前源码 | [学习总览](learning/01-总览.md) → [业务链路与自测](learning/03-链路.md) → [面试追问](learning/04-面试.md) |
| 定位业务实现 | [赛事开发导航](contest/README.md) → [源码阅读地图](contest/source-reading.md) |
| 理解一致性与恢复 | [报名恢复](contest/registration-recovery.md)、[隔离执行与回收](contest/isolated-sandbox.md)、链路篇的榜单部分 |
| 复核验收 | [证据索引](../outputs/validation/README.md) → 对应日期的 report、summary、JV 矩阵和清理记录 |
| 查历史设计与问题 | [历史索引](history/README.md)，包括原方案、基础版图解、各阶段与修复记录 |
| 整理目录或清理文件 | [文件管理与保留规则](project-files.md) |

`learning/02-模块/` 保留六篇基础版本模块笔记，适合查组件职责；新版行为按链路篇、当前源码和对应证据判断。各文件路径保持不变，便于已有学习链接继续使用。

当前维护文档位于 `docs/`；运行证据位于 `outputs/validation/`，按日期保留失败和成功记录。个人材料目录 `output/` 与项目运行证据用途不同，不纳入仓库。详细目录职责见文件管理文档。
