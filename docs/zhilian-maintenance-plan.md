# 实施计划：智联招聘（Zhilian）自动投递流程维护

> 本文档面向执行 agent。所有代码位置在 2026-10-17 实测核对。行号偏移时以"原文"片段搜索定位。
> 性质：**先取证、后修复**的健康检查。分两类任务：确定性缺陷修复（代码层已证实，直接改）与证据依赖修复（依赖智联网站当前 DOM，先跑一轮取证再改）。

## 1. 平台与架构现状

- 智联投递 = Playwright **DOM 点击模式**（不同于 Boss 的 JSON 监听模式）：导航搜索页 → 搜索框输入关键词回车 → 等 `div.joblist-box__item` 卡片 → 采集入库 `zhilian_data` → 逐卡点 `button.collect-and-apply__btn` → 标记"已投递"。
- 任务框架完好：`ZhilianJobService`（登录校验/暂停监控/Playwright 专用线程/SSE 进度）、`ZhilianController`（/api/zhilian 全端点：config GET/PUT、login-status、login/logout、start/stop、stats、list）、`ConfigService.getZhilianConfig()` 每次投递从 `zhilian_config` 专表现读（无缓存）。
- **关键数据事实**：`zhilian_data` 表 **0 行**（从未成功投递过任何岗位）；`zhilian_config` 1 行（keywords=["实习","python","后端","fastapi","MySQL"]、city_code='0'、salary='不限'，2026-10-14 保存后未再更新）；`cookie` 表 zhilian 条目 5135 字符、**2026-10-14 18:57 更新（已 3 天）**；`zhilian_option` 仅 42 条 city 字典。
- 用户 10-14 曾报告智联"搜索结果为空"（截图），当时怀疑未登录/反爬——结合 0 行数据，**智联流程在本机从未跑通过**。

## 2. 环境事实（与 Boss 计划文档相同，执行前必读）

- 项目根：`C:\Users\wanghaomin\Desktop\jianli\get_jobs`，**非 git 仓库**。开始任何修改前先备份：
  ```bash
  cp src/main/java/com/getjobs/worker/zhilian/ZhiLian.java src/main/java/com/getjobs/worker/zhilian/ZhiLian.java.bak
  cp front/app/zhilian/page.tsx front/app/zhilian/page.tsx.bak
  cp db/getjobs.db db/getjobs.db.bak
  ```
- `db/getjobs.db` 是生产库（含 Boss 的 AI 配置/cookie），绝不可用模板覆盖。
- 服务启动：项目根 `start.bat`；API 9527、管理页 6866。若服务已在运行，改完 Java 需重启（gradle bootRun 不热编译）。
- 前端构建链已配好：`front/` 下 `npm run build:prod`（pnpm 依赖已装齐，产物自动进 `src/main/resources/dist/`）；构建后需重启服务+浏览器 Ctrl+F5。
- Windows GBK 终端查中文数据：python 落 UTF-8 文件再读（参考 Boss 计划 §2 的 `python -I -c` 模板）。
- curl 传含中文 JSON 必须 `--data-binary @文件`（UTF-8 文件），直接 `-d '{"中文"}'` 会被 Windows 代码页转码导致 400（2026-10-17 实测踩过）。
- 智联投递使用浏览器持久化登录态（`browser-data/` + cookie 表），需要用户**人工扫码登录**一次。

## 3. 确定性缺陷（已代码证实，直接修）

### P1 投递计数恒为 0（统计假象）

`ZhiLian.java` :104 完成消息与 `ZhilianJobService.java` :95 都输出 `resultList.size()`，但 `resultList` **只**在 `deliverSimilarJobs()`（:469）里 add——而 `deliverSimilarJobs`/`handleDeliveryDialog`（:374-486）全文件无调用者，是旧弹窗流程的死代码。真实投递循环（:303-356）从不 add → **永远报"共投递0个岗位"**，即使点击全部成功。
**修复**：在投递循环 `markDeliveredByJobId(...)` 成功后（:340 之后）加：
```java
Job applied = new Job();
applied.setJobName(pj.jobTitle);
applied.setCompanyName(pj.companyName);
resultList.add(applied);
```
（`com.getjobs.worker.utils.Job` 已 import。）死代码 `handleDeliveryDialog`/`deliverSimilarJobs` **保留不动**（与新主流程语义不符，删除属额外重构，超出本次范围）。

### P2 重跑会对已投递岗位重复点击（最严重）

采集循环 :264-282：`existsByJobId` 命中后只跳过**入库**（toInsert），但 :288 `jobs.add(...)` 在 if-else 之外**无条件执行** → 投递循环照样点击这些老岗位。第二次运行 = 对历史岗位全量重复点击投递（风控风险 + 计数虚增）。
**修复**：把 `jobs.add` 收进"字段完整且不存在"的分支——将 :258-288 的采集块改为：
```java
try {
    String jid = jobId == null ? "" : jobId.trim();
    String jtitle = jobTitle == null ? "" : jobTitle.trim();
    if (jid.isEmpty() || jtitle.isEmpty()) {
        log.info("岗位缺少jobId或jobTitle，跳过：title={}，company={}", jtitle, companyName);
    } else {
        boolean exists = false;
        try { exists = zhilianService.existsByJobId(jid); } catch (Exception checkEx) {
            log.warn("查询jobId是否已存在失败: {}", checkEx.getMessage());
        }
        if (exists) {
            log.info("jobId已存在，跳过采集与投递：jobId={}，title={}", jid, jtitle);
        } else {
            ZhilianJobDataEntity entity = new ZhilianJobDataEntity();
            // ……原有 set 字段保持不变……
            toInsert.add(entity);
            jobs.add(new PageJob(i, jobId, jobTitle, companyName));
        }
    }
} catch (Exception ex) {
    log.warn("采集岗位数据失败: {}", ex.getMessage());
}
```
即：**删除原 :288 的无条件 `jobs.add(...)` 行**，只在 `!exists` 分支里 add。（`existsByJobId` 查询失败时按"不存在"处理——宁可重复采集，勿漏新岗。）

### P3 投递点完弹窗 200ms 被无条件强关，成功与否零校验

:316-335：点击"立即投递"会打开新窗口（投递结果页），closer 回调只做 `waitForLoadState() + sleep(200) + close()`——不看内容、不记结果；随后 :338-344 **无条件** `markDelivered`。三种失败形态全部被记成"已投递"：未登录跳转页、需要选简历/实名验证的交互窗、投递报错窗。
**修复（保守，不引入交互流程）**：在 closer 的 close 之前读取一次新窗口文本并打日志：
```java
if (newPage.opener() == page) {
    try { newPage.waitForLoadState(); } catch (Exception ignored) {}
    try {
        String body = newPage.locator("body").innerText();
        if (body != null) {
            if (body.contains("登录")) { log.warn("投递弹窗疑似登录引导，本岗位可能未投递成功: {}", pj.jobTitle); }
            else if (body.contains("申请成功") || body.contains("投递成功")) { log.info("投递弹窗确认成功: {}", pj.jobTitle); }
            else { log.info("投递弹窗内容未确认，原文摘要: {}", body.length() > 120 ? body.substring(0, 120) : body); }
        }
    } catch (Exception ignored) {}
    try { PlaywrightUtil.sleep(200); } catch (Exception ignored) {}
    try { newPage.close(); } catch (Exception ignored) {}
}
```
注意：`pj.jobTitle` 在 lambda 中不可直接引用非 effectively-final 变量，closer 是在循环内以 `pj` 为参数构建的——**把 closer 改为局部 final 变量捕获**（`final PageJob fpj = pj;`）或把文本日志改为不含岗位名。以编译通过为准，最小改动优先。

### P4 前端"开始投递"不先保存配置（Boss 同款陷阱）

`front/app/zhilian/page.tsx` :157-166 `handleStartDelivery` 直接 `POST /api/zhilian/start`——页面上改过的关键词/城市/薪资若不点"保存配置"，投递用旧库值。**这是用户"配置了但没生效"类问题的第一来源**（Boss 平台 2026-10-17 已实测确认同款缺陷）。
**修复**：`handleStartDelivery` 的 try 内第一行加 `await handleSave(true)`（该文件若无 handleSave 或签名不同，先 grep 确认再适配；保存失败应中止启动：`if (!(await handleSave(true))) return`——按现有 handleSave 返回值形态实现，若无返回值则保存后等待 300ms 即可）。

## 5. 证据依赖项（先跑一轮取证，拿到证据再修，禁止凭猜测改选择器）

智联网站 DOM 是本计划的**最大未知数**：本机 `zhilian_data` 0 行意味着从未成功，所有选择器（`div.joblist-box__item`、`a.jobinfo__name`、`button.collect-and-apply__btn`、`a.soupager__btn:has-text("下一页")`、搜索框 candidates、checkIsLimit 的 XPath `//div[@class='a-job-apply-workflow']`）**均未经本机验证是否仍与线上一致**。

### 取证步骤（按序执行，全程记录）

1. 启动服务，打开 http://localhost:6866/zhilian：
   - GET 状态：`curl http://localhost:9527/api/zhilian/login-status`——若未登录，点页面"登录"（POST /api/zhilian/login 会弹浏览器窗口），**请用户人工扫码**，再查 login-status 确认。
   - 确认 `zhilian_config` 三字段是用户想要的（关键词含"实习"、city_code='0'、salary='不限'——向用户确认 0=全国/不限 的语义是否符合预期；zhilian_option 字典仅 42 城，若用户要具体城市需核对 code 是否在其中）。
2. 先修 P1-P4（确定性项，无需运行时证据）→ `gradle compileJava` + `npm run build:prod` → 重启服务。
3. 跑**单关键词小样本**：把 keywords 临时只留 `"python后端开发实习生"`（PUT /api/zhilian/config，中文 body 用 `--data-binary @file`），POST /api/zhilian/start，观察：
   - 后端日志逐行判读：`未找到搜索输入框` / `等待岗位列表超时` / `检测到当前页岗位数量: N` / `已保存岗位数据` / `已标记投递`。
   - 任何 `投递当前页面失败` 会触发 :362 的 `saveCurrentPageHtml()` → **读取 `src/main/java/com/getjobs/worker/zhilian/page.html`**，对照代码选择器逐一核对类名/结构是否漂移。若未触发失败但日志显示"等待岗位列表超时"，则手动在弹出的 Chrome 里打开搜索页另存 HTML 到同一路径再分析。
   - 观察智联是否弹**验证码/滑块**（反爬信号）：若出现，记录触发点（第几次导航/点击），处理方案为增加 `PlaywrightUtil.sleep` 随机抖动 + 降低页数上限（:167 的 50 页 → 建议首轮降到 5 页），不要尝试对抗验证码。
4. 根据证据修复失效选择器（只改证据指出的项），重跑第 3 步直至 `zhilian_data` 有真实入库且标记投递至少 1 条。
5. 让用户核对智联 App/网页端"投递记录"：确认 `delivery_status='已投递'` 的岗位在智联侧真实存在投递记录（P3 校验日志 + 用户肉眼双重确认）。

## 6. 收尾验证清单

- [ ] `gradle compileJava`、`npm run build:prod` 通过
- [ ] 单关键词首轮：日志出现 `检测到当前页岗位数量: N`（N>0）、`已保存岗位数据`、`已标记投递`，`zhilian_data` 行数 >0
- [ ] 完成消息投递数 ≠ 0 且与 `SELECT COUNT(*) FROM zhilian_data WHERE delivery_status='已投递'` 增量一致（P1）
- [ ] **第二轮重跑同一关键词**：日志应出现 `jobId已存在，跳过采集与投递`，投递增量≈0（P2 回归）
- [ ] 页面改配置后直接点"开始投递"，库中配置随之更新（P4：查 `zhilian_config.updated_at`）
- [ ] 投递弹窗校验日志正常输出（P3：`投递弹窗确认成功` 或明确的未确认告警，不再零日志）
- [ ] 恢复用户完整关键词列表（PUT config 写回 `["实习","python","后端","fastapi","MySQL"]`）

## 7. 禁止事项

- 不重构任务框架（ZhilianJobService/ZhilianController/SSE 链路已被 Boss/猎聘等共用同款模式）。
- 不动 Boss/猎聘/51job 的文件（除 P4 若用户同意可将同款"start 前保存"也修到 Boss page.tsx——需单独获批）。
- 不删除 `handleDeliveryDialog`/`deliverSimilarJobs` 死代码（保留现状，注释说明即可）。
- 不改 `zhilian_option` 字典数据、不改 DB schema。
- 不写任何绕过智联验证码/风控的逻辑；遇到验证码停下向用户报告。
- 未取证前不得凭猜测批量替换选择器。

## 8. 已知限制（写入代码注释或 README 备注，不修）

- 智联流程无招呼语/AI 筛选/黑名单/HR 活跃过滤（列表卡片未采集 HR 活跃字段）——与 Boss 能力不对等是平台特性（智联一键投不聊天），如用户后续要求对齐，另立计划。
- `maxPage=500` 与 :167 的 50 页上限并存，以后者为准（死常量，不碍事）。
- cookie 表 zhilian 条目是登录态快照，有效期短；每次投递前 login-status 必须为已登录。
