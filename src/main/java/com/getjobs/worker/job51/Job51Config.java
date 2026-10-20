package com.getjobs.worker.job51;

import com.getjobs.worker.utils.JobUtils;
import lombok.Data;
import lombok.SneakyThrows;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 * 前程无忧自动投递简历
 */
@Data
public class Job51Config {


    /**
     * 搜索关键词列表
     */
    private List<String> keywords;

    /**
     * 城市编码
     */
    private List<String> jobArea;

    /**
     * 薪资范围
     */
    private List<String> salary;

    /**
     * 职位类型/工作性质过滤（空=不过滤；取值为 job51_option type=jobNature 的 code：
     * ''=全部、01=全职、02=兼职、03=实习——T0.3 真机取证，2026-10-17）
     */
    private String jobNature;


    // 注意：已改为在 Job51JobService 中通过 ConfigService 构建配置
    // 保留空的 init 以兼容旧调用，但建议不要再使用
    @SneakyThrows
    public static Job51Config init() {
        throw new UnsupportedOperationException("请在 Job51JobService 中通过 ConfigService 构建配置");
    }

}
