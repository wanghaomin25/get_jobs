package com.getjobs.application.init;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.ZhilianOptionEntity;
import com.getjobs.application.mapper.ZhilianOptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
 

/**
 * 应用启动时：
 * 1) 创建 zhilian_option 表（若不存在）
 * 2) 从 city.json 解析城市码并插入（若不存在）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZhilianOptionInitializer implements CommandLineRunner {

    private final DataSource dataSource;
    private final ZhilianOptionMapper zhilianOptionMapper;

    @Override
    public void run(String... args) throws Exception {
        ensureTableExists();
        ensureJobNatureColumn();
        seedJobNatureOptions();
        // 已改为从数据库获取城市选项，移除基于 city.json 的种子插入逻辑
    }

    /**
     * zhilian_config 补 job_nature 列（幂等）。
     * 该表由既有库提供、项目内没有独立建表语句，这里做一次性补列，保证新装库与老库结构一致。
     */
    private void ensureJobNatureColumn() {
        try (Connection conn = dataSource.getConnection()) {
            boolean exists = false;
            try (Statement stmt = conn.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery("PRAGMA table_info(zhilian_config)")) {
                while (rs.next()) {
                    if ("job_nature".equalsIgnoreCase(rs.getString("name"))) {
                        exists = true;
                        break;
                    }
                }
            }
            if (!exists) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE zhilian_config ADD COLUMN job_nature VARCHAR(50)");
                    log.info("zhilian_config 已补充 job_nature 列");
                }
            }
        } catch (Exception e) {
            log.warn("检查/补充 zhilian_config.job_nature 列失败: {}", e.getMessage());
        }
    }

    /**
     * 工作性质字典（幂等）。路线 B 按选项文本点击，故 code 与 name 相同；「不限」的 code 为空串。
     * 选项集来自 2026-10-17 实测的站点面板，日后站点改文案只需改这里/字典表。
     */
    private void seedJobNatureOptions() {
        insertIfNotExists("jobNature", "不限", "", 0);
        insertIfNotExists("jobNature", "全职", "全职", 1);
        // 2026-10-19 真机探针复核：站点面板真实选项是「兼职/临时」（半角斜杠），
        // 2026-10-17 记录的「兼职·临时」（间隔点）有误。站点文本与字典本就一致，
        // 匹配兼容性由 ZhiLian.applyJobNatureFilter 的别名兜底（/ ↔ ·）保证。
        // 本迁移把误改的「兼职·临时」回迁为站点真实文案。
        fixLegacyJobNatureText("兼职·临时", "兼职/临时");
        insertIfNotExists("jobNature", "兼职/临时", "兼职/临时", 2);
        insertIfNotExists("jobNature", "实习", "实习", 3);
        insertIfNotExists("jobNature", "校园", "校园", 4);
    }

    /** 把旧版「兼职/临时」字典行与用户配置里的引用迁移为站点真实文案「兼职·临时」 */
    private void fixLegacyJobNatureText(String legacy, String fixed) {
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                int n = st.executeUpdate(
                        "UPDATE zhilian_option SET name='" + fixed + "', code='" + fixed
                                + "' WHERE type='jobNature' AND (name='" + legacy + "' OR code='" + legacy + "')");
                if (n > 0) {
                    log.info("已迁移 jobNature 字典旧文案 {} -> {}（{} 行）", legacy, fixed, n);
                }
                int c = st.executeUpdate(
                        "UPDATE zhilian_config SET job_nature='" + fixed + "' WHERE job_nature='" + legacy + "'");
                if (c > 0) {
                    log.info("已迁移 zhilian_config.job_nature 旧文案 {} -> {}（{} 行）", legacy, fixed, c);
                }
            }
        } catch (Exception e) {
            log.warn("迁移 jobNature 旧文案失败: {}", e.getMessage());
        }
    }

    private void ensureTableExists() {
        String ddl = "CREATE TABLE IF NOT EXISTS zhilian_option (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " type VARCHAR(50)," +
                " name VARCHAR(100)," +
                " code VARCHAR(100)," +
                " sort_order INTEGER," +
                " created_at DATETIME," +
                " updated_at DATETIME" +
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            log.info("确保 zhilian_option 表已存在");
        } catch (Exception e) {
            log.warn("创建 zhilian_option 表失败: {}", e.getMessage());
        }
    }

    // 已移除基于 city.json 的种子插入方法

    private void insertIfNotExists(String type, String name, String code, int sortOrder) {
        try {
            ZhilianOptionEntity existing = zhilianOptionMapper.selectOne(
                    new QueryWrapper<ZhilianOptionEntity>()
                            .eq("type", type)
                            .eq("code", code)
                            .last("LIMIT 1")
            );
            if (existing != null) return;

            LocalDateTime now = LocalDateTime.now();
            ZhilianOptionEntity e = new ZhilianOptionEntity();
            e.setType(type);
            e.setName(name);
            e.setCode(code);
            e.setSortOrder(sortOrder);
            e.setCreatedAt(now);
            e.setUpdatedAt(now);
            zhilianOptionMapper.insert(e);
        } catch (Exception ex) {
            log.warn("插入选项失败 type={} code={}: {}", type, code, ex.getMessage());
        }
    }

    // 已移除 CityItem 记录类型
}