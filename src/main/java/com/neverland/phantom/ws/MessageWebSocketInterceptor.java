package com.neverland.phantom.ws;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

import static org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE;

public class MessageWebSocketInterceptor implements HandshakeInterceptor {
    @Override
    public boolean beforeHandshake(ServerHttpRequest serverHttpRequest, ServerHttpResponse serverHttpResponse, WebSocketHandler webSocketHandler, Map<String, Object> map) throws Exception {
        if (!serverHttpRequest.getHeaders().get("sec-websocket-protocol").contains("phantom-core")) {
            serverHttpResponse.setStatusCode(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            return false;
        }
        serverHttpResponse.getHeaders().set("sec-webSocket-protocol", "phantom-core");
        if (serverHttpRequest instanceof ServletServerHttpRequest) {
            ServletServerHttpRequest request = (ServletServerHttpRequest) serverHttpRequest;
            Map uriAttrs = (Map) request.getServletRequest().getAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            map.putAll(uriAttrs);
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest serverHttpRequest, ServerHttpResponse serverHttpResponse, WebSocketHandler webSocketHandler, Exception e) {
    }
}
