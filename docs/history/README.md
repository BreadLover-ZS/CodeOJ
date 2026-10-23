# 历史文档与阶段记录

历史文档保留当时的实现与验证范围。查看项目当前结论请先读[状态页](../status.md)；各轮运行记录另见[验收索引](../../outputs/validation/README.md)。本目录采用索引归类，原阶段文档和证据路径保持不变。

## 设计与基础版本

| 文档 | 用途与边界 |
| --- | --- |
| [赛事平台 1.1 原方案](../contest/development-plan-v1.1.md) | J0—J6、JV01—JV29 的需求与完成条件；原文中的 UNTESTED 是设计阶段状态 |
| [基础版判题流程与图解](legacy-judge-walkthrough.md) | 从旧 README 拆出的提交、MQ、沙箱三条流程；不能替代可靠判题链 |
| [基础模块学习目录](../learning/02-模块/README.md) | 六篇按模块学习的原笔记；已有新版补充段落，正文仍含早期分析 |
| [2026-09-22 Docker 联调报告](../docker-local-joint-debug-report.md) | 当时 Windows/Docker 环境与旧分支；不能证明当前 Mac 新版的 Nacos 或全容器部署 |

## 2026-10-17—18 阶段开发与问题修复

| 文档 | 记录内容 |
| --- | --- |
| [J0/J1](../contest/j0-j1-local.md) | 本机工具链、判题结构化结果与可信 Java 样例 |
| [J2](../contest/j2-local.md) | 可靠受理、Outbox、租约、有限恢复与执行结果 fencing |
| [J3](../contest/j3-local.md) | 赛事配置、发布快照、资格与比赛提交 |
| [J4](../contest/j4-local.md) | Redis 预留、异步确认、未决补建与回收对账 |
| [J5](../contest/j5-local.md) | 榜单投影、版本化缓存、重建与最终结算 |
| [J6 真实本机联调](../contest/j6-native-local.md) | 临时 MySQL/Redis/MQ、HTTP、浏览器与恢复记录 |
| [原生判题修复](../contest/fix-native-verdict.md) | 注释中类声明误判 CE、可归因 RE 等问题与单独复验 |

## 2026-10-22 后续补强

| 文档 | 记录内容与后续入口 |
| --- | --- |
| [F03 权限修复](../contest/fix-current-permissions.md) | 旧 Session 撤权后仍有权限的问题；改为按当前账号授权 |
| [剩余开发执行记录](../contest/remaining-development.md) | 当时补齐 Linux 隔离、持久回收、完整 JV 与旧队列切换的过程；不是当前未完成事项清单 |
| [隔离配置与边界](../contest/isolated-sandbox.md) | 当前仍维护的隔离运行说明，包含历史验证索引 |
| [完整启动交付](../contest/local-startup.md) | `verify --browser`、`serve` 实际 Ctrl+C 的独立交付证据 |
| [报名恢复说明](../contest/registration-recovery.md) | 旧快照、Lua 部分写入、V005 与旧账本升级；技术说明仍适用 |

2026-10-23 在报名修复后的源码上完成了新一轮完整本机验收。历史报告中的 FAIL、UNTESTED、“尚未提交”和诊断错误保留原样；后续修复以新报告建立证据链，不回写历史结果。
