# 验收与运行证据索引

当前汇总结论见[项目状态](../../docs/status.md)。各目录是按时间保留的运行快照，失败、诊断、修复和成功记录分开阅读；本轮整理仅新增索引，没有移动、重写或重新计算历史证据。

## 最近完整验收

[overall-acceptance-20261023](overall-acceptance-20261023/report.md)：源码基线 `19f77fc`，归档提交 `bbfd4af`。本机范围 PASS：后端 248 tests，JV01—JV29 全通过，25 个业务分组、16 项隔离、53 项权限、8 项真实 Chrome 检查，以及报名恢复专项与旧队列切换通过。临时环境清理后独立核对 63 个端口关闭。

先读 [report.md](overall-acceptance-20261023/report.md)、[summary.json](overall-acceptance-20261023/summary.json) 和 [JV 矩阵](overall-acceptance-20261023/jv-matrix.md)，再按引用定位原始事实。原始 `business-matrix/` 的浏览器失败是验收构建配置遗漏，修正后完整重跑到 `business-matrix-02/`；[根因记录](overall-acceptance-20261023/fixture-correction.json)与两轮日志均保留。

## 2026-10-22 修复与补验

| 目录 | 阅读入口与用途 |
| --- | --- |
| `registration-fix-20261022/` | [修复报告](registration-fix-20261022/report.md)：V005、Lua 部分写入与旧快照防线的独立证据 |
| `registration-review-20261022/` | [审查报告](registration-review-20261022/report.md)：修复前的 R1/R2 与复现；不能要求缺陷复现脚本在修复后仍暴露同一问题 |
| `startup-20261022/` | [交付报告](startup-20261022/report.md)：默认全构建 verify/browser、serve 实际 Ctrl+C 与编排失败契约 |
| `isolation-20261022/` | [隔离完整复验](isolation-20261022/report.md)：该轮 230 tests、17 项隔离/恢复检查及完整 JV；计数不套用到新轮 |
| `f03-20261022/` | [权限复验](f03-20261022/report.md)：当前账号授权及真实旧 Session 撤权回归 |

## 2026-10-17—18 开发与早期验收

| 目录 | 阅读入口与用途 |
| --- | --- |
| `final-acceptance-20261018/` | [报告](final-acceptance-20261018/report.md)：当时 26 PASS / 1 FAIL / 2 UNTESTED，F03 缺陷仍开放；其中 Python 夹具仍被当前完整业务入口加载 |
| `fix-native-verdict-20261018/` | [修复报告](fix-native-verdict-20261018/report.md)：F01/F02 判题问题及成功/失败轮 |
| `acceptance-20261018/` | [早期全面验收](acceptance-20261018/report.md)：首次缺陷与尚缺故障矩阵的记录 |
| `j6-native-20261018/` | [范围限制](j6-native-20261018/known-limitations.md)：真实本机联合链的阶段材料 |
| `j5-local-20261018/` | [范围限制](j5-local-20261018/known-limitations.md)：计分、缓存与重建 |
| `j4-local-20261018/` | [范围限制](j4-local-20261018/known-limitations.md)：报名预留与恢复 |
| `j3-local-20261018/` | [范围限制](j3-local-20261018/known-limitations.md)：赛事快照与提交 |
| `j2-local-20261017/` | [范围限制](j2-local-20261017/known-limitations.md)：可靠判题链 |
| `j1-native-20261017/` | [范围限制](j1-native-20261017/known-limitations.md)：可信 Java 判题与工具链 |

## 使用与保留方式

`report.md` 是解释，`summary.json`/事实 JSON/日志是对应收据，`source-*.json` 或源码指纹说明版本，`sha256sums.txt` 用于验证原始材料完整性。并非每个早期目录都有相同文件名，按该目录报告引用读取。

历史 FAIL 不会因后来修复而改写为 PASS。`published=false`、“尚未提交”和临时绝对路径是归档当时快照；发布状态以 Git 历史为准，私有运行时已清理，不可直接重用路径。新验收写新目录，不覆盖旧轮或改写清单。

`final-acceptance-20261018/*.py` 含当前脚本的实际依赖；`overall-acceptance-20261023/` 的调度脚本则是已结束运行的过程记录，不能直接重启已删除的环境。详见[文件保留规则](../../docs/project-files.md)。Nacos、目标环境部署、压测与长期稳定性继续后置，本机 PASS 不提升为生产结论。
