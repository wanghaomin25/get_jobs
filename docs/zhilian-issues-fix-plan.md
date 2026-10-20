# 智联招聘问题修复更改规划

> 产出：软件开发团队（主理人齐活林取证 → 架构师高见远规划），2026-10-19。
> 取证证据 A-F 均含文件:行号，全部经主理人核实。本文档为实施蓝图，未含代码改动。

## 一、问题清单（按严重级别排序）

### Z1｜筛选态被 reload 重置，导致投递不符合 jobNature 配置的岗位【P0 正确性缺陷】
- **描述**：智联「工作性质」筛选是纯前端过滤（URL 不变、选中态存前端内存）。翻页循环中 `waitForSelector("div.job-card")` 超时后走 `page.reload()`（ZhiLian.java:185-192），reload 重置筛选；随后 `deliverCurrentPage` 的 waitForSelector(15s) 在"未筛选列表"上成功，继续投递了不符合配置的岗位。
- **根因**：超时兜底只考虑"页面假死"，未考虑"筛选态丢失"；`applyJobNatureFilter`（253-323 行）筛选后列表为空时 8 秒空转 + 5s waitForSelector 被忽略，必然走到「10s 超时 → reload → 误投递」链路。
- **影响**：用户配置 jobNature 后仍收到不匹配岗位投递，且消耗真实投递机会。

### Z2｜关键词无职位结果时静默跳过 + 无统计 + 超时空转【P1】（用户报告 1）
- 搜索后 `waitForSelector` 10s 超时仅 `log.warn + return`（ZhiLian.java:161-166），不 sendProgress；`execute()` 无"0 结果关键词"统计；筛选空结果路径每关键词浪费约 10+15 秒超时——「筛选空列表 → 翻页循环 waitForSelector 10s 超时 → reload → deliverCurrentPage waitForSelector 15s 超时 → 外层 catch 保存 HTML + return false → break」（证据 A：161-166 / 253-323 / 562-564 行）。Z1 的修复同时切断这条浪费链路。

### Z3｜前端轮询链中断 → 停止后 UI 永久卡死【P0 功能异常】（用户报告 2、3 的核心根因）
- 轮询 `loop()` 仅在 `st && st !== 'IDLE'` 时重排（zhilian/page.tsx:179-196）；fetch 失败返回 null → **轮询链永久中断**，此后后端任何状态变化都不反映到 UI，按钮永久卡在"停止投递"。

### Z4｜/status、/stop 无 stopRequested 过渡态字段【P1】
- `stopDelivery()` 置 shouldStop + 15s 看门狗，`/stop` 返回 success 时 state 仍是 RUNNING；`/status` 无 `stopRequested` 字段 → 前端无法显示"正在停止"，用户以为没生效而反复点击。

### Z5｜/stop 竞态返回 400 时前端无兜底【P1】
- 任务自然结束瞬间点停止 → `/stop` 400 "没有正在运行的任务"，前端 else/catch 不做任何处理（zhilian/page.tsx:217-227）——叠加 Z3 后状态卡死。

### Z6｜启动失败全程静默【P1】
- `handleStartDelivery` 失败只 `setDeliverState('IDLE')` 无提示（zhilian/page.tsx:198-215）；对比 boss 页有 saveResult 弹窗。

### Z7｜翻页进度 total 硬编码 50【P2】
- `sendProgress("正在投递第X页", pageNum, 50)` 实际页数常为 1-13，进度百分比长期失真。

### Z8｜登出后登录态缓存回弹误判（历史 N7）【P2，"状态未正确重置"相关】
- 登出只清 cookie 不刷新页面，监控线程 `checkIfZhilianLoggedIn`（PlaywrightManager.java:1284+，1275 行调用）基于 DOM 把状态置回 true；cookie 记录是"替换为空数组"（长度 2 非空）而非删除。

**用户报告映射**：问题 1 → Z1+Z2；问题 2 → Z3+Z4+Z5；问题 3 → Z3（前端复位）+ Z8（登录态复位）。

## 二、修复方案

约定：不引入新依赖；不改 DeliveryCoordinator / StopWatchdog 语义；前端遵循现有 deliverState + saveResult 模式；worker 侧改动"证据先行"小样本验证。

### Z1（ZhiLian.java：deliverByKeyword 翻页循环 + applyJobNatureFilter）
核心不变式：**任何 reload 之后，必须重放筛选再投递**。
1. 翻页循环超时分支（185-192 行）不再无条件 reload：
   - `cardCount > 0` → 列表还在（选择器瞬时抖动），直接继续 deliverCurrentPage，不 reload；
   - `cardCount == 0` 且 `isNextDisabled()` → 正常结束本关键词，break；
   - `cardCount == 0` 且还有下一页 → 才 reload，且 reload 后**必须重新调用 applyJobNatureFilter** 再 deliverCurrentPage。
2. `applyJobNatureFilter` 空结果提前退出：筛选后 `div.job-card` count==0（配一次 waitForSelector(5s) 二次确认）→ sendProgress("筛选条件下无岗位") 并 return false，删除 8 秒空转循环。
3. 改前证据先行：jobNature=实习 + 混合岗位关键词，抓取 reload 前后筛选态，确认误投递链路可复现。

### Z2（ZhiLian.java：161-166 + execute）
1. 搜索超时分支：先 `sendProgress("关键词「X」未搜索到职位，已跳过")` 再 return。
2. execute() 增加局部统计（found/empty/limit 三态），结束上报"搜索完成：N 个关键词，其中 M 个无结果"。
3. 筛选空结果路径同样上报，消除无效等待。

### Z3（front/app/zhilian/page.tsx：轮询 useEffect 179-196）
1. `syncDeliveryStatus` 返回 null 时**仍重排** `setTimeout(loop, 5000)`（失败降频 5s，成功维持 3s），轮询链不再永久中断。
2. 确认 effect cleanup 有 clearTimeout。
3. 响应 `stopRequested === true` 时进入 `STOPPING` 过渡态（按钮"停止中…"禁用）；字段缺失时 undefined 自然 falsy，向后兼容。

### Z4（ZhilianJobService.java + ZhilianController.java）
1. Service：新增 `volatile boolean stopRequested`；stopDelivery RUNNING 分支置 true；executeDelivery finally 复位 false；getStatus() 返回体加 `stopRequested`。
2. Controller `/stop` 返回体加 `state` 与 `stopRequested`。
3. 不改 DeliveryCoordinator/StopWatchdog。

### Z5（front：handleStopDelivery 217-227）
- `/stop` 400 时调一次 `syncDeliveryStatus()` 拉平状态 + saveResult 提示"任务已结束，无需停止"；网络异常提示"停止请求发送失败，请稍后查看状态"（轮询兜底自愈）。

### Z6（front：handleStartDelivery 198-215）
- handleSaveConfig(true) 失败弹窗提示（对齐 boss 页模式）；`/start` 失败展示后端 message。

### Z7（ZhiLian.java：execute）
- 进度主轴从"页"改为"关键词"：total=关键词数，当前值=已完成关键词数；页码仅保留在文案，不参与百分比。

### Z8（PlaywrightManager.java：checkIfZhilianLoggedIn + clearZhilianCookies）——推荐方案 B
- 方案 B（零导航风险）：`checkIfZhilianLoggedIn` 开头先读该平台 cookie，空数组时直接 return false（cookie 是真值来源）；`clearZhilianCookies` 内同步 `setLoginStatus("zhilian", false)` 立即纠偏。只改 zhilian 专属方法，不触碰 `clearCookieByPlatform` 共用语义。
- 备选方案 A（clearZhilianCookies 后 reload zhilianPage）：必须加"仅 IDLE 时 reload"守卫，风险更高。

## 三、修改文件清单

| 问题 | 文件 | 模块/方法 |
|------|------|-----------|
| Z1/Z2/Z7 | src/main/java/com/getjobs/worker/zhilian/ZhiLian.java | deliverByKeyword、applyJobNatureFilter、execute |
| Z4 | src/main/java/com/getjobs/application/service/ZhilianJobService.java | stopDelivery、executeDelivery、getStatus |
| Z4 | src/main/java/com/getjobs/application/controller/ZhilianController.java | /stop |
| Z3/Z5/Z6 | front/app/zhilian/page.tsx | 轮询 useEffect、handleStopDelivery、handleStartDelivery |
| Z8 | src/main/java/com/getjobs/worker/manager/PlaywrightManager.java | checkIfZhilianLoggedIn、clearZhilianCookies（仅 zhilian 路径） |

## 四、实施顺序（分批）

1. **批次 1｜后端·状态与进度（重启 1 次）**：Z4 + Z2 + Z7 —— stopRequested 是前端过渡态的前置依赖；三个低风险后端改动合并一次重启。
2. **批次 2｜前端·状态自愈与提示（重建 dist 1 次）**：Z3 + Z5 + Z6（同文件 page.tsx）。发布窗口：先重启后端 → 重建替换 dist → 一次联调。
3. **批次 3｜后端·筛选正确性（重启 1 次，独立批）**：Z1 —— 核心投递循环，正确性风险最高，独立小样本验证、独立可回滚。
4. **批次 4｜后端·登录态（重启 1 次，可选/P2）**：Z8 —— 触碰共用文件 PlaywrightManager，单独批次单独验证。

## 五、验证步骤

### Z2+Z7（批次 1 后）
1. `curl -X POST http://localhost:9527/api/zhilian/start`（关键词含一个必然无结果的随机字符串）。
2. 进度流出现"关键词「X」未搜索到职位，已跳过"；无结果关键词不再有 25s+ 停顿。
3. 结束出现"搜索完成：N 个关键词，其中 M 个无结果"；进度百分比随关键词推进。

### Z4（批次 1 后）
1. `/api/zhilian/status` 返回体含 `stopRequested`。
2. 启动后 `/stop` 响应含 `stopRequested:true`、`state:"RUNNING"`；15s 内轮询持续 true 至 IDLE 后复位 false。

### Z3+Z5+Z6（批次 2 后，浏览器操作）
1. 启动投递 → 点"停止投递"→ 不刷新页面：按钮变"停止中…"禁用 → ≤15s 回"开始投递"。
2. DevTools Network offline 3s → 恢复 → 5s 内状态恢复同步（不再永久卡死）。
3. 任务自然结束瞬间点停止 → "任务已结束"提示且按钮正确复位。
4. 清空必填配置点开始 → 弹出失败提示。

### Z1（批次 3 后，小样本）
1. jobNature=实习 + 混合岗位关键词，人工核对每条投递岗位工作性质均符合配置。
2. 筛选后空结果：日志走"筛选条件下无岗位"快速路径（秒级跳过），无 reload+15s 超时链路。
3. 翻页假死：count>0 不触发 reload；count==0 且有下一页时 reload 后日志可见"重放筛选"。

### Z8（批次 4 后）
- 登录后页内登出 → SSE 推"未登录"且下一监控周期不回弹 true；cookie 空数组时 `/status` 登录态 false。

### 回归清单（不破坏共用逻辑）
1. 评审确认未触碰 DeliveryCoordinator、StopWatchdog、`clearCookieByPlatform`。
2. boss/51job/liepin 三平台 status 200；各平台 start→stop 一轮正常（stop 后 ≤15s 复位）。
3. 并发排队：boss RUNNING 时 zhilian start → QUEUED，公平信号量行为不变。
4. StopWatchdog 强制复位路径仍正常、无二次复位。

## 六、风险与回滚

| 批次 | 风险点 | 缓解 | 回滚 |
|------|--------|------|------|
| 1 | 新增字段仅透出，向后兼容 | 旧前端忽略未知字段 | git revert + 重启 |
| 2 | 失败降频轮询略增请求量（5s 单链路，可忽略） | 仅 zhilian 页 | revert + 回滚版 dist |
| 3 | count==0 提前退出可能把"慢加载"误判为空结果导致漏投 | 退出前 waitForSelector(5s) 二次确认；仅 count==0 且 isNextDisabled 才 break；先小样本 | 单独 commit revert + 重启 |
| 4 | 触碰共用文件；cookie 优先判定可能影响正常登录判定 | 仅"cookie 为空数组"确定性信号下短路，不反向；不改 clearCookieByPlatform | 单独 commit revert + 重启 |

## 假设与不确定点
1. API 路径与响应结构以 ZhilianController 现场代码为准微调。
2. Z7 若产品要求按真实页数展示进度，需解析分页组件文本（isNextDisabled 可复用），成本略高。
3. Z8 方案 B 为推荐；若倾向 DOM 一致性可换方案 A，但必须加"仅 IDLE 时 reload"守卫。
