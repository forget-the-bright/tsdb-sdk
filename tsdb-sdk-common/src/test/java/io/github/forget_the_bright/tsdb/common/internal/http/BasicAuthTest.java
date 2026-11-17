package io.github.forget_the_bright.tsdb.common.internal.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BasicAuthTest {

    @Test
    void encodesUserAndPassword() {
        assertEquals("Basic dXNlcjpwYXNz", BasicAuth.header("user", "pass"));
    }

    @Test
    void treatsNullAsEmpty() {
        assertEquals("Basic Og==", BasicAuth.header(null, null));
        assertEquals("Basic dXNlcjo=", BasicAuth.header("user", null));
        assertEquals("Basic OnBhc3M=", BasicAuth.header(null, "pass"));
    }

    @Test
    void encodesNonAsciiCredentialsAsUtf8() {
        assertEquals("Basic 5byg5LiJOnNlY3JldA==", BasicAuth.header("张三", "secret"));
    }
}
