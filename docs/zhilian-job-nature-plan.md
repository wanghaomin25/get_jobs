# 实施计划：智联招聘新增「工作性质」筛选（不限/全职/兼职·临时/实习/校园）

> 面向执行 agent。代码位置 2026-10-17 实测核对。**必须先完成 §4 网站机制取证，才允许进入 §5 实施**——智联站对"工作性质"的过滤编码未经验证，是本计划唯一未知数，取证结果决定实现路线（A=URL 参数 / B=DOM 点击）。
> 前置：智联投递流程已修通（P1-P4 已落地，zhilian_data 已有 7 条真实投递记录）。

## 1. 目标

在智联配置页增加单选下拉「工作性质」：不限 / 全职 / 兼职·临时 / 实习 / 校园（最终以 §4 取证发现的站点实际选项名为准）。选择后，投递流程搜索阶段即按该性质过滤职位（智联站点原生过滤，不做采集后近似过滤）。

## 2. 环境与惯例（同前两份计划）

- 项目根 `C:\Users\wanghaomin\Desktop\jianli\get_jobs`，非 git：先备份
  ```bash
  cp db/getjobs.db db/getjobs.db.bak
  cp src/main/java/com/getjobs/worker/zhilian/ZhiLian.java src/main/java/com/getjobs/worker/zhilian/ZhiLian.java.bak
  cp src/main/java/com/getjobs/application/service/ZhilianService.java src/main/java/com/getjobs/application/service/ZhilianService.java.bak
  cp front/app/zhilian/page.tsx front/app/zhilian/page.tsx.bak
  ```
- 服务：项目根 `start.bat`；API 9527。改 Java 后重启；改前端后 `cd front && npm run build:prod` 再重启（构建链 pnpm/node_modules 已配好）。
- GBK 终端：查中文数据用 `python -I` 落 UTF-8 文件再 Read；curl 传含中文 JSON 用 `--data-binary @utf8文件`（直接 `-d` 会 400，实测踩过）。
- `db/getjobs.db` 生产库只准 `ALTER TABLE ADD COLUMN` 和 INSERT/UPDATE，禁止重建表/覆盖。

## 3. 现状链路（改动落点，均已核实）

| 层 | 位置 | 现状 |
|---|---|---|
| DB | `zhilian_config` 表 | 列：id/keywords/city_code/salary/created_at/updated_at，**无 job_nature 列** |
| 实体 | `application/entity/ZhilianConfigEntity.java` | 三业务字段 |
| 保存 | `ZhilianService.saveOrUpdateFirstSelective` :122-145 | 插入分支逐字段 set（:127-129）；更新分支 selective 白名单（:137-139）——**新字段两处都要登记**，否则保存静默丢弃 |
| 加载 | `ZhilianService.loadZhilianConfig` :43-81 | 关键词 parseListString；城市名→码（`getCodeByTypeAndName("city",…)`，未命中回退 0）；薪资"不限"→"0"——新字段解析仿此 |
| worker 配置类 | `worker/zhilian/ZhilianConfig.java` | keywords/cityCode/salary 三字段 |
| URL 构建 | `worker/zhilian/ZhiLian.buildBaseUrl` :613-619 | `HOME_URL + jl{cityCode}/p{页}?sl={salary}`；`JobUtils.appendParam(k,v)` 产 query 片段 |
| 搜索流程 | `ZhiLian.deliverByKeyword`（:99 每关键词 buildBaseUrl(1)→navigate→搜索框 fill+Enter→:157 区域 waitForSelector） | **路线 B 的过滤点击插入点在"列表加载成功之后、翻页循环之前"** |
| 字典 | `zhilian_option` 表（type/name/code/sort_order）+ `ZhilianOptionInitializer` | 现仅 city 42 条 |
| API | `ZhilianController.getAllZhilianConfig` :47-68 | options 目前只下发 city 数组 |
| 前端 | `front/app/zhilian/page.tsx` :16-24 接口、:37-38 state、:207 payload={...config}、:316-326 城市下拉（options.city 驱动） | 新下拉完全复制城市模式；payload 自动携带新字段，`handleSaveConfig`/开始投递前自动保存（P4 已实现）都不用改 |

## 4. 取证任务（先做，产出决定 §5 走哪条路线）

### 4.1 已知公开线索（未经本站实证，仅作假设起点）
- 智联旧后端 API `fe-api.zhaopin.com/c/i/sou` 有 `employmentType` 参数（-1=全部），具体枚举值无权威公开文档。
- 新站搜索是路径式 URL：`https://www.zhaopin.com/sou/jl{城市码}/p{页}?sl={薪资}`（即代码里 HOME_URL 模式）。
- 校园招聘可能走独立域名 `xiaoyuan.zhaopin.com`——若如此，"校园"档在主搜过滤里可能不存在（见 4.4 降级）。

### 4.2 取证步骤
1. 启动服务，用项目浏览器（`/api/zhilian/login` 弹窗，登录态在 browser-data 里已存在）打开 `https://www.zhaopin.com/sou/jl530/`。
2. 在搜索页筛选栏找到"工作性质"（或近似命名）筛选组，**逐个**点击 全职/兼职/实习/校园（及"临时"若有）每个选项：
   - 记录点击前后地址栏 URL 全文；
   - F12 Network 面板记录搜索请求（XHR 或文档请求）的 URL/查询参数变化；
   - 记录筛选控件的 DOM 结构（外层容器 class、每个选项按钮的 class 与文本、选中态 class）。
3. 截图/HTML 存 `docs/evidence/`（新建）供人复核。

### 4.3 判定
- **URL 或搜索请求出现稳定的过滤参数/路径段** → 路线 A：参数名+每选项编码值全部记入表格。
- **URL 不变、纯前端状态+点击后列表刷新** → 路线 B：记录选项按钮选择器与选中后的等待信号（列表容器重新渲染）。
- **搜索页根本没有"工作性质"筛选** → 停止实施，向用户报告：智联主搜不支持该维度，近似替代是把性质词并入关键词（"python 实习"），此路由用户决策，不许擅自做采集后伪过滤。

### 4.4 特别核查：「校园」
若站点选项集里没有"校园"（或点击后跳 xiaoyuan 域名/新路径），如实报告并把下拉做成站点支持的子集（用户列的 5 项里砍掉不可用项），**不要硬造**。

## 5. 实施改动清单（取证后按所选路线）

### 5.0 公共改动（A/B 都要）
1. **列**：`ALTER TABLE zhilian_config ADD COLUMN job_nature TEXT;`（幂等：先 PRAGMA table_info 检查）。项目内建表模板（grep `zhilian_config` in `src/main/resources/**`）同步加列，保新装环境一致。
2. **实体** `ZhilianConfigEntity.java`：`/** 工作性质（站点过滤值；null/空/不限=不过滤） */ private String jobNature;`
3. **worker 配置类** `ZhilianConfig.java`：加 `private String jobNature;`
4. **保存白名单** `ZhilianService.saveOrUpdateFirstSelective`：插入分支 `toInsert.setJobNature(incoming.getJobNature());`；更新分支 `if (incoming.getJobNature() != null) toUpdate.setJobNature(incoming.getJobNature());`
5. **加载** `ZhilianService.loadZhilianConfig`：`String jn = safeTrim(entity.getJobNature()); config.setJobNature(jn == null || jn.isEmpty() || "不限".equals(jn) ? "" : jn);`（空串=不过滤，贯穿全链路的约定）
6. **字典** `zhilian_option` 插入 type='jobNature' 行：name=站点显示文本、code=（路线 A：站点编码值；路线 B：与 name 同）。含"不限"行（code=''）。写入 `ZhilianOptionInitializer`（幂等 upsert）+ 直接 INSERT 现有库。**下拉不硬编码**，与城市同款数据驱动，日后站点改文案只动字典。
7. **API 下发** `ZhilianController.getAllZhilianConfig` :56-62 后仿 city 加 `options.put("jobNature", …)`。
8. **前端** `page.tsx`：接口 `ZhilianOptions` 加 `jobNature: Option[]`（:24），初始 options state（:38）加 `jobNature: []`，config 接口（:16-21）与初始值（:37）加 `jobNature: ''`，城市下拉（:316-326）后加同构下拉：
   ```tsx
   <div className="space-y-2">
     <Label>工作性质</Label>
     <Select value={config.jobNature || ''}
       onChange={(e) => setConfig((c) => ({ ...c, jobNature: e.target.value }))}
       placeholder="请选择工作性质">
       {options.jobNature.map((o) => (<option key={o.name} value={o.code}>{o.name}</option>))}
     </Select>
   </div>
   ```
   （payload={...config} 已自动携带，无需其他改动。）
9. **构建**：`gradle compileJava`；`npm run build:prod`；重启服务。

### 5.1 路线 A：URL 参数（取证证实编码后首选）
`ZhiLian.buildBaseUrl`（:613-619）在 `sl` 后追加：
```java
String jn = config.getJobNature();
if (jn != null && !jn.isEmpty()) {
    url.append("&").append(JobUtils.appendParam("<取证参数名>", jn)); // 参数名/值格式以取证为准，注意与现有 "?" 的衔接
}
```
（若取证显示过滤编码在**路径段**而非 query，如 `/sou/jl530_XXX/`，则按该格式拼路径段——以取证记录为唯一依据。）

### 5.2 路线 B：DOM 点击
新增方法：
```java
/** 搜索完成后按配置点击“工作性质”筛选（config.jobNature 存选项文本；空=跳过） */
private void applyJobNatureFilter() {
    String name = config.getJobNature();
    if (name == null || name.isEmpty()) return;
    try {
        Locator btn = page.locator("<取证得到的选项按钮选择器模板>".replace("__NAME__", name)); // 具体模板以取证为准
        if (btn.count() == 0) { log.warn("未找到工作性质筛选项【{}】，本关键词不过滤继续", name); return; }
        btn.first().click();
        PlaywrightUtil.sleep(2); // 等待列表刷新；以取证发现的刷新信号为准
        log.info("已应用工作性质筛选: {}", name);
    } catch (Exception e) { log.warn("工作性质筛选点击失败: {}", e.getMessage()); }
}
```
调用点：`deliverByKeyword` 中**每个关键词**搜索成功、`waitForSelector("div.joblist-box__item")`（:157 区域）通过之后、翻页 while 循环之前。每关键词都点一次（因为每次 navigate 回 baseUrl 会重置筛选）。失败降级为"不过滤继续投递"——**严禁**因筛选失败抛异常中断整个关键词。

## 6. 验证

1. `curl GET /api/zhilian/config`：options 含 jobNature 数组；
2. 浏览器（Ctrl+F5）选"实习"→ 保存 → 查库 `job_nature` 值正确；
3. **单关键词小样本投递**（临时把 keywords 设为 `["python后端"]`，中文用 `--data-binary @file`）：
   - 路线 A：后端日志/断点看 buildBaseUrl 产出的 URL，人工浏览器打开确认列表只有实习岗；
   - 路线 B：日志出现 `已应用工作性质筛选: 实习`；
   - 采集入库的 `zhilian_data.job_title` 抽查应全部符合所选性质（新行 ≥3 条，无一违反）；
4. **回归**：job_nature=''（不限）时 URL/流程与取证前行为一致（对照 7 条历史数据的采集模式）；
5. 恢复用户原关键词 `["实习","python","后端","fastapi","MySQL"]` 并确认 updated_at。

## 7. 禁止事项

- 不动 Boss/猎聘/51job 任何文件；不动 zhilian 投递主循环既有逻辑（P1-P4 修复点保持）。
- 不重建 `zhilian_config` 表、不动既有 7 条 zhilian_data。
- 未经取证不写选择器/参数编码；取证发现站点不支持"校园"就砍选项，不硬造。
- 遇验证码/滑块：停止并报告，不做任何对抗。

## 8. 验收

- [ ] 取证记录（URL/DOM 证据文件）归档 docs/evidence/，路线判定有依据
- [ ] 公共 9 项改动全落（重点：保存白名单双分支——漏登记会静默丢字段）
- [ ] 所选路线实现 + 编译/构建通过
- [ ] 验证 3 的 job_title 抽查零违反；不限档回归通过
- [ ] 用户配置（关键词等）原样恢复
