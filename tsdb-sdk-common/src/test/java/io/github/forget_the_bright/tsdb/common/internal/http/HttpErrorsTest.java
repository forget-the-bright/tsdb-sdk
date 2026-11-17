package io.github.forget_the_bright.tsdb.common.internal.http;

import io.github.forget_the_bright.tsdb.common.exception.TsdbAuthenticationException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpErrorsTest {

    @Test
    void mapsUnauthorizedAndForbiddenToAuthentication() {
        assertInstanceOf(TsdbAuthenticationException.class,
                HttpErrors.map("TEST", "GET", "/p", 401, "no"));
        assertInstanceOf(TsdbAuthenticationException.class,
                HttpErrors.map("TEST", "GET", "/p", 403, "no"));
    }

    @Test
    void mapsGatewayTimeoutsToTimeout() {
        assertInstanceOf(TsdbTimeoutException.class,
                HttpErrors.map("TEST", "GET", "/p", 408, null));
        assertInstanceOf(TsdbTimeoutException.class,
                HttpErrors.map("TEST", "GET", "/p", 504, null));
    }

    @Test
    void mapsVendorAuthStatusesOnlyWhenRequested() {
        assertInstanceOf(TsdbAuthenticationException.class,
                HttpErrors.map("TEST", "POST", "/p", 600, "auth", 600, 603));
        assertInstanceOf(TsdbAuthenticationException.class,
                HttpErrors.map("TEST", "POST", "/p", 603, "auth", 600, 603));
        assertInstanceOf(TsdbConnectionException.class,
                HttpErrors.map("TEST", "POST", "/p", 600, "auth"));
    }

    @Test
    void mapsRedirectAndOtherStatusesToConnection() {
        assertInstanceOf(TsdbConnectionException.class,
                HttpErrors.map("TEST", "GET", "/p", 302, ""));
        assertInstanceOf(TsdbConnectionException.class,
                HttpErrors.map("TEST", "GET", "/p", 500, "boom"));
    }

    @Test
    void includesPrefixStatusAndAbbreviatedBody() {
        String longBody = repeat("x", 600);
        String message = HttpErrors.map("IoTDB", "POST", "http://h/rest/v2/query", 500, longBody)
                .getMessage();
        assertTrue(message.startsWith("IoTDB POST http://h/rest/v2/query returned HTTP 500: "));
        assertTrue(message.endsWith("..."));
        assertTrue(message.length() < 600);
    }

    @Test
    void omitsBodyWhenEmpty() {
        String message = HttpErrors.map("GE", "GET", "/x", 404, "").getMessage();
        assertTrue(message.endsWith("returned HTTP 404"));
    }

    private static String repeat(String unit, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(unit);
        }
        return builder.toString();
    }
}
