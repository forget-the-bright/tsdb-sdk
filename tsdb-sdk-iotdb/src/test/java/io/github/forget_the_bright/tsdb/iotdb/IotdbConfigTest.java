package io.github.forget_the_bright.tsdb.iotdb;

import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.Grouping;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IotdbConfig} 里批量相关键的离线单测：改名不留别名、新键默认值与非法值拒绝。
 *
 * <p>这一族键决定的是「一条 SQL 覆盖多大范围、多少个表达式、撞预算了怎么补采」，
 * 现网配置里残留旧键或写错档位，都必须变成<b>启动失败</b>：静默忽略旧键等于
 * 「上限还是旧的那个值」，而调用方读到的是新默认值——差别只在报表数字对不对。</p>
 */
class IotdbConfigTest {

    private static IotdbConfig configWith(String... keyValues) {
        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setType("iotdb");
        instance.setBaseUrl("http://127.0.0.1:18080");
        instance.getAuth().setUsername("root");
        instance.getAuth().setPassword("root");
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            options.put(keyValues[i], keyValues[i + 1]);
        }
        instance.setOptions(options);
        SecurityConfig security = new SecurityConfig();
        security.setAllowInsecureTransport(true);
        return new IotdbConfig("iotdb_config_test", instance, security);
    }

    @Test
    void newBatchKeysCarryTheDocumentedDefaults() {
        IotdbConfig config = configWith();
        assertEquals(200, config.getQueryBatchSize());
        assertEquals(4, config.getQueryParallelism());
        assertEquals(10_000, config.getQueryBatchRowBudget());
        assertEquals(32, config.getQueryBatchCompletionMax());
        assertEquals(IotdbConfig.DEFAULT_QUERY_BATCH_SIZE, config.getQueryBatchSize());
        assertEquals(IotdbConfig.DEFAULT_QUERY_PARALLELISM, config.getQueryParallelism());
    }

    @Test
    void batchScopeDefaultsToDatabaseNowThatTheDualRunPassed() {
        // 默认档位是库级：这是 L3 同库自对自双跑对数换来的结论，不是拍脑袋——
        // 同一实例、同一批位号、同一窗口，query-batch-size: 1（逐表达式，等价于改造前的
        // 逐位号取数）、device、database 三种配置各跑一遍，逐序列逐点 0 容差，
        // 最新值/RAW/等间隔/聚合/整窗增量/差分/插值七个族全部 diffs=0，
        // 见 IotdbTierParityIT（docs/14 §8.3 记了实测数字）。
        // 这条断言是双向哨兵：无依据地翻回 DEVICE 或翻成 ROOT 都会在这里撞红。
        assertEquals(Grouping.DATABASE, IotdbConfig.DEFAULT_QUERY_BATCH_SCOPE);
        assertEquals(Grouping.DATABASE, configWith().getQueryBatchScope());
    }

    @Test
    void batchScopeAcceptsAllThreeTiersCaseInsensitively() {
        assertEquals(Grouping.DATABASE, configWith("query-batch-scope", "database").getQueryBatchScope());
        assertEquals(Grouping.DATABASE, configWith("query-batch-scope", " DATABASE ").getQueryBatchScope());
        assertEquals(Grouping.ROOT, configWith("query-batch-scope", "root").getQueryBatchScope());
        assertEquals(Grouping.DEVICE, configWith("query-batch-scope", "Device").getQueryBatchScope());
    }

    @Test
    void batchScopeRejectsATierItCannotMap() {
        // 按名字兜底到默认档会让现场以为自己在用回退档、实际跑的是库级批量。
        TsdbConfigurationException failure = assertThrows(TsdbConfigurationException.class,
                () -> configWith("query-batch-scope", "db"));
        assertTrue(failure.getMessage().contains("database, root or device"), failure.getMessage());
        assertThrows(TsdbConfigurationException.class, () -> configWith("query-batch-scope", "table"));
    }

    @Test
    void legacyCurrentKeysFailStartupInsteadOfBeingIgnored() {
        TsdbConfigurationException size = assertThrows(TsdbConfigurationException.class,
                () -> configWith("current-batch-size", "50"));
        assertTrue(size.getMessage().contains("current-batch-size"), size.getMessage());
        assertTrue(size.getMessage().contains("query-batch-size"), size.getMessage());

        TsdbConfigurationException parallelism = assertThrows(TsdbConfigurationException.class,
                () -> configWith("current-parallelism", "8"));
        assertTrue(parallelism.getMessage().contains("current-parallelism"), parallelism.getMessage());
        assertTrue(parallelism.getMessage().contains("query-parallelism"), parallelism.getMessage());
    }

    @Test
    void renamedKeysTakeTheirValues() {
        IotdbConfig config = configWith("query-batch-size", "50", "query-parallelism", "8");
        assertEquals(50, config.getQueryBatchSize());
        assertEquals(8, config.getQueryParallelism());
    }

    @Test
    void batchSizingRejectsNonPositiveAndNonNumericValues() {
        assertThrows(TsdbConfigurationException.class, () -> configWith("query-batch-size", "0"));
        assertThrows(TsdbConfigurationException.class, () -> configWith("query-parallelism", "-1"));
        assertThrows(TsdbConfigurationException.class, () -> configWith("query-batch-row-budget", "many"));
        assertThrows(TsdbConfigurationException.class, () -> configWith("query-batch-completion-max", "0"));
    }

    @Test
    void rowBudgetAndCompletionCapAreReadBackForTheRawCompletionPass() {
        IotdbConfig config = configWith("query-batch-row-budget", "500", "query-batch-completion-max", "3");
        assertEquals(500, config.getQueryBatchRowBudget());
        assertEquals(3, config.getQueryBatchCompletionMax());
    }
}
