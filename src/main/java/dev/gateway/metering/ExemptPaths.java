package dev.gateway.metering;

import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

final class ExemptPaths {

    private static final PathPattern ACTUATOR = PathPatternParser.defaultInstance.parse("/actuator/**");

    private ExemptPaths() {}

    static boolean isExempt(ServerWebExchange exchange) {
        return ACTUATOR.matches(exchange.getRequest().getPath().pathWithinApplication());
    }
}
