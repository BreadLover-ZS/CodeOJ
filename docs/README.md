# 项目文档导航

| 你要做什么 | 从这里开始 |
| --- | --- |
| 从头学习项目并准备面试 | [项目全貌与阅读顺序](learning/README.md) → [登录](learning/01-登录与权限链路.md) → [提交判题](learning/02-提交与判题链路.md) → [沙箱](learning/03-沙箱执行与回收链路.md) → [赛事](learning/04-赛事报名与参赛链路.md) → [榜单](learning/05-成绩榜单与结算链路.md) → [面试复盘](learning/06-面试复盘.md) |
| 深入赛事扩展 | [赛事与报名](learning/04-赛事报名与参赛链路.md) → [榜单与结算](learning/05-成绩榜单与结算链路.md) |
| 在 Mac 启动和演示 | [完整本机启动](contest/local-startup.md) → [脚本索引](../scripts/README.md) |
| 查看完成情况 | [当前状态](status.md)；测试数量与环境结论只在这里汇总 |
| 继续维护赛事功能 | [赛事导航](contest/README.md)、[源码速查](contest/source-reading.md) |
| 核对运行证据 | [证据索引](../outputs/validation/README.md) → 对应运行的报告与收据 |
| 查原方案和旧问题 | [历史索引](history/README.md) |
| 清理目录或交接 | [文件管理](project-files.md)、[运行交接](contest/handoff.md) |

学习文档解释当前实现；专题文档保存操作细节；历史方案与运行报告保留当时状态。不要把旧版本通过的结果直接套用到新源码。

学习材料以熟悉 Spring Boot 基础、尚未串起项目为起点。每条链先介绍在整个项目中的位置，再给文件、方法和阅读范围，配合业务解释和数据变化，先读完整正常流程再分析异常。正文不贴源码；测试链接供阅读断言，实际运行条件见脚本与启动说明。
