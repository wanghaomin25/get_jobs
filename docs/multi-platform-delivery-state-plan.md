# 实施计划：多平台同时投递的状态错乱修复（串行语义如实化 + 四项附带修复）

> 面向执行 agent。项目根 `C:\Users\wanghaomin\Desktop\jianli\get_jobs`。
> 症状与根因已于 2026-10-19 核实（见 §1），证据带 `文件:行号`。
> **本计划明确不做真并发。** 现状是四个平台共用同一个浏览器、同一条 Playwright 线程，投递在执行层**本来就被强制串行**。本计划要做的是：**让界面如实反映这个串行事实**，而不是去实现并发。任何"新增浏览器实例 / 新增 user-data-dir / 把执行器改成多线程"的改动，都超出本计划范围（§2 已列为禁止项）。

## 1. 症状与根因（已核实，含证据）

**用户症状**：同时启动两个平台的投递时，"开始投递"一栏状态错误——**每次必现**；具体表现为「状态与实际相反」「卡住/不更新」「按钮状态错」；**任意两个平台组合**都如此。用户已明确：**接受串行排队，只要状态不再错乱、按钮不再锁死**。

### 根因链

| # | 机制 | 证据 |
|---|---|---|
| R1 | 四个平台共用同一个 `BrowserContext` 和**同一条 Playwright 专用线程** | `PlaywrightManager.java:60`（"浏览器上下文（所有平台共享…）"）、`:152-156`（`Executors.newSingleThreadExecutor`）、`:179-185`（`callOnPlaywright` = 阻塞式 `submit().get()`） |
| R2 | `isRunning = true` 在**进入执行器之前**就置位，真正的执行体却阻塞在 `callOnPlaywright` 的队列里 | `BossJobService.java:66`（置位）→ `:98`（阻塞）；`ZhilianJobService.java:63` → `:92`；`Job51JobService` / `LiepinJobService` 同构 |
| R3 | 排队期间零日志、零状态变化；且非 Boss 平台的 `stopDelivery` 无兜底 | `ZhilianJobService.java:110-115`（只置 `shouldStop`）对比 `BossJobService.java:127-146`（15s watchdog）；`Job51JobService.stopDelivery` / `LiepinJobService.stopDelivery` 同 Zhilian |

### 症状 ↔ 原因对照

| 用户观察 | 直接原因 |
|---|---|
| 状态与实际相反（显示"投递中"、实际一行都没投） | R2：`isRunning=true` 早于真正执行，任务在队列里干等前一个平台跑完（可能几十分钟） |
| 卡住 / 不更新 | R2 + R3：排队期间执行体尚未开始，没有任何日志或进度输出 |
| 按钮状态错（永久"停止投递"、点停止停不下来） | R3：非 Boss 平台无 watchdog，而 `shouldStop` 在排队阶段无人读取，`isRunning` 永久为 true |

### 核心结论

这不是并发 bug，而是**"前端按每平台独立来承诺，执行层却全局串行"的语义不一致**。因此修法是让 UI 如实表达"排队"，**不是**去实现并发。

### 附带查出的四个真实缺陷（本计划一并修，详见 §4）

| # | 缺陷 | 证据 |
|---|---|---|
| D1 | 任一平台"退出登录"执行 `context.clearCookies()`，会**清掉共享上下文里所有平台的 Cookie**，投递中途会让另一个平台掉线 | `PlaywrightManager.java:1114-1126`（51job）、`:1548-1560`（zhilian）、`:1639-1651`（liepin）、`:1740-1752`（boss）四个方法体完全相同 |
| D2 | 前端投递状态是各页**局部 `useState`**，无轮询、无后端查询：刷新页面或新开标签页后归零（显示"开始投递"但后端仍在跑） | `boss/page.tsx:126`、`zhilian/page.tsx:29`、`51job/page.tsx:33`、`liepin/page.tsx:48` |
| D3 | 智联/51job/猎聘的"停止投递"没有兜底：任务卡在某个 Playwright 调用里时 `isRunning` 永远 true，该平台按钮被永久锁死，只能重启应用 | `ZhilianJobService.java:110-115`、`Job51JobService.stopDelivery`、`LiepinJobService.stopDelivery` |
| D4 | SQLite 单文件多写者（投递入库 vs 保存 Cookie/配置）无并发保护：未启用 WAL、未设 `busy_timeout` | `application.yaml:15`（`jdbc:sqlite:./db/getjobs.db`）、`:16-19`（Hikari max 5）；`CookieService.java:74-92`（无锁 select-then-write） |

## 2. 目标与非目标

### 目标

1. 投递调度从**隐性排队**改为**显性排队**：状态明确区分 `IDLE / QUEUED / RUNNING`，排队时界面告诉用户"正在等待 X 平台投递完成"。
2. 各平台页面的投递状态**以后端为准**（挂载时查询 + 运行期同步），刷新/新标签页后仍显示真实状态。
3. 停止投递在四个平台行为一致可靠：**排队中可立即取消**；运行中卡死有 watchdog 兜底，按钮不会永久锁死。
4. 任一平台"退出登录"只清本平台域名的 Cookie，不再影响其它平台登录态。
5. SQLite 开启 WAL + `busy_timeout`，消除多写者场景下的 `database is locked` 风险。

### 非目标（禁止实施）

- ❌ **不拆浏览器**：不新增 `Playwright` 实例、不新增 `user-data-dir`（`browser-data` 保持唯一）、不新增独立 Chrome 进程。
- ❌ **不实现真并发**：不改 `playwrightExecutor` 的线程数（保持单线程），不引入并行投递。
- ❌ 不改四个平台各自的投递业务逻辑（`Boss.java` / `ZhiLian.java` / `Job51.java` / `Liepin.java` 内部的采集与投递流程）。
- ❌ 不改站点选择器、不改配置字段、不动数据库表结构（只允许对连接执行 `PRAGMA`）。
- ❌ 不顺手修 §8「范围外已知问题」清单里的其它缺陷。

## 3. 环境与惯例

- **git**：仓库可用（含上游历史）。动手前先 `git add -A && git commit -m "checkpoint before multi-platform state fix"`（message 用英文）。**每个任务完成后再提交一次**，便于回退。
- 服务：用户双击 `start.bat`；API 端口 9527。**执行 agent 若从自己的 shell 起 `gradle bootRun`，必须先 `unset SERVER__PORT`**（该环境变量会经 Spring 宽松绑定把端口改成 61517，历史踩过）。
- 编译：`JAVA_HOME=~/jdk-21 ~/gradle-9.7.1/bin/gradle.bat compileJava -q`；改前端后 `cd front && npm run build:prod`（若构建守卫拦截批量删除，沿用"把旧产物 mv 旁移"的既有做法）。
- 改 Java 或前端后**都必须重启服务**才生效。
- GBK 终端坑：curl 传含中文 JSON 用 `--data-binary @utf8文件`；查中文数据用 `python -I` 落 UTF-8 文件再读；不要用管道直接把含中文 JSON 喂给 python。
- `db/getjobs.db` 是生产库：**只允许 INSERT/UPDATE 和 `PRAGMA`**，禁止重建表、禁止模板覆盖、禁止清空业务数据。
- **铁律：先取证、后改代码。** T0 未产出证据前，不得开始 T1-T5。取证阶段的实际投递请用小样本关键词，投完即停。

## 4. 任务

### T0 取证（先做，产出 `docs/evidence/multi-platform-state.md`）

按 §1 的根因逐条验证，**每条都要落证据（日志行 / 接口返回体 / 截图描述）**：

| # | 验证项 | 方法 | 期望证据 |
|---|---|---|---|
| E1 | 第二个平台"假运行" | 依次 `POST /api/boss/start`、`POST /api/zhilian/start`，随后每 10s 轮询 `GET /api/zhilian/status` 与 `GET /api/boss/status` | zhilian 的 `isRunning=true`，而其投递日志**一条都没有**；boss 在正常输出 |
| E2 | 排队期间零输出 | 观察 `target/logs/get-jobs.log` 中 zhilian 相关行 | 从 start 到 boss 结束之间，zhilian **无任何日志**（证明"卡住/不更新"的观感来源） |
| E3 | 按钮永久锁死 | 在 E1 的排队阶段 `POST /api/zhilian/stop`，然后反复查 `/api/zhilian/status` | `isRunning` 长时间保持 true；此后再次 `POST /api/zhilian/start` 返回"任务已在运行中" |
| E4 | 行为等价性基线（改造后要复现同样的最终结果） | E1 场景下等 boss 跑完 | zhilian **会自动接着开始执行**（这是现有隐性排队的真实行为，改造后必须保持） |
| E5 | 前端状态本地化 | 投递运行中刷新页面 | 按钮回到"开始投递"（证明 D2） |
| E6 | SQLite 当前模式 | `PRAGMA journal_mode;` / `PRAGMA busy_timeout;`（用 `python -I` 走 sqlite3） | 记录当前值（预期 `delete` / `0`） |
| E7 | 跨平台 Cookie 互伤 | **只读核实**：读 `PlaywrightManager` 四个 `clearXxxCookies` 与调用方（`BossController.logoutBoss:157` 等），确认都是无参 `context.clearCookies()` | 代码证据即可。**不要真机上逐个登出验证**（会破坏登录态、需要重新扫码），除非用户明确同意 |

**E1/E3 会真实投递**：用小样本配置（单关键词），并在 boss 投递完成第一页后 `POST /api/boss/stop`，避免消耗日限额。

### T1 后端：投递调度改为显性排队

**新增** `com.getjobs.worker.service.DeliveryCoordinator`（`@Component` 单例），管理全局投递执行权：

```java
// 设计要点（实现细节可调整，语义不得变）
private final ReentrantLock deliveryLock = new ReentrantLock(true); // 公平锁 = 先到先得
private volatile String runningPlatform;                             // 当前持锁平台

boolean tryAcquireImmediately(String platform);                       // 立即尝试
boolean awaitTurn(String platform, BooleanSupplier cancelled, long pollMs); // 自旋 tryLock(500ms)，每轮检查取消
void release(String platform);
String currentRunningPlatform();
String displayName(String platform);  // boss→Boss直聘 / zhilian→智联招聘 / 51job→前程无忧51job / liepin→猎聘
```

**四平台 `executeDelivery` 统一改造**（`BossJobService` / `ZhilianJobService` / `Job51JobService` / `LiepinJobService`，共 4 处）：

1. 保留最外层的参数级检查；**状态字段在拿到执行权之前不得置为运行**。
2. 加入排队点：
   - 立即拿到执行权 → `state = RUNNING`，记 `runningSince`，日志"获得投递执行权"；
   - 未拿到 → `state = QUEUED`，记 `blockedBy = coordinator.currentRunningPlatform()`、`queuedSince`，日志"已排队，等待 {平台名} 投递完成"，然后进入**可取消的自旋等待**（`awaitTurn`）。
3. **拿到执行权之后**才执行这些有副作用的步骤（顺序很重要）：
   `ensureReady()` → 取 Page → 检查已登录 → `pauseXxxMonitoring()` → 读取配置 → `prepare()` → `callOnPlaywright(xxx::execute)`。
   > 现状里 `pauseXxxMonitoring()`（`BossJobService.java:71` / `ZhilianJobService.java:67`）、`prepare()`（`:93` / `:89`）都在**排队点之前**，会让排队期间就提前暂停后台登录监控——必须整体后移到拿到执行权之后。
4. `finally`：`release()` + `state = IDLE` + `resumeXxxMonitoring()`（异常时也不能漏放锁，否则全系统投递永久卡死）。

**状态字段与接口扩展**（各 `JobService`）：
- 新增 `state`（`IDLE`/`QUEUED`/`RUNNING`）、`queuedSince`、`blockedBy`、`cancelQueued` 标志。
- `getStatus()` 返回值扩展（**保留 `isRunning`，其值 = `state == RUNNING`**，避免前端其它逻辑失效）：

```json
{"platform":"zhilian","state":"QUEUED","isRunning":false,"isQueued":true,
 "blockedBy":"boss","blockedByName":"Boss直聘","isLoggedIn":true,"queuedSeconds":42}
```

- Controller 的 `/start` 响应体带上启动后的初始状态，让前端能立刻区分"已开始跑"与"已排队"（如 `{"success":true,"state":"QUEUED","blockedByName":"Boss直聘"}`）；四个 Controller 的 `/start` 都要改（`BossController:88`、`ZhilianController:275`、`JobController:/51job/start:389`、`LiepinController:70`）。
- 兼容性：前端旧代码若只看 `success` 不受影响。

### T2 停止逻辑统一（T3 的 D3 修复）

抽公共实现，避免四处复制粘贴（例如在 `JobPlatformService` 加默认方法，或新增 `StopWatchdog` 工具类）：

- `state == QUEUED` → 置 `cancelQueued = true`，排队自旋立即退出，**立即**回 `IDLE`（不启动 watchdog、不等待）。
- `state == RUNNING` → 置 `shouldStop = true` + 启动 watchdog，宽限期统一 **15 秒**（照 `BossJobService.java:127-146` 与 `FORCE_RESET_GRACE_MS=15_000L`）。
- `state == IDLE` → 幂等返回。
- Boss 已有此实现：改为复用公共实现，行为保持一致（不要改它的 15s 数值）。
- 强制复位时同样要 `release()` 执行权 + `resumeXxxMonitoring()`，否则排队中的平台永远拿不到锁。

### T3 前端：投递状态以后端为准

四个页面统一改造（`front/app/boss/page.tsx`、`zhilian/page.tsx`、`51job/page.tsx`、`liepin/page.tsx`）：

1. **挂载时初始化**：`useEffect` 里 `GET /api/{platform}/status`，用返回的 `state` 初始化本页状态（解决 D2：刷新/新标签页后不再显示错误的"开始投递"）。
2. **运行期同步**：投递中或排队中每 **3 秒** 轮询一次 `/status`（四个平台的进度推送能力不一致——智联/猎聘**根本没有进度 SSE 端点**，只有 Boss/51job 有 `:stream`，所以统一用轮询最省事、行为最一致）。轮询只在 `state != IDLE` 时进行。
3. **按钮与文案按真实状态渲染**：

| 后端状态 | 按钮 | 文案 |
|---|---|---|
| `IDLE` + 已登录 | 「开始投递」（可点） | — |
| `IDLE` + 未登录 | 「请先登录 {平台}」（禁用） | — |
| `QUEUED` | 「取消排队」（可点，调 `/stop`） | "{平台名} 投递中，本任务已排队等待" |
| `RUNNING` | 「停止投递」（可点） | 保持现有进度展示 |

4. 点击"开始投递"后，用 `/start` 返回体里的 `state` 决定立即显示"排队中"还是"投递中"，**不再无条件假定为运行中**。
5. 保持各页现有表单/配置逻辑不动（`handleStartDelivery` 里"先保存配置再启动"的链路不要破坏，`zhilian/page.tsx:161`、`51job/page.tsx:287`）。
6. 各页的登录态 SSE 逻辑保持不变（它是按 `platform` 字段过滤的，本身没有串台问题）。

### T4 跨平台登出只清本平台 Cookie（D1 修复）

改造 `PlaywrightManager` 四个 `clearXxxCookies()`（`:1114`、`:1548`、`:1639`、`:1740`）：

- **实现方式**：读出上下文全部 Cookie → 只保留**非本平台域名**的 → `clearCookies()` → 把保留的部分 `addCookies(保留集)`。
  （先取证当前 Playwright 版本是否支持带 domain 过滤的 `clearCookies(filters)`；不支持就用上面的保守做法，**不要赌 API**。）
- 同步核对数据库侧：确认各平台的 logout 只删除 `cookie` 表中本平台的记录（若现状删了别的平台记录，一并修正）。
- 域名常量已存在于 `PlaywrightManager`（`BOSS_DOMAIN` / `LIEPIN_DOMAIN` / `JOB51_DOMAIN` / `ZHILIAN_DOMAIN`，见 `:497`、`:662`、`:842`、`:1210` 的用法），直接复用。
- ⚠️ 这是本计划**风险最高**的一项（直接关系登录态）：改完后必须逐个平台真机验证「登出 A 之后 B 仍处于登录状态」，并把结果写进取证文档。会真实登出，需要重新扫码，**提前告知用户**。

### T5 SQLite 并发写保护（D4 修复）

- **WAL**：对生产库执行一次 `PRAGMA journal_mode=WAL;`（该设置持久化在库文件里，执行一次即可）。**不改表结构、不动数据。**
- **busy_timeout**：在 Hikari 上配置连接初始化语句，使每个连接都生效，例如 `spring.datasource.hikari.connection-init-sql: PRAGMA busy_timeout=10000`（10000ms 可调）。
- 注意副作用：库目录会多出 `getjobs.db-wal` / `getjobs.db-shm` 文件；**提醒用户：日后备份数据库时要连这两个文件一起复制**（或先执行 `PRAGMA wal_checkpoint(TRUNCATE)`）。
- 验证：见 §5 第 6 项。

## 5. 验证清单（逐条留证据到 `docs/evidence/multi-platform-state.md`）

1. **排队状态如实**：依次启动 Boss、智联。智联页面/接口在排队期显示 `state=QUEUED` + `blockedByName=Boss直聘`，界面文案为"排队等待"，**不再显示"投递中"**。
2. **行为等价性（不回归）**：T0-E4 的场景重跑一遍——Boss 跑完后**智联自动开始执行**，与改造前一致（证明只是状态变诚实，行为没变）。
3. **排队可取消**：智联排队中点"取消排队"，立刻回 `IDLE`、按钮变回"开始投递"，Boss 的投递**不受影响**。
4. **按钮不再锁死**：投递中触发停止，最长 15 秒内 `isRunning`/`state` 复位，可再次点击"开始投递"。
5. **状态跨刷新正确**：投递运行中刷新智联页面，按钮仍显示"停止投递"（后端为准），不再归零。
6. **并发写库安全**：`PRAGMA journal_mode` 返回 `wal`、`busy_timeout` 为配置值；制造并发写（一个平台投递进行中，同时在另一个平台点"保存配置"），日志中**不出现** `database is locked` / `SQLITE_BUSY`。
7. **登录态隔离**：登出 A 平台后，B 平台仍为已登录（`GET /api/{b}/login-status` → `isLoggedIn:true`），且 B 的 Cookie 在上下文中仍存在。
8. **四平台两两组合**：至少验证 3 组不同平台组合（如 boss+zhilian、zhilian+51job、51job+liepin），确认现象一致正确（用户报告的是"任意两个都这样"，不能只测一组）。
9. **构建与重启**：`compileJava` exit 0、`npm run build:prod` 成功、重启后无异常堆栈。

## 6. 禁止事项

- 不拆浏览器、不新增 Playwright 实例 / user-data-dir / Chrome 进程、不改执行器线程数（§2 非目标）。
- 不改 `Boss.java` / `ZhiLian.java` / `Job51.java` / `Liepin.java` 的投递业务逻辑（只改 `*JobService` 的调度与状态层）。
- 不改站点选择器、不动配置字段、不动数据库表结构；生产库只允许 `PRAGMA` 与正常 DML。
- 不新增"真正并发"相关的实验性代码；若执行中发现非并发不可解，**停下来报告**，不要自行扩大范围。
- T4 登出验证会造成真实登出，**先告知用户再执行**。
- 不顺手修 §8 范围外问题。
- 遇验证码/滑块/WAF：停止、报告、证据存 `docs/evidence/`，不做对抗。

## 7. 交付物与验收

- [ ] `docs/evidence/multi-platform-state.md`：E1-E7 逐项"结论 + 证据"，以及 T4 的登出隔离真机结果
- [ ] T1-T5 全部落地，`compileJava` exit 0、前端 `build:prod` 成功、服务重启正常
- [ ] §5 九项逐条通过并留证据（第 8 项至少 3 组平台组合）
- [ ] 改造后「排队 → 自动接续」行为与改造前一致（§5 第 2 项）
- [ ] 每个任务一次 git commit（英文 message），交付说明写明改了哪些文件、最终状态机语义、以及"四平台仍为串行执行"这一事实

## 8. 范围外已知问题（本次不修，仅记录，供用户后续决定）

| # | 问题 | 证据 |
|---|---|---|
| N1 | 登录状态监听器只注册不移除：每次 SSE 连接都 `addLoginStatusListener`，断开时从不 `removeLoginStatusListener`（该方法无调用者），监听器无限累积、登录状态变化被重复广播 | `JobController.java:94`、`:96-109`；`PlaywrightManager.java:1905` |
| N2 | 猎聘每次投递都在共享 page 上**追加**一份 `page.onRequest/onResponse` 监听（`monitoringRegistered` 是 prototype bean 的实例字段，恒为 false），反复投递会让响应被解析多次 | `Liepin.java:63-101`、`:104-113` |
| N3 | Boss 有重复入口 `/execute`，校验弱于 `/start`（不检查登录） | `BossController.java:70-85` vs `:88` |
| N4 | 各平台 `/start` 是「先查 isRunning 再启动」的非原子 check-then-act，同一平台并发双击理论上可穿透 | `BossController.java:98-108`、`ZhilianController.java:289-301`、`JobController.java:399-409`、`LiepinController.java:92-96` |
| N5 | `AsyncConfig` 配置的 `taskExecutor` 实际未被投递启动使用（四处都用单参 `CompletableFuture.runAsync`，走 `ForkJoinPool.commonPool`） | `AsyncConfig.java:24-54`；`BossController.java:79,108` 等 |
| N6 | `browser-data` 是全局唯一持久化 profile，四平台登录态物理上混在其中，且同时运行第二个应用实例会因 profile 占用而失败 | `PlaywrightManager.java:114`、`:305` |