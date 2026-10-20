# 实施计划：Boss 直聘「Boss 活跃状态」档位筛选

> 本文档面向执行 agent。所有代码位置均在 2026-10-16 实测核对过，若行号有偏移以"原文"片段搜索定位。
> 完成标准见文末「验收」。

## 1. 目标

在 get_jobs 管理页（Next.js）的 Boss直聘配置区，把现有的「HR活跃过滤」0/1 开关升级为一个 4 档单选下拉「Boss 活跃状态」，按 HR 活跃程度过滤职位。

**核心架构决策（不要偏离）**：
- "档位"概念只存在于前端。前端把档位翻译成**黑名单过滤词列表**，写入现有字段 `boss_config.dead_status`（TEXT，括号列表格式如 `[2月内活跃,年]`）。
- 后端只改一件事：把两处硬编码 `contains("年")` 改为读 `config.getDeadStatus()` 列表；列表为空时回退 `["年"]`（= 现状行为，向后兼容）。
- **零数据库 schema 变更、零新接口、零新字典表数据、零 BossConfigEntity 改动**（`deadStatus` 字段的保存/加载链路已全通，只是执行端和 UI 没接上）。

## 2. 环境事实（执行前必读）

- 项目根：`C:\Users\wanghaomin\Desktop\jianli\get_jobs`（Windows，shell 为 Git Bash）
- **此目录不是 git 仓库**——回滚手段只有改前手工备份。开始任何修改前先执行：
  ```bash
  cp db/getjobs.db db/getjobs.db.bak
  cp src/main/java/com/getjobs/worker/boss/Boss.java src/main/java/com/getjobs/worker/boss/Boss.java.bak
  cp front/app/boss/page.tsx front/app/boss/page.tsx.bak
  ```
- `db/getjobs.db` 是含用户真实配置（AI key、cookie、简历路径）的生产库，**绝不可用项目内模板/初始化 SQL 覆盖**。
- Java 21（`~/jdk-21`），Gradle（`~/gradle-9.7.1`），服务启动必须从项目根（相对路径 `./db/`、`data/`、`browser-data/`）：双击根目录 `start.bat`，或 `JAVA_HOME=~/jdk-21 ~/gradle-9.7.1/bin/gradle.bat bootRun`。API 端口 9527，管理页端口 6866。
- 前端源码在 `front/`，构建产物输出到 `src/main/resources/dist/`（服务端直接托管该目录）。构建脚本 `build:prod` = `next build && node scripts/copy-dist.mjs`（已在 front/package.json 核实）。
- **前端依赖环境已于 2026-10-16 配好并实测通过**（`pnpm install` exit 0 + `npm run build:prod` 完整成功、dist 已更新）：
  - `front/node_modules` 已存在（pnpm 12.14.0 装齐，lockfile v9 frozen 通过）。正常迭代**不需要**重装依赖。
  - 包管理器用 pnpm（本机已 `npm install -g pnpm`，prefix 在用户目录无需管理员）。若在新机器/新环境重做：`cd front && pnpm install --frozen-lockfile`。
  - `front/pnpm-workspace.yaml` 的 `allowBuilds` 作者占位符（`set this to true or false`）已改为 `sharp: false` / `unrs-resolver: false`——pnpm 10+ 的严格脚本策略否则会以 `ERR_PNPM_IGNORED_BUILDS` 非零退出；两者均无需安装脚本，不影响构建产物。
  - 若未来 `next build` 或 pnpm 网络超时，可切镜像：`pnpm config set registry https://registry.npmmirror.com`（本机实测官方源可直连，默认不用切）。
- Windows 终端是 GBK 代码页，直接 `cat`/`sqlite3` 输出中文会乱码——查库请落 UTF-8 临时文件再看，例如：
  ```bash
  python -I -c "import sqlite3;c=sqlite3.connect('db/getjobs.db');open('db_check.txt','w',encoding='utf-8').write(str(c.execute('SELECT filter_dead_hr, dead_status FROM boss_config').fetchall()))"
  ```
  然后用 Read 工具看 `db_check.txt`（`sqlite3`/`json` 是标准库，`-I` 隔离模式可用）。

## 3. 现状代码证据（改哪些点）

| 位置 | 现状 |
|---|---|
| `src/main/java/com/getjobs/worker/boss/Boss.java` 约 :423-428 | 投递循环过滤：`boolean hrInactiveByYear = bossActive != null && bossActive.contains("年");` → `if (Boolean.TRUE.equals(config.getFilterDeadHR()) && hrInactiveByYear)` 则 `log.info("被过滤：HR活跃状态包含'年' …")` + `continue` |
| 同文件 约 :552-558 | 入库标记过滤：`if (!filtered && Boolean.TRUE.equals(config.getFilterDeadHR())) { String hrActive = entity.getHrActiveStatus(); if (hrActive != null && hrActive.contains("年")) filtered = true; }`，随后 :560 `entity.setDeliveryStatus(filtered ? "已过滤" : "未投递")` |
| 同文件 :1686 | 现成通用函数 `public boolean containsDeadStatus(String activeTimeText, List<String> deadStatus)`（子串命中即 true），目前无人调用 |
| 同文件 :405 | `bossActive = boss.optString("activeTimeDesc", "")`（岗位卡 JSON 字段） |
| `Boss.java` 中 `config` 类型 | `com.getjobs.worker.boss.BossConfig`（@Data，含 `Boolean filterDeadHR`、`List<String> deadStatus`；getter 为 `getFilterDeadHR()`/`getDeadStatus()`） |
| `src/main/java/com/getjobs/application/service/BossService.java` :283 | 保存链路：`if (partial.getDeadStatus() != null) existing.setDeadStatus(partial.getDeadStatus());`（已通，不改） |
| 同文件 :361 | 加载链路：`config.setDeadStatus(parseListString(entity.getDeadStatus()));`（已通，不改；`parseListString` 同时支持 `[a,b]` 与 JSON 数组格式） |
| `src/main/java/com/getjobs/application/entity/BossConfigEntity.java` | `@TableName("boss_config")`，字段 camelCase 自动映射 snake_case 列（`filter_dead_hr`、`dead_status`，均 TEXT/INTEGER，列已存在） |
| `front/app/boss/page.tsx` 约 :892-904 | 旧 UI：`filterDeadHr` 的 0/1 `<Select>`（原生 select + option 写法）及说明文字「开启后将过滤活跃状态包含"年"的HR…」 |
| 同文件 :34-35 | config 类型已声明 `filterDeadHr?: number` 与 `deadStatus?: string`（不需要加字段） |
| 同文件 :368-370 | 加载回显处：`const normalizedFilterDeadHr = (data.config?.filterDeadHr ?? 0); setConfig(prev => ({ ...prev, filterDeadHr: normalizedFilterDeadHr }))` |
| 同文件 :416-431 | `handleSave` 组装 `payload = { ...config, ... }` 后 `PUT ${API_BASE}/api/boss/config`——`deadStatus` 在 config state 里就会自动随 payload 发出，**无需改 handleSave** |
| 同文件 约 :373 | 前端已有 `parseListString` 函数（被 industry 回显使用），档位反查直接复用 |

**实测标签全集**（来自 `boss_data.hr_active_status` 265 条）：
`刚刚活跃 / 今日活跃 / 3日内活跃 / 本周活跃 / 2周内活跃 / 本月活跃 / 2月内活跃 / 3月内活跃 / 4月内活跃 / 5月内活跃 / 半年前活跃 / 近半年活跃 / null`

## 4. 档位定义（唯一事实来源）

| 下拉值 | 文案 | 保留的 HR | filterDeadHr | dead_status 存储值 |
|---|---|---|---|---|
| `any` | 不限 | 全部 | 0 | `''`（空串） |
| `week` | 一周内活跃（推荐） | 刚刚/今日/3日内/本周 | 1 | `[2周内活跃,本月活跃,2月内活跃,3月内活跃,4月内活跃,5月内活跃,年]` |
| `month` | 一月内活跃 | 上面 + 2周内/本月 | 1 | `[2月内活跃,3月内活跃,4月内活跃,5月内活跃,年]` |
| `loose` | 宽松（排除半年以上不活跃） | 除"半年及以上"外全保留 | 1 | `[年]` |

**两条必须写进注释的边界语义**（勿"修正"它们，是设计决定）：
1. 匹配是**子串包含**语义：过滤词 `年` 同时命中 `半年前活跃`/`近半年活跃`/`X年前活跃`，所以"宽松"档的真实含义是"排除半年以上"，做不到只排"1年以上"。上表黑名单不再显式列"半年前活跃"等（已被 `年` 覆盖）。
2. `activeTimeDesc` 为空/null 的岗位**保留不滤**（沿用现状 `bossActive != null` 短路）。宁可放过，不误杀 Boss 未来新增文案。

## 5. 后端改动：`Boss.java` 两处

### 5.1 投递循环过滤点（原约 :423-428）

原文：
```java
// HR活跃状态过滤：当开启过滤开关且活跃描述包含“年”时，视为不活跃
boolean hrInactiveByYear = bossActive != null && bossActive.contains("年");
if (Boolean.TRUE.equals(config.getFilterDeadHR()) && hrInactiveByYear) {
    log.info("被过滤：HR活跃状态包含‘年’ | 公司：{} | 岗位：{} | 活跃：{}", bossCompany != null ? bossCompany : "", jobName != null ? jobName : "", bossActive);
    continue;
}
```
替换为：
```java
// HR活跃状态过滤：按管理页配置的 dead_status 过滤词命中即视为不活跃；未配置时回退 ["年"]（旧行为）
if (Boolean.TRUE.equals(config.getFilterDeadHR()) && bossActive != null) {
    List<String> deadStatus = (config.getDeadStatus() != null && !config.getDeadStatus().isEmpty())
            ? config.getDeadStatus() : List.of("年");
    if (containsDeadStatus(bossActive, deadStatus)) {
        log.info("被过滤：HR活跃状态命中过滤词 | 公司：{} | 岗位：{} | 活跃：{}", bossCompany != null ? bossCompany : "", jobName != null ? jobName : "", bossActive);
        continue;
    }
}
```
注意：删除 `hrInactiveByYear` 前先 grep 确认该局部变量仅此一处引用。`java.util.List` 已被本文件 import（`containsDeadStatus` 签名即用它）；`List.of` 需 JDK9+（本项目 JDK21，满足）。

### 5.2 入库标记过滤点（原约 :552-558）

原文：
```java
// HR活跃状态过滤：开启过滤且活跃描述包含“年”，则标记为已过滤，但仍入库
if (!filtered && Boolean.TRUE.equals(config.getFilterDeadHR())) {
    String hrActive = entity.getHrActiveStatus();
    if (hrActive != null && hrActive.contains("年")) {
        filtered = true;
    }
}
```
替换为：
```java
// HR活跃状态过滤：命中配置过滤词则标记“已过滤”（仍入库），未配置时回退 ["年"]
if (!filtered && Boolean.TRUE.equals(config.getFilterDeadHR())) {
    String hrActive = entity.getHrActiveStatus();
    List<String> deadStatus = (config.getDeadStatus() != null && !config.getDeadStatus().isEmpty())
            ? config.getDeadStatus() : List.of("年");
    if (hrActive != null && containsDeadStatus(hrActive, deadStatus)) {
        filtered = true;
    }
}
```
（两处各自内联回退逻辑即可，不必抽 helper——两点位于不同方法，改动最小化优先。）

## 6. 前端改动：`front/app/boss/page.tsx`

### 6.1 档位常量 + 状态（模块顶层、`BossConfig` interface 附近加常量；组件内加一个 state）

```tsx
// Boss 活跃档位 → 黑名单过滤词。子串包含语义：“年”会同时命中“半年前活跃/近半年活跃”，故宽松档=排除半年以上。
// 无活跃标签(null)的岗位一律保留。档位只在前端存在，落到配置即 filterDeadHr + dead_status 两字段。
const DEAD_LEVELS = [
  { value: 'any', label: '不限', filterDeadHr: 0, deadStatus: [] as string[] },
  { value: 'week', label: '一周内活跃（推荐）', filterDeadHr: 1, deadStatus: ['2周内活跃', '本月活跃', '2月内活跃', '3月内活跃', '4月内活跃', '5月内活跃', '年'] },
  { value: 'month', label: '一月内活跃', filterDeadHr: 1, deadStatus: ['2月内活跃', '3月内活跃', '4月内活跃', '5月内活跃', '年'] },
  { value: 'loose', label: '宽松（排除半年以上不活跃）', filterDeadHr: 1, deadStatus: ['年'] },
]
```
组件内（其他 useState 旁）：
```tsx
const [deadLevel, setDeadLevel] = useState<string>('any')
```

### 6.2 回显（改原 :368-370 区域）

```tsx
// HR活跃档位回显：由 filterDeadHr + dead_status 反查档位；库中值不匹配任何预设档时显示“宽松”，且不重写原字段
const normalizedFilterDeadHr = (data.config?.filterDeadHr ?? 0)
const rawDead: string[] = parseListString(data.config?.deadStatus ?? '')
const matchedLevel = DEAD_LEVELS.find(l =>
  l.filterDeadHr === normalizedFilterDeadHr &&
  l.deadStatus.length === rawDead.length &&
  l.deadStatus.every((k, i) => k === rawDead[i])
)
setDeadLevel(matchedLevel ? matchedLevel.value : (normalizedFilterDeadHr === 0 ? 'any' : 'loose'))
setConfig(prev => ({ ...prev, filterDeadHr: normalizedFilterDeadHr, deadStatus: data.config?.deadStatus ?? '' }))
```
若前端 `parseListString` 返回类型/容错行为与此处预期不符（先读它的实现，约在 industry 回显调用点附近），按实际微调；目标是把 `[a,b]`/`a,b`/空串都解析成 string[]。

### 6.3 替换旧 UI（原 :892-904 整块）

```tsx
{/* Boss 活跃状态档位筛选 */}
<div className="space-y-2">
  <Label htmlFor="deadLevel">Boss 活跃状态</Label>
  <Select
    id="deadLevel"
    value={deadLevel}
    onChange={(e) => {
      const level = DEAD_LEVELS.find(l => l.value === e.target.value) ?? DEAD_LEVELS[0]
      setDeadLevel(level.value)
      setConfig({
        ...config,
        filterDeadHr: level.filterDeadHr,
        deadStatus: level.deadStatus.length ? `[${level.deadStatus.join(',')}]` : '',
      })
    }}
  >
    {DEAD_LEVELS.map(l => (
      <option key={l.value} value={l.value}>{l.label}</option>
    ))}
  </Select>
  <p className="text-xs text-muted-foreground">仅保留所选活跃度及以上的HR职位；无活跃显示的职位会被保留。</p>
</div>
```
沿用文件中既有的 `Select`/`Label` 用法（就是原生 select + option，见被替换的旧代码块）。`handleSave` 的 payload 是 `{...config}`，`filterDeadHr`/`deadStatus` 自动随保存发出，**不要改 handleSave、不要改后端 PUT/GET 接口**。

## 7. 构建与部署

```bash
# 0) 仅当 front/node_modules 缺失时（本机 2026-10-16 起已存在，平时跳过）：
cd front && pnpm install --frozen-lockfile && cd ..

# 1) 前端构建（脚本已核实为 build:prod，2026-10-16 基线构建实测成功）
cd front && npm run build:prod && cd ..
# 产物由 scripts/copy-dist.mjs 自动覆盖到 src/main/resources/dist/（看文件时间戳）

# 2) 后端编译自检（不必起服务）
JAVA_HOME=~/jdk-21 ~/gradle-9.7.1/bin/gradle.bat compileJava

# 3) 重启服务：用户双击 start.bat（或上述 gradle bootRun，须从项目根执行）
```
若 gradle/npm 出现"代理拒绝连接"类报错，检查是否误恢复了 `gradle.properties` 中被删除的作者本机代理配置（应保持删除状态）。

## 8. 验证（按顺序执行）

1. **编译/构建**：`compileJava` 与 `npm run build:prod` 均成功；`src/main/resources/dist` 时间戳为本次构建。
2. **保存链路回归**（不必开浏览器也可）：服务启动后
   ```bash
   curl -s -X PUT http://localhost:9527/api/boss/config -H 'Content-Type: application/json' \
     -d '{"filterDeadHr":1,"deadStatus":"[2月内活跃,3月内活跃,4月内活跃,5月内活跃,年]"}'
   ```
   再用第 2 节的 python 查库命令确认 `boss_config.filter_dead_hr=1`、`dead_status` 为该括号列表。查完把库里两字段恢复用户原值（从 `db/getjobs.db.bak` 对照，或重设 `"{"filterDeadHr":0,"deadStatus":""}"`）。
3. **回显**：GET `/api/boss/config`（或直接打开 http://localhost:6866 的 Boss 配置页）确认下拉显示"一月内活跃"；改选"不限"保存后库中 `dead_status` 为空且 `filter_dead_hr=0`。
4. **行为验证（用户配合跑一次自动投递）**：档位选"一周内活跃"，日志应出现 `被过滤：HR活跃状态命中过滤词`，且被过滤条目的活跃文本包含"本月活跃/半年前活跃"等（旧代码只会滤带"年"的）；`boss_data` 中这些行 `delivery_status='已过滤'` 而 `hr_active_status` 正常入库。
5. **兼容性**：`dead_status` 置空 + `filter_dead_hr=1` 时行为与改造前完全一致（只滤含"年"文本）——对照日志确认。

## 9. 禁止事项

- 不改数据库 schema、不改 `BossConfigEntity`、不改 `BossService.java`（:283/:361 已通）、不新增 `boss_option` 字典行、不改任何后端 Controller/接口签名。
- 不动猎聘/智联/51job 等其他平台的 Worker 与 Service（`containsDeadStatus` 若有他处引用则不受影响，本次只改 Boss.java）。
- 不覆盖/重置 `db/getjobs.db`；改前必备份（第 2 节）。
- 完成修改并验证后，`.bak` 文件保留，由用户决定何时删除；未经用户要求不删。

## 10. 验收标准

- [ ] Boss.java 两处硬编码 `contains("年")` 均已替换为 `containsDeadStatus(..., deadStatus列表)` + 空回退 `["年"]`，`compileJava` 通过
- [ ] page.tsx 旧 0/1 开关被 4 档下拉替代；保存/回显/构建全部走通
- [ ] 验证第 2、3 步实测通过（保存→查库→回显往返一致）
- [ ] 空 `dead_status` 时行为与改造前逐字节等价（兼容）
- [ ] 未触碰第 9 节禁止清单
