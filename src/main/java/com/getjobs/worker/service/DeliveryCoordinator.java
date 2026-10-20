package com.getjobs.worker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 全局投递执行权协调器（T1 显性排队）。
 * <p>
 * 四个平台共用同一个 BrowserContext 和同一条 Playwright 专用线程，投递在执行层本来就是
 * 强制串行的（见 docs/multi-platform-delivery-state-plan.md §1-R1）。本类的职责是把这种
 * 隐性排队变成显性排队：谁在跑、谁在等，都可以被查询与取消。
 * <p>
 * 实现说明：计划里描述的是公平 ReentrantLock，但停止逻辑的 watchdog 需要在任务卡死时
 * 从**另一个线程**强制释放执行权，ReentrantLock 不允许非持有线程 unlock。
 * 因此这里用公平信号量 {@code Semaphore(1, true)} 实现（先到先得语义不变），并用
 * {@link #permitHeld} 防止卡死任务恢复后与 watchdog 的强制释放发生二次释放。
 */
@Slf4j
@Component
public class DeliveryCoordinator {

    /** 公平信号量：同时只允许一个平台持有投递执行权 */
    private final Semaphore permit = new Semaphore(1, true);

    /** 当前持锁平台（null=无人投递） */
    private volatile String runningPlatform;

    /** 是否真的持有许可；release 与 forceRelease 都靠它防二次释放 */
    private final AtomicBoolean permitHeld = new AtomicBoolean(false);

    /** 立即尝试获取执行权；拿到返回 true */
    public boolean tryAcquireImmediately(String platform) {
        if (permit.tryAcquire()) {
            if (permitHeld.compareAndSet(false, true)) {
                runningPlatform = platform;
                return true;
            }
            // 理论不可达：许可拿到了却没有登记持有者，退回以免泄漏
            permit.release();
        }
        return false;
    }

    /**
     * 自旋等待执行权：每 {@code pollMs} 毫秒 tryAcquire 一次，每轮检查取消标志。
     * 返回 false 表示取消或中断（未获得执行权）。
     */
    public boolean awaitTurn(String platform, BooleanSupplier cancelled, long pollMs) {
        long poll = pollMs > 0 ? pollMs : 500L;
        while (!cancelled.getAsBoolean()) {
            try {
                if (permit.tryAcquire(poll, TimeUnit.MILLISECONDS)) {
                    if (permitHeld.compareAndSet(false, true)) {
                        runningPlatform = platform;
                        return true;
                    }
                    permit.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 释放执行权（只有当前持有者 platform 匹配时才生效，防误放） */
    public void release(String platform) {
        if (platform != null && platform.equals(runningPlatform)
                && permitHeld.compareAndSet(true, false)) {
            runningPlatform = null;
            permit.release();
        }
    }

    /** 强制释放（watchdog 用）：不管登记的平台是谁，只要许可被持有就放掉 */
    public void forceRelease() {
        if (permitHeld.compareAndSet(true, false)) {
            String stuck = runningPlatform;
            runningPlatform = null;
            permit.release();
            log.warn("[coordinator] 执行权被强制释放（原持有者: {}）", stuck);
        }
    }

    /** 当前正在投递的平台标识；无人投递返回 null */
    public String currentRunningPlatform() {
        return runningPlatform;
    }

    /** 平台标识 → 界面显示名 */
    public String displayName(String platform) {
        if (platform == null) return "未知平台";
        switch (platform) {
            case "boss":    return "Boss直聘";
            case "zhilian": return "智联招聘";
            case "51job":   return "前程无忧51job";
            case "liepin":  return "猎聘";
            default:        return platform;
        }
    }
}
