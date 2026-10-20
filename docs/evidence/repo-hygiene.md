# 仓库卫生治理执行记录（docs/repo-hygiene-plan.md）

> 执行时间：2026-10-19 13:10–13:35。全部结论为实测输出，非推测。
> 对应计划：`docs/repo-hygiene-plan.md`（§2 止血 → §3 拔根 → §4 历史清理 → §5 验收）。

## 0. 执行前基线（实测）

- `.git` 114 MB（pack 72.3 MB + loose 41 MB）；跟踪文件 205；提交 12（其中 origin/main..HEAD 11 个未推送）。
- 历史中 stale 对象 546 个，顶层目录 6 个（`.next.stale-104931/-201558`、`out.stale-104931/-201558`、`dist.stale-104931/-201558`）。
- 工作区干净（仅未跟踪的计划文档本身）。

## 1. §2 止血：gitignore 前缀通配（提交 2600e74）

- `front/.gitignore`：`/.next/`、`/out/` → `/.next*/`、`/out*/`（附历史教训注释）。
- 根 `.gitignore`：`dist/` → `dist*/`；新增 `*.stale-*/` 规则。
- §2.3 验证：`git check-ignore -v` 6/6 命中（含两条 `.stale-` 路径）。

## 2. §3 拔根：旁移目标改为 `build/stale/<ts>/`

- 新增 `front/scripts/park-build.mjs`：把 `front/.next`、`front/out`、`src/main/resources/dist` 整体 mv 到 `build/stale/<HHmmss>/`（`build/` 已被 gitignore 忽略，实测命中 `.gitignore:37 build/`）。
  - 支持 `--dry`（只打印计划）；跨盘时退化为复制并保留源目录；时间戳参数以 `--` 开头时按 dry-run/忽略处理。
  - 实测 mv 三件套约 68 MB，瞬时完成，未触发删除守卫。
- 构建后不 `git add -A` 的约定已写入 `.workbuddy/memory/MEMORY.md`（用 `git add -u` + 显式 add）。

## 3. §4 历史清理（用户确认后执行，选项 A）

### 与计划的偏差（重要）

1. **仓库是浅克隆**（`.git/shallow` 指向 `adcb011`），计划指定的 `git bundle create --all` 生成的 bundle **缺少父对象、无法克隆**（clone 报 `remote did not send all necessary objects`）。该 bundle（112 MB）已废弃删除。
   - 替代备份：整份 `.git` 目录复制 → `C:\Users\wanghaomin\Desktop\jianli\get_jobs-git-backup-20261019\.git`（114 MB，可读性验证通过：`git --git-dir=… log` 正常、HEAD=44d41a1、stale 对象 546 在案）。**恢复方式：换回该 `.git` 目录即可。**
2. 试跑副本用 `git clone --no-checkout`（避免检出 113 MB 垃圾）；filter-branch 要求索引与工作区干净，故试跑前 `git reset` + `git checkout -- .`。

### 试跑副本结果（副本已删除）

- filter-branch 重写 11 个提交（40 秒），`refs/original` 删除 + reflog expire + `gc --prune=now --aggressive` 后：stale 对象 **0**，`size-pack` **29.92 MiB**，`.git` 31 MB。

### 真仓库执行结果

- filter-branch 重写 11 个提交（40 秒）；删 `refs/original` + reflog expire + gc 后见 §5 验收表。
- **未动 `origin/main`**（仍为 `adcb011`），未对远端做任何 push。

### 哈希映射（旧 → 新，供检索历史文档）

| 旧 | 新 |
|---|---|
| 5f3bb6c | 6cfbd54 |
| 8803d0d | a3b443b |
| 4d486e0 | 1145523 |
| ea4c845 | a744738 |
| 8254c15 | 6450bf2 |
| 75b50ca | 269f451 |
| a51413e | 25c9f6d |
| 3829c8c | bf81eff |
| 2e73da1 | 339afc1 |
| 44d41a1 | 2600e74 |
| b971550 | b971550（不含 stale，哈希不变） |

## 4. §5 验收清单

| # | 检查 | 结果 |
|---|---|---|
| 1 | 规则生效 | ✓ 6/6 命中 |
| 2 | 历史无 stale | ✓ `git rev-list --objects --all \| grep -c ".stale-"` = 0 |
| 3 | 体积回落 | ✓ `.git` 114 MB → **31 MB**（size-pack 29.92 MiB） |
| 4 | 内容无损 | ✓ 跟踪文件 205 不变；提交数 12 不变；origin/main..HEAD 仍 11；origin/main 仍 adcb011 |
| 5 | 工作区干净 | ✓ `git status --short` 无输出 |
| 6 | 实战防复发 | ✓ 用 `park-build.mjs` 停车 + `npm run build:prod`（EXIT 0，31s）后 `git status --short` **0 个未跟踪条目**；停车目标 `build/stale/132448` 全程未出现在 status 中 |
| 7 | 数据库无恙 | ✓ `journal_mode=wal`；job51_data 119 行、boss_data 419、zhilian_data 17、job51_config 1（可读可写） |

## 5. 清理与残留

- 已删除：试跑副本 `../repo-hygiene-test`（63 MB）；本会话停车产物 `build/stale/132448`（68 MB，构建成功后不再需要）。
  - 备注：回收站工具（genie-trash）对上千文件的大目录会规模化失败（`Some operations were aborted`），小目录正常；按子目录分块回收站删除成功。
- 保留：`../get_jobs-git-backup-20261019/.git`（114 MB，清理前完整历史备份）——**确认一切正常后可自行删除**。
- 仓库内未提交垃圾为零；`db/`、`browser-data/`、`doc/imgs/` 未动。

## 6. 禁止事项遵守情况

- 未删 `doc/imgs/*.png`；未改写已推送历史（origin/main 未动）；未对 origin push；未手改 `.git` 内部结构（仅删 filter-branch 自留的 `refs/original/`，属计划步骤）；备份先行。
