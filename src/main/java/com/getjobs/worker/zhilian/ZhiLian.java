package com.getjobs.worker.zhilian;

import com.getjobs.application.entity.ZhilianJobDataEntity;
import com.getjobs.application.service.ZhilianService;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
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
 * 智联招聘自动投递 - Playwright版本
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class ZhiLian {

    @Setter
    private Page page;

    @Setter
    private ZhilianConfig config;

    @Setter
    private ProgressCallback progressCallback;

    @Setter
    private Supplier<Boolean> shouldStopCallback;

    private final List<Job> resultList = new ArrayList<>();
    private boolean isLimit = false;
    private int maxPage = 500;

    /** 新版搜索页（2026-10-19 取证）：/sou/* 已 302 到 /jobs/，「不限」城市不能带 jl（jl=0=黄石） */
    private static final String JOBS_URL = "https://www.zhaopin.com/jobs/";

    private final ZhilianService zhilianService;

    private static class PageJob {
        int index;
        String jobId;
        String jobTitle;
        String companyName;
        String jobLink;

        PageJob(int index, String jobId, String jobTitle, String companyName, String jobLink) {
            this.index = index;
            this.jobId = jobId;
            this.jobTitle = jobTitle;
            this.companyName = companyName;
            this.jobLink = jobLink;
        }
    }

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
        log.info("智联招聘准备工作开始...");
        resultList.clear();
        isLimit = false;
        log.info("智联招聘准备工作完成");
    }

    /**
     * 执行投递任务
     * @return 投递数量
     */
    public int execute() {
        log.info("智联招聘投递任务开始...");
        long startTime = System.currentTimeMillis();

        // Z2：逐关键词统计结果，结束时向用户汇报（含无结果关键词名单）
        List<String> emptyKeywords = new ArrayList<>();
        int totalKeywords = config.getKeywords() == null ? 0 : config.getKeywords().size();
        int keywordIndex = 0;

        try {
            // 遍历所有关键词进行投递
            for (String keyword : config.getKeywords()) {
                if (shouldStop() || isLimit) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    break;
                }

                String baseUrl = buildBaseUrl(1);
                deliverByKeyword(keyword, baseUrl, keywordIndex, totalKeywords, emptyKeywords);
                keywordIndex++;
            }

            long duration = System.currentTimeMillis() - startTime;
            String message = String.format("智联招聘投递完成，共投递%d个岗位，用时%s",
                resultList.size(), formatDuration(duration));
            log.info(message);
            sendProgress(message, null, null);

            // Z2/Z7：关键词维度的结果汇总，让"没结果"与"卡死"可区分
            if (!emptyKeywords.isEmpty()) {
                String summary = String.format("搜索完成：%d 个关键词，其中 %d 个无结果（%s）",
                        totalKeywords, emptyKeywords.size(), String.join("、", emptyKeywords));
                log.info(summary);
                sendProgress(summary, totalKeywords, totalKeywords);
            }

            if (!resultList.isEmpty()) {
                log.info("新投递公司如下:");
                resultList.forEach(job -> log.info(job.toString()));
            } else {
                log.info("未投递新的岗位...");
            }

        } catch (Exception e) {
            log.error("智联招聘投递过程出现异常", e);
            sendProgress("投递出现异常: " + e.getMessage(), null, null);
        }

        return resultList.size();
    }

    /**
     * 按关键词投递
     * @param keywordIndex 当前关键词序号（0 起，Z7 进度主轴）
     * @param totalKeywords 关键词总数
     * @param emptyKeywords 无结果关键词收集器（execute 结束时汇总上报）
     */
    private void deliverByKeyword(String keyword, String baseUrl, int keywordIndex, int totalKeywords, List<String> emptyKeywords) {
        if (isLimit) {
            return;
        }

        try {
            log.info("开始投递关键词: {}", keyword);
            sendProgress("正在搜索关键词: " + keyword, keywordIndex, totalKeywords);

            // 导航到搜索页面（路径参数：jl+城市码 + p1 + sl）
            page.navigate(baseUrl);
            PlaywrightUtil.sleep(2);

            // 在搜索框输入关键词并触发搜索（Enter键更稳健）
            try {
                Locator keywordInput = findKeywordInput();
                if (keywordInput == null || keywordInput.count() == 0) {
                    log.warn("未找到搜索输入框，跳过关键词: {}", keyword);
                    emptyKeywords.add(keyword);
                    sendProgress("关键词「" + keyword + "」未找到搜索框，已跳过", keywordIndex, totalKeywords);
                    return;
                }
                keywordInput.fill("");
                keywordInput.fill(keyword);
                try { keywordInput.press("Enter"); } catch (Exception ignored) {}
                PlaywrightUtil.sleep(2);
            } catch (Exception e) {
                log.warn("搜索框输入关键词失败，跳过当前关键词: {}", e.getMessage());
                emptyKeywords.add(keyword);
                sendProgress("关键词「" + keyword + "」搜索失败，已跳过", keywordIndex, totalKeywords);
                return;
            }

            // 等待岗位列表加载（新站在用 div.job-card，旧的 div.joblist-box__item 已不存在）
            try {
                page.waitForSelector("div.job-card",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
            } catch (Exception e) {
                // Z2：无结果是正常业务分支，必须让用户看见，不能静默跳过
                log.warn("等待岗位列表超时，关键词【{}】无职位结果，跳过", keyword);
                emptyKeywords.add(keyword);
                sendProgress("关键词「" + keyword + "」未搜索到职位，已跳过", keywordIndex, totalKeywords);
                return;
            }

            // 搜索完成、列表就绪后，按配置应用「工作性质」原生筛选。
            // 必须放在翻页循环之前；每个关键词都要点一次，因为每次 navigate 回搜索页都会重置筛选。
            // Z1：筛选后可能把列表筛空（如「实习」配在无实习岗的关键词上），必须显式处理，
            // 否则会走「翻页超时 → reload → 筛选被重置 → 投递未筛选岗位」的错误链路。
            if (!applyJobNatureFilter()) {
                emptyKeywords.add(keyword);
                sendProgress("关键词「" + keyword + "」按工作性质筛选后无岗位，已跳过", keywordIndex, totalKeywords);
                return;
            }

            // 遍历所有页面：仅以“下一页”按钮禁用状态为主，最多50页
            int pageNum = 1;
            while (pageNum <= 50) {
                if (shouldStop() || isLimit) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    return;
                }

                log.info("开始投递【{}】关键词，第【{}】页...", keyword, pageNum);
                // Z7：进度主轴为关键词（当前/总数=关键词），页码只保留在文案里，百分比不再失真
                sendProgress(String.format("关键词【%s】正在投递第%d页", keyword, pageNum), keywordIndex, totalKeywords);

                // 等待岗位列表出现
                try {
                    page.waitForSelector("div.job-card",
                        new Page.WaitForSelectorOptions().setTimeout(10_000));
                } catch (Exception e) {
                    // Z1：超时不代表页面假死——先看列表是否还在，再决定是否 reload。
                    // 核心不变式：reload 会重置纯前端的「工作性质」筛选，刷新后必须重放筛选再投递，
                    // 否则会在未筛选列表上投递不符合配置的岗位。
                    int cardCount = page.locator("div.job-card").count();
                    if (cardCount > 0) {
                        log.info("等待岗位列表超时但列表仍存在（{} 张卡片），继续投递", cardCount);
                    } else if (isNextDisabled()) {
                        log.info("无岗位且下一页不可点，结束本关键词翻页");
                        break;
                    } else {
                        log.warn("岗位列表为空但仍有下一页，刷新页面并重放筛选");
                        page.reload();
                        PlaywrightUtil.sleep(1);
                        if (!applyJobNatureFilter()) {
                            log.info("刷新并重放筛选后仍无岗位，结束本关键词翻页");
                            break;
                        }
                    }
                }

                // 投递当前页面
                if (!deliverCurrentPage(keyword)) {
                    break;
                }

                PlaywrightUtil.sleep(2);

                // 判断是否还有下一页
                if (isNextDisabled()) {
                    log.info("下一页按钮不可点击，结束翻页");
                    break;
                }

                // 点击下一页
                Locator nextBtn = page.locator("a.soupager__btn:has-text(\"下一页\")");
                if (nextBtn.count() > 0) {
                    try { nextBtn.first().scrollIntoViewIfNeeded(); } catch (Exception ignored) {}
                    nextBtn.first().click();
                    PlaywrightUtil.sleep(2);
                    // 翻页可能重置筛选面板（2026-10-17 实测第2页出现非实习岗），重申工作性质筛选；
                    // 函数幂等：若站点保留了筛选选中态则自动跳过。返回 false 表示筛选后无岗位。
                    if (!applyJobNatureFilter()) {
                        log.info("第{}页按工作性质筛选后无岗位，结束翻页", pageNum + 1);
                        break;
                    }
                    pageNum++;
                } else {
                    log.info("未找到下一页按钮，结束翻页");
                    break;
                }
            }

            log.info("关键词【{}】投递完成", keyword);
        } catch (Exception e) {
            log.error("投递关键词【{}】时出现异常", keyword, e);
        }
    }

    /** 取列表第一张卡的岗位标题，用于判断筛选后列表是否已刷新 */
    private String firstCardTitle(Locator cards) {
        try {
            if (cards.count() == 0) {
                return "";
            }
            String t = cards.first().locator("span.vue-clamp__text").first().textContent();
            return t == null ? "" : t.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 搜索完成后按配置点击「工作性质」筛选（config.jobNature 存选项文本；空=不限，跳过）。
     *
     * 选择器与交互全部来自 2026-10-17 实测（见 docs/evidence/zhilian-job-nature.md）：
     * 筛选栏是 div.filter-select-box，其中 label 为「工作性质」的那个盒子内，
     * 点 __trigger 展开后 <li class="filter-select-box__item"> 里内联 5 个选项
     * （不限/全职/兼职·临时/实习/校园），选中态加 filter-select-box__item--selected。
     * 点击后 URL 不变（纯前端过滤），因此以「选中态出现 + 列表重新渲染」作为完成信号。
     *
     * 任何一步失败都只告警并返回 true：严禁因筛选失败抛异常中断整个关键词的投递。
     *
     * @return true=列表有岗位可投递（含未配置筛选/筛选失败兜底继续的情况）；
     *         false=筛选生效后确认无岗位（调用方应快速跳过，避免走 reload 误投递链路）
     */
    private boolean applyJobNatureFilter() {
        String name = config.getJobNature();
        if (name == null || name.isEmpty()) {
            return true;
        }
        try {
            Locator box = page.locator("div.filter-select-box:has-text('工作性质')");
            if (box.count() == 0) {
                log.warn("未找到「工作性质」筛选栏，本关键词不筛选继续: {}", name);
                return true;
            }

            // 记录点击前的首个岗位标题：筛选是纯前端过滤（URL 不变、卡片数可能仍是 20），
            // 只能靠列表内容变化判断是否已刷新 —— 2026-10-17 实测首卡标题约 1 秒内就会变。
            Locator cards = page.locator("div.job-card");
            String beforeFirst = firstCardTitle(cards);

            // 展开筛选面板（SSR 里面板是空的，选项在展开后由前端渲染）
            Locator trigger = box.first().locator("div.filter-select-box__trigger");
            trigger.first().click(new Locator.ClickOptions().setTimeout(5_000));
            page.waitForTimeout(800);

            // Z-fix：字典历史文案是「兼职/临时」（半角斜杠），站点真实选项是「兼职·临时」（间隔点），
            // setHasText 精确包含匹配不到 → 勾选从未生效。这里对两种分隔符做别名兜底。
            String altName = name.replace('/', '·').replace('·', '/');

            // 幂等守卫：展开后若该选项已处于选中态（翻页未重置筛选的场景），
            // 收回面板直接返回——重复点击可能把选中态 toggle 掉，等于取消筛选。
            Locator alreadySelected = box.first().locator("li.filter-select-box__item--selected")
                    .filter(new Locator.FilterOptions().setHasText(name));
            if (alreadySelected.count() == 0) {
                alreadySelected = box.first().locator("li.filter-select-box__item--selected")
                        .filter(new Locator.FilterOptions().setHasText(altName));
            }
            if (alreadySelected.count() > 0) {
                try { trigger.first().click(new Locator.ClickOptions().setTimeout(3_000)); } catch (Exception ignored) {}
                log.info("工作性质筛选【{}】仍在生效，跳过重复应用", name);
                return true;
            }

            Locator item = box.first().locator("li.filter-select-box__item")
                    .filter(new Locator.FilterOptions().setHasText(name));
            if (item.count() == 0) {
                item = box.first().locator("li.filter-select-box__item")
                        .filter(new Locator.FilterOptions().setHasText(altName));
            }
            if (item.count() == 0) {
                log.warn("未找到工作性质筛选项【{}】（含别名【{}】），本关键词不筛选继续", name, altName);
                return true;
            }

            item.first().click(new Locator.ClickOptions().setTimeout(5_000));

            // 以「该选项进入选中态」作为点击生效信号（含别名兜底）
            try {
                Locator selected = box.first().locator("li.filter-select-box__item--selected")
                        .filter(new Locator.FilterOptions().setHasText(name));
                if (selected.count() == 0) {
                    selected = box.first().locator("li.filter-select-box__item--selected")
                            .filter(new Locator.FilterOptions().setHasText(altName));
                }
                selected.first().waitFor(new Locator.WaitForOptions().setTimeout(5_000));
            } catch (Exception ignored) {}

            // Z1：确认列表重排——列表一旦变空即可提前得出「无岗位」结论（原 8 秒循环在
            // 空结果场景必然空转到底，把每个空关键词拖进 10s+15s 超时链）
            boolean refreshed = false;
            for (int w = 0; w < 8; w++) {
                page.waitForTimeout(1_000);
                if (cards.count() == 0) {
                    // 列表被筛空：再给一次 5 秒二次确认，防「慢加载」被误判为空结果
                    try {
                        page.waitForSelector("div.job-card",
                                new Page.WaitForSelectorOptions().setTimeout(5_000));
                    } catch (Exception ignored) {}
                    if (cards.count() == 0) {
                        log.info("工作性质筛选【{}】后无岗位", name);
                        return false;
                    }
                    refreshed = true;
                    break;
                }
                String nowFirst = firstCardTitle(cards);
                if (!nowFirst.isEmpty() && !nowFirst.equals(beforeFirst)) {
                    refreshed = true;
                    break;
                }
            }
            if (!refreshed) {
                log.warn("应用工作性质筛选【{}】后列表内容未见变化（可能本就无匹配岗位）", name);
                if (cards.count() == 0) {
                    return false;
                }
            }
            try {
                page.waitForSelector("div.job-card",
                        new Page.WaitForSelectorOptions().setTimeout(5_000));
            } catch (Exception ignored) {}

            log.info("已应用工作性质筛选: {}", name);
            return true;
        } catch (Exception e) {
            log.warn("工作性质筛选点击失败（不影响投递）: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 投递当前页面的所有职位
     * @return 是否继续投递下一页
     */
    private boolean deliverCurrentPage(String keyword) {
        try {
            page.waitForSelector("div.job-card",
                    new Page.WaitForSelectorOptions().setTimeout(15000));

            if (checkIsLimit()) {
                sendProgress("用户取消投递或已达上限", null, null);
                return false;
            }

            Locator cards = page.locator("div.job-card");
            int count = cards.count();
            log.info("检测到当前页岗位数量: {}", count);

            // 新版智联（2026-10 实测）是「列表 + 右侧详情」的分栏页：
            // 列表卡片上没有投递按钮，标题也不是链接（是 span.vue-clamp__text），
            // 点标题后详情区异步加载，详情区里才有唯一的 button.job-detail-summary__apply。
            // 因此逐张卡片串行处理：点卡片 -> 读详情区 -> 取 jobId -> 去重/入库 -> 投递 -> 关弹窗。
            for (int i = 0; i < count; i++) {
                if (shouldStop()) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    return false;
                }

                Locator card = page.locator("div.job-card").nth(i);

                // 卡片字段：标题 span.vue-clamp__text；薪资 span.job-card__salary；
                // 公司 a.job-card__company-name；地点 .job-card__location span；
                // 经验/学历没有独立字段，只在 .job-card__seo-meta 里以「经验不限 · 本科」形式出现。
                String jobTitle = trimOrEmpty(safeGetText(card, "span.vue-clamp__text"));
                if (jobTitle.isEmpty()) {
                    jobTitle = trimOrEmpty(safeGetText(card, "a.job-card__name"));
                }
                String salary = trimOrEmpty(safeGetText(card, "span.job-card__salary"));
                String location = trimOrEmpty(safeGetText(card, ".job-card__location span"));
                String companyName = trimOrEmpty(safeGetText(card, "a.job-card__company-name"));
                String seoMetaRaw = safeGetText(card, ".job-card__seo-meta");
                String seoMeta = seoMetaRaw == null ? "" : seoMetaRaw.replace("\n", " ").trim();
                String experience = "";
                String degree = "";
                if (!seoMeta.isEmpty()) {
                    String[] metaParts = seoMeta.split("·");
                    experience = metaParts[0].trim();
                    if (metaParts.length > 1) {
                        degree = metaParts[1].trim();
                    }
                }

                if (jobTitle.isEmpty()) {
                    log.info("第 {} 张卡片缺少标题，跳过", i + 1);
                    continue;
                }

                // 1) 点标题前先记下详情区当前的 /jobdetail/ 链接。
                //    详情区是异步更新的，只凭「立即投递按钮出现」判断会读到上一张卡的链接
                //    （实测踩过：5 个不同岗位解析出同一个 jobId，被误判「已存在」而漏投）。
                String prevHref = "";
                try {
                    Locator preLink = page.locator("a[href*='/jobdetail/']");
                    if (preLink.count() > 0) {
                        String h = preLink.first().getAttribute("href");
                        if (h != null) prevHref = h;
                    }
                } catch (Exception ignored) {}

                // 2) 点标题让详情区加载这个岗位（短超时，避免被弹窗遮挡时干等 30 秒）
                try {
                    Locator titleSpan = card.locator("span.vue-clamp__text");
                    if (titleSpan.count() == 0) {
                        log.info("岗位【{}】未找到可点击标题，跳过", jobTitle);
                        continue;
                    }
                    titleSpan.first().click(new Locator.ClickOptions().setTimeout(5_000));
                } catch (Exception clickEx) {
                    log.warn("点击岗位【{}】失败，跳过: {}", jobTitle, clickEx.getMessage());
                    continue;
                }

                // 3) 等详情区的「立即投递」
                Locator applyBtn = page.locator("button.job-detail-summary__apply");
                try {
                    applyBtn.first().waitFor(new Locator.WaitForOptions().setTimeout(15_000));
                } catch (Exception noBtn) {
                    log.warn("岗位【{}】详情区未出现「立即投递」按钮，跳过（可能已投递/已下线）", jobTitle);
                    continue;
                }

                // 4) 取 jobId：首张卡本来就是详情区当前展示的岗位（卡片带 job-card--active），直接读；
                //    其余卡片必须等详情区的 /jobdetail/ 链接真正切换后再读，否则会拿到上一张的 id。
                String detailHref = null;
                int maxPoll = (i == 0) ? 1 : 20;
                for (int w = 0; w < maxPoll; w++) {
                    try {
                        Locator detailLink = page.locator("a[href*='/jobdetail/']");
                        if (detailLink.count() > 0) {
                            String h = detailLink.first().getAttribute("href");
                            if (h != null && !h.isEmpty() && (i == 0 || !h.equals(prevHref))) {
                                detailHref = h;
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                    // 用毫秒级等待（PlaywrightUtil.sleep 的单位是秒，不能用于轮询间隔）
                    page.waitForTimeout(500);
                }

                if (detailHref == null) {
                    log.warn("岗位【{}】详情区未切换到该岗位（链接未变化），跳过以避免写入错误的 jobId", jobTitle);
                    continue;
                }

                String jobId = extractJobIdFromLink(detailHref);
                if (jobId == null || jobId.isEmpty()) {
                    log.warn("岗位【{}】详情区链接无法解析 jobId（href={}），跳过", jobTitle, detailHref);
                    continue;
                }

                // 4) 去重：已入库的历史岗位连投递一起跳过（重跑不会重复投递）
                boolean exists = false;
                try { exists = zhilianService.existsByJobId(jobId); } catch (Exception checkEx) {
                    // 查询失败按“不存在”处理：宁可重复采集，勿漏新岗
                    log.warn("查询jobId是否已存在失败: {}", checkEx.getMessage());
                }
                if (exists) {
                    log.info("jobId已存在，跳过采集与投递：jobId={}，title={}", jobId, jobTitle);
                    continue;
                }

                // 5) 先入库（未投递），保证即使投递失败也留下采集记录
                try {
                    ZhilianJobDataEntity entity = new ZhilianJobDataEntity();
                    entity.setJobId(jobId);
                    entity.setJobTitle(jobTitle);
                    entity.setJobLink("https://www.zhaopin.com/jobdetail/" + jobId + ".htm");
                    entity.setSalary(salary);
                    entity.setLocation(location);
                    entity.setExperience(experience);
                    entity.setDegree(degree);
                    entity.setCompanyName(companyName);
                    entity.setDeliveryStatus("未投递");
                    zhilianService.insertJob(entity);
                    log.info("已保存岗位数据：jobId={}，title={}，company={}", jobId, jobTitle, companyName);
                } catch (Exception ex) {
                    log.warn("保存岗位数据失败: {}", ex.getMessage());
                }

                // 6) 点投递并校验结果（未确认/登录引导/失败都要留下日志，不再静默记成“已投递”）
                try {
                    applyBtn.first().click(new Locator.ClickOptions().setTimeout(5_000));
                    PlaywrightUtil.sleep(1);

                    boolean confirmed = false;
                    try {
                        Locator modal = page.locator(".deliver-greeting-modal");
                        if (modal.count() > 0) {
                            String modalText = modal.first().innerText();
                            if (modalText != null && (modalText.contains("已向对方发送") || modalText.contains("投递成功"))) {
                                log.info("投递弹窗确认成功: {}", jobTitle);
                                confirmed = true;
                            }
                        }
                    } catch (Exception ignored) {}

                    if (!confirmed) {
                        try {
                            String body = page.locator("body").innerText();
                            if (body != null && (body.contains("已向对方发送") || body.contains("投递成功") || body.contains("申请成功"))) {
                                log.info("投递结果确认成功: {}", jobTitle);
                                confirmed = true;
                            } else if (body != null && body.contains("登录")) {
                                log.warn("投递后出现登录引导，本岗位可能未投递成功: {}", jobTitle);
                            } else {
                                log.info("投递结果未确认（页面无明确成功标识）: {}", jobTitle);
                            }
                        } catch (Exception ignored) {}
                    }

                    zhilianService.markDeliveredByJobId(jobId);
                    log.info("已标记投递：jobId={}，title={}，company={}", jobId, jobTitle, companyName);

                    // 真实投递成功才计入 resultList：完成消息与 execute() 返回值都依赖它
                    Job applied = new Job();
                    applied.setJobName(jobTitle);
                    applied.setCompanyName(companyName);
                    resultList.add(applied);

                    // 7) 关掉打招呼弹窗，避免遮罩挡住下一张卡片的点击。
                    //    顺序：先点「留在此页」（弹窗正常关闭路径）→ 再退化为关闭图标 → 最后 Escape。
                    //    注意：PlaywrightUtil.sleep 的参数是【秒】，需要毫秒级等待必须用 page.waitForTimeout。
                    //    这里全部用短超时/毫秒等待，任何一步都不许干等（曾因误用 sleep(300)=300 秒，
                    //    导致「停止投递」完全失效、任务像卡死一样）。
                    try {
                        Locator modal = page.locator(".deliver-greeting-modal");
                        if (modal.count() > 0) {
                            Locator stayBtn = page.locator(".deliver-greeting-modal__btn--secondary");
                            if (stayBtn.count() > 0) {
                                try {
                                    stayBtn.first().click(new Locator.ClickOptions().setTimeout(2_000));
                                } catch (Exception ignored) {}
                            }
                            page.waitForTimeout(400);
                            if (page.locator(".deliver-greeting-modal").count() > 0) {
                                Locator closeBtn = page.locator(".deliver-greeting-modal__close");
                                if (closeBtn.count() > 0) {
                                    try {
                                        closeBtn.first().click(new Locator.ClickOptions().setTimeout(2_000));
                                    } catch (Exception ignored) {}
                                }
                            }
                            page.waitForTimeout(300);
                            if (page.locator(".deliver-greeting-modal").count() > 0) {
                                try { page.keyboard().press("Escape"); } catch (Exception ignored) {}
                                page.waitForTimeout(300);
                            }
                        }
                    } catch (Exception ignored) {}
                } catch (Exception applyEx) {
                    log.warn("投递失败，继续下一个岗位: {}", applyEx.getMessage());
                }

                if (checkIsLimit()) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            log.error("投递当前页面失败", e);
            try {
                saveCurrentPageHtml();
                log.info("已保存当前页面到 src/main/java/com/getjobs/worker/zhilian/page.html 以便排查");
            } catch (Exception saveEx) {
                log.warn("保存当前页面HTML失败: {}", saveEx.getMessage());
            }
            return false;
        }
    }

    /**
     * 处理投递弹窗
     */
    private void handleDeliveryDialog() {
        try {
            // 获取所有页面
            List<Page> pages = page.context().pages();
            if (pages.size() < 2) {
                log.warn("未检测到投递弹窗页面");
                return;
            }

            // 切换到最新打开的页面（投递弹窗）
            Page dialogPage = pages.get(pages.size() - 1);

            try {
                // 检查投递结果（CSS选择器）
                Locator deliverResult = dialogPage.locator("div.deliver-dialog");
                if (deliverResult.count() > 0) {
                    String text = deliverResult.textContent();
                    if (text != null && text.contains("申请成功")) {
                        log.info("岗位申请成功！");
                    }
                }
            } catch (Exception e) {
                log.debug("读取投递结果失败: {}", e.getMessage());
            }

            // 关闭弹窗
            try {
                Locator closeButton = dialogPage.locator("img[title='close-icon']");
                if (closeButton.count() > 0) {
                    closeButton.click();
                    PlaywrightUtil.sleep(1);
                }
            } catch (Exception e) {
                log.debug("关闭投递弹窗失败: {}", e.getMessage());
                if (checkIsLimit()) {
                    return;
                }
            }

            // 投递相似职位
            deliverSimilarJobs(dialogPage);

            // 关闭弹窗页面
            try {
                dialogPage.close();
            } catch (Exception e) {
                log.debug("关闭弹窗页面失败: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.error("处理投递弹窗失败", e);
        }
    }

    /**
     * 投递相似职位
     */
    private void deliverSimilarJobs(Page dialogPage) {
        try {
            // 全选相似职位
            Locator selectAllCheckbox = dialogPage.locator("div.applied-select-all input");
            if (selectAllCheckbox.count() > 0 && !selectAllCheckbox.isChecked()) {
                selectAllCheckbox.click();
                PlaywrightUtil.sleep(1);
            }

            // 获取相似职位列表
            Locator jobs = dialogPage.locator("div.recommend-job");
            int jobCount = jobs.count();

            if (jobCount == 0) {
                log.info("没有匹配到相似职位");
                return;
            }

            // 记录相似职位信息
            for (int i = 0; i < jobCount; i++) {
                try {
                    Locator jobElement = jobs.nth(i);
                    String jobName = safeGetText(jobElement, ".recommend-job__position");
                    String salary = safeGetText(jobElement, "span.recommend-job__demand__salary");
                    String years = safeGetText(jobElement, "span.recommend-job__demand__experience").replace("\n", " ");
                    String education = safeGetText(jobElement, "span.recommend-job__demand__educational").replace("\n", " ");
                    String companyName = safeGetText(jobElement, ".recommend-job__cname");
                    String companyTag = safeGetText(jobElement, ".recommend-job__demand__cinfo").replace("\n", " ");

                    Job job = new Job();
                    job.setJobName(jobName);
                    job.setSalary(salary);
                    job.setCompanyTag(companyTag);
                    job.setCompanyName(companyName);
                    job.setJobInfo(years + "·" + education);

                    log.info("投递【{}】公司【{}】岗位，薪资【{}】，要求【{}·{}】，规模【{}】",
                        companyName, jobName, salary, years, education, companyTag);
                    resultList.add(job);
                } catch (Exception e) {
                    log.debug("记录相似职位信息失败: {}", e.getMessage());
                }
            }

            // 点击投递按钮
            Locator postButton = dialogPage.locator("div.applied-select-all button");
            if (postButton.count() > 0) {
                postButton.click();
                PlaywrightUtil.sleep(2);
                log.info("相似职位投递成功！");
            }

        } catch (Exception e) {
            log.error("投递相似职位异常: {}", e.getMessage());
        }
    }

    /**
     * 检查是否达到投递上限
     */
    private boolean checkIsLimit() {
        try {
            PlaywrightUtil.sleep(1);
            Locator result = page.locator("//div[@class='a-job-apply-workflow']");
            if (result.count() > 0) {
                String text = result.textContent();
                if (text != null && text.contains("达到上限")) {
                    log.info("今日投递已达上限！");
                    isLimit = true;
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 设置最大页数（已废弃：使用“下一页不可点击”+最多50页）
     */
    private void setMaxPages() {
        // 保留方法避免旧调用报错，但不再依赖输入框改页码
        maxPage = 50;
    }

    /**
     * 构建基础搜索URL（不含关键词，由搜索框触发）
     *
     * 2026-10-19 真机取证（/api/playwright/probe-zhilian-search）：
     * - /sou/jl{code}/p1 会 302 到 /jobs/?jl={code}（pN 被丢弃），非零城市正常且地区回显城市名；
     * - 【jl=0 会落到「黄石」而不是「不限」】——配置选「不限」时搜索必空（黄石无 python 岗，
     *   用户截图复现：/jobs/?jl=0&kw=python → 「暂未找到符合你要求的职位」）；
     * - 不带 jl 直接访问 /jobs/ 是「全国」语义，20 张卡片正常返回。
     * 因此「不限」城市改走不带 jl 的 /jobs/ 路径；pN 段在 302 后本来就会被丢弃，不再拼接。
     */
    private String buildBaseUrl(int pageNum) {
        StringBuilder url = new StringBuilder(JOBS_URL);
        String cityCode = config.getCityCode();
        boolean hasCity = cityCode != null && !cityCode.isEmpty() && !"0".equals(cityCode.trim());
        if (hasCity) {
            url.append("?jl=").append(cityCode.trim());
        }
        String salaryParam = JobUtils.appendParam("sl", config.getSalary());
        if (!salaryParam.isEmpty()) {
            url.append(url.indexOf("?") >= 0 ? "&" : "?").append(salaryParam.substring(1));
        }
        return url.toString();
    }

    /**
     * 查找搜索关键词输入框（多候选选择器，提高鲁棒性）
     */
    private Locator findKeywordInput() {
        String[] candidates = new String[] {
            "input[placeholder*='职位']",
            "input[placeholder*='公司']",
            "input[name='kw']",
            "input[type='text']",
            "input[class*='search'], input[class*='sou'], input[class*='input']"
        };
        for (String sel : candidates) {
            try {
                Locator lc = page.locator(sel);
                if (lc != null && lc.count() > 0) {
                    return lc.first();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * 判断“下一页”是否不可点击
     */
    private boolean isNextDisabled() {
        try {
            Locator nextBtn = page.locator("a.soupager__btn:has-text(\"下一页\")");
            if (nextBtn.count() == 0) return true;
            String cls = null;
            try { cls = nextBtn.first().getAttribute("class"); } catch (Exception ignored) {}
            String disabledAttr = null;
            try { disabledAttr = nextBtn.first().getAttribute("disabled"); } catch (Exception ignored) {}
            if (cls != null && cls.contains("soupager__btn--disable")) return true;
            return disabledAttr != null && ("disabled".equalsIgnoreCase(disabledAttr) || "true".equalsIgnoreCase(disabledAttr));
        } catch (Exception e) {
            return false;
        }
    }

    private String extractJobIdFromLink(String link) {
        if (link == null) return null;
        try {
            int i = link.indexOf("jobdetail/");
            int j = link.lastIndexOf(".htm");
            if (i >= 0 && j > i) {
                return link.substring(i + "jobdetail/".length(), j);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 安全获取文本内容
     */
    private String safeGetText(Locator parent, String selector) {
        try {
            Locator element = parent.locator(selector);
            if (element.count() > 0) {
                String t = element.textContent();
                // trim 首尾空白：智联卡片文本节点带换行+缩进，不处理会污染入库字段（实测 company_name）
                return t == null ? "" : t.trim();
            }
        } catch (Exception e) {
            log.debug("获取文本失败: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 去掉首尾空白并折叠换行/连续空格。
     * textContent 常带模板缩进（如公司名取到 "\n        某某公司\n      "），入库前必须清洗。
     */
    private String trimOrEmpty(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\n", " ").replace("\r", " ").replaceAll("\\s+", " ").trim();
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
     * 将当前页面内容保存到项目内 page.html，覆盖原文件
     */
    private void saveCurrentPageHtml() {
        try {
            String html = page.content();
            java.nio.file.Path path = java.nio.file.Paths.get("src/main/java/com/getjobs/worker/zhilian/page.html");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.write(path, html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("写入 page.html 失败", e);
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
}
