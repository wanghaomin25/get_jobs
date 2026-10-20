# boss / 51job / liepin 三页问题排查与分优先级修复规划

> 2026-10-19 排查。背景：智联页修复（docs/zhilian-issues-fix-plan.md Z1-Z8）后，逐一核对其余三页是否存在同款问题。
> 证据均为当日代码实测（文件:行号）。本文档为实施蓝图，未含代码改动。

## 一、问题清单

### A｜轮询链中断 → 停止后 UI 永久卡死【P1，三页同款 Z3，最高优先】
- **位置**：`front/app/boss/page.tsx:240-247`、`front/app/51job/page.tsx:169-176`、`front/app/liepin/page.tsx:132-139`
- **原因**：轮询 `loop()` 仅在 `st && st !== 'IDLE'` 时重排；`syncDeliveryStatus()` fetch 失败返回 null → 轮询链永久中断，停止后 IDLE 永远不会反映到 UI（与智联修复前完全相同）。
- **影响**：停止投递/任务结束后按钮卡在"停止投递"，必须手动刷新页面。

### B｜缺 stopRequested 过渡态【P1，三平台同款 Z4】
- **位置**：`BossJobService` / `Job51JobService` / `LiepinJobService`（grep 证实仅 ZhilianJobService 有该字段）；三页前端均无 STOPPING 态。
- **原因**：stopDelivery 后最长 15s 看门狗宽限期内 `/status` 无过渡信号。
- **影响**：点停止后 UI 仍显示 RUNNING，用户以为没生效而反复点击。

### C｜liepin 启动失败静默 + 启动前不落库【P1，Z6 同款+配置陷阱】
- **位置**：`front/app/liepin/page.tsx:237-253`——`/start` 失败仅 `console.warn('启动失败：不弹框')`；且 **start 前不调用 handleSaveConfig**，页面上改过的配置不保存就投递（读旧库值）。
- **放大器**：`liepin_config` 表当前 **0 行**（实测 COUNT=0）——首次启动必因"关键词为空"类错误失败，而前端完全静默，表现为"点了没反应"。
- **影响**：猎聘页"点开始没反应"最可能的直接原因。

### D｜boss 页启动前不落库【P1，同 C 的配置陷阱】
- **位置**：`front/app/boss/page.tsx:639-643`——handleStartDelivery 直接 `/start`，不先 handleSaveConfig。
- **影响**：boss 页改配置不点保存 → 投递按旧库值执行（zhilian/51job 均已改为"先落库再启动"）。

### E｜51job/liepin 启动失败静默【P2，Z6 同款】
- **位置**：`front/app/51job/page.tsx:338-346`（console.warn + IDLE，无弹窗）、liepin 同 C。boss 页此前已修（有 saveResult 弹窗）。

### F｜登出后登录态回弹【P2，Z8/N7 同款，三平台】
- **位置**：`PlaywrightManager.clearBossCookies:1764` / `clear51jobCookies:1126` / `clearLiepinCookies:1673` 均只调 `clearCookiesForPlatform`，**不 `setLoginStatus(platform,false)`**；`checkIfBossLoggedIn` 等三个判定函数也无 cookie 真值短路（cookie 空数组检查仅智联有，:1295-1299）。
- **影响**：登出后页面很快又显示"已登录"（智联同款 N7），在未登录态下启动投递必失败。

### G｜stop 失败无用户提示【P3，Z5 弱化版】
- **位置**：boss:683-686 / liepin:271-274 失败分支 `setDeliverState('IDLE')` 但无提示；51job:351-355 对 !response.ok 直接 IDLE。
- **影响**：轻微——竞态下直接复位 UI 可接受，但"任务已结束"与"真失败"不可区分。

### H｜Boss 无结果语义混淆【P3，可选优化】
- **位置**：`Boss.java:waitForJobList()`（936-963 行）——重试 3×15s 后抛 `IllegalStateException("岗位列表始终未出现")`，最终以"投递失败"上报。
- **影响**：功能上够用（失败可见、有结构快照日志），但"搜索无结果"与"页面改版选择器失效"混为一谈。51job 已有"该关键词暂无职位，提前结束"（Job51.java:250），智联已修，唯 Boss 未区分。

### 未发现问题的项（排查过）
- 51job 空结果处理完善（"暂无职位"文案检测 + 进度上报，Job51.java:247-250/852-857）；
- 51job buildSearchUrl 的 jobArea/salary 空值与"不限"处理正确（appendListParam 过滤），jobType 编码已真机取证；
- liepin getSearchUrl 城市**空值**处理正确（不拼 city/dq 即全国）；**city='0' 语义未取证**（字典若存在 code=0 的选项则有 jl=0 同款风险，见验证步骤）；
- 三平台 worker 未发现智联 Z7 式进度 total 硬编码（Boss/Liepin 进度按关键词/岗位推进）。

## 二、修复方案与涉及文件

| 编号 | 文件 | 修改方案 |
|---|---|---|
| A | 三个 page.tsx | 照抄智联修法：`st === null` 时仍 `setTimeout(loop, 5000)` 重排（失败降频），非 IDLE 维持 3s |
| B-后端 | BossJobService / Job51JobService / LiepinJobService | 照抄 ZhilianJobService：`volatile boolean stopRequested`；stopDelivery RUNNING 分支置 true；executeDelivery 拿锁后与 finally 复位；getStatus 透出 `stopRequested` |
| B-后端 | BossController / JobController / LiepinController 的 /stop | 照抄 ZhilianController：响应体加 `stopRequested` 与 `state` |
| B-前端 | 三个 page.tsx | syncDeliveryStatus 中 `state==='RUNNING' && stopRequested===true` → `'STOPPING'`；状态类型加 STOPPING；按钮加"停止中…"禁用分支（照抄智联） |
| C | front/app/liepin/page.tsx | start 前加 `await handleSaveConfig(true)`；/start 失败与 catch 分支加 saveResult 弹窗（照抄智联/51job 模式） |
| D | front/app/boss/page.tsx | start 前加 `await handleSaveConfig(true)`（确认 boss 页已有该函数与保存端点） |
| E | front/app/51job/page.tsx | /start 失败分支加 saveResult 弹窗（对齐 boss） |
| F | PlaywrightManager | ① `clearBossCookies/clear51jobCookies/clearLiepinCookies` 各加一行 `setLoginStatus(platform,false)`（照抄智联 20:12 版）；② `checkIfBossLoggedIn/checkIf51jobLoggedIn/checkIfLiepinLoggedIn` 开头加 cookie 空数组短路（照抄 :1295-1299 智联写法，仅本平台） |
| G | 三个 page.tsx | stop 失败分支加 saveResult 提示"任务已结束，无需停止"（照抄智联） |
| H | Boss.java（可选） | waitForJobList 三次失败后，先 dumpPageStructure 判断是否"确实无结果"页（含"暂无适合你的职位"文案），是则 sendProgress("该筛选条件下无职位") 并优雅结束，否则维持现抛错 |

## 三、实施顺序（考虑依赖与重启/重建成本）

1. **批次 1｜前端轮询自愈（A）+ 启动链路（C/D/E）+ stop 提示（G）**：全部是前端三页改动，**一次 dist 重建**交付；不依赖后端。注意：C 的"保存先行"需要验证 liepin_config 0 行时 PUT /api/liepin/config 是否会**创建**首行（若只 update 不 insert，需后端一并修，届时多一次重启）。
2. **批次 2｜后端 stopRequested（B-后端）**：三 Service + 三 Controller，一次重启；旧前端忽略新字段，向后兼容。
3. **批次 3｜前端 STOPPING 态（B-前端）**：依赖批次 2 字段；与批次 1 同文件可合并进批次 1 构建，但功能上等批次 2 上线后才生效——建议与批次 1 同一构建、批次 2 之后联调。
4. **批次 4｜登出回弹（F）**：共用文件 PlaywrightManager，单独一批一次重启，逐平台验证。
5. **批次 5｜Boss 无结果语义（H，可选）**：独立小提交。

> 推荐合并执行：**一次构建（前端 A/C/D/E/G + B-前端）+ 一次重启（后端 B-后端 + F）**，共 1 次构建 + 1 次重启；H 单独。

## 四、验证步骤

- **A**：启动任一平台投递 → DevTools Network offline 3s → 恢复 → 5s 内状态恢复同步；停止后 ≤15s 按钮自动回"开始投递"，无需刷新。
- **B**：`curl /api/boss/status` 等三端点含 `stopRequested`；RUNNING 中 `/stop` 响应含 `stopRequested:true`；页面按钮变"停止中…"并 ≤15s 回 IDLE。
- **C**：清空 liepin_config（或保持 0 行）→ 页面点开始 → 弹出失败提示（不再是静默）；配置保存后 liepin_config 出现首行；改关键词不点保存直接开始 → 日志确认按新值投递。
- **D**：boss 页改关键词不点保存直接开始 → 投递按新值执行（对比数据库）。
- **E**：51job 未登录态点开始 → 弹窗显示后端 message。
- **F**：任一平台页内登出 → SSE 推"未登录"且下一监控周期不回弹；DB cookie 为空数组时 /status 登录态 false。
- **G**：任务自然结束瞬间点停止 → 出现"任务已结束"提示。
- **回归**：智联页行为不回归（本轮改动不触碰 Zhilian 文件）；三平台 start→stop 一轮正常；DeliveryCoordinator/StopWatchdog 零改动。
- **liepin city='0' 取证（实施 C 时顺带）**：查 liepin 城市字典是否存在 code='0' 选项；若存在，用探针验证 city=0 的返回，必要时照智联修法处理。

## 五、风险与回滚

| 批次 | 风险 | 缓解 | 回滚 |
|---|---|---|---|
| 1 | liepin_config 空表时 PUT 可能不建行 | 实施前先验证 PUT 行为，必要时后端补 upsert | git revert 前端提交 + 回滚 dist |
| 2 | 新字段仅透出 | 旧前端忽略 | git revert + 重启 |
| 4 | PlaywrightManager 共用文件 | 只加"cookie 空数组→未登录"确定性短路，不反向；逐平台验证 | 单独 commit revert |
