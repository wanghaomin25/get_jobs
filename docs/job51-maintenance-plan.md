# 实施计划：维护 51job（前程无忧）自动投递流程

> 面向执行 agent。代码位置 2026-10-17 实测核对。
> **铁律：先取证、后改选择器。** 本流程从未跑通过（见 §3 证据），代码里的所有站点选择器都是"纸面待验证"状态——凡 §5 清单内的选择器/接口/文案，未经真机日志或 HTML 证据证实前，禁止"猜一个改上去"。确定性缺陷（§4）可以先修，与取证不冲突。

## 1. 目标

把 51job 自动投递从"从未跑通"维护到"真实可用"：登录 → 保存配置 → 小样本投递 → `job51_data` 有真实数据且 `delivered` 标记准确。顺带修复 §4 列出的确定性缺陷。

## 2. 环境与惯例（与智联计划相同，已验证有效）

- 项目根 `C:\Users\wanghaomin\Desktop\jianli\get_jobs`，**非 git**：动手前先备份
  ```bash
  cp db/getjobs.db db/getjobs.db.bak
  cp src/main/java/com/getjobs/worker/job51/Job51.java src/main/java/com/getjobs/worker/job51/Job51.java.bak
  cp front/app/51job/page.tsx front/app/51job/page.tsx.bak
  ```
- 服务：项目根 `start.bat`（双击或命令行均可）；API 端口 9527。**当前服务未运行**（实测 curl 9527 失败）。
- 改 Java → `compileJava` 通过后**重启服务**；改前端 → `cd front && npm run build:prod` 后重启（构建链 pnpm/node_modules 已配好，勿再动 pnpm-workspace.yaml）。
- 编译命令（已验证可用）：
  ```bash
  JAVA_HOME=~/jdk-21 ~/gradle-9.7.1/bin/gradle.bat compileJava -q
  ```
- Windows GBK 坑：curl 传含中文 JSON 必须 `--data-binary @utf8文件`（直接 `-d` 会 400，实测踩过）；查中文数据用 `python -I` 落 UTF-8 文件再读。
- `db/getjobs.db` 生产库：只允许 INSERT/UPDATE 和 `ALTER TABLE ADD COLUMN`，**禁止重建表/模板覆盖**。
- 遇到验证码/滑块/WAF：只记录证据并报告，**不做任何对抗**。

## 3. 现状证据（2026-10-17 实测，非猜测）

| 事实 | 证据 |
|---|---|
| `job51_config` **0 行**（用户从未成功保存过配置） | sqlite 直查 |
| `job51_data` **0 行**（投递链路从未产生数据） | sqlite 直查 |
| `job51_option` 字典健康：jobArea 26 条（含"不限"code=0）、salary 13 条（含"不限"code=0） | sqlite 直查 |
| `cookie` 表 51job 行 **cookie_value 长度为 0**（从未登录成功过；boss/zhilian/liepin 均非空） | sqlite 直查 |
| 服务当前未运行 | `curl http://localhost:9527/api/51job/login-status` 连接失败 |
| 后端端点齐备（配置/登录/Cookie/启停/状态/统计/列表） | `JobController.java`，路由 `/api/51job/...` |
| 调度链齐备且与智联同构（专用 Playwright 线程、监控暂停/恢复、SSE） | `Job51JobService.java`、`PlaywrightManager.java:835-1198` |
| 前端页完整（关键词/城市/薪资多选/统计页签），但**投递前不自动保存配置** | `front/app/51job/page.tsx:275-288` |

**"从未跑通"的直接原因链**：从未登录（cookie 空）→ 前端"开始投递"按钮在未登录时禁用；即使登录，`job51_config` 空 + 保存入口从未成功使用 → 后端加载空关键词 → `execute()` 直接返回"关键词列表为空"。所以本计划的第一步不是改代码，是 §5 真机取证。

## 4. 确定性缺陷（无需真机即可修，先做）

### D1 前端开始投递前不保存配置（智联 P4 同款，必修）

`front/app/51job/page.tsx:275-288` `handleStartDelivery` 直接 POST /start。用户在页面改了关键词但没点"保存配置"，投递用的还是库里的旧值/空值（本次事故根因之一）。
**修法**：完全照抄智联已修好的模式——参考 `front/app/zhilian/page.tsx` 中 `handleSaveConfig` 的 `silent` 参数与开始投递处的 `const saved = await handleSaveConfig(true); if (!saved) return;`。
本文件当前 `handleSaveConfig`（:344-397）返回 `void` 且总是弹"保存成功/失败"对话框。改为：
1. 签名改 `handleSaveConfig = async (silent?: boolean)`，成功返回 `true`、失败返回 `false`；`silent` 为真时不弹结果对话框（setShowSaveDialog 分支包进 `if (!silent)`）。
2. `handleStartDelivery` 开头插入：
   ```tsx
   const saved = await handleSaveConfig(true)
   if (!saved) return
   ```
   （保持其后原有逻辑不变；失败时不进入投递，并保留对话框提示原因。）

### D2 手动输入城市提示是智联编码体系（会误导出错数据）

`page.tsx:497` placeholder `请输入城市码，例如：410`、`:514` 提示 `410代表北京`——**410 是智联城市码**。51job 的 jobArea 码是 6 位（`010000`=北京、`080200`=杭州，见 `job51_option`）。用户照提示填"410"，`normalizeOptionCode` 未命中会原值透传进 URL，导致搜索区域错误。
**修法**：两处文案改为 51job 体系，例如 placeholder `请输入51job城市码，例如：010000`、提示 `6位城市码（如 010000=北京）`。不改交互逻辑。

### D3 keyword 未做 URL 编码

`Job51.java:704`：`url.append("&keyword=").append(keyword)`——中文关键词原样拼进 URL。
**修法**（零风险防御，无论站点是否宽容）：
```java
url.append("&keyword=").append(java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8));
```
（`appendListParam` 拼的是数字码，无需动。）

### D4 死代码：collectJobIdsOnPage 及其辅助方法

`Job51.java:711-793` `collectJobIdsOnPage()` 全项目无调用（grep 证实只有定义处）；`parseJobIdFromHref` 仅被它调用。其内部"采集为空视为达上限停止"的保护逻辑**从未生效**。
**修法**：整段删除这两个方法（:711-793）。若 §5 取证后 D6 需要行级 jobId 映射，届时按真机 DOM 重写，不复用这份未验证代码。

### D5 首页列表未等待就绪即采集

`deliverCurrentPage` 在 `checkboxes.count() == 0` 时直接 return——网络慢导致首屏未渲染时，第 1 页整页漏投且无任何告警，随后 `jumpToPage(2)` 失败才 break。
**修法**：在翻页循环内 `PlaywrightUtil.sleep(2)`（:223）之后加一次显式等待：
```java
try { page.waitForSelector("div.ick", new Page.WaitForSelectorOptions().setTimeout(15_000)); } catch (Exception ignored) {}
```
（选择器 `div.ick` 若 §5 取证证实已改版，以取证结果为准同步替换。）

### D6（低优先级，可选）完成消息统计口径

`execute()` 结束消息用 `resultList.size()`（勾选数），非弹窗确认的真实投递成功数。部分拒绝场景（"已投递过/屏蔽公司"）会虚报。
**修法**：仅当 §5 取证证实投递结果弹窗确实解析出 `successNum` 时，在类字段累加 `totalConfirmedSuccess`，完成消息改为 `共勾选%d个、弹窗确认成功%d个`。弹窗解析不到的场景保持现口径，不要输出"0 个成功"这种误导文案。

## 5. 真机取证（修完 §4 后做；决定后续是否还有改动）

**步骤 0：服务与登录**
1. `start.bat` 启动服务。启动时 `setup51jobPlatform()`（`PlaywrightManager.java:835`）会打开 51job 首页、自动点击登录入口并**异步等待扫码约 5 分钟**——请用户此时用手机 51job App/微信扫码登录。错过窗口则 `curl -X POST http://localhost:9527/api/51job/login` 重新触发。
2. 登录成功后 `on51jobLoginSuccess()` 自动把 Cookie 写入 `cookie` 表；验证：`curl http://localhost:9527/api/51job/login-status` 应返回 `isLoggedIn: true`，且 cookie 表 51job 行长度 > 0。

**步骤 1：小样本跑通并留证据**
用 curl（`--data-binary @utf8文件`）把配置设为单关键词小样本，例如 `keywords=["python后端"]`、城市=杭州（jobArea 名"杭州"）、salary=`["不限"]`，PUT `/api/51job/config`；POST `/api/51job/start`；观察日志与 SSE，直至第 1 页投递完成即 POST `/api/51job/stop`。

**逐项验证表（每项记录"实际观察到的"证据：日志行/DOM 片段/JSON 字段）**

| # | 纸面假设（代码出处） | 需证实/证伪 |
|---|---|---|
| E1 | 搜索接口 `/api/job/search-pc`，GET，JSON 含 `requestId` 去重键（`Job51.java:130-151`） | 监听是否命中？`parseAndPersistJob51SearchJson` 的列表路径（`data.items` 等 7 种兜底，`Job51Service.java:368-374`）实际是哪个？`jobId/jobName/provideSalaryString/...` 字段名是否存在？ |
| E2 | 勾选框 `div.ick`、职位名 `[class*='jname text-cut']`、公司 `[class*='cname text-cut']`（:258-263） | 是否匹配当前页面 DOM？数量是否一致（titles 与 checkboxes 一一对应吗，D10 错位风险）？ |
| E3 | 批量投递按钮 `div.tabs_in` 内 `button.p_but` 第 2 个（:322-327） | nth(1) 是不是"批量投递/一键投递"？ |
| E4 | 投递结果弹窗 `.el-dialog__body` 含"投递成功N个，未投递M个"（:373-386）；下载 App 弹窗 `successContent`/"快来扫码下载"（:358-369）；单独申请弹窗文案（:476） | 弹窗是否出现、文案是否仍是这套？解析出的 N/M 与页面勾选数的关系？ |
| E5 | 跳页：`#jump_page` 输入 + 长 CSS 链按钮（:504-516） | 元素是否存在？跳到第 2 页是否成功触发新一次 search-pc 响应（currentPageJobIds 更新）？ |
| E6 | 登录检测 `//a[contains(@class,'uname')]`（:599）与首页检测链（`PlaywrightManager.checkIf51jobLoggedIn` :944-967） | 已登录/未登录两态下判定是否都正确？ |
| E7 | 访问验证 WAF（:651-662）与日限额文案（:616 五组关键词） | 真机是否遇到？文案是否命中？ |
| E8 | 空态文案 `detectNoJobs51job`（:670） | 用生僻关键词触发一次，确认提前结束生效 |
| E9 | 排序点击 `div.ss`（:201） | 点击是否有效/是否会打乱列表触发重复 search 响应？无把握可整段删除（它只是选默认排序） |

**取证后决策**：
- E1-E5 任一失配 → 按智联 2026-10 的教训选择器已改版，依据证据更新对应选择器/解析路径，改完重跑小样本。**每处改动都要在交付说明里附上证据。**
- **D6/标记准确性专项**：跑完后查 `job51_data`：`delivered=1` 的行是否确实是本轮投递的岗位（抽查 `job_title` 与浏览器"我投递的职位"页对得上）。若前缀标记法（`handleDeliverySuccessDialog` :389-403，取 `currentPageJobIds` 前 successNum 个）出现错位：
  - 首选：按真机行结构给每行提取行内 jobId（`a[href*='jobId=']` 或 data 属性），与 JSON 顺序取交集后精确标记；
  - 兜底（实现不起时）：改为仅当 `successNum == 本页勾选数` 时整页标记，否则只告警不标记（宁可漏标，不可错标）。

## 6. 构建与重启

```bash
# Java
JAVA_HOME=~/jdk-21 ~/gradle-9.7.1/bin/gradle.bat compileJava -q
# 前端（D1/D2 涉及）
cd front && npm run build:prod   # 产物自动复制到 src/main/resources/dist
# 然后关闭服务窗口，重新 start.bat
```

## 7. 验证清单（全部要留证据）

1. `GET /api/51job/config`：options 含 jobArea 26 条、salary 13 条；
2. `PUT /api/51job/config` 往返：中文 body 用 `--data-binary @utf8文件`，回读库中 `keywords/job_area/salary` 为中文名括号列表；
3. 前端页（Ctrl+F5）：填关键词→直接点"开始投递"→**无需先点保存**，任务按页面配置启动（D1 生效）；手动输入城市提示为 51job 六位码（D2）；
4. 小样本投递：日志出现搜索响应命中、`job51_data` 新增 >0 行、字段完整（title/comp_name/薪资/地区非空）；`div.ick` 等待逻辑不误伤正常页（D5）；
5. `delivered` 标记抽查无错位（§5 专项）；
6. 点"停止投递"能在一页边界内停止，`isRunning` 复位；
7. **收尾**：把 `job51_config` 恢复成用户真实求职配置。若执行期间用户未给过，就填与智联同口径的一套（关键词 `["实习","python","后端","fastapi","MySQL"]`、城市按智联 530 对应城市名映射到 jobArea 字典、薪资"不限"），并在交付说明里明确标注"此套为默认值，请用户在页面确认后再投递"。

## 8. 禁止事项

- 不动 Boss/智联/猎聘任何文件；不动 `PlaywrightManager` 其他平台分支。
- 不重建 `job51_*` 表；不动字典既有行（如需补城市行，只 INSERT）。
- 未经 §5 证据不写新选择器；证据失配时禁止"猜一个相近的 class"。
- 不放宽 `DEFAULT_MAX_PAGE=50`、不加"智能跳过已投递"这类新功能——本计划只做维护修复，扩功能由用户另行决策。
- 遇验证码/滑块：停止、报告、截图存 `docs/evidence/`，不做对抗。
- 测试产生的 `job51_data` 行**保留**（delivered 标记反映真机结果），不要清空生产表。

## 9. 交付物与验收

- [ ] §4 D1-D5 全部落地（D6 视 E4 证据），`compileJava` exit 0、`build:prod` 成功
- [ ] `docs/evidence/job51-run.md`：E1-E9 逐项"结论+证据"（日志行/DOM 片段/JSON 字段路径），以及取证后的选择器修正清单
- [ ] 验证清单 §7 七项逐条通过（附证据），`cookie` 表 51job 行非空
- [ ] `job51_data` 有真实行且 `delivered` 抽查无错位
- [ ] 用户配置按 §7.7 收尾，交付说明写明最终 keywords/城市/薪资取值
