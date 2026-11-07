package larder.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Boot 4 版本兼容性冒烟测试（V01-T01 R2）。
 *
 * <p>失败条件（可证伪）：固定版本组合无法编译/启动上下文、Flyway 无法执行迁移、
 * MyBatis-Plus 无法映射 BaseMapper、Flyway 的 MySQL 模块缺失、S3 客户端缺失。
 * 本测试用 H2 代替 MySQL，只证明 Java 侧组合兼容，不证明 MySQL/Redis/MQ/Meili/S3 的真实行为。
 */
@SpringBootTest
class VersionCompatibilityTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CompatProbeMapper probeMapper;

    @Test
    @DisplayName("Boot 4.1.2 + MyBatis-Plus 3.5.18(boot4 starter) + Flyway 12 可启动并完成真实查询")
    void contextBootsAndMapsDatabase() {
        // 1) 上下文真的起来了，且数据源、Flyway、Mapper 都在
        assertThat(context.getBean(org.springframework.jdbc.core.JdbcTemplate.class)).isNotNull();
        assertThat(context.getBean(Flyway.class)).isNotNull();
        assertThat(probeMapper).isNotNull();

        // 2) Flyway 迁移确实执行过：种子行由 V1__compat_init.sql 插入
        Integer seed = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class)
                .queryForObject("select count(*) from compat_probe", Integer.class);
        assertThat(seed).isEqualTo(1);

        // 3) MyBatis-Plus BaseMapper 真能执行 SQL（不是只装配了接口）
        Long count = probeMapper.selectCount(null);
        assertThat(count).isEqualTo(1L);
        List<CompatProbe> rows = probeMapper.selectList(null);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getNote()).isEqualTo("flyway+mapper ok");

        // 4) 版本自证：断言实际加载的构件，避免"锁了但没生效"
        String bootVersion = SpringBootVersion.getVersion();
        assertThat(bootVersion).isEqualTo("4.1.2");

        String flywayJar = jarNameOf(Flyway.class);
        assertThat(flywayJar).isEqualTo("flyway-core-12.4.0.jar");

        String mybatisPlusJar = jarNameOf(com.baomidou.mybatisplus.core.mapper.BaseMapper.class);
        assertThat(mybatisPlusJar).startsWith("mybatis-plus-core-3.5.18");

        String springFrameworkJar = jarNameOf(org.springframework.context.ApplicationContext.class);
        assertThat(springFrameworkJar).isEqualTo("spring-context-7.0.10.jar");

        System.out.println("[COMPAT] spring-boot=" + bootVersion);
        System.out.println("[COMPAT] spring-framework=" + springFrameworkJar);
        System.out.println("[COMPAT] flyway=" + flywayJar);
        System.out.println("[COMPAT] mybatis-plus=" + mybatisPlusJar);
        System.out.println("[COMPAT] s3-client="
                + jarNameOf(software.amazon.awssdk.services.s3.S3Client.class));
        System.out.println("[COMPAT] java=" + System.getProperty("java.version"));
    }

    @Test
    @DisplayName("flyway-mysql 模块与 S3 客户端类在类路径上（缺任一即失败）")
    void optionalModulesPresent() throws Exception {
        // Flyway 10+ 把 MySQL 方言拆到 flyway-mysql，缺失时真实 MySQL 迁移会在运行期失败
        Class<?> mysqlType = Class.forName("org.flywaydb.database.mysql.MySQLDatabaseType");
        assertThat(mysqlType).isNotNull();

        // S3 客户端：确认 AWS SDK v2 可用（adapter 由 V02 实现）
        assertThat(software.amazon.awssdk.services.s3.S3Client.builder()).isNotNull();
    }

    /** 取类实际加载的 jar 文件名，用于证明运行期版本与锁定版本一致。 */
    private static String jarNameOf(Class<?> type) {
        String path = type.getProtectionDomain().getCodeSource().getLocation().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
