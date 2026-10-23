#!/usr/bin/env python3
"""Derive acceptance status only from this round's receipts, never historical PASS counts."""
from collections import Counter
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import subprocess

repo = Path.cwd()
root = Path(__file__).resolve().parent


def read(name):
    return json.loads((root / name).read_text())


def write(name, value):
    (root / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def main():
    source = read('source-provenance.json')
    changed = [p for p, digest in source['sourceSha256'].items()
               if not (repo / p).exists() or hashlib.sha256((repo / p).read_bytes()).hexdigest() != digest]
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    tests = read('backend-tests.json')
    probes = read('isolation-probes/isolation-results.json')
    business = read('business-matrix-02/matrix-cases.json')
    permissions = read('current-permissions/permission-results.json')
    registration = read('registration-recovery/registration-facts.json')
    cutover = read('judge-cutover/cutover-results.json')
    browser = read('business-matrix-02/native/browser.log')
    baseline = read('business-matrix-02/native/run.json')
    cleanup = read('cleanup.json')
    audit = read('independent-cleanup-audit.json')
    stages = read('stages.json')
    rerun = read('business-rerun.json')
    raw_stage_failures = [r for r in stages if r['status'] != 'PASS']
    unresolved = [r for r in raw_stage_failures if not (r['stage'] == 'business-matrix' and rerun['status'] == 'PASS')]
    labels = {}
    text = (repo / 'docs/contest/development-plan-v1.1.md').read_text()
    for jv, label in re.findall(r'^\| (JV\d{2}) \| ([^|]+) \|', text, re.M):
        labels[jv] = label.strip()
    matrix = []
    for n in range(1, 30):
        jv = f'JV{n:02}'
        supporting = [{'suite': name, 'case': r['case'], 'status': r['status']}
                      for name, rows in [('business-matrix-02', business), ('isolation-probes', probes)]
                      for r in rows if jv in r.get('jv', [])]
        state = 'UNTESTED' if not supporting else 'FAIL' if any(r['status'] != 'PASS' for r in supporting) else 'PASS'
        matrix.append({'id': jv, 'label': labels.get(jv), 'status': state, 'evidence': supporting})
    counts = dict(Counter(r['status'] for r in matrix))
    write('jv-matrix.json', matrix)
    lines = ['# JV01—JV29 本轮验收矩阵', '',
             f"基线提交 `{head}`。按本轮真实业务与隔离检查生成：{counts.get('PASS', 0)} PASS / {counts.get('FAIL', 0)} FAIL / {counts.get('UNTESTED', 0)} UNTESTED。", '',
             '| ID | 场景 | 状态 | 本轮证据分组 |', '| --- | --- | --- | --- |']
    for row in matrix:
        refs = '; '.join(f"{r['suite']}: {r['case']}" for r in row['evidence'])
        lines.append(f"| {row['id']} | {row['label']} | {row['status']} | {refs} |")
    lines += ['', '业务 JSON：[matrix-cases.json](business-matrix-02/matrix-cases.json)；隔离 JSON：[isolation-results.json](isolation-probes/isolation-results.json)。', '',
              '精确并列排名使用明确标注的临时数据库 acceptedAt 夹具；跨截止时间完成另有真实受理/完成时间证据。功能并发样例不作为负载性能结论。', '']
    (root / 'jv-matrix.md').write_text('\n'.join(lines))
    checks = {
        'sameSourceAndCommit': not changed and head == source['commit'],
        'backend': tests['totals']['tests'] > 0 and all(tests['totals'][k] == 0 for k in ('failures', 'errors', 'skipped')),
        'packagedLuaMatchesSource': read('resource-integrity.json')['packagedLuaEqualsSource'],
        'businessGroups': len(business) == 25 and all(r['status'] == 'PASS' for r in business),
        'fullBaseline': baseline['passed'] and any(r['action'] == 'FINALIZATION_PASSED' for r in baseline['events']),
        'browser': len(browser['passed']) == 8 and not browser['pageErrors'],
        'permissions': len(permissions) == 53 and all(r['status'] == 'PASS' for r in permissions),
        'isolation': len(probes) == 16 and all(r['status'] == 'PASS' for r in probes),
        'registrationRecovery': registration['passed'],
        'cutover': cutover['status'] == 'PASS',
        'allJv': counts == {'PASS': 29},
        'cleanup': cleanup['privateVmRemoved'] and cleanup['temporaryRootRemoved'] and audit['passed'],
        'remainingStages': not unresolved,
        'localDocumentLinks': not read('document-links.json')['broken'],
    }
    overall = 'PASS' if all(checks.values()) else 'FAIL'
    summary = {'status': overall, 'scope': source['scope'], 'commit': head,
               'finishedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'checks': checks,
               'changedTrackedSources': changed, 'backend': tests['totals'], 'backendClasses': len(tests['classes']),
               'businessGroups': len(business), 'businessFaultGroupsExcludingFixtureSetup': len(business) - 1,
               'browserChecks': len(browser['passed']), 'isolationChecks': len(probes),
               'permissions': len(permissions), 'jv': counts,
               'rawStageFailures': raw_stage_failures, 'resolvedFixtureFailure': read('fixture-correction.json'),
               'unresolvedStages': unresolved,
               'nonblockingFindings': ['Frontend: 21 ESLint warnings, 5.02 MiB entrypoint, outdated Browserslist data and Node API deprecation notices',
                    'Historical handoff counts differ from current default isolation entrypoint, which now runs 16 checks including CPU/PID kernel observations'],
               'untested': ['Real Nacos service discovery', 'Target-environment deployment and production rollout/rollback',
                            'Load/performance and long-duration stability', 'Multi-host capacity and power-loss recovery',
                            'Kernel/container-escape certification'],
               'published': False}
    write('summary.json', summary)
    (root / 'report.md').write_text(f'''# CodeOJ 整体本机验收（2026-10-23）

结论：**{overall}，限当前 Mac 全面本机验收范围**。基线提交 `{head}`。本轮重新构建、重新创建临时数据库/账号与 Linux VM，没有引用历史 PASS 数字充当新结果。业务源码与验收断言未修改，证据尚未提交。

| 范围 | 本轮结果 | 证据 |
| --- | --- | --- |
| Java 8 完整 clean/package 回归 | {tests['totals']['tests']} tests，{len(tests['classes'])} 类，0 失败/错误/跳过 | [backend-tests.json](backend-tests.json)、[构建日志](backend-regression.log) |
| 前端生产构建 | 通过；最终构建明确配置临时 Gateway 代理 | [最终构建日志](frontend-build-relative.log)、[产物指纹](frontend-artifacts-relative.json) |
| 启动/下载/清理脚本测试 | 23 PASS；这是脚本契约测试 | [日志](startup-unit-tests.log) |
| 真实依赖完整业务链 | MySQL 8.0.46 / Redis 6.2.24 / RabbitMQ 4.3.6；V001—V005；五个服务与第二 judge | [运行收据](business-matrix-02/native/run.json) |
| Chrome 与最终结算 | 8 项浏览器检查通过、pageErrors=[]；真实 AC/WA/CE、FINALIZING→FINISHED | [浏览器日志](business-matrix-02/native/browser.log)、[最终榜单](business-matrix-02/native/final-scoreboard.json) |
| 故障/并发业务分组 | 25/25 PASS，含 1 个准备夹具组、24 个业务组 | [业务矩阵](business-matrix-02/matrix-cases.json) |
| Linux cgroup v2 隔离 | 16/16 PASS，包含 CPU/PID 内核观测、网络/文件/内存边界、SIGKILL 回收及跨管理器去重 | [检查结果](isolation-probes/isolation-results.json)、[运行时来源](runtime-provenance.json) |
| 当前账号权限 | 53/53 PASS；同一 Session 降权/提权、封禁/删除、跨服务和隐私入口 | [权限结果](current-permissions/permission-results.json) |
| 报名恢复专项 | 真实 V005/唯一索引、旧快照、Lua 部分写入、单次回收、旧格式升级、容量上限及终态收敛通过 | [事实收据](registration-recovery/registration-facts.json) |
| 旧队列切换演练 | 排空旧在途消息、停止生产者后切换、新隔离链 AC、旧记录不改写 | [切换结果](judge-cutover/cutover-results.json) |
| JV01—JV29 | {counts.get('PASS',0)} PASS / {counts.get('FAIL',0)} FAIL / {counts.get('UNTESTED',0)} UNTESTED | [逐项矩阵](jv-matrix.md) |
| 清理与交接 | 自有 VM/运行时目录删除，独立核对进程、端口与数据目录；213 个文档文件链接无缺失 | [清理](cleanup.json)、[独立核对](independent-cleanup-audit.json)、[链接检查](document-links.json) |

## 首轮失败与修正

首轮 `business-matrix` 的浏览器登录页等待超时。原因是本轮自定义验收调度器遗漏 `VUE_APP_API_BASE_URL=`，使构建沿用默认 `http://localhost:8101`；临时 Gateway 使用随机端口。仓库已有启动入口原本已设置该变量，本轮没有修改产品逻辑。按现有启动文档重新构建后，在新数据库和 `business-matrix-02/` 完整重跑基线、浏览器及 25 组业务断言，通过结果才用于上表。原失败日志与原始 stages 保留。[原因与修正](fixture-correction.json)、[重跑收据](business-rerun.json)。

## 非阻塞问题

前端仍有 21 条 ESLint 警告、约 5.02 MiB 入口体积告警、Browserslist 旧数据及 Node API 废弃提示。本轮构建和浏览器功能通过，尚无加载性能验收结论。历史交接说明保留了旧隔离数量/分步执行提示；本轮按当前脚本实际输出计为 16 项，CPU/PID 内核观测均已执行，不沿用历史“17 项”的计数。

## 未验证边界

按已确认范围，真实 Nacos、目标环境部署、生产发布/回滚、压测与长期稳定性继续后置。当前证据针对 Mac + 单个临时 Linux Docker daemon 的功能和故障样例，不证明多宿主容量、断电恢复或内核逃逸防护。排名精确并列使用显式 acceptedAt 数据夹具；跨截止完成另有真实时间证据。空 infrastructure context 测试由项目默认标签配置排除，248 项不包含这些测试；真实依赖由独立运行验证。DianPing 不属于本轮项目验收。

切换演练的旧通道显式使用 native 执行仓库内可信样例，切换后的新通道使用 isolated；旧通道样例不用于证明隔离安全。完整业务链的浏览器日志有历史文案“remote native AC”，本轮实际模式以 run.json 的 sandboxMode=isolated、镜像 ID 和隔离证据为准。

## 复核入口

先检查 [summary.json](summary.json) 的 checks、[source-provenance.json](source-provenance.json) 的提交/指纹，再读取各类事实与 [JV 矩阵](jv-matrix.json)。下载摘要与镜像 ID 分别保留在 native/runtime provenance 文件。临时路径仅为已清理环境的历史记录，不可直接复用。[run_overall.py](run_overall.py) 是初始调度过程的原样记录；[rerun_business.py](rerun_business.py) 记录本轮修正后的继续执行过程，二者均不应直接覆盖本目录重跑。新验收须使用新证据目录与新临时环境。
''')
    print(json.dumps({'status': overall, 'checks': checks, 'jv': counts}))


if __name__ == '__main__':
    main()
