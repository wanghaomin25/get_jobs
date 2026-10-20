# 实施计划：51job 翻页修复 + 投递结果弹窗解析 + 新增「职位类型」筛选（全部/全职/兼职/实习）

> 面向执行 agent。前置：`docs/job51-maintenance-plan.md` 已完成——D1-D6 落地，主链路真机跑通（19:37 轮实测：搜索 JSON 入库 20 条、`div.ick`=20 全勾选、一键投递已点击、站点端用户确认投递成功）。
> 本计划三个任务：**T1 翻页修复**（现在只能投第 1 页）、**T2 弹窗结果解析修复**（delivered 标记恒为 0 的根因）、**T3 职位类型筛选**（新功能，架构完全复刻智联 jobNature）。
> **铁律不变：先取证、后改代码。** 取证过程严禁点击"一键投递"（会真实投递、消耗日限额）。

## 1. 环境与惯例

- 项目根 `C:\Users\wanghaomin\Desktop\jianli\get_jobs`。**现在 `.git` 可用**（含上游历史，`git status` 能看到本地修改）：动手前 `git add -A && git commit -m "checkpoint before job51 T1-T3"`（message 用英文）。这比 .bak 文件更可靠。
- 服务：项目根 `start.bat`（用户双击启动）；API 9527。**执行 agent 若从自己的工作 shell 起 `gradle bootRun`，必须先 `unset SERVER__PORT`**（WorkBuddy 会话环境变量会经 Spring 宽松绑定把端口改成 61517，19:30 实测踩过）。
- 编译：`JAVA_HOME=~/jdk-21 ~/gradle-9.7.1/bin/gradle.bat compileJava -q`；改前端再 `cd front && npm run build:prod`。改 Java 或前端后都要重启服务才生效。
- GBK 坑照旧：curl 含中文 JSON 用 `--data-binary @utf8文件`；查中文数据 `python -I` 落 UTF-8 文件。
- `db/getjobs.db` 生产库：只允许 INSERT/UPDATE 和 `ALTER TABLE ADD COLUMN`。
- `job51_data` 现有 58 行（`delivered` 全 0）保留不动，作为 T2 修复后的对照。
- 遇 WAF/验证码：记录证据并报告，不对抗（§8 教训：离线 curl 无 cookie 会被阿里云 WAF 拦截，见 `docs/evidence/job51-run.md` §2.2）。

## 2. 现状证据（2026-10-17 19:40 复核，日志+源码+sqlite 实证）

| 事实 | 证据 |
|---|---|
| 翻页失败：`#jump_page` 在新版页面不存在 | 18:58 轮 `正在投递第2页` 后立即 `投递完成`（jumpToPage 找不到输入框返回 false → break）；18:22 dump `#jump_page=0` |
| 第 1 页链路完好 | 19:37 轮：`第1页 DOM 就绪：ick=20，jname=20，cname=20`；search-pc 命中路径 `resultbody.job.items`，20 条入库 |
| 弹窗出现但数字解析失败 | 19:37 轮 `投递结果：成功 null 个，未投递 null 个（本页勾选 20 个）` → 弹窗文本**含"投递成功"**（否则进不了解析分支），但正则 `投递成功\D*(\d+)` 匹不到数字 → 新版文案格式未知 |
| 保守标记兜底按设计工作 | 同轮 `投递成功数量为0或未解析到，不标记投递状态`；库内 `sum(delivered)=0` |
| 排序入口失配 | 每轮 `排序入口 div.ss 命中 0 个`（影响小，本计划顺手处理，见 T1.3） |
| 配置仍是测试值 | `job51_config`：`keywords=["python后端"]、job_area=["杭州"]、salary=["不限"]` |
| 登录/Cookie 正常 | cookie 表 51job 行 4840 字符；重启后 `已注入 16 条 → 51job已登录` |

## 3. 取证任务 T0（先做，产出 `docs/evidence/job51-pagination-nature.md`）

### T0.1 翻页控件结构
给 `Job51.jumpToPage()` 的"未找到页码输入框"分支加一行诊断（`dumpPageStructure("跳页控件缺失")`，复用现成方法），并新增一次**只读 DOM dump**：
```java
for (String cand : new String[]{".el-pagination", "[class*='page']", "[class*='pag']"}) {
    Locator l = page.locator(cand);
    if (l.count() > 0) log.info("[51job] 分页候选 {} 命中 {} 个，首个HTML: {}", cand, l.count(),
        l.first().evaluate("e => e.outerHTML.substring(0, 1500)"));
}
```
然后跑一轮**小样本**（单关键词 `["python后端"]`、杭州）让它自然走到第 2 页失败处，日志即证据。判定：
- 存在跳页输入框（新 id/class）→ 改 `jumpToPage` 的选择器，保持"跳到绝对页"语义；
- 只有"上一页/下一页/页码"按钮 → `jumpToPage` 改为**点下一页**（绝对页号仅作计数），并检测禁用态（如 `button.btn-next[disabled]` / aria-disabled）→ 返回 false 结束该关键词。

### T0.2 投递结果弹窗真实文案
在 `handleDeliverySuccessDialog` 解析分支加一行（**只加日志，不改逻辑**）：
```java
log.info("[51job] 结果弹窗原文(截前300字): {}", dialogText.length() > 300 ? dialogText.substring(0, 300) : dialogText);
```
跑 T0.1 同一轮即可顺带取证。据真实文案修正数字正则（例如新版若是"投递成功！本次成功 15 个"之类，正则相应改；**以证据为准，禁止猜**）。若弹窗只在部分轮次出现，多跑一轮观察。

### T0.3 「职位类型/工作性质」过滤机制（决定 T3 路线）
候选参数与字段线索（来自 19:37 日志的首条字段表）：items 里有 `jobType`、`hireType`、`property`、`isIntern`、`isFromXyx`，先要弄清"性质"是哪个字段、取值集合是什么；搜索参数名待证。两条取证通道按顺序尝试：
1. **curl + 登录 Cookie**（成本低）：从 `cookie` 表取 51job 行，拼 `Cookie:` 头 + 真实 Chrome UA，GET `https://we.51job.com/api/job/search-pc?...keyword=python&jobArea=080200...`（基线，无性质参数）。若返回真 JSON（WAF 只拦无 cookie 请求，见 job51-run.md §2.2 的前提是"cookie-less"）：抽 20 条的 `jobType/hireType/property/isIntern/jobName` 对照表 → 找出性质字段与编码；再逐个试候选参数（`workType/jobType/property/hireType/...`=疑似实习编码），**比对返回的岗位标题分布**（出现整页"…实习生"=该参数生效，即路线 A）。全程只读 API，不碰投递。若带 cookie 仍被 WAF 拦截 → 放弃此通道，转 2。
2. **浏览器交互取证**：服务在跑、浏览器可见且已登录。用 `/api/playwright/diagnose` 不行（probe JS 写死 Boss 选择器）；改法：临时给 PlaywrightController 加一个只读探针端点 `GET /api/playwright/probe-51job?url=<编码后的搜索页>`——新开标签页（`context.newPage()`，仿 diagnose 写法、走 `callOnPlaywright`、用完 close）、导航、`onResponse` 里抓 `/api/job/search-pc` 的**请求完整 URL** 并记录，再 `page.evaluate` dump 筛选区 DOM（class/文本/选项，含每个筛选项点击前后的请求 URL diff：可用 `page.getByText('实习')` 这类文本定位点击）。点击筛选属只读操作（只刷新列表，不投递）。**该探针端点取证后删除，不进最终代码**。
   注意：操作的是独立新标签页，**不要占用常驻 job51Page**（避免与投递任务并发）。

**路线判定**（与智联 §4.3 相同语义）：
- 参数有效（列表分布随参数变化）→ **路线 A**：搜索 URL 直拼参数，改动最小。
- 参数无效、筛选栏存在"工作性质"类控件 → **路线 B**：DOM 点击（每关键词导航后应用 + 幂等守卫 + **翻页后重申**——智联 2026-10-17 实测翻页会重置筛选，51job 若同为 SPA 点页码可能保留，须用 T0.3 的 URL/请求 diff 证据判定，判不了就在翻页后重申一次，函数幂等无害）。
- 两者皆无、但性质字段（如 `isIntern`）在 JSON 里可靠 → **路线 C（最后手段，先报告征得用户同意才做）**：投递勾选前按行过滤。行↔jobId 映射用行容器内 `a[href*='jobId']` 取证证明可行才实施；不可行就如实报告"51job 不支持该维度原生过滤"，不许做采集后伪过滤。

## 4. T1 翻页修复（按 T0.1 证据实施）

- `jumpToPage(int)`：按证据改选择器/改语义（下一页点击制）。保留：跳页前 `closeAnyModalOverlays()`、重试 3 次、访问验证检查。失败返回 false 时**必须输出原因日志**（当前 `未找到页码输入框` 太粗）。
- `deliverByKeyword` 翻页循环：若 T0.3 走路线 B，在 `jumpToPage` 成功后重申 `applyJobNatureFilter()`（T3 新增，幂等）。
- 结束条件：下一页禁用/无新数据（search-pc 该页 `resultbody.job.items` 为空）→ break，不再盲跑满 50 页。
- T1.3：删除 `div.ss` 排序点击整段（连续多轮命中 0，属死逻辑），保留其注释说明与删除依据（job51-run.md §6.3）。

## 5. T2 弹窗解析与标记修复（按 T0.2 证据实施）

- 按真实文案改正则，解析出成功数/未投递数；`totalConfirmedSuccess` 累加与完成消息自动恢复准确。
- **保留现有一致性校验兜底**（成功数==勾选数==缓存 jobId 数才标记）不动。
- 部分成功（有被拒）场景的精确标记 = 需要"行级 jobId ↔ 勾选行"映射，属新复杂度：**本计划不做**，维持"部分成功只告警不标记"。验收时确认：整页全成功的一轮，`delivered=1` 行数 == 该页勾选数。

## 6. T3 职位类型筛选（复刻智联 jobNature 全链，9 项）

UI 文案用用户词「职位类型」；代码/库命名与智联概念对齐，用 `jobNature`/`job_nature`（51job 响应 JSON 里已有自己的 `jobType` 字段，避免撞名混淆）。选项集：全部/全职/兼职/实习——**字典 name 以 T0.3 证实的站点实际文案为准**（如站点写"兼职/临时"就跟随站点），站点若无某档，如实砍掉并报告，不硬造。

| # | 层 | 改动 |
|---|---|---|
| 1 | DB 列 | 幂等 `ALTER TABLE job51_config ADD COLUMN job_nature TEXT`——放在 `Job51Service.ensureJob51OptionTableAndData()`（@PostConstruct）里，仿智联启动补列手法；**禁止**重建表 |
| 2 | 实体 | `Job51ConfigEntity` 加 `/** 职位类型（站点过滤值；null/空/全部=不过滤） */ private String jobNature;` |
| 3 | 保存白名单 | `Job51Service.saveOrUpdateFirstSelective` **插入、更新两分支都登记** `jobNature`（漏一处=静默丢字段，智联已踩过） |
| 4 | 加载 | `loadJob51Config`：trim 后 `null/空/"全部"` → `""`（贯穿全链路"空=不过滤"约定） |
| 5 | worker 配置类 | `Job51Config` 加 `private String jobNature;` |
| 6 | 字典 | `job51_option` 插 type=`jobNature` 行（含"全部" code=''，其余 code=路线 A 的编码 / 路线 B 与 name 同）：在 @PostConstruct 里 `insertIfNotExists` 幂等种入 + 直接 INSERT 现有库。下拉数据驱动，不许前端硬编码 |
| 7 | API | `JobController.getAllJob51Config` 的 options 加 `options.put("jobNature", buildOptionsFromDb("jobNature"))` |
| 8 | 前端 | `front/app/51job/page.tsx`：接口 `Job51Config`/`Job51Options` 各加 `jobNature` 字段与初始值 `''`/`[]`；城市下拉之后加单选 Select（完全复制现有城市下拉写法，`options.jobNature.map` 出 option）；`handleSaveConfig` 的 payload 已 `{...config}` 自动携带，无需其他改动；`fetchAllData` 回显时按 name/code 双匹配映射（同智联） |
| 9 | worker 实现 | 路线 A：`buildSearchUrl` 末尾 `if (jn != null && !jn.isEmpty()) url.append("&").append(JobUtils.appendParam(<取证参数名>, jn));`（注意 URLEncoder 与 `&` 衔接，keyword 已在末尾，顺序无碍）。路线 B：新增 `applyJobNatureFilter()`，代码骨架与智联同款（触发展开→幂等守卫 `--selected`/选中态检测→点选项→等首卡标题变化信号 8s 超时只告警；失败降级"不过滤继续"，严禁抛异常中断关键词）；调用点：每关键词搜索页就绪后（D5 的 waitForSelector 之后）、翻页循环前 + 翻页后重申 |

## 7. 构建与重启

```bash
JAVA_HOME=~/jdk-21 ~/gradle-9.7.1/bin/gradle.bat compileJava -q
cd front && npm run build:prod    # 若 WorkBuddy 守卫再拦，沿用 mv 旧产物旁移的既有做法
# 重启：用户 start.bat；agent 自己 bootRun 记得 unset SERVER__PORT
```

## 8. 验证清单（全部留证据到取证文档）

1. `GET /api/51job/config`：options 含 `jobNature` 数组（≥4 项）；
2. PUT 往返（`--data-binary @utf8文件`）：`job_nature` 落库、回读一致；**改职位类型不点保存、直接点开始投递**也能生效（D1 自动保存链路覆盖新字段）；
3. 路线 B 时日志出现 `已应用职位类型筛选: 实习`（或站点实际文案）；
4. **翻页**：单关键词小样本，日志出现第 2、3 页投递记录，每页 `ick=20`（或站点实际页容量），且末页正常 break（不盲跑 50 页）；
5. **弹窗**：日志出现真实文案 dump 后修复的正则解析成功：`投递结果：成功 N 个…` N 非 null；整页全成功的一轮 `sum(delivered)` 相应增加，抽查标记行标题与实际投递页一致（智联教训：翻页不重置/不错标）；
6. **职位类型**：选"实习"跑小样本，新入库 `job_title` 抽查 ≥5 条全部实习向、零违反；
7. **回归**：`job_nature=''`（全部）时 URL 与行为与 T3 前一致；
8. **收尾**：`job51_config` 恢复用户真实求职配置（用户未指定则暂填与智联同套：`["实习","python","后端","fastapi","MySQL"]`、杭州、不限、职位类型=实习），交付说明写明最终取值并提示用户在页面确认；删除 T0.3 的临时探针端点与 `Job51.java.bak` 之外的临时文件；`git commit` 收尾（英文 message）。

## 9. 禁止事项

- 不动 Boss/智联/猎聘文件；不动 `PlaywrightManager` 其他平台分支。
- 取证与新功能验证全程**不得把投递范围扩大到测试关键词之外**；小样本关键词固定用 `python后端` 单轮，验证完恢复。
- 未经取证不写选择器/参数编码；T0.3 三路皆无证据支持时如实报告，停手等用户决策。
- 不重建 `job51_*` 表、不清 `job51_data`（58 行历史是 T2 对照基准）。
- 弹窗若解析出"未投递"含**日限额**语义（复用现成 `detectDailyLimitToast51job` 词表判断），按既有逻辑停止，不重试轰炸。
- 遇验证码/滑块：停止、报告、证据存 `docs/evidence/`。

## 10. 验收

- [ ] T0 取证文档齐（翻页控件 HTML、弹窗真实文案、性质字段/参数判定表 + 路线结论）
- [ ] T1 翻页：至少稳定投到第 2-3 页，末页正常收敛
- [ ] T2 弹窗正则解析出数字，整页成功轮 delivered 标记准确
- [ ] T3 九项全落（重点 §3 保存白名单双分支），所选路线实现，编译/构建/重启通过
- [ ] §8 验证 1-7 逐条有证据；用户配置收尾恢复
