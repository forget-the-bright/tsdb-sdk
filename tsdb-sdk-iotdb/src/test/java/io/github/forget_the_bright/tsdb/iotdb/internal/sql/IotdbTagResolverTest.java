package io.github.forget_the_bright.tsdb.iotdb.internal.sql;

import io.github.forget_the_bright.tsdb.common.config.AuthConfig;
import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;
import io.github.forget_the_bright.tsdb.iotdb.IotdbConfig;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IotdbTagResolverTest {

    @Test
    void prependsConfiguredDefaultDatabaseToRelativeTag() {
        IotdbTagResolver resolver = new IotdbTagResolver(config("root.factory"));
        assertEquals("root.factory.device01.temperature",
                resolver.resolvePath("device01.temperature").getAbsolutePath());
    }

    @Test
    void keepsFullRootPathAndExtractsItsOwnDatabase() {
        IotdbTagResolver resolver = new IotdbTagResolver(config("root.factory"));
        assertEquals("root.other.line1.device02.pressure",
                resolver.resolvePath("root.other.line1.device02.pressure").getAbsolutePath());
        assertEquals("root.other", resolver.resolvePath("root.other.line1.device02.pressure").getDatabase());
    }

    @Test
    void rejectsRelativeTagWithoutDefaultDatabase() {
        IotdbTagResolver resolver = new IotdbTagResolver(config(null));
        assertThrows(TsdbDataException.class, () -> resolver.resolvePath("device01.temperature"));
    }

    @Test
    void rejectsPathThatHasNoDatabaseAndDeviceLayers() {
        IotdbTagResolver resolver = new IotdbTagResolver(config("root.factory"));
        assertThrows(TsdbDataException.class, () -> resolver.resolvePath("temperature"));
        assertThrows(TsdbDataException.class, () -> resolver.resolvePath("root.factory.temperature"));
    }

    @Test
    void resolvesRelativeAndAbsoluteCatalogPrefixes() {
        IotdbTagResolver resolver = new IotdbTagResolver(config("root.factory"));
        assertEquals("root.factory.device01", resolver.resolvePrefix("device01.*"));
        assertEquals("root.factory.device01", resolver.resolvePrefix("device01.**"));
        assertEquals("root.other.device01", resolver.resolvePrefix("root.other.device01.**"));
        assertEquals("root.factory", resolver.resolvePrefix(null));
    }

    @Test
    void rejectsRelativeCatalogPrefixWithoutDefaultDatabase() {
        IotdbTagResolver resolver = new IotdbTagResolver(config(null));
        assertThrows(TsdbDataException.class, () -> resolver.resolvePrefix("device01.*"));
    }

    @Test
    void rejectsIllegalDefaultDatabaseFormat() {
        assertThrows(TsdbConfigurationException.class, () -> config("factory"));
        assertThrows(TsdbConfigurationException.class, () -> config("root.factory.device"));
    }

    @Test
    void mappedTagWinsAndItsHierarchyComesFromTheTable() {
        Map<String, String> mapping = new LinkedHashMap<String, String>();
        // 业务位号只有两段，映射表把它落到三层设备下：切分依据必须是映射值，不是原始位号
        mapping.put("BGZH1A.X-PV", "root.factory.线1.BGZH1A.X-PV");
        IotdbTagResolver resolver = new IotdbTagResolver(config("root.factory"), TagPathMapper.of(mapping));

        IotdbTagPath path = resolver.resolvePath("BGZH1A.X-PV");
        assertEquals("root.factory.线1.BGZH1A.X-PV", path.getAbsolutePath());
        assertEquals("root.factory.线1.BGZH1A", path.getDevicePath());
        assertEquals("X-PV", path.getMeasurement());
        assertEquals(1L, resolver.mappingStats().getMappedTotal());
        assertEquals(0L, resolver.mappingStats().getFallbackTotal());
    }

    @Test
    void mappingValueMayAlreadyBeQuotedAndIsStoredCanonical() {
        Map<String, String> mapping = new LinkedHashMap<String, String>();
        mapping.put("PT-1001A", "root.factory.`101`.`PT-1001A`");
        IotdbTagResolver resolver = new IotdbTagResolver(config("root.factory"), TagPathMapper.of(mapping));

        assertEquals("root.factory.101.PT-1001A", resolver.resolvePath("PT-1001A").getAbsolutePath());
        assertEquals("`101`.`PT-1001A`", resolver.resolvePath("PT-1001A").fromDatabase());
    }

    @Test
    void quotedTagIsAcceptedAndParsedLikeAPlainOne() {
        IotdbTagResolver resolver = new IotdbTagResolver(config("root.factory"));
        assertEquals("root.factory.RL.FCS0107.TI-1071815_PV",
                resolver.resolvePath("RL.FCS0107.`TI-1071815_PV`").getAbsolutePath());
        assertEquals(1L, resolver.mappingStats().getFallbackTotal());
    }

    @Test
    void unmappedTagFallsBackToDefaultDatabaseAndIsCounted() {
        IotdbTagResolver resolver = new IotdbTagResolver(config("root.factory"),
                TagPathMapper.of(Collections.<String, String>emptyMap()));
        assertEquals("root.factory.device01.temperature",
                resolver.resolvePath("device01.temperature").getAbsolutePath());
        IotdbTagResolver.TagMappingStats stats = resolver.mappingStats();
        assertEquals(1L, stats.getFallbackTotal());
        assertEquals(0L, stats.getMappedTotal());
        assertTrue(stats.getFallbackTags().contains("device01.temperature"));
    }

    @Test
    void rejectsMappingValueThatIsNotAFullPath() {
        Map<String, String> mapping = new LinkedHashMap<String, String>();
        mapping.put("A.PV", "factory.d.A.PV");
        assertThrows(TsdbDataException.class, () -> TagPathMapper.of(mapping));
    }

    @Test
    void rejectsMappingValueShallowerThanFourLevelsAtLoadTime() {
        Map<String, String> mapping = new LinkedHashMap<String, String>();
        // 缺测点层：以前要到查询时才报错，现在装载即失败，并把出问题的键列出来
        mapping.put("A.PV", "root.factory.d1");
        TsdbDataException failure = assertThrows(TsdbDataException.class, () -> TagPathMapper.of(mapping));
        assertTrue(failure.getMessage().contains("A.PV"), failure.getMessage());
    }

    @Test
    void resolvePathReturnsTheCanonicalTextForSingleTagEntry() {
        // 回退档 require-query-service: false 的逐位号直连走 resolvePath，文本必须是规范形
        IotdbTagResolver resolver = new IotdbTagResolver(config("root.factory"));
        assertEquals("root.factory.RL.FCS0107.TI-1071815_PV",
                resolver.resolvePath("RL.FCS0107.`TI-1071815_PV`").getAbsolutePath());
        assertEquals(resolver.resolvePath("RL.FCS0107.TI-1071815_PV").getAbsolutePath(),
                resolver.resolvePath("RL.FCS0107.`TI-1071815_PV`").getAbsolutePath());
    }

    @Test
    void rejectsMultiValueTagAndTellsTheCallerToSplitIt() {
        IotdbTagResolver resolver = new IotdbTagResolver(config("root.factory"));
        TsdbDataException comma = assertThrows(TsdbDataException.class,
                () -> resolver.resolvePath("d1.A,d2.B"));
        assertTrue(comma.getMessage().contains("multiple values"), comma.getMessage());
        assertTrue(comma.getMessage().contains("separate element"), comma.getMessage());
        assertThrows(TsdbDataException.class, () -> resolver.resolvePath("d1.A;d2.B"));
        // 反引号包裹的整串同样是多位号串，不能因为“看起来已处理过”就放行
        assertThrows(TsdbDataException.class, () -> resolver.resolvePath("`d1`.`A`,`d2`.`B`"));
    }

    @Test
    void rejectsUnresolvedTemplateAndChinesePlaceholder() {
        IotdbTagResolver resolver = new IotdbTagResolver(config("root.factory"));
        assertThrows(TsdbDataException.class, () -> resolver.resolvePath("${device}.PV"));
        assertThrows(TsdbDataException.class, () -> resolver.resolvePath("待补充位号"));
    }

    @Test
    void chineseTagIsUsableWhenRegisteredInMappingTable() {
        Map<String, String> mapping = new LinkedHashMap<String, String>();
        mapping.put("待补充位号", "root.factory.d1.PV");
        IotdbTagResolver resolver = new IotdbTagResolver(config("root.factory"), TagPathMapper.of(mapping));
        assertEquals("root.factory.d1.PV", resolver.resolvePath("待补充位号").getAbsolutePath());
        assertEquals(1L, resolver.mappingStats().getMappedTotal());
    }

    private static IotdbConfig config(String defaultDatabase) {
        TsdbInstanceConfig instance = new TsdbInstanceConfig();
        instance.setType("iotdb");
        instance.setBaseUrl("http://127.0.0.1:18080");
        AuthConfig auth = new AuthConfig();
        auth.setUsername("root");
        auth.setPassword("root");
        instance.setAuth(auth);
        if (defaultDatabase != null) {
            Map<String, Object> options = new LinkedHashMap<String, Object>();
            options.put("default-database", defaultDatabase);
            instance.setOptions(options);
        }
        SecurityConfig security = new SecurityConfig();
        security.setAllowInsecureTransport(true);
        return new IotdbConfig("iotdb_test", instance, security);
    }
}
