package com.getjobs.worker.service;

import lombok.extern.slf4j.Slf4j;

import java.util.function.BooleanSupplier;

/**
 * 停止指令看门狗（T2 统一兜底）。
 * <p>
 * shouldStop 只在投递流程的检查点生效；如果任务卡在某个 Playwright 调用里，它永远看不到
 * 停止标志，isRunning 就会一直为 true，把"开始投递"永久堵死。看门狗给任务一段宽限期自行
 * 退出，超时则执行强制复位回调（复位状态机 + 释放执行权 + 恢复监控），让界面能重新发起任务。
 * <p>
 * 原实现位于 BossJobService（FORCE_RESET_GRACE_MS = 15 秒），四平台统一复用本工具，
 * Boss 保持原有 15 秒数值不变。
 */
@Slf4j
public final class StopWatchdog {

    private StopWatchdog() {
    }

    /**
     * 启动看门狗线程。
     *
     * @param platform      平台标识（用于线程名与日志）
     * @param stillRunning  任务是否仍在运行的判定（每 500ms 轮询）
     * @param graceMs       宽限毫秒数
     * @param onForceReset  超时未退出时的强制复位回调（复位状态 + release 执行权 + resume 监控）
     */
    public static void start(String platform, BooleanSupplier stillRunning, long graceMs, Runnable onForceReset) {
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + graceMs;
            while (stillRunning.getAsBoolean() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (stillRunning.getAsBoolean()) {
                log.warn("[{}] 投递任务在 {} 秒内未响应停止指令，强制复位运行状态（后台可能仍有残留操作）",
                        platform, graceMs / 1000);
                onForceReset.run();
            }
        }, platform + "-stop-watchdog").start();
    }
}
