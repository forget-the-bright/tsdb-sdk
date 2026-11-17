package io.github.forget_the_bright.tsdb.common.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TagSample} 附加属性契约测试：防御性拷贝、只读、null 键值忽略，
 * 以及“无值”与“出错”两种 null sample 的区分约定。
 */
class TagSampleTest {

    @Test
    void withoutAttributesReturnsEmptyMap() {
        TagSample sample = TagSample.of("AI-101", null);
        assertTrue(sample.getAttributes().isEmpty());
        assertNull(sample.getSample());
        assertEquals("AI-101", sample.getTagName());
    }

    @Test
    void nullAttributesMapIsEmpty() {
        assertTrue(TagSample.of("AI-101", null, null).getAttributes().isEmpty());
    }

    @Test
    void attributesAreDefensivelyCopied() {
        Map<String, String> source = new LinkedHashMap<String, String>();
        source.put(TagSample.ATTR_ERROR_CODE, "7");
        TagSample sample = TagSample.of("AI-101", null, source);
        source.put(TagSample.ATTR_ERROR_CODE, "0");
        source.put("extra", "x");
        assertEquals("7", sample.getAttributes().get(TagSample.ATTR_ERROR_CODE));
        assertEquals(1, sample.getAttributes().size());
    }

    @Test
    void attributesAreUnmodifiable() {
        Map<String, String> source = new LinkedHashMap<String, String>();
        source.put(TagSample.ATTR_ERROR_CODE, "0");
        TagSample sample = TagSample.of("AI-101", null, source);
        assertThrows(UnsupportedOperationException.class,
                () -> sample.getAttributes().put("k", "v"));
    }

    @Test
    void nullKeysAndValuesAreIgnored() {
        Map<String, String> source = new LinkedHashMap<String, String>();
        source.put(null, "v");
        source.put("k", null);
        source.put(TagSample.ATTR_ENGINEERING_UNITS, "t");
        TagSample sample = TagSample.of("AI-101", null, source);
        assertEquals(1, sample.getAttributes().size());
        assertEquals("t", sample.getAttributes().get(TagSample.ATTR_ENGINEERING_UNITS));
    }

    @Test
    void errorAttributesDistinguishFailureFromMissingValue() {
        // 约定：sample==null 且 errorCode!="0" 表示该位号查询出错；
        // sample==null 且无错误码表示确实无值。
        Map<String, String> error = new LinkedHashMap<String, String>();
        error.put(TagSample.ATTR_ERROR_CODE, "7");
        error.put(TagSample.ATTR_ERROR_MESSAGE, "Tag not found");
        TagSample failed = TagSample.of("AI-ERR", null, error);
        assertNull(failed.getSample());
        assertEquals("7", failed.getAttributes().get(TagSample.ATTR_ERROR_CODE));
        assertEquals("Tag not found", failed.getAttributes().get(TagSample.ATTR_ERROR_MESSAGE));

        TagSample missing = TagSample.of("AI-999", null);
        assertNull(missing.getSample());
        assertTrue(missing.getAttributes().isEmpty());
    }

    @Test
    void tagNameMustNotBeNull() {
        assertThrows(NullPointerException.class, () -> TagSample.of(null, null));
    }
}