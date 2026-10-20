# 第二轮验证记录与自行修复的不足

> 执行时间：2026-10-19 17:40–18:10。指令：用户「再次验证，本轮验证你可以自己解决不足的问题」。
> 全部结论为实测输出（编译、构建、curl、日志计数、sqlite 直查、CDP/页面观测），非推测。

## 1. 自行解决的三个不足

### 1.1 把 park-build 真正接入构建流程（原为手动前置步骤）

**问题**：`front/scripts/park-build.mjs`（上一轮新增）没有被 `package.json` 引用，构建前是否停车全靠人记得。忘了停车的后果是 `[safe-delete]` 守卫拦下 `next build` 的清缓存动作 → 构建失败。

**改法**：
- `front/package.json` 新增 `"prebuild:prod": "node scripts/park-build.mjs"`（npm 会在 `npm run build:prod` 前自动执行）。
- **同时纠正 park-build 自身的一个隐患**：它原本把 `src/main/resources/dist` 也一起停车，而 dist 是**服务运行所需的最终页面产物**。若在 `next build` 开始前就移走，一旦构建失败会连累线上页面。现在 `TARGETS` 只保留 `.next` 与 `out`；dist 改由 `copy-dist.mjs` 在**复制前**自行停车。
- `copy-dist.mjs` 的 `deleteFolderRecursive`（逐文件 `unlinkSync` + `rmdirSync`，正是触发 `[safe-delete]` 守卫的写法）改为 `parkOldDist()`：把旧 dist 移到 `build/stale/<HHmmss>-dist/`。附带好处：旧产物**可回滚**了，原先直接删掉就没了。

**实测**（`npm run build:prod`，EXIT=0）：

```
> node scripts/park-build.mjs
[park] front/.next -> build/stale/174505/.next
[park] front/out -> build/stale/174505/out
✓ Compiled successfully in 2.5s
旧的目标目录已停车到: ...\build\stale\174513-dist
已补齐 10 个 RSC 预取别名文件
✅ 构建文件复制成功!
```

产物核对：`dist` 101 文件、`index.html` 15359 字节、旧 dist 完整停在 `build/stale/174513-dist`（可回滚）；`git status` **0 个未跟踪条目**。服务启动后 `GET / -> 200`、`GET /boss -> 200`，页面正常。

### 1.2 登录状态 SSE 的监听器泄漏（N1，上一轮记录的范围外缺陷）

**根因**：[JobController.streamLoginStatus](../../src/main/java/com/getjobs/application/controller/JobController.java) 每建立一次连接都执行 `playwrightManager.addLoginStatusListener(this::sendLoginStatusChange)`，而 `removeLoginStatusListener` **全仓库零调用**。

两个叠加问题：
1. **方法引用每次求值都是新对象**（`this::sendLoginStatusChange`），即便调用 `remove` 也匹配不上 —— 必须持有同一实例才能注销。
2. 从不注销 → 前端每刷新一次页面就永久新增一个监听器，旧连接断开后仍被通知，向已关闭的 emitter 写入并抛 `IOException`。

这正是上一轮日志里 4 条 `登录状态SSE连接错误` ERROR 堆栈的来源（来自 `onError` 回调里的 `log.error`）。

**改法**：保存 listener 引用，`onCompletion`/`onTimeout`/`onError` 三处统一注销；`onError` 的 ERROR 降级为 `log.debug`（客户端关页面/刷新属正常事件）。

**实测**：连续两次建立 SSE 连接并让客户端断开，`登录状态SSE连接错误` 计数 **0**；连接建立时正常收到平台状态推送。

### 1.3 修正被误判写入的数据库记录

见 §2：第一次服务启动时智联被误判为登录成功，把登录页的匿名 Cookie 写进了 `cookie` 表（`updated_at=17:46:32`，覆盖了登出后的空值）。已用 `UPDATE`（符合「只允许 INSERT/UPDATE」约束）恢复为 `[]` 并标注原因。**该修正发生在用户真实登录之前，未影响用户的登录态。**

## 2. 新发现的真实缺陷：登录检测在「非首页」上误判为已登录

### 2.1 现象（实测时间线，服务首次启动）

```
17:46:26  开始初始化智联招聘平台...
17:46:26  WARN 解析智联招聘Cookie失败，未能加载任何Cookie
17:46:29  检测到未登录智联招聘，重定向到登录页面
17:46:30  已切换到智联二维码登录页面，等待用户扫码...
17:46:32  智联招聘平台登录成功          ← 距二维码出现仅 1.5 秒
17:46:32  保存智联招聘Cookie成功，共 15 条，remark=login success
```

用户不可能在 1.5 秒内完成扫码；且此刻页面刚被导航到 `passport.zhaopin.com/login`。

### 2.2 根因

`checkIfZhilianLoggedIn()` 的**兜底**：

```java
// 如果没有登录按钮，也认为已登录
if (!loginButtonExists) { isLoggedIn = true; }
```

登录按钮选择器 `a.home-header__c-no-login` 是**首页头部**的元素。二维码登录页上没有它 → `loginButtonExists=false` → 兜底判「已登录」→ `onZhilianLoginSuccess()` 置状态为 true **并把当天页面的匿名 Cookie 保存进数据库**（下次启动加载这些 Cookie 仍判未登录，形成循环）。

猎聘 `checkIfLiepinLoggedIn()` 有同构兜底（`未发现登录入口 → 判定为已登录`），实测中它确实走了这条路径（用户登录后日志反复打印「未发现登录入口，兜底判定为已登录」）。

### 2.3 已做的加固

- **智联**：位于 `passport.zhaopin.com`（通行证/登录域）时一律判未登录；本平台 Cookie 已被清空时判未登录（覆盖「登出后页面仍停留在登录后界面，3 秒一次的监控把状态又置回 true」这一原始 N7 症状）。保留原有 `i.zhaopin.com` 与「无登录按钮」判定，仅在其前加排除。
- **猎聘**：本平台 Cookie 已被清空时判未登录。保留原有明确特征（用户信息容器/头像）与兜底逻辑。

**诚实标注：本次加固只做到「代码路径论证」，未能在真机上复现误判场景验证**。原因是复现需要「智联处于二维码页」这一状态，而用户随后已成功扫码登录，场景不再可重现。加固的判定方向是单向的（只会把 true 变 false，不会把 false 变 true），因此不会影响正常登录态的识别 —— 用户登录后 5 分钟以上状态稳定为 true，与之一致。

## 3. 与用户登录的交叉验证

用户在 18:01 前后扫码登录了智联与猎聘，数据库与内存状态均**正确记录**：

| 平台 | cookie 条数 | 更新时间 | 关键凭据 |
|---|---|---|---|
| zhilian | 19 | 18:01:45 | `x-zp-client-id`、`_uab_collina` |
| liepin | 20 | 18:01:53 | `XSRF-TOKEN`、`__sessionId`、`need_bind_tel`、`c_flag` |

`need_bind_tel`/`c_flag` 等属登录后才出现的 Cookie，确证是真登录。四个平台的 `/status` 均返回新状态机字段：

```
{"isRunning":false,"isLoggedIn":true,"state":"IDLE","isQueued":false,"platform":"..."}
```

即状态与实际一致、接口可重复调用（幂等）——前端刷新只是重新拉取这些接口，拿到的是后端真值。

## 4. 未做与原因

- **真机多平台排队验证**：需要两个平台同时进入真实投递（消耗日限额、简历真实投出，属对外不可逆动作），未擅自触发。若需验证，建议由用户自行点击「开始投递」，从旁读日志与 `/status` 确认 `QUEUED` → `RUNNING` 迁移。
- **前端页面级刷新实测**：已由 §3 的接口层幂等性验证覆盖（页面刷新仅重拉这些接口），未额外开浏览器标签页打扰用户。

## 5. 本轮改动清单（5 个已跟踪文件，+85/−26，未提交）

```
front/package.json                                     |  1 +
front/scripts/copy-dist.mjs                            | 45 +++++++++--------
front/scripts/park-build.mjs                           | 10 ++++-
src/main/java/.../controller/JobController.java        | 14 ++++++-
src/main/java/.../manager/PlaywrightManager.java       | 41 ++++++++++++++---
```

`git status --short` 无任何 `??` 未跟踪条目。

## 6. 残留与待办

- `build/stale/` 现有 68 MB 停车产物（`174505/`、`174513-dist/`），已被 gitignore，`gradle clean` 或手动删除即可回收。
- 备份 `../get_jobs-git-backup-20261019/`（114 MB）确认一切正常后可自行删除。
- 服务当前**保持运行**（原样交给用户，同时监听 9527 与 6866），未提交任何改动，未对 origin 做任何 push。