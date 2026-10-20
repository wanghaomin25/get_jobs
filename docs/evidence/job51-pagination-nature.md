# 51job 翻页修复 + 弹窗解析 + 职位类型筛选 取证记录（docs/job51-pagination-and-nature-plan.md §3）

> 状态：**T0 取证完成；T1/T2/T3 已按证据实现并真机验证通过**（2026-10-17 20:18 验证轮）。

## 1. T0.1 翻页控件结构（证据：get-jobs.log 20:07:23）

「跳页控件缺失」dump 输出：

```
counts={div.ick=20, ..., #jump_page=0}
分页候选 .el-pagination 命中 1 个，首个HTML:
<div class="el-pagination is-background">
  <button type="button" disabled="disabled" class="btn-prev">…</button>
  <ul class="el-pager">
    <li class="number active">1</li><li class="number">2</li>…<li class="number">13</li>
  </ul>
  <button type="button" class="btn-next">…</button>
</div>
```

**判定**（对应计划 §3 的第二种情形）：无跳页输入框，只有 prev/页码/next。
`jumpToPage` 改为**点下一页**语义：`.el-pagination button.btn-next`，`disabled` 属性=末页收敛；
绝对页号仅作计数与到位校验（`li.number.active` 文本应变为目标页号）。

## 2. T0.2 投递结果弹窗真实文案（证据：get-jobs.log 20:06:49）

```
[51job] 结果弹窗原文(截前300字): 投递成功！
```

**判定**：新版弹窗文案只有「投递成功！」，**不含任何数字**，旧正则 `投递成功\D*(\d+)` 永远解析不出
（旧日志：`投递结果：成功 null 个`）。修复：文案含「投递成功」且不含「未投递」时按整页成功处理
（成功数=本页勾选数）；带「未投递」的旧格式仍走数字正则。一致性校验兜底（成功数==勾选数==缓存
jobId 数才标记）保持不变。

## 3. T0.3 「工作类型」过滤机制 → **路线 A**

通道 1（curl + 登录 cookie）被阿里云 WAF 拦截（带 cookie 仍是 JS 挑战页，与 job51-run.md §2.2 一致），
转通道 2：临时探针端点 `/api/playwright/probe-51job`（新开受控标签页，onResponse 抓 search-pc 请求
URL，dump 筛选区 DOM，可点击筛选项取 URL diff）。**取证后该端点已从源码删除。**

| 步骤 | search-pc 请求变化 | 首屏岗位标题分布 |
|---|---|---|
| 基线（无点击） | 无 jobType 参数 | 混合（实习/正式均有） |
| 点击「工作类型」→「实习」 | **`jobType=03`** | 全部实习向（FDE实习生、AI大模型开发实习生…） |
| 页面 URL 直拼 `&jobType=03` | **透传**，search-pc 请求含 `jobType=03` | 全部实习向 |
| 点击「全职」 | **`jobType=01`** | 全部正式岗（IT应用工程师、全栈开发工程师…） |
| 点击「兼职」 | **`jobType=02`** | 站点归类为兼职的岗位 |

筛选区 DOM：触发器 `SPAN.fixed-text`（文案「工作类型」，容器 `DIV.otab > custom-select-wrapper`），
选项为 `DIV.custom-option`（全部[selected]/全职/兼职/实习）。

**结论**：路线 A 成立——页面 URL 直拼 `jobType=<code>` 即可，SPA 会透传给 search-pc。
编码：**01=全职、02=兼职、03=实习、空=全部**。

## 4. 实现与验证（2026-10-17 20:18 验证轮，job_nature=03 实习、杭州、python后端）

| 验证项（计划 §8） | 结果 | 证据 |
|---|---|---|
| 1. options.jobNature | ✓ 4 项自动种入 | `GET /api/51job/config` → [全部'', 全职01, 兼职02, 实习03] |
| 2. PUT 往返 | ✓ `job_nature='03'` 落库回读一致 | sqlite 回读；自动保存链路：前端 payload `{...config}` 已携带 |
| 3. 筛选生效日志 | ✓ | 20:18:10 `已应用职位类型筛选: jobType=03` |
| 4. 翻页 | ✓ 投到第 2、3 页，末页正常收敛 | `成功翻到第2页/第3页`（ick=20/20/19）；第 4 页前 `已是末页（btn-next 禁用）` |
| 5. 弹窗解析+标记 | ✓ | `投递结果：成功 20 个，未投递 0 个`；第 1、2 页各标记 20 条（`成功更新 20 条`）；第 3 页勾选 19/缓存 20 → 保守不标记（宁漏标不错标，按设计） |
| 6. 标题分布 | ✓ 本轮 40 行全部实习向，零违反 | sqlite：后端实习生、AI大模型开发实习生、量化开发实习生… |
| 7. 回归（job_nature=''） | ✓ 代码路径 `jn.isEmpty()` 不拼参数；T0 轮（20:06，改前代码）行为一致 | 源码 + 20:06 轮日志 |
| 8. 收尾 | ✓ 配置恢复为实习向求职配置；探针端点已删 | job51_config：keywords=[实习,python,后端,fastapi,MySQL]、杭州、不限、job_nature=03 |

补充说明：
- 第 3 页的「成功 19 / 勾选 19 / 缓存 jobId 20」不一致告警来自末页 search JSON 返回 20 条而 DOM 只
  渲染 19 张卡（末页数据量不足一页），保守兜底正确工作。
- 全部类型下站点共 13 页（el-pager 页码），实习过滤后仅 3 页——过滤参数在 search-pc 服务端生效。
- 运行中的服务实例仍加载着已删除的探针端点类（取证后删除源码，未为此再重启）；该端点为只读探测，
  下次按 start.bat 重启后即消失。
