package com.neverland.phantom.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;

public class WebSocketHandler extends BinaryWebSocketHandler {
  Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);

  /**
   * 客户端会话合集
   */
  static HashMap<String, WebSocketSession> clientSessions = new HashMap<>();

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    logger.info(session.getRemoteAddress().toString()+" accepted.");
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    logger.info(session.getRemoteAddress().toString()+" closed.("+status+")");
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
    ByteBuffer buffer = message.getPayload();
    byte type = buffer.get();
    if (buffer.get(0) == 0x1) {//无需握手、直接发送数据
      try {
        Method method = this.getClass().getDeclaredMethod("messageHandlerForType" + type, ByteBuffer.class, WebSocketSession.class);
        String result = (String) method.invoke(this, buffer, session);
        if (result != null) {
          session.sendMessage(new BinaryMessage(ByteBuffer.wrap(result.getBytes())));
        }
      } catch (NoSuchMethodException e) {
        logger.error("can't transform data: " + type, e);
      } catch (IllegalAccessException e) {
        logger.error(e.getLocalizedMessage(), e.getCause());
      } catch (InvocationTargetException e) {
        logger.error(e.getLocalizedMessage(), e.getCause());
      }
    }
  }

  @Override
  protected void handlePongMessage(WebSocketSession session, PongMessage message) throws Exception {
    logger.info("【"+(String) session.getAttributes().get("deviceid")+"】发来一个Pong");
    super.handlePongMessage(session, message);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
    super.handleTransportError(session, exception);
  }

  /**
   * 代理请求
   */
  void messageHandlerForType1(ByteBuffer buffer, WebSocketSession session) {
    short length = buffer.getShort();
    byte[] dst = new byte[length];
    buffer.get(dst);
    logger.info("data length:"+ length + " > " + new String(dst));
  }

}
