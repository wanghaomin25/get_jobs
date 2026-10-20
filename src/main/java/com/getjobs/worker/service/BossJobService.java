package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Boss直聘任务服务
 * 管理Boss平台的投递任务执行和状态
 * <p>
 * T1 显性排队：四平台共用一条 Playwright 线程，投递在执行层本就串行。
 * 本类状态机为 IDLE / QUEUED / RUNNING；拿到执行权之前不做任何有副作用的操作。
 * T2：停止兜底改用公共 StopWatchdog（原 15 秒宽限语义不变）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BossJobService implements JobPlatformService {
    private static final String PLATFORM = "boss";

    public static final String STATE_IDLE = "IDLE";
    public static final String STATE_QUEUED = "QUEUED";
    public static final String STATE_RUNNING = "RUNNING";

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Boss> bossProvider;
    private final DeliveryCoordinator coordinator;

    // 状态机：IDLE / QUEUED / RUNNING
    private volatile String state = STATE_IDLE;
    // 停止标志
    private volatile boolean shouldStop = false;
    // Z4/B：停止请求已发出但任务尚未退出（RUNNING 期间的过渡信号，供前端显示"停止中…"）
    private volatile boolean stopRequested = false;
    // 本次任务的开始时间（毫秒），用于判断任务是不是卡死了
    private volatile long runningSince = 0L;
    // 排队相关：进入队列的时间、被谁阻塞、取消标志
    private volatile long queuedSince = 0L;
    private volatile String blockedBy;
    private volatile boolean cancelQueued = false;

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        if (!STATE_IDLE.equals(state)) {
            progressCallback.accept(JobProgressMessage.warning(PLATFORM, "任务已在运行中或已排队"));
            return;
        }

        boolean acquired = false;
        try {
            // T1：先拿全局投递执行权，再做任何有副作用的步骤。
            if (coordinator.tryAcquireImmediately(PLATFORM)) {
                acquired = true;
                log.info("[{}] 获得投递执行权", PLATFORM);
            } else {
                String blocker = coordinator.currentRunningPlatform();
                state = STATE_QUEUED;
                cancelQueued = false;
                queuedSince = System.currentTimeMillis();
                blockedBy = blocker;
                String blockerName = coordinator.displayName(blocker);
                progressCallback.accept(JobProgressMessage.info(PLATFORM,
                        String.format("%s 投递中，本任务已排队等待", blockerName)));
                log.info("[{}] 已排队，等待 {} 投递完成", PLATFORM, blockerName);

                acquired = coordinator.awaitTurn(PLATFORM, this::cancelQueuedFlag, 500L);
                if (!acquired) {
                    log.info("[{}] 排队等待已取消，未执行投递", PLATFORM);
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, "排队已取消，未执行投递"));
                    return;
                }
                log.info("[{}] 排队结束，获得投递执行权", PLATFORM);
            }

            // ===== 以下为持有执行权之后的副作用步骤（顺序很重要）=====
            state = STATE_RUNNING;
            shouldStop = false;
            stopRequested = false;
            runningSince = System.currentTimeMillis();
            queuedSince = 0L;
            blockedBy = null;

            // 浏览器可能已经被关掉（手动关窗口 / Chrome 崩溃），这时各字段仍非空，
            // 但每个操作都会抛 TargetClosedError。先确保浏览器可用，必要时重新拉起。
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "检查浏览器状态..."));
            playwrightManager.ensureReady();

            // 获取Boss页面实例
            Page page = playwrightManager.getBossPage();
            if (page == null) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "Boss页面未初始化"));
                return;
            }

            // 检查是否已登录
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "请先登录Boss直聘"));
                return;
            }

            // 暂停后台登录监控，避免与投递流程并发访问同一Page
            playwrightManager.pauseBossMonitoring();

            // 加载配置（统一从 boss_config 专表读取）
            BossConfig config = configService.getBossConfig();
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));

            progressCallback.accept(JobProgressMessage.info(PLATFORM, "开始投递任务..."));

            // 创建Boss实例并执行投递
            Boss.ProgressCallback bossCallback = (message, current, total) -> {
                if (current != null && total != null) {
                    progressCallback.accept(JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, message));
                }
            };

            Boss boss = bossProvider.getObject();
            boss.setPage(page);
            boss.setConfig(config);
            boss.setProgressCallback(bossCallback);
            boss.setShouldStopCallback(this::shouldStop);
            boss.prepare();

            // 整个投递流程必须跑在 Playwright 专用线程上。
            // 原来跑在 ForkJoinPool 上，会和 scheduling-1 的登录监控同时操作同一条
            // Playwright 连接，导航被打断报 net::ERR_ABORTED，整个任务直接挂掉。
            int deliveredCount = playwrightManager.callOnPlaywright(boss::execute);

            progressCallback.accept(JobProgressMessage.success(PLATFORM,
                String.format("投递任务完成，共发起%d个聊天", deliveredCount)));
        } catch (Exception e) {
            log.error("Boss投递任务执行失败", e);
            progressCallback.accept(JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            // 异常时也不能漏放锁，否则全系统投递永久卡死
            if (acquired) {
                coordinator.release(PLATFORM);
            }
            state = STATE_IDLE;
            shouldStop = false;
            stopRequested = false;
            cancelQueued = false;
            queuedSince = 0L;
            blockedBy = null;
            runningSince = 0L;
            // 恢复后台登录监控
            try {
                playwrightManager.resumeBossMonitoring();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 停止投递（T2 统一语义，宽限期保持原 15 秒）：
     * QUEUED → 置取消标志，排队自旋立即退出回 IDLE；
     * RUNNING → 置 shouldStop + StopWatchdog 兜底（原 Boss watchdog 的公共化）；
     * IDLE → 幂等返回。
     */
    @Override
    public void stopDelivery() {
        String st = state;
        if (STATE_QUEUED.equals(st)) {
            log.info("[{}] 收到取消排队请求", PLATFORM);
            cancelQueued = true;
            return;
        }
        if (!STATE_RUNNING.equals(st)) {
            return; // IDLE 幂等返回
        }
        log.info("收到停止Boss投递任务的请求");
        shouldStop = true;
        stopRequested = true;
        StopWatchdog.start(PLATFORM, this::isRunning, FORCE_RESET_GRACE_MS, () -> {
            state = STATE_IDLE;
            queuedSince = 0L;
            blockedBy = null;
            runningSince = 0L;
            // 强制复位时必须释放执行权 + 恢复监控，否则排队中的平台永远拿不到锁
            coordinator.forceRelease();
            try {
                playwrightManager.resumeBossMonitoring();
            } catch (Exception ignored) {}
        });
    }

    /** 停止指令发出后，等待任务自行退出的宽限时间 */
    private static final long FORCE_RESET_GRACE_MS = 15_000L;

    /** 任务已经跑了多久（毫秒）；没在跑返回 0 */
    public long runningForMillis() {
        long since = runningSince;
        return since == 0L ? 0L : System.currentTimeMillis() - since;
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        String st = state;
        status.put("platform", PLATFORM);
        status.put("state", st);
        status.put("isRunning", STATE_RUNNING.equals(st));
        status.put("isQueued", STATE_QUEUED.equals(st));
        // Z4/B：RUNNING 期间停止请求已发出、任务尚未退出的过渡信号（IDLE/QUEUED 恒为 false）
        status.put("stopRequested", stopRequested && STATE_RUNNING.equals(st));
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        if (STATE_QUEUED.equals(st)) {
            status.put("blockedBy", blockedBy);
            status.put("blockedByName", coordinator.displayName(blockedBy));
            status.put("queuedSeconds", queuedSince == 0L ? 0L : (System.currentTimeMillis() - queuedSince) / 1000);
        }
        return status;
    }

    /**
     * 供 /start 响应体使用：短暂等待执行线程完成排队判定，返回初始状态信息。
     */
    public Map<String, Object> getStartStateInfo() {
        String st = state;
        for (int i = 0; i < 40 && STATE_IDLE.equals(st); i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            st = state;
        }
        Map<String, Object> info = new HashMap<>();
        info.put("state", st);
        info.put("isQueued", STATE_QUEUED.equals(st));
        if (STATE_QUEUED.equals(st)) {
            info.put("blockedByName", coordinator.displayName(blockedBy));
        }
        return info;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public boolean isRunning() {
        return STATE_RUNNING.equals(state);
    }

    @Override
    public boolean isQueued() {
        return STATE_QUEUED.equals(state);
    }

    /**
     * 检查是否应该停止
     * 供Boss.java调用
     */
    public boolean shouldStop() {
        return shouldStop;
    }

    /** 排队取消标志（DeliveryCoordinator.awaitTurn 每轮检查） */
    private boolean cancelQueuedFlag() {
        return cancelQueued;
    }


}
