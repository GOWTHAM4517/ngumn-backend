package com.ngumn.backend;

import com.ngumn.backend.dto.ApiError;
import com.ngumn.backend.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A mistake in a request is a 4xx with a plain message; only a real fault is a 500, without internals. */
class ErrorResponsesTest {

    final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @SuppressWarnings("unused")
    public void endpoint(String authorization, double lat) {
    }

    MethodParameter param(int i) throws NoSuchMethodException {
        Method m = ErrorResponsesTest.class.getMethod("endpoint", String.class, double.class);
        return new MethodParameter(m, i);
    }

    static void is(ResponseEntity<ApiError> r, int status, String text) {
        assertEquals(status, r.getStatusCode().value());
        assertTrue(r.getBody().getMessage().contains(text), r.getBody().getMessage());
    }

    @Test
    void notSignedInIs401() throws Exception {
        is(handler.handleMissingHeader(new MissingRequestHeaderException("Authorization", param(0))), 401, "Sign in first");
        is(handler.handleMissingHeader(new MissingRequestHeaderException("X-Thing", param(0))), 400, "X-Thing");
    }

    @Test
    void badParametersAre400() throws Exception {
        is(handler.handleMissingParam(new MissingServletRequestParameterException("lat", "double")), 400, "Missing lat");
        is(handler.handleBadParam(new MethodArgumentTypeMismatchException("abc", double.class, "lat", param(1), null)), 400, "'lat' isn't valid");
        HttpInputMessage empty = new HttpInputMessage() {
            @Override public InputStream getBody() { return new ByteArrayInputStream(new byte[0]); }
            @Override public HttpHeaders getHeaders() { return new HttpHeaders(); }
        };
        is(handler.handleUnreadable(new HttpMessageNotReadableException("JSON parse error", empty)), 400, "isn't valid");
        is(handler.handleMethod(new HttpRequestMethodNotSupportedException("DELETE")), 405, "DELETE");
    }

    @Test
    void serverFaultsDontShowInternals() {
        ResponseEntity<ApiError> r = handler.handleGeneric(new IllegalStateException("SQL: table users exploded"));
        is(r, 500, "Something went wrong");
        assertFalse(r.getBody().getMessage().contains("SQL"));
    }
}
