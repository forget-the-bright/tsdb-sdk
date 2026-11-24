package io.github.forget_the_bright.tsdb.pspace.it;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.pspace.PspaceClient;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceHttp;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceModels;
import io.github.forget_the_bright.tsdb.pspace.internal.PspaceNativeApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pSpace 造数集成测试（默认不执行，双重门禁）。
 *
 * <p>开启方式：{@code mvn -o -pl tsdb-sdk-pspace -Dpspace.it=true
 * -Dpspace.it.allow-write=true test}，且 base URL 主机必须属于
 * {127.0.0.1, localhost, 172.16.176.14}。</p>
 *
 * <p><strong>现有点位优先：</strong>先 {@code /Refresh} 再探测；只要环境里存在任何
 * 可读位号，本测试直接跳过，不建点、不写数、不删点。只有探测为空时才走
 * {@code /AddPoint → /UpdRealData → /RealData → /HistData → /DelPoint} 全链路，
 * 且 {@code finally} 只删除本测试创建的点。</p>
 *
 * <p>厂商接口细节（AddPoint 字段集、UpdRealData 是否落历史、DelPoint 是否可用）尚未
 * 全部现场确认；任一环节被厂商拒绝时用 {@link Assumptions} 跳过并打印原始响应，
 * 记录为「未执行，待厂商确认」，不伪造通过。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
@Tag("pspace-it")
class PspaceWriteIT {

    private static PspaceClient client;
    private static PspaceNativeApi nativeApi;
    private static List<PspaceModels.Point> existingPoints;

    @BeforeAll
    static void connect() throws IOException {
        Assumptions.assumeTrue(PspaceItSupport.enabled(),
                "pSpace IT is disabled; pass -Dpspace.it=true to enable it");
        Assumptions.assumeTrue(PspaceItSupport.writeAllowed(),
                "pSpace write gate is closed; pass -Dpspace.it.allow-write=true on the mock host");

        Properties file = PspaceItSupport.loadProperties();
        String baseUrl = PspaceItSupport.baseUrl(file);
        Assumptions.assumeTrue(PspaceItSupport.writeHostAllowed(baseUrl),
                "refusing to create points on non-mock host: " + baseUrl);

        client = PspaceItSupport.client(file, true);
        nativeApi = client.unwrap(PspaceNativeApi.class);
        existingPoints = PspaceItSupport.probePoints(nativeApi);
        System.out.println("pSpace write IT: baseUrl=" + baseUrl
                + ", existingPoints=" + existingPoints.size());
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void createsWritesReadsAndDeletesOnlyWhenNoPointExists() {
        List<String> readable = PspaceItSupport.readableTags(existingPoints, 1);
        Assumptions.assumeTrue(readable.isEmpty(),
                "环境已有点位，按约定跳过建点：" + readable);

        String suffix = String.valueOf(System.currentTimeMillis());
        String longName = "/IT_PSPACE/IT_" + suffix;
        List<Long> created = new ArrayList<Long>();
        try {
            long pid = addPoint(longName);
            created.add(Long.valueOf(pid));
            nativeApi.refresh();

            TagInfo resolved = client.tagCatalog().getTag(longName);
            Assumptions.assumeTrue(resolved != null,
                    "未执行：/AddPoint 已返回 pid=" + pid + "，但刷新后 getTag 仍解析不到，"
                            + "待厂商确认缓存/字段语义");

            String writeResponse = nativeApi.updRealData(updRealDataBody(pid, 12.34d, 192));
            System.out.println("pSpace write IT: /UpdRealData -> " + writeResponse);

            List<PspaceModels.RealValue> values =
                    nativeApi.realData(Collections.singletonList(longName));
            assertEquals(1, values.size(), "/RealData must return the point just written");
            assertNotNull(values.get(0).value, "written real value must be readable");
            assertEquals(12.34d, Double.parseDouble(String.valueOf(values.get(0).value)), 0.0001d);

            Instant now = Instant.now();
            List<PspaceModels.HistRow> rows = nativeApi.histData(
                    Collections.singletonList(Long.valueOf(pid)), now.minus(Duration.ofHours(1)),
                    now.plus(Duration.ofMinutes(1)));
            boolean saved = false;
            for (PspaceModels.HistRow row : rows) {
                if (!row.values.isEmpty()) {
                    saved = true;
                }
            }
            System.out.println("pSpace write IT: /UpdRealData lands in history=" + saved
                    + (saved ? "" : "（未执行该项：模拟环境未确认实时写入落历史）"));
        } finally {
            if (!created.isEmpty()) {
                String deleted = nativeApi.delPoint(created);
                System.out.println("pSpace write IT: /DelPoint " + created + " -> " + deleted);
                assertDeleted(deleted, created.get(0).longValue());
                assertTrue(nativeApi.pointsByIds(created).isEmpty(),
                        "created points must be gone after /DelPoint");
            }
        }
    }

    private static long addPoint(String longName) {
        JSONObject point = new JSONObject();
        point.put("Name", longName.substring(longName.lastIndexOf('/') + 1));
        point.put("LongName", longName);
        point.put("Desc", "tsdb-sdk pSpace IT");
        point.put("ParentId", 0);
        point.put("TagType", 2);
        point.put("DataType", 11);
        point.put("EngUnit", "MPa");
        point.put("His_IsSave", 1);
        JSONArray body = new JSONArray();
        body.add(point);
        String response = nativeApi.addPoint(body.toJSONString());
        System.out.println("pSpace write IT: /AddPoint -> " + response);
        JSONArray results = PspaceHttp.asArray(response);
        Assumptions.assumeFalse(results.isEmpty(),
                "未执行：/AddPoint 未返回结果，待厂商确认建点字段集: " + response);
        JSONObject result = results.getJSONObject(0);
        Integer code = result.getInteger("code");
        Long pid = firstLong(result, "pid", "TagId");
        Assumptions.assumeTrue(code != null && code.intValue() == 0 && pid != null,
                "未执行：/AddPoint 被拒绝（code=" + code + ", mesg=" + result.getString("mesg")
                        + "），待厂商确认建点字段集");
        return pid.longValue();
    }

    private static void assertDeleted(String response, long pid) {
        JSONArray results = PspaceHttp.asArray(response);
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            Long itemPid = firstLong(item, "pid", "TagId");
            if (itemPid != null && itemPid.longValue() == pid) {
                Integer code = item.getInteger("code");
                assertEquals(0, code == null ? -1 : code.intValue(),
                        "/DelPoint failed: " + item.toJSONString());
                return;
            }
        }
        // 有的实现返回空数组表示成功；空响应按成功处理，但仍要由 pointsByIds 复核。
        assertTrue(results.isEmpty() || PspaceItSupport.blank(response),
                "unexpected /DelPoint response: " + response);
    }

    private static String updRealDataBody(long pid, double value, int quality) {
        JSONObject item = new JSONObject();
        item.put("tagid", pid);
        item.put("pv", value);
        item.put("qy", quality);
        JSONArray body = new JSONArray();
        body.add(item);
        return body.toJSONString();
    }

    private static Long firstLong(JSONObject object, String... keys) {
        for (String key : keys) {
            Long value = object.getLong(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}