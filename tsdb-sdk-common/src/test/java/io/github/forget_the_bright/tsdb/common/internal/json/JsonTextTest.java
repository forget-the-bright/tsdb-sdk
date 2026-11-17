package io.github.forget_the_bright.tsdb.common.internal.json;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTextTest {

    @Test
    void parsesObjectAndReadsScalars() {
        JSONObject object = JsonText.parseObject("{\"name\":\"p1\",\"value\":12,\"ok\":true}");
        assertEquals("p1", JsonText.text(object, "name"));
        assertEquals("12", JsonText.text(object, "value"));
        assertEquals(12L, JsonText.longValue(object, "value"));
        assertEquals(12, JsonText.integer(object, "value"));
        assertEquals("true", JsonText.text(object, "ok"));
    }

    @Test
    void appliesLenientTextSemantics() {
        JSONObject object = JsonText.parseObject(
                "{\"missing\":null,\"nested\":{\"a\":1},\"list\":[1,2]}");
        assertNull(JsonText.text(object, "absent"));
        assertNull(JsonText.text(object, "missing"));
        assertEquals("", JsonText.text(object, "nested"));
        assertEquals("", JsonText.text(object, "list"));
        assertNull(JsonText.text(null, "any"));
    }

    @Test
    void readsFieldNamesCaseInsensitively() {
        JSONObject object = JsonText.parseObject("{\"AccessToken\":\"t\",\"ExpiresIn\":\"60\"}");
        assertEquals("t", JsonText.textIgnoreCase(object, "accessToken"));
        assertEquals("60", JsonText.textIgnoreCase(object, "expiresin"));
        assertEquals(60L, JsonText.longValue(object, "ExpiresIn"));
    }

    @Test
    void convertsArraysAndRejectsWrongShape() {
        JSONArray array = JsonText.array(JsonText.parse("[1,2,3]"));
        assertEquals(3, array.size());
        assertEquals(2, array.getIntValue(1));
        assertThrows(TsdbDataException.class, () -> JsonText.object("[1,2]"));
        assertThrows(TsdbDataException.class, () -> JsonText.array("{\"a\":1}"));
        assertThrows(TsdbDataException.class, () -> JsonText.parse("{broken"));
    }

    @Test
    void serializesTreeWithoutAutoType() {
        JSONObject object = new JSONObject();
        object.put("name", "p1");
        assertTrue(JsonText.toJson(object).contains("\"name\":\"p1\""));
    }
}
