package com.getjobs.application.controller;

import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Playwright管理控制器
 * 用于测试和管理Playwright实例
 */
@RestController
@RequestMapping("/api/playwright")
public class PlaywrightController {

    private final PlaywrightManager playwrightManager;

    public PlaywrightController(PlaywrightManager playwrightManager) {
        this.playwrightManager = playwrightManager;
    }

    /**
     * 获取Playwright状态信息
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("initialized", playwrightManager.isInitialized());
        status.put("cdpPort", playwrightManager.getCdpPort());
        status.put("hasBossPage", playwrightManager.getBossPage() != null);
        // 改用持久化上下文后没有独立的 Browser 对象，context 即代表浏览器本身
        status.put("hasBrowser", playwrightManager.getContext() != null);
        status.put("bossLoggedIn", playwrightManager.isLoggedIn("boss"));

        return ResponseEntity.ok(status);
    }

}
