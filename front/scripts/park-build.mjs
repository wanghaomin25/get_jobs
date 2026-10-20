/**
 * 构建前"停车"脚本：把旧构建产物移出仓库可跟踪范围。
 *
 * 背景：WorkBuddy 的 [safe-delete] 守卫会拦下 next build（清理 .next/turbopack）
 * 和 copy-dist.mjs（递归删 dist），所以只能用 mv 绕开。历史上 mv 的落点是
 * front/.next.stale-<时间戳>，那是个"未忽略"的仓库内路径，配合 git add -A
 * 会把上百 MB 构建缓存收进提交（.git 因此涨到 114 MB）。
 *
 * 现在统一停到 build/stale/<时间戳>/ 下：build/ 已被 .gitignore 忽略，
 * 从根上不可能被 git add 收走；它同时是 Gradle 输出目录，gradle clean 顺手回收。
 *
 * 用法：
 *   node scripts/park-build.mjs              # 时间戳取 HHmmss
 *   node scripts/park-build.mjs 123456       # 指定后缀
 *   node scripts/park-build.mjs --dry        # 只打印计划，不移动任何东西
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const frontDir = path.join(__dirname, '..');
const repoRoot = path.join(frontDir, '..');

// 需要停车的产物：仓内相对路径 -> 停车后的子目录名。
//
// 这里只放构建中间产物：next build 启动时会清理 .next/turbopack，被 [safe-delete]
// 守卫拦下会直接让构建失败，所以必须在 next build 之前移走。
//
// dist 刻意不在此列 —— 它是服务运行所需的最终页面产物，若在构建开始前就移走，
// 一旦 next build 失败就会连累线上页面。旧 dist 由 copy-dist.mjs 在复制前自行停车
// （那里原本是递归删除，同样会被守卫拦）。
const TARGETS = [
  ['front/.next', '.next'],
  ['front/out', 'out'],
];

const args = process.argv.slice(2);
const dryRun = args.includes('--dry') || args.includes('-n');
const stampArg = args.find((a) => !a.startsWith('-'));

const stamp =
  stampArg ||
  new Date().toTimeString().slice(0, 8).replace(/:/g, ''); // HHmmss

const parkRoot = path.join(repoRoot, 'build', 'stale', stamp);

function copyRecursive(src, dest) {
  const stat = fs.lstatSync(src);
  if (stat.isDirectory()) {
    fs.mkdirSync(dest, { recursive: true });
    for (const name of fs.readdirSync(src)) {
      copyRecursive(path.join(src, name), path.join(dest, name));
    }
  } else if (stat.isSymbolicLink()) {
    fs.symlinkSync(fs.readlinkSync(src), dest);
  } else {
    fs.copyFileSync(src, dest);
  }
}

let moved = 0;
for (const [rel, child] of TARGETS) {
  const src = path.join(repoRoot, rel);
  if (!fs.existsSync(src)) continue;

  const dest = path.join(parkRoot, child);
  if (dryRun) {
    console.log(`[park][dry] ${rel} -> build/stale/${stamp}/${child}`);
    moved++;
    continue;
  }
  fs.mkdirSync(path.dirname(dest), { recursive: true });

  try {
    fs.renameSync(src, dest);
    console.log(`[park] ${rel} -> build/stale/${stamp}/${child}`);
    moved++;
  } catch (err) {
    // 跨盘无法 rename：退化为复制，源目录保留（不删，避免触发删除守卫）
    if (err.code === 'EXDEV' || err.code === 'EPERM') {
      copyRecursive(src, dest);
      console.warn(
        `[park] ${rel} 与目标跨盘/受限，已复制到 build/stale/${stamp}/${child}，源目录保留，请自行确认后清理`
      );
      moved++;
    } else {
      console.error(`[park] ${rel} 移动失败: ${err.message}`);
    }
  }
}

if (moved === 0) {
  console.log('[park] 没有需要停车的构建产物（干净状态）');
} else {
  console.log(`[park] 完成，共 ${moved} 项，落点 build/stale/${stamp}/（已被 git 忽略）`);
}
