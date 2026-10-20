# 取证记录：智联「工作性质」筛选（2026-10-17）

> 对应计划 `docs/zhilian-job-nature-plan.md` §4。所有结论均为本机实测，非猜测。

## 1. 计划前提的两处修正

| 计划原文 | 实测结论 |
|---|---|
| 搜索 URL 为 `https://www.zhaopin.com/sou/jl{城市码}/p{页}?sl={薪资}` | **已失效**。`/sou/...` 会 302 到 `/jobs/?jl={城市码}`，路径里的 `p{页}` 被丢弃；真实搜索页是 `https://www.zhaopin.com/jobs/?jl=530&pageMode=search&kw={关键词}` |
| `fe-api.zhaopin.com/c/i/sou` 有 `employmentType` 参数 | 页面中 `employmentType` 出现 0 次；实际字段名是 **`workType` / `workTypeCode`** |

## 2. 站点字段与编码（实证）

页面把每个岗位的数据以 JSON 内嵌，字段对：

| `workType`（显示文本） | `workTypeCode` |
|---|---|
| 全职 | `2` |
| 兼职/临时 | `1` |
| 实习 | `4` |
| 校园 | 本次采样（41 条岗位）**未出现** |

采样命令等价于：GET `/jobs/?jl=530&pageMode=search&kw=python后端`（带登录态 cookie），正则统计 `"workType":"…"` 与 `"workTypeCode":"…"` 的成对分布。

## 3. 路线 A（URL 参数）——实测无效，已排除

给同一个搜索 URL 追加 6 种候选参数，比对返回岗位的 `workType` 分布（基线 41 条：全职 34 / 兼职·临时 4 / 实习 3）：

| 追加参数 | 岗位数 | workType 分布 |
|---|---|---|
| （基线，无参数） | 41 | 全职34 / 兼职·临时4 / 实习3 |
| `workType=4` | 42 | 同基线（仅在 JSON 里多出一个键值，非过滤） |
| `workTypeCode=4` | 41 | 同基线 |
| `S_SOU_WORK_TYPE=4` | 41 | 同基线 |
| `wt=4` | 41 | 同基线 |
| `jobNature=4` | 41 | 同基线 |

**结论：URL 查询参数不触发过滤，路线 A 排除，改走路线 B（DOM 点击）。**

## 4. 页面内搜索条件（佐证）

页面内嵌 `search_condition`（无筛选时的形态）：

```json
{"S_SOU_FULL_INDEX":"python后端","S_SOU_WORK_CITY":"530","order":0,
 "pageSize":20,"pageIndex":1,"sortType":"DEFAULT", ...}
```

键名规律为 `S_SOU_*`，但当前不含任何工作性质维度——与「URL 参数无效」一致，说明过滤由前端交互驱动。

## 5. 筛选栏结构（SSR）

筛选栏存在「工作性质」一项，形如：

```html
<div class="filter-select-box">
  <div class="filter-select-box__trigger">
    <span class="filter-select-box__label">工作性质</span>
    <span class="filter-select-box__arrow"></span>
  </div>
  <div class="filter-select-box__panel"></div>   <!-- SSR 为空，选项点击后由前端渲染 -->
</div>
```

同级的其他筛选项：`职位类别`、`公司行业`、`经验`、`公司性质`、`融资阶段`、`公司人数`、`工作性质`；另有「清空筛选」。

**因 `filter-select-box__panel` 在 SSR 中为空，选项文本、按钮选择器、选中态 class 需在浏览器实际点击后 dump（见第 6 节）。**

## 6. 面板真实 DOM（点击后，2026-10-17 16:31 实测）

在搜索页（`kw=python后端`、`jl=530`）点击「工作性质」触发条后，面板内元素数 12，实际 HTML：

```html
<div class="filter-select-box__panel" >
  <ul class="filter-select-box__column">
    <li class="filter-select-box__item filter-select-box__item--selected">
      <span title="不限" class="filter-select-box__item-text">不限</span>
      <i class="filter-select-box__tick"></i>
    </li>
    <li class="filter-select-box__item"><span title="全职" class="filter-select-box__item-text">全职</span></li>
    <li class="filter-select-box__item"><span title="兼职/临时" class="filter-select-box__item-text">兼职/临时</span></li>
    <li class="filter-select-box__item"><span title="实习" class="filter-select-box__item-text">实习</span></li>
    <li class="filter-select-box__item"><span title="校园" class="filter-select-box__item-text">校园</span></li>
  </ul>
</div>
```

同时打印出筛选栏全部 11 个盒子：`北京 / 地铁 / 薪资 / 学历 / 经验 / 公司性质 / 融资阶段 / 公司人数 / 工作性质 / 职位类别 / 公司行业`。

要点：

- **选项集恰好是计划列出的 5 项：不限 / 全职 / 兼职·临时 / 实习 / 校园**——「校园」**存在**，计划 §4.4 的降级分支不需要启用。
- 点击前后 **URL 完全不变**，确认是纯前端过滤 → **路线 B**。
- 选项元素：`li.filter-select-box__item`，文本在 `span.filter-select-box__item-text[title=…]`；选中态为 `filter-select-box__item--selected`（初始在「不限」上）。
- 面板在 SSR 里为空，必须先点 `div.filter-select-box__trigger` 展开，选项才渲染。
- 定位「工作性质」这个盒子：`div.filter-select-box:has-text('工作性质')`（11 个盒子中唯一）。

## 7. 路线判定

- 路线 A（URL 参数）：**排除**（第 3 节实测，6 种参数均无效）。
- 路线 B（DOM 点击）：**采用**，选择器与等待信号以第 6 节为准。
- 「校园」：站点支持，保留在下拉中。

## 8. 点击后的刷新信号（2026-10-17 16:42 实测）

同一关键词 `python后端`、`jl=530`，点击「工作性质 → 实习」前后对比：

| | 卡片数 | 前 5 个岗位标题 |
|---|---|---|
| 过滤前 | 20 | Python 后端开发工程师（AI 应用/数字员工方向）· 软件开发工程师（线上兼职）· 软件测试数据分析 质量检测 五险一金 · python后端开发工程师 · python后端开发工程师 |
| 过滤后 | 20 | **研发实习生**（后端&AI）· **测试开发实习生** · **Java开发工程师（实习）** · **web全栈实习生** · **智能体全栈工程师实习生** |

结论：

- 筛选**确实生效**，全部结果变为实习岗。
- 但有两点容易误判，实现时必须注意：
  1. **卡片数量可能仍是 20**（该关键词下实习岗本就不止 20 条），所以「数量没变」不能作为「没过滤」的判断依据。
  2. **URL 不变**，无法用 URL 判断。
- 可靠的刷新信号是**列表首个岗位标题发生变化**，实测约 1 秒内完成。因此等待逻辑应为「记录点击前首卡标题 → 点击 → 轮询等首卡标题变化（上限 8 秒）→ 超时只告警不中断」。
- 另注：**职位详情页的 `workType` 不可用于判定单个岗位性质**——详情页混入大量「相似职位」数据（实测某岗位页出现 31 对 `全职/2`、4 对 `校园/5`、1 对 `兼职/临时/1`），主岗位的字段无法可靠区分。


