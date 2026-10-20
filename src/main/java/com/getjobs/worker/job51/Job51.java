package com.getjobs.worker.job51;

import com.getjobs.application.service.Job51Service;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 * 前程无忧自动投递简历 - Playwright版本
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class Job51 {

    // 显式setter，避免对 Lombok 的依赖导致编译问题
    @Setter
    private Page page;

    @Setter
    private Job51Config config;

    @Setter
    private ProgressCallback progressCallback;

    @Setter
    private Supplier<Boolean> shouldStopCallback;

    private final List<String> resultList = new ArrayList<>();
    private final Job51Service job51Service;
    private boolean networkHooked = false;
    private boolean reachedDailyLimit = false;
    private final java.util.Set<String> processedRequestIds = new java.util.HashSet<>();
    @Getter
    private int currentPageNum = 0;
    // 当前页从JSON拦截到的jobId列表
    private final java.util.List<Long> currentPageJobIds = new java.util.ArrayList<>();
    // 本次任务累计“弹窗确认成功”的岗位数（仅当弹窗解析出 successNum 时累加，否则保持 0）
    private int totalConfirmedSuccess = 0;
    // 当前页勾选的数量，用于与弹窗结果做一致性判断，避免错标已投递
    private int lastCheckedCount = 0;

    private static final int DEFAULT_MAX_PAGE = 50;
    private static final String BASE_URL = "https://we.51job.com/pc/search?";

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    /**
     * 准备工作：加载配置、初始化数据
     */
    public void prepare() {
        resultList.clear();
        totalConfirmedSuccess = 0;
        lastCheckedCount = 0;
    }

    /**
     * 执行投递任务
     * @return 投递数量
     */
    public int execute() {
        long startTime = System.currentTimeMillis();

        try {
            // 检查配置是否有效
            if (config == null) {
                log.error("[51job] 配置为空，无法执行投递任务");
                sendProgress("配置为空，无法执行投递任务", null, null);
                return 0;
            }
            
            if (config.getKeywords() == null || config.getKeywords().isEmpty()) {
                log.warn("[51job] 关键词列表为空，无法执行投递任务");
                sendProgress("关键词列表为空，请先配置搜索关键词", null, null);
                return 0;
            }
            
            // 遍历所有关键词进行投递
            for (String keyword : config.getKeywords()) {
                if (shouldStop()) {
                    sendProgress("用户取消投递", null, null);
                    break;
                }

                String searchUrl = buildSearchUrl(keyword);
                deliverByKeyword(keyword, searchUrl);
            }

            long duration = System.currentTimeMillis() - startTime;
            // 口径说明：resultList.size() 是“勾选数”，不等于真实投递成功数（会包含已投递过/被屏蔽等被拒的岗位）。
            // 只有当投递结果弹窗确实解析出 successNum 时才额外报真实成功数；
            // 解析不到就保持原口径，避免输出“确认成功0个”这种误导文案。
            String message = totalConfirmedSuccess > 0
                    ? String.format("51job投递完成，共勾选%d个、弹窗确认成功%d个，用时%s",
                        resultList.size(), totalConfirmedSuccess, formatDuration(duration))
                    : String.format("51job投递完成，共投递%d个简历，用时%s",
                        resultList.size(), formatDuration(duration));
            sendProgress(message, null, null);

        } catch (Exception e) {
            log.error("51job投递过程出现异常", e);
            sendProgress("投递出现异常: " + e.getMessage(), null, null);
        }

        return resultList.size();
    }

    /**
     * 按关键词投递
     */
    private void deliverByKeyword(String keyword, String searchUrl) {
        try {
            // 收敛日志：不输出关键词级日志，仅保留页级摘要

            // 在跳转前监听 51job 搜索接口，抓取 JSON 并保存到数据库 + 打印诊断日志
            if (!networkHooked) {
                try {
                    page.onResponse(r -> {
                        try {
                            String url = r.url();
                            if (url != null && url.contains("/api/job/search-pc") && "GET".equalsIgnoreCase(r.request().method())) {
                                int status = 0;
                                try { status = r.status(); } catch (Throwable ignored) {}
                                String text = null;
                                try { text = r.text(); } catch (Throwable ignored) {}
                                int len = text == null ? 0 : text.length();
                                // 基于 URL 的 requestId 做去重，避免重复解析
                                String requestId = null;
                                try {
                                    java.net.URI u = new java.net.URI(url);
                                    String q = u.getQuery();
                                    if (q != null) {
                                        for (String part : q.split("&")) {
                                            int i = part.indexOf('=');
                                            if (i > 0 && "requestId".equals(part.substring(0, i))) {
                                                requestId = java.net.URLDecoder.decode(part.substring(i + 1), java.nio.charset.StandardCharsets.UTF_8);
                                                break;
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {}
                                if (requestId != null && !requestId.isBlank() && processedRequestIds.contains(requestId)) {
                                    return;
                                }
                                if (text != null) {
                                    // 根据 Content-Type 粗判是否为 JSON
                                    boolean isJson = false;
                                    try {
                                        java.util.Map<String, String> headers = r.headers();
                                        if (headers != null) {
                                            String ct = headers.getOrDefault("content-type", headers.get("Content-Type"));
                                            if (ct != null && ct.toLowerCase().contains("json")) isJson = true;
                                        }
                                    } catch (Throwable ignored) {}
                                    if (isJson) {
                                        // 解析并保存到数据库
                                        job51Service.parseAndPersistJob51SearchJson(text);
                                        // 📋 提取当前页的jobId列表并缓存
                                        List<Long> jobIds = extractJobIdsFromJson(text);
                                        if (jobIds != null && !jobIds.isEmpty()) {
                                            synchronized (currentPageJobIds) {
                                                currentPageJobIds.clear();
                                                currentPageJobIds.addAll(jobIds);
                                            }
                                        }
                                        if (requestId != null && !requestId.isBlank()) processedRequestIds.add(requestId);
                                    } // 非JSON静默跳过
                                }
                            }
                        } catch (Throwable e) {
                            // 静默错误
                        }
                    });
                    networkHooked = true;
                } catch (Throwable e) {
                    // 静默错误
                }
            }

            // 导航到搜索页面
            page.navigate(searchUrl);
            PlaywrightUtil.sleep(1);
            log.info("[51job] 已导航到搜索页: keyword={}，url={}", keyword, page.url());

            // 检查是否需要登录
            if (checkNeedLogin()) {
                log.warn("[51job] 检测到未登录（页面仍有登录入口），跳过关键词: {}", keyword);
                sendProgress("需要重新登录，跳过关键词: " + keyword, null, null);
                return;
            }

            // T1.3：删除旧版「点击排序入口 div.ss」整段——连续多轮真机命中 0（新版页面无此元素），
            // 属死逻辑。删除依据见 docs/evidence/job51-run.md §6.3 与 job51-pagination-nature.md。

            // 遍历页面投递
            for (int pageNum = 1; pageNum <= DEFAULT_MAX_PAGE; pageNum++) {
                if (shouldStop()) {
                    sendProgress("用户取消投递", null, null);
                    return;
                }

                sendProgress(String.format("正在投递第%d页", pageNum), pageNum, DEFAULT_MAX_PAGE);
                currentPageNum = pageNum;

                // 跳转到指定页码
                if (pageNum > 1 && !jumpToPage(pageNum)) {
                    break;
                }

                PlaywrightUtil.sleep(2);

                // 首屏/翻页后列表可能尚未渲染完成：此时 checkboxes.count()==0 会静默跳过整页，
                // 而调用方只会在 jumpToPage 失败后才 break，整页岗位就漏投且无任何告警。
                // 这里显式等一次岗位勾选框出现；超时不抛错，交给 deliverCurrentPage 记录结构诊断。
                try {
                    page.waitForSelector("div.ick",
                            new Page.WaitForSelectorOptions().setTimeout(15_000));
                } catch (Exception ignored) {}

                // 检查是否出现访问验证
                if (checkAccessVerification()) {
                    sendProgress("出现访问验证，停止投递", null, null);
                    return;
                }

                // 检测“无职位”文案，提前结束当前关键词
                try {
                    if (detectNoJobs51job()) {
                        sendProgress("该关键词暂无职位，提前结束", null, null);
                        break;
                    }
                } catch (Exception ignored) {}

                // 投递当前页面的所有职位
                deliverCurrentPage();
                if (reachedDailyLimit) break;

                PlaywrightUtil.sleep(3);
            }

            // 关键词完成不输出日志
        } catch (Exception e) {
            // 原实现静默吞掉异常：一旦选择器失配或页面异常，日志里完全看不到线索。
            String currentUrl = null;
            try { currentUrl = page.url(); } catch (Exception ignored) {}
            log.warn("[51job] 关键词【{}】投递过程异常（已跳过该关键词）: {}，当前URL={}",
                    keyword, e.getMessage(), currentUrl);
            dumpPageStructure("关键词投递异常");
        }
    }

    /**
     * 投递当前页面的所有职位
     */
    private void deliverCurrentPage() {
        try {
            PlaywrightUtil.sleep(1);

            // 查找所有职位的checkbox
            Locator checkboxes = page.locator("div.ick");
            int checkCount = checkboxes.count();
            if (checkCount == 0) {
                // 原实现此处直接 return：整页漏投且无任何痕迹，事后无法区分“没有岗位”和“选择器失效”。
                // 这里留一条告警并落一次页面结构诊断，便于按证据修正选择器。
                log.warn("[51job] 当前页未找到岗位勾选框（div.ick=0），跳过本页；URL={}", page.url());
                dumpPageStructure("第" + currentPageNum + "页无勾选框");
                return;
            }

            // 查找职位名称和公司名称
            Locator titles = page.locator("[class*='jname text-cut']");
            Locator companies = page.locator("[class*='cname text-cut']");

            int jobCount = checkCount;
            lastCheckedCount = jobCount;

            int titleCount = titles.count();
            int companyCount = companies.count();
            if (titleCount != jobCount || companyCount != jobCount) {
                // 名称元素与勾选框数量不一致时，按索引取标题会错位（数据写错比漏写更难排查）
                log.warn("[51job] 第{}页元素数量不一致：ick={}，jname={}，cname={}，标题/公司可能错位",
                        currentPageNum, jobCount, titleCount, companyCount);
            } else {
                log.info("[51job] 第{}页 DOM 就绪：ick={}，jname={}，cname={}",
                        currentPageNum, jobCount, titleCount, companyCount);
            }

            // 选中所有职位
            for (int i = 0; i < jobCount; i++) {
                if (shouldStop()) {
                    return;
                }

                try {
                    Locator checkbox = checkboxes.nth(i);
                    // 使用JavaScript点击，避免元素被遮挡
                    checkbox.evaluate("el => el.click()");

                    String title = i < titleCount ? titles.nth(i).textContent() : "未知职位";
                    String company = i < companyCount ? companies.nth(i).textContent() : "未知公司";
                    String jobInfo = company + " | " + title;
                    resultList.add(jobInfo);
//                    log.info("选中: {}", jobInfo);
                } catch (Exception e) { /* 静默 */ }
            }

            PlaywrightUtil.sleep(1);

            // 滚动到页面顶部
            page.evaluate("window.scrollTo(0, 0)");
            PlaywrightUtil.sleep(1);

            // 点击批量投递按钮
            boolean clicked = clickBatchDeliverButton();

            PlaywrightUtil.sleep(3);

            // 处理投递成功弹窗
            handleDeliverySuccessDialog(clicked);

            // 处理单独投递申请弹窗
            handleSeparateDeliveryDialog();

        } catch (Exception e) {
            log.error("投递当前页面失败", e);
        }
    }

    /**
     * 页面结构诊断：只读，不参与任何点击/投递决策。
     * <p>
     * 输出「实际存在的 class 频次（前 40）+ 正文前 600 字 + 若干候选选择器的命中数」，
     * 用于出现选择器失配时按证据修正，而不是凭猜测换一个 class。
     */
    private void dumpPageStructure(String reason) {
        try {
            Object raw = page.evaluate("""
                    () => {
                      const freq = {};
                      document.querySelectorAll('[class]').forEach(el => {
                        el.classList.forEach(c => { freq[c] = (freq[c] || 0) + 1; });
                      });
                      const top = Object.entries(freq)
                        .sort((a, b) => b[1] - a[1])
                        .slice(0, 40)
                        .map(([c, n]) => c + ':' + n)
                        .join(', ');
                      const cnt = s => document.querySelectorAll(s).length;
                      return {
                        url: location.href,
                        title: document.title,
                        bodyLen: document.body ? document.body.innerHTML.length : 0,
                        counts: {
                          'div.ick': cnt('div.ick'),
                          "[class*='jname']": cnt("[class*='jname']"),
                          "[class*='cname']": cnt("[class*='cname']"),
                          'button.p_but': cnt('button.p_but'),
                          'div.tabs_in': cnt('div.tabs_in'),
                          '#jump_page': cnt('#jump_page')
                        },
                        classesTop: top,
                        text: (document.body ? (document.body.innerText || '') : '').replace(/\\s+/g, ' ').slice(0, 600)
                      };
                    }""");
            log.warn("[51job] 页面结构诊断（{}）: {}", reason, String.valueOf(raw));
        } catch (Exception e) {
            log.warn("[51job] 页面结构诊断失败: {}", e.getMessage());
        }
    }

    /**
     * 点击批量投递按钮
     *
     * @return 是否真的点击了投递按钮
     */
    private boolean clickBatchDeliverButton() {
        int retryCount = 0;
        boolean success = false;

        while (!success && retryCount < 5) {
            try {
                if (shouldStop()) {
                    return false;
                }

                // 查找批量投递按钮
                Locator parent = page.locator("div.tabs_in");
                Locator buttons = parent.locator("button.p_but");

                // 2026-10-17 真机取证：新版搜索页 tabs_in 里只有一个「一键投递」按钮
                // （div.tabs_in=1，button.p_but=1）；旧代码要求 count>1 并点第 2 个，
                // 导致按钮明明存在却静默漏投。改为按文案优先，单个按钮也直接点。
                Locator target = null;
                Locator byText = parent.locator("button.p_but:has-text('一键投递')");
                if (byText.count() > 0) {
                    target = byText.first();
                } else if (buttons.count() == 1) {
                    target = buttons.first();
                } else if (buttons.count() > 1) {
                    target = buttons.nth(1); // 旧版多按钮布局兜底，保留原行为
                }

                if (target != null) {
                    PlaywrightUtil.sleep(1);
                    target.click();

                    // 🚨 点击后立即检测“日投递上限”提示（短暂出现，需快速多次检测）
                    for (int i = 0; i < 10; i++) {
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {} // 每200ms检测一次
                        if (detectDailyLimitToast51job()) {
                            reachedDailyLimit = true;
                            log.warn("点击投递按钮后，检测到 51job 日投递上限提示，停止投递");
                            sendProgress("检测到日投递上限，任务已停止", null, null);
                            return false;
                        }
                    }

                    success = true;
                } else {
                    // 按钮选择器失配时留告警 + 页面结构诊断，按真实 class 频次修正，而不是猜。
                    log.warn("[51job] 批量投递按钮未命中：div.tabs_in={}，button.p_but={}，文案命中=0",
                            parent.count(), buttons.count());
                    dumpPageStructure("批量投递按钮未命中");
                    break;
                }
            } catch (Exception e) {
                retryCount++;
                PlaywrightUtil.sleep(1);
            }
        }
        return success;
    }

    /**
     * 处理投递成功弹窗
     *
     * @param afterClick 本次调用是否发生在真实点击投递按钮之后；为 true 且未识别到任何
     *                   已知弹窗时输出诊断，避免新版弹窗结构变化后静默空转
     */
    private void handleDeliverySuccessDialog(boolean afterClick) {
        boolean recognized = false;
        try {
            PlaywrightUtil.sleep(2);

            Locator successContent = page.locator("//div[@class='successContent']");
            if (successContent.count() > 0) {
                String text = successContent.textContent();
                if (text != null && text.contains("快来扫码下载")) {
                    recognized = true;
                    log.info("检测到下载App弹窗，关闭中...");
                    // 关闭弹窗
                    Locator closeButton = page.locator("[class*='van-icon van-icon-cross van-popup__close-icon van-popup__close-icon--top-right']");
                    if (closeButton.count() > 0) {
                        closeButton.click();
                        log.info("成功关闭下载App弹窗");
                    }
                }
            }

            // 兼容提示弹框：投递成功N个，未投递M个（更稳健选择器）
            Locator elDialogBody = page.locator(".el-dialog__body");
            if (elDialogBody.count() > 0) {
                String dialogText = elDialogBody.first().innerText();
                // T0.2 取证：数字解析失败时必须看到真实文案，按证据修正则，禁止猜
                log.info("[51job] 结果弹窗原文(截前300字): {}", dialogText == null ? "null" : (dialogText.length() > 300 ? dialogText.substring(0, 300) : dialogText));
                if (dialogText != null && dialogText.contains("投递成功")) {
                    recognized = true;
                    Integer successNum = null;
                    Integer failNum = null;
                    try {
                        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("投递成功\\D*(\\d+)").matcher(dialogText);
                        if (m1.find()) successNum = Integer.parseInt(m1.group(1));
                        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("未投递\\D*(\\d+)").matcher(dialogText);
                        if (m2.find()) failNum = Integer.parseInt(m2.group(1));
                    } catch (Exception ignored) {}
                    // T0.2 取证（2026-10-17 20:06）：新版弹窗文案只有「投递成功！」，不含任何数字，
                    // 旧正则永远解析不出（日志证据：投递结果：成功 null 个）。出现该弹窗即站点确认
                    // 整页提交成功，故按整页成功处理：成功数=本页勾选数；被拒场景站点会带「未投递」
                    // 字样（旧格式），仍走上面的数字解析分支。下方一致性校验兜底保持不变。
                    if (successNum == null && dialogText.contains("投递成功")
                            && !dialogText.contains("未投递") && lastCheckedCount > 0) {
                        successNum = lastCheckedCount;
                        failNum = 0;
                        log.info("[51job] 弹窗为无数字的「投递成功！」，按整页成功处理（本页勾选 {} 个）", lastCheckedCount);
                    }
                    log.info("[51job] 投递结果：成功 {} 个，未投递 {} 个（本页勾选 {} 个）",
                            successNum, failNum, lastCheckedCount);
                    sendProgress(String.format("投递结果：成功 %s 个，未投递 %s 个", successNum == null ? "?" : successNum, failNum == null ? "?" : failNum), null, null);

                    // 只有弹窗真的解析出成功数才计入“确认成功”，否则保持原口径
                    if (successNum != null && successNum > 0) {
                        totalConfirmedSuccess += successNum;
                    }

                    // ✅ 标记数据库中的岗位为已投递
                    // 原实现按“缓存 jobId 列表的前 successNum 个”标记，但被拒的岗位不一定排在末尾，
                    // 会出现“标题与 jobId 对不上”的错标。这里改为一致性校验：
                    // 仅当 成功数 == 本页勾选数 且 缓存 jobId 数 == 本页勾选数 时整页标记；
                    // 其它情形只告警不标记（宁可漏标，不可错标）。
                    if (successNum == null || successNum <= 0) {
                        log.warn("[51job] 投递成功数量为0或未解析到，不标记投递状态");
                    } else if (lastCheckedCount <= 0) {
                        log.warn("[51job] 未记录到本页勾选数，无法校验投递结果对应关系，不标记投递状态");
                    } else {
                        try {
                            List<Long> deliveredIds = new ArrayList<>();
                            synchronized (currentPageJobIds) {
                                deliveredIds.addAll(currentPageJobIds);
                            }
                            if (deliveredIds.isEmpty()) {
                                log.warn("[51job] 当前页没有缓存的jobId，无法标记投递状态");
                            } else if (successNum != lastCheckedCount || deliveredIds.size() != lastCheckedCount) {
                                log.warn("[51job] 投递结果与勾选数不一致（成功 {} / 勾选 {} / 缓存 jobId {}），"
                                                + "无法确定是哪几个被拒，本次不标记以避免错标",
                                        successNum, lastCheckedCount, deliveredIds.size());
                            } else {
                                job51Service.markDeliveredBatch(deliveredIds);
                                log.info("[51job] 标记已投递 {} 个职位", deliveredIds.size());
                            }
                        } catch (Exception e) {
                            log.warn("[51job] 标记投递状态失败: {}", e.getMessage());
                        }
                    }

                    // 优先点击“确定/关闭”按钮，其次点右上角关闭，再次退格键
                    try {
                        Locator okBtn = page.locator(".el-dialog__footer button:has-text('确定'), .el-message-box__btns button:has-text('确定')");
                        if (okBtn.count() > 0) {
                            okBtn.first().click();
                        } else {
                            // 1) 点击关闭图标的父按钮
                            Locator iconClose = page.locator("i.el-dialog__close.el-icon.el-icon-close");
                            boolean closed = false;
                            if (iconClose.count() > 0 && iconClose.first().isVisible()) {
                                try {
                                    // 有些站点图标本身不接收点击，点击其父按钮更稳定
                                    iconClose.first().evaluate("el => el.parentElement && el.parentElement.click()");
                                    closed = true;
                                } catch (Exception ignored) {}
                            }
                            // 2) 直接点击 header 关闭按钮（带 aria-label="Close"）
                            if (!closed) {
                                Locator headerBtn = page.locator("button.el-dialog__headerbtn, .el-dialog__header button.el-dialog__headerbtn, button[aria-label='Close']");
                                if (headerBtn.count() > 0 && headerBtn.first().isVisible()) {
                                    try {
                                        headerBtn.first().click(new Locator.ClickOptions().setForce(true).setTimeout(2000));
                                        closed = true;
                                    } catch (Exception ignored) {}
                                }
                            }
                            // 3) JS 兜底点击
                            if (!closed) {
                                try {
                                    page.evaluate("document.querySelector('button.el-dialog__headerbtn')?.click() || document.querySelector('button[aria-label=\\'Close\\']')?.click() ");
                                    closed = true;
                                } catch (Exception ignored) {}
                            }
                            // 4) 最终兜底：按 ESC
                            if (!closed) {
                                page.keyboard().press("Escape");
                            }
                        }
                        PlaywrightUtil.sleep(1);
                    } catch (Exception ignored) {}
                }
            }

            // 真实点击了投递按钮却没认出任何已知弹窗 → 输出诊断，按证据补新弹窗的分支
            if (afterClick && !recognized) {
                String hint;
                if (elDialogBody.count() > 0) {
                    String t = elDialogBody.first().innerText();
                    hint = "，存在 .el-dialog__body 但文案不含「投递成功」：" + (t == null ? "null" : t.replace('\n', ' '));
                } else {
                    hint = "，未见任何已知弹窗";
                }
                if (hint.length() > 320) hint = hint.substring(0, 320);
                log.warn("[51job] 点击一键投递后未识别到结果弹窗{}", hint);
                dumpPageStructure("点击一键投递后未识别到结果弹窗");
            }

            // 统一尝试关闭任何残留的弹框覆盖层
            closeAnyModalOverlays();
            // 弹窗处理后再次检测是否出现“日投递上限”提示
            try {
                if (detectDailyLimitToast51job()) {
                    reachedDailyLimit = true;
                    log.warn("处理成功弹窗后，检测到 51job 日投递上限提示，停止当前页");
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            log.debug("未找到投递成功弹窗或处理失败: {}", e.getMessage());
        }
    }

    /**
     * 处理单独投递申请弹窗
     */
    private void handleSeparateDeliveryDialog() {
        try {
            Locator dialogContent = page.locator("//div[@class='el-dialog__body']/span");
            if (dialogContent.count() > 0) {
                String text = dialogContent.textContent();
                if (text != null && text.contains("需要到企业招聘平台单独申请")) {
                    log.info("检测到单独投递申请弹窗，关闭中...");
                    // 关闭弹窗
                    Locator closeButton = page.locator("#app > div > div.post > div > div > div.j_result > div > div:nth-child(2) > div > div:nth-child(2) > div:nth-child(2) > div > div.el-dialog__header > button > i");
                    if (closeButton.count() > 0) {
                        closeButton.click();
                        log.info("成功关闭单独投递申请弹窗");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("未找到单独投递申请弹窗或处理失败: {}", e.getMessage());
        }
    }

    /**
     * 跳转到指定页码。
     * <p>
     * T0.1 取证（2026-10-17，docs/evidence/job51-pagination-nature.md）：新版搜索页没有
     * 旧版的 #jump_page 输入框，分页是标准 el-pagination（btn-prev / li.number 页码 /
     * btn-next，末页时 btn-next 带 disabled 属性）。因此实现为「点下一页」语义：
     * pageNum 仅作计数与到位校验（li.number.active 文本应变为 pageNum），禁用/点不动即结束。
     */
    private boolean jumpToPage(int pageNum) {
        for (int retry = 0; retry < 3; retry++) {
            try {
                if (shouldStop()) {
                    return false;
                }

                // 跳页前先尝试关闭可能遮挡操作的弹框
                closeAnyModalOverlays();

                Locator nextBtn = page.locator(".el-pagination button.btn-next");
                if (nextBtn.count() == 0) {
                    log.warn("[51job] 翻到第{}页失败：未找到 btn-next（.el-pagination 命中 {} 个）",
                            pageNum, page.locator(".el-pagination").count());
                    dumpPageStructure("翻页控件缺失");
                    return false;
                }

                // 末页收敛：Element-UI 会在最后一页给 btn-next 加 disabled
                String disabled = nextBtn.first().getAttribute("disabled");
                if (disabled != null && !"false".equals(disabled)) {
                    log.info("[51job] 已是末页（btn-next 禁用），本关键词翻页结束");
                    return false;
                }

                // 记录点击前的活动页码，点击后用其变化校验是否真的翻过去了
                String beforeActive = "";
                try {
                    Locator active = page.locator(".el-pagination li.number.active");
                    if (active.count() > 0) beforeActive = active.first().textContent();
                } catch (Exception ignored) {}

                nextBtn.first().click();
                PlaywrightUtil.sleep(2);

                // 列表刷新后活动页码会移动到下一格；等不到就按重试处理
                try {
                    page.waitForSelector(".el-pagination li.number.active",
                            new Page.WaitForSelectorOptions().setTimeout(10_000));
                } catch (Exception ignored) {}
                boolean arrived = false;
                try {
                    Locator active = page.locator(".el-pagination li.number.active");
                    if (active.count() > 0) {
                        String nowActive = active.first().textContent();
                        arrived = !nowActive.equals(beforeActive);
                    }
                } catch (Exception ignored) {}

                if (arrived) {
                    log.info("[51job] 成功翻到第{}页", pageNum);
                    page.evaluate("window.scrollTo(0, 0)");
                    PlaywrightUtil.sleep(2);
                    return true;
                }

                log.warn("[51job] 点击下一页后活动页码未变化（重试第{}次）", retry + 1);
                if (checkAccessVerification()) {
                    return false;
                }
                PlaywrightUtil.sleep(1);
            } catch (Exception e) {
                log.warn("翻到第{}页失败，重试第{}次: {}", pageNum, retry + 1, e.getMessage());
                PlaywrightUtil.sleep(1);

                // 检查是否出现异常，如果出现则刷新页面
                if (checkAccessVerification()) {
                    return false;
                }
            }
        }
        log.warn("[51job] 翻到第{}页重试耗尽，结束本关键词翻页", pageNum);
        return false;
    }

    /**
     * 统一关闭可能出现的弹框覆盖层（ElementUI/VanPopup 等）。
     */
    private void closeAnyModalOverlays() {
        try {
            boolean closedOnce = false;
            for (int t = 0; t < 3; t++) {
                boolean closedThisRound = false;
                Locator headerClose = page.locator("button.el-dialog__headerbtn, button[aria-label='Close']");
                if (headerClose.count() > 0 && headerClose.first().isVisible()) {
                    try {
                        headerClose.first().click(new Locator.ClickOptions().setForce(true).setTimeout(2000));
                        closedThisRound = true;
                    } catch (Exception ignored) {}
                }
                // 直接点击关闭图标或其父按钮
                Locator iconClose = page.locator("i.el-dialog__close.el-icon.el-icon-close");
                if (iconClose.count() > 0 && iconClose.first().isVisible()) {
                    try {
                        iconClose.first().evaluate("el => el.parentElement && el.parentElement.click()");
                        closedThisRound = true;
                    } catch (Exception ignored) {}
                }
                Locator okBtn = page.locator(".el-dialog__footer button:has-text('确定'), .el-message-box__btns button:has-text('确定')");
                if (okBtn.count() > 0 && okBtn.first().isVisible()) {
                    okBtn.first().click();
                    closedThisRound = true;
                }
                // JS 一次性点击所有可能的关闭按钮，作为强兜底
                if (!closedThisRound) {
                    try {
                        page.evaluate("document.querySelectorAll('button.el-dialog__headerbtn, button[aria-label=\\'Close\\']').forEach(b=>b.click())");
                    } catch (Exception ignored) {}
                }
                Locator popupClose = page.locator(".van-popup__close-icon, .van-icon-cross");
                if (popupClose.count() > 0 && popupClose.first().isVisible()) {
                    popupClose.first().click();
                    closedThisRound = true;
                }
                if (!closedThisRound) break;
                closedOnce = true;
                PlaywrightUtil.sleep(1);
            }
            if (closedOnce) {
                // 等待弹层移除
                try { page.waitForSelector(".el-dialog__wrapper, .van-popup", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.DETACHED).setTimeout(2000)); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.debug("关闭弹框覆盖层失败: {}", e.getMessage());
        }
    }

    /**
     * 检查是否需要登录
     */
    private boolean checkNeedLogin() {
        try {
            // 取值改走 first()：命中多个时 textContent() 会因严格模式直接抛错，被上层误判成“已登录”
            Locator loginElement = page.locator("//a[contains(@class, 'uname')]");
            int count = loginElement.count();
            if (count > 0) {
                String text = loginElement.first().textContent();
                boolean needLogin = text != null && text.contains("登录");
                log.info("[51job] 登录检测：a.uname 命中 {} 个，文案={}，判定需要登录={}", count, text, needLogin);
                return needLogin;
            }
            log.info("[51job] 登录检测：未命中 a.uname 登录入口，视为已登录");
            return false;
        } catch (Exception e) {
            log.warn("[51job] 登录检测异常，按已登录继续: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检测 51job 页面是否出现“日投递上限”提示的浮框（短暂存在，需及时检查）。
     */
    private boolean detectDailyLimitToast51job() {
        try {
            String[] kws = new String[]{
                    "今日投递太多", "您今日投递太多", "休息一下明天再来", "达到上限", "次数过多"
            };
            for (String kw : kws) {
                Locator textToast = page.locator("text=" + kw);
                if (textToast.count() > 0 && textToast.first().isVisible()) {
                    return true;
                }
            }
            Locator msg = page.locator(".el-message, .el-message--info, .toast, .message, div[role='alert'], .el-notification__content");
            if (msg.count() > 0) {
                java.util.List<String> texts = new java.util.ArrayList<>();
                try { texts = msg.allInnerTexts(); } catch (Exception ignored) {}
                for (String t : texts) {
                    if (t == null) continue;
                    String tt = t.replace('\n', ' ').trim();
                    for (String kw : kws) {
                        if (tt.contains(kw)) return true;
                    }
                }
            }
            Object foundObj = page.evaluate("() => { const kws = ['今日投递太多','您今日投递太多','休息一下明天再来','达到上限','次数过多']; const bodyText = document.body ? (document.body.innerText || '') : ''; return kws.some(k=>bodyText.includes(k)); }");
            if (foundObj instanceof Boolean) {
                return (Boolean) foundObj;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查是否出现访问验证
     */
    private boolean checkAccessVerification() {
        try {
            Locator wafTitle = page.locator("//p[@class='waf-nc-title']");
            Locator wafScript = page.locator("script[name^='aliyunwaf_']");
            Locator verifyText = page.locator("text=访问验证, text=请按住滑块");
            if ((wafTitle.count() > 0 && wafTitle.first().isVisible()) || wafScript.count() > 0 || verifyText.count() > 0) {
                log.error("出现访问验证，需要手动处理");
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检测 51job 页面是否显示“无职位/没有符合条件的职位”等提示。
     */
    private boolean detectNoJobs51job() {
        try {
            String[] kws = new String[]{
                    "暂无职位", "没有符合条件的职位", "暂无符合条件职位", "暂无符合职位", "暂无相关职位"
            };
            for (String kw : kws) {
                Locator t = page.locator("text=" + kw);
                if (t.count() > 0 && t.first().isVisible()) {
                    return true;
                }
            }
            // 常见空态容器（若存在则进一步通过文本确认）
            Locator empty = page.locator(".el-empty, .empty, .no-result, .no_res");
            if (empty.count() > 0) {
                java.util.List<String> texts = new java.util.ArrayList<>();
                try { texts = empty.allInnerTexts(); } catch (Exception ignored) {}
                for (String t : texts) {
                    if (t == null) continue;
                    String tt = t.replace('\n', ' ').trim();
                    for (String kw : kws) {
                        if (tt.contains(kw)) return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构建搜索URL
     */
    private String buildSearchUrl(String keyword) {
        StringBuilder url = new StringBuilder(BASE_URL);
        url.append(JobUtils.appendListParam("jobArea", config.getJobArea()));
        url.append(JobUtils.appendListParam("salary", config.getSalary()));
        // 中文关键词必须编码后再拼进 URL：直接拼接会因 GBK/UTF-8 不一致被站点按乱码检索。
        // 这里只编码 keyword，jobArea/salary 是纯数字码（appendListParam 已处理“不限”）。
        url.append("&keyword=").append(java.net.URLEncoder.encode(
                keyword == null ? "" : keyword, java.nio.charset.StandardCharsets.UTF_8));
        // T3 职位类型筛选（路线 A，T0.3 取证：页面 URL 的 jobType 参数会被 SPA 透传给 search-pc，
        // 01=全职、02=兼职、03=实习、空=全部）。空值不拼参数，行为与 T3 之前完全一致。
        String jn = config.getJobNature() == null ? "" : config.getJobNature().trim();
        if (!jn.isEmpty()) {
            url.append("&jobType=").append(java.net.URLEncoder.encode(jn, java.nio.charset.StandardCharsets.UTF_8));
            log.info("[51job] 已应用职位类型筛选: jobType={}（{}）", jn,
                    "01=全职/02=兼职/03=实习");
        }
        return url.toString();
    }

    /**
     * 格式化时长
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format("%d小时%d分钟", hours, minutes % 60);
        } else if (minutes > 0) {
            return String.format("%d分钟%d秒", minutes, seconds % 60);
        } else {
            return String.format("%d秒", seconds);
        }
    }

    /**
     * 发送进度消息
     */
    private void sendProgress(String message, Integer current, Integer total) {
        if (progressCallback != null) {
            progressCallback.accept(message, current, total);
        }
    }

    /**
     * 检查是否应该停止
     */
    private boolean shouldStop() {
        return shouldStopCallback != null && shouldStopCallback.get();
    }

    /**
     * 从JSON文本中提取jobId列表
     */
    private List<Long> extractJobIdsFromJson(String json) {
        List<Long> jobIds = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return jobIds;
        }
        
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            
            // 兼容多种列表命名
            com.fasterxml.jackson.databind.JsonNode list = root.path("data").path("items");
            if (!list.isArray()) list = root.path("data").path("jobList");
            if (!list.isArray()) list = root.path("data").path("list");
            if (!list.isArray()) list = root.path("data").path("jobs");
            if (!list.isArray()) list = root.path("resultbody").path("job").path("items");
            if (!list.isArray()) list = root.path("job").path("items");
            if (!list.isArray()) list = root.path("resultbody").path("items");
            
            if (!list.isArray()) {
                return jobIds;
            }
            
            // 提取每个jobId
            for (com.fasterxml.jackson.databind.JsonNode item : list) {
                com.fasterxml.jackson.databind.JsonNode jobIdNode = item.path("jobId");
                if (!jobIdNode.isMissingNode() && !jobIdNode.isNull()) {
                    try {
                        Long jobId = jobIdNode.asLong();
                        if (jobId != null && jobId > 0) {
                            jobIds.add(jobId);
                        }
                    } catch (Exception e) {
                        // 忽略单个解析失败
                    }
                }
            }
            
        } catch (Exception e) {
            log.warn("[51job] 解析JSON提取jobId失败: {}", e.getMessage());
        }
        
        return jobIds;
    }
}
