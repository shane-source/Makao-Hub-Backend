package com.makaohub.backend.observability;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.server.ResponseStatusException;

/**
 * Records WHY a request failed (the reason text and the root cause) and then lets Spring handle the
 * exception exactly as before: it returns null, so responses are unchanged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ErrorReasonLogger implements HandlerExceptionResolver {

    private static final Logger log = LoggerFactory.getLogger("makao.errors");

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception exception) {
        String where = request.getMethod() + " " + request.getRequestURI();
        if (exception instanceof ResponseStatusException status) {
            if (status.getStatusCode().is5xxServerError()) {
                log.error("REQUEST_FAILED {} status={} reason=\"{}\" cause={}", where,
                        status.getStatusCode().value(), status.getReason(), root(status), status);
            } else {
                log.warn("REQUEST_REJECTED {} status={} reason=\"{}\"", where,
                        status.getStatusCode().value(), status.getReason());
            }
        } else {
            log.error("REQUEST_ERROR {} {} root={}", where, exception.getClass().getSimpleName(), root(exception), exception);
        }
        return null;
    }

    private static String root(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getClass().getSimpleName() + ": " + r.getMessage();
    }
}
