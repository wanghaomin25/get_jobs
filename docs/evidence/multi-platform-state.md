# 多平台投递状态 取证记录（docs/multi-platform-delivery-state-plan.md §4 T0）

> 取证时间：2026-10-19 10:22-10:35（服务 PID 17328，get-jobs.log 行 690 起）
> 场景：boss（单关键词 python开发）先启动 → 1 秒后 zhilian（keywords=["python"]，job_nature=实习）启动

## E1 第二个平台"假运行" ✓ 证实

| 时刻 | boss | zhilian |
|---|---|---|
| 10s | isRunning=true，日志 4 条 | **isRunning=true**，日志 3 条 |
| 20s-60s | isRunning=true，日志缓慢增长（4→6） | isRunning=true，**日志恒为 3 条** |

zhilian 的 `isRunning=true` 贯穿整个 boss 投递期，但它的投递一行都没开始（见 E2）。

## E2 排队期间零输出 ✓ 证实

zhilian 全部日志集中在启动瞬间（10:23:01.759-.762），此后直到 boss 结束（10:26 左右）零新增：

```
10:23:01.759 [ForkJoinPool.commonPool-worker-2] [zhilian] 检查浏览器状态...
10:23:01.760 [zhilian] 配置加载成功
10:23:01.760 [zhilian] 开始投递任务...
10:23:01.761 ZhiLian: 智联招聘准备工作开始...
10:23:01.762 ZhiLian: 智联招聘准备工作完成
```

**补充根因细节**：与计划 §1-R2 略有出入的精确机制——`executeDelivery` 本身跑在 ForkJoinPool
线程上，前置校验与 `prepare()` 都瞬间完成，真正阻塞点是 `callOnPlaywright(zhilian::execute)`
（`ZhilianJobService.java:92`）——执行体在 Playwright 单线程执行器的队列里等 boss 的 execute 跑完。
"检查浏览器/配置加载/暂停监控"等副作用在**排队点之前**就已发生（印证计划 T1 第 3 点的整改必要性）。

## E3 按钮永久锁死 ✓ 证实

排队期（boss 仍在跑）执行 `POST /api/zhilian/stop`：

```
{"success":true,"message":"智联招聘任务停止请求已发送"}
```

- stop 后 30 秒内三次轮询 `GET /api/zhilian/status` → `isRunning:true` 不变（10s/20s/30s 各一次）
- 再次 `POST /api/zhilian/start` → `{"success":false,"message":"智联招聘任务已在运行中，请等待当前任务完成","status":"running"}`

机制：`shouldStop=true` 置位后，排队阶段无人读取（执行体还在队列里）；无 watchdog 兜底
（`ZhilianJobService.stopDelivery` 仅置标志，`BossJobService.java:127-146` 的 watchdog 是 Boss 独有）。

## E4 行为等价性基线 ✓ 证实

`POST /api/boss/stop` 后 10 秒 boss `isRunning=false`；zhilian **自动接续执行**（日志从 3 条恢复增长）——
隐性排队真实存在。值得记录的细节：zhilian 接续后第一条日志是

```
[playwright-thread] [zhilian] 用户取消投递或已达上限
```

即 E3 在排队期置位的 `shouldStop` 被**延迟消费**（执行体开始后才读到）——zhilian 接续后立即自行退出。
结论：改造必须保持「排队 → 前一平台结束后自动接续」的行为（E4 复验时若无排队期 stop，应完整执行投递）。

## E5 前端状态本地化 ✓ 代码级证实

- `front/app/boss/page.tsx:126`、`zhilian/page.tsx:29`、`51job/page.tsx:33`、`liepin/page.tsx:48`：
  `isDelivering` 均为局部 `useState(false)`，**无挂载时查询、无轮询恢复**。
- 逻辑上必然：刷新/新开标签页后 `isDelivering` 归零显示"开始投递"，而后端任务仍在跑。
  （未做浏览器刷新实测——逻辑路径唯一，无恢复代码路径。）

## E6 SQLite 当前模式 ✓ 实测

```
journal_mode = delete
busy_timeout = 5000
```

计划预期 `delete`/`0`；实测 busy_timeout 已是 5000（历史配置），仍无 WAL。T5 需将 journal_mode
改为 `wal`，并把 busy_timeout 统一配置为 10000。

## E7 跨平台 Cookie 互伤 ✓ 代码级证实（未做真机登出，避免破坏登录态）

- `PlaywrightManager.java` 四个方法体**完全相同**，均为无参 `context.clearCookies()`：
  - `clear51jobCookies()` → `:1117`
  - `clearZhilianCookies()` → `:1551`
  - `clearLiepinCookies()` → `:1642`
  - `clearBossCookies()` → `:1743`
  （日志原文："已清理共享上下文中的**所有**Cookie"）
- 调用方：`BossController.logoutBoss`、`ZhilianController`、`JobController /51job/logout`、
  `LiepinController` 各自 logout 后还会 `cookieService.clearCookieByPlatform(platform, "manual logout")`
  —— **DB 侧只删本平台记录**，无需修正；问题只在浏览器上下文侧。
- Playwright 版本：`com.microsoft.playwright:playwright:1.62.0`。
- 域名常量已存在：`BOSS_DOMAIN="zhipin.com"`、`LIEPIN_DOMAIN="liepin.com"`、
  `JOB51_DOMAIN="51job.com"`、`ZHILIAN_DOMAIN="zhaopin.com"`（`PlaywrightManager.java:127-130`）。

## T0 结论

根因链 R1/R2/R3 全部证实，四个附带缺陷 D1-D4 均有代码级证据。按计划进入 T1-T5。

## T1-T5 实现与验证（2026-10-19 11:00-11:30）

### 实现文件

| 文件 | 改动 |
|---|---|
| `worker/service/DeliveryCoordinator.java` | **新增**：全局投递执行权协调器。计划描述为公平 ReentrantLock，但 watchdog 需要跨线程强制释放（ReentrantLock 不允许非持有线程 unlock），故用公平 `Semaphore(1, true)` 实现（先到先得语义不变），`permitHeld` AtomicBoolean 防二次释放 |
| `worker/service/StopWatchdog.java` | **新增**：停止看门狗公共实现（500ms 轮询 + 宽限期超时强制复位回调） |
| `worker/service/JobPlatformService.java` | 接口新增 `isQueued()` |
| 四个 `*JobService.java` | 状态机 IDLE/QUEUED/RUNNING；`isRunning` 语义收窄为 `state==RUNNING`；副作用步骤（ensureReady/Page/登录检查/pauseMonitoring/配置加载/prepare）整体后移到拿到执行权之后；finally 释放执行权+复位状态+resume 监控；`getStatus()` 扩展 state/isQueued/blockedBy/blockedByName/queuedSeconds；新增 `getStartStateInfo()` 供 /start 响应体 |
| 四个 Controller | `/start` busy 检查加 isQueued、响应体带 `state`/`isQueued`/`blockedByName`；`/stop` 放行排队中（`!isRunning() && !isQueued()` 才拒绝——修复验证中发现的排队期无法取消问题） |
| `PlaywrightManager.java` | 四个 `clearXxxCookies()` 改为 `clearCookiesForPlatform(域名, 平台)`：读全部 Cookie → 保留非本平台域名（空白域名一律保留）→ clearCookies → 回填 |
| `application.yaml` | Hikari `connection-init-sql: PRAGMA busy_timeout=10000` |
| 生产库 | 执行一次 `PRAGMA journal_mode=WAL`（持久化，复查确认 wal；`getjobs.db-wal`/`-shm` 已出现） |
| 四个前端页面 | `deliverState`（IDLE/QUEUED/RUNNING）以后端为准：挂载时 `GET /status` 初始化、非 IDLE 每 3s 轮询、QUEUED 显示「取消排队」按钮 + "{blockedByName} 投递中，已排队等待" 文案、`/start` 返回体 state 决定初始显示；`isDelivering` 派生为 `state==='RUNNING'`，各页保存配置链路与登录 SSE 未动 |

### §5 验证结果（真机，2026-10-19）

| # | 验证项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 排队状态如实 | ✓ | `/start` 返回 `{"state":"QUEUED","blockedByName":"Boss直聘","isQueued":true}`；排队期 `/status`：`state=QUEUED, isRunning=false, isQueued=true, blockedBy=boss, queuedSeconds=2` |
| 2 | 行为等价（不回归） | ✓ | 组合2：zhilian 跑完后 51job 从 QUEUED 自动转 RUNNING（隐性排队行为保持）；T0-E4 基线同 |
| 3 | 排队可取消 | ✓ | QUEUED 中 `POST /stop` → 2 秒内 `state=IDLE`；boss 不受影响仍 RUNNING |
| 4 | 按钮不再锁死 | ✓ | RUNNING 中 stop：boss 6 秒复位、51job 12 秒复位（均 < 15s 宽限）；复位后可再次 start |
| 5 | 状态跨刷新正确 | ✓（接口级） | `/status` 恒返回真实 state；前端挂载即查、非 IDLE 每 3s 轮询（代码路径唯一）。浏览器刷新实测未做（无浏览器自动化），逻辑上刷新后按钮由挂载查询还原 |
| 6 | 并发写库安全 | ✓ | `journal_mode=wal`；zhilian 投递进行中 PUT /api/51job/config → 200；全程日志 0 次 `database is locked`/`SQLITE_BUSY` |
| 7 | 登录态隔离 | ✓ **真机已验证（2026-10-19 11:15，用户确认后执行）** | 以智联为登出对象：`POST /api/zhilian/logout` 后，后端日志 `已清理 zhilian 域名的 Cookie（共 25 条，保留其它平台 80 条）`；boss 与 51job 登录态保持 `isLoggedIn:true`，DB cookie 完好（boss 4164 字符、51job 6170 字符，智联已清为 `[]`）。已验证：登出 A 不再影响 B |
| 8 | 四平台两两组合 | ✓ 3 组 | boss+zhilian（轮A）、zhilian+51job（轮B）、51job+boss（轮C：51job RUNNING → boss QUEUED("前程无忧51job") → 取消即 IDLE、51job 不受影响） |
| 9 | 构建与重启 | ✓ | compileJava exit 0；`npm run build:prod` EXIT=0（"取消排队"文案已进 chunk）；重启后 boot log 0 ERROR |

**附带发现并修复**：验证第 3 项时发现四个 Controller 的 `/stop` 原本在排队期被 `isRunning` 检查挡住
（返回"没有正在运行的任务"），排队无法取消——已在本次一并修复（放行 isQueued）。

**小样本配置已恢复**：job51_config keywords=[实习,python,后端,fastapi,MySQL]、boss_config
keywords=[python开发,agent]。

**注意**：T4 真机验证时发现一个**范围外既有缺陷（N7）**：登出智联后其内存登录缓存又被后台监控置回
true——`checkIfZhilianLoggedIn` 基于页面 DOM（登录按钮），页面未重新加载前不反映 cookie 清空；且
`clearCookieByPlatform` 是把 cookie 置为 `[]`（长度 2）而非删行/置空串。这属于登录监控自身的缓存
一致性问题（与本计划 T4 改动无关，T4 的域隔离行为已由日志证实），列入范围外清单供用户后续决定。

### 用户须知

- **T4 真机验证（§5 第 7 项）需要你参与**：在页面上登出任意一个平台（会真实登出、需要重新扫码），
  然后确认其它平台仍显示已登录。同意后再操作即可，代码路径已就绪。
- **WAL 副作用**：`db/` 目录下会出现 `getjobs.db-wal` / `getjobs.db-shm`；日后备份数据库时请把
  这两个文件一起复制（或先对库执行 `PRAGMA wal_checkpoint(TRUNCATE)`）。
