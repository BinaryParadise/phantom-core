package com.neverland.phantom.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.UnknownHostException;
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
        logger.info(session.getRemoteAddress().toString() + " accepted.");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        session.getAttributes().remove("socket");
        logger.info(session.getRemoteAddress().toString() + " closed.(" + status + ")");
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        ByteBuffer buffer = message.getPayload();
        byte type = buffer.get();
        try {
            Method method = this.getClass().getDeclaredMethod("messageHandlerForType" + type, ByteBuffer.class, WebSocketSession.class);
            method.invoke(this, buffer, session);
        } catch (NoSuchMethodException e) {
            logger.error("can't transform data: " + type, e);
        } catch (IllegalAccessException e) {
            logger.error(e.getLocalizedMessage(), e.getCause());
        } catch (InvocationTargetException e) {
            logger.error(e.getLocalizedMessage(), e.getCause());
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) throws Exception {
        super.handlePongMessage(session, message);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        super.handleTransportError(session, exception);
    }

    /**
     * 和目标服务器建立连接
     */
    void messageHandlerForType1(ByteBuffer buffer, WebSocketSession session) throws IOException {
        buffer.position(1);
        int addressLength = buffer.get();
        byte[] dst1 = new byte[addressLength];
        buffer.get(dst1);
        String targetAddress = new String(dst1);

        int targetPort = buffer.getShort();

        Socket socket = (Socket) session.getAttributes().get("socket");
        if (socket == null) {
            try {
                socket = new Socket(targetAddress, targetPort);
                session.getAttributes().put("socket", socket);
                logger.info("请求连接至 "+socket.getRemoteSocketAddress().toString());
                PingMessage pingMessage = new PingMessage(ByteBuffer.wrap(new byte[]{0x01, 0x00}));
                session.sendMessage(pingMessage);
            } catch (UnknownHostException e) {
                e.printStackTrace();
                session.close(CloseStatus.PROTOCOL_ERROR);
            } catch (IOException e) {
                e.printStackTrace();
                session.close(CloseStatus.PROTOCOL_ERROR);
            }
        }
    }

    void messageHandlerForType2(ByteBuffer buffer, WebSocketSession session) {
        try {
            handleClientCommand(buffer, session);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // HTTP代理连接目标地址
    private void handleHTTPProxy(ByteBuffer buffer, WebSocketSession session) throws IOException {
        byte[] dataBuff = new byte[buffer.limit() - 1];
        buffer.get(dataBuff);
        String string = new String(dataBuff);
        String header = string.split("\r\n")[0];
        logger.debug(header);
        String[] host = header.split(" ")[1].split(":");
        Socket socket = (Socket) session.getAttributes().get("socket");
        if (socket == null) {
            try {
                socket = new Socket(host[0], Integer.parseInt(host[1]));
            } catch (UnknownHostException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        BinaryMessage msg = new BinaryMessage("HTTP/1.1 200 Connection Established\\r\\n\\r\\n".getBytes());
        session.sendMessage(msg);
    }

    // 认证通过，开始处理客户端发送过来的指令
    private void handleClientCommand(ByteBuffer buffer, WebSocketSession session) throws IOException {
        buffer.position(1);
        Socket socket = (Socket) session.getAttributes().get("socket");
        if (socket == null) {
            PingMessage pingMessage = new PingMessage(ByteBuffer.wrap(new byte[]{0x02}));
            session.sendMessage(pingMessage);
        } else {
            byte[] forwardBuff = new byte[buffer.array().length - buffer.position()];
            buffer.get(forwardBuff);
            logger.info("开始转发数据..." + forwardBuff.length);
            BufferedOutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
            outputStream.write(forwardBuff);
            outputStream.flush();
            logger.info("转发完成，获取响应...");
            BufferedInputStream inputStream = new BufferedInputStream(socket.getInputStream());
            int receive = 0;
            while (true) {
                byte[] readBuffer = new byte[1024];
                int read = inputStream.read(readBuffer);
                if (read > 0) {
                    receive += read;
                    logger.info("数据回传中..." + read);
                    BinaryMessage binaryMessage = new BinaryMessage(ByteBuffer.wrap(readBuffer, 0, read));
                    session.sendMessage(binaryMessage);
                    if (read < readBuffer.length) {//不能使用-1，这里是从网络读取会等待超时才返回
                        break;
                    }
                } else {
                    break;
                }
            }
            logger.info("本次请求转发完成..." + receive);
        }
    }

    // convert ip address from 4 byte to string
    private String ipAddressBytesToString(byte[] ipAddressBytes) {
        // first convert to int avoid negative
        return (ipAddressBytes[0] & 0XFF) + "." + (ipAddressBytes[1] & 0XFF) + "." + (ipAddressBytes[2] & 0XFF) + "." + (ipAddressBytes[3] & 0XFF);
    }

    // 客户端命令
    public static enum COMMAND {
        CONNECT((byte) 0X01, "CONNECT"),
        BIND((byte) 0X02, "BIND"),
        UDP_ASSOCIATE((byte) 0X03, "UDP ASSOCIATE");

        byte value;
        String description;

        COMMAND(byte value, String description) {
            this.value = value;
            this.description = description;
        }

        public static COMMAND convertToCmd(byte value) {
            for (COMMAND cmd : COMMAND.values()) {
                if (cmd.value == value) {
                    return cmd;
                }
            }
            return null;
        }

    }

    // 要请求的地址类型
    public static enum ADDRESS_TYPE {
        IPV4((byte) 0X01, "the address is a version-4 IP address, with a length of 4 octets"),
        DOMAIN((byte) 0X03, "the address field contains a fully-qualified domain name.  The first\n" +
                "   octet of the address field contains the number of octets of name that\n" +
                "   follow, there is no terminating NUL octet."),
        IPV6((byte) 0X04, "the address is a version-6 IP address, with a length of 16 octets.");
        byte value;
        String description;

        ADDRESS_TYPE(byte value, String description) {
            this.value = value;
            this.description = description;
        }

        public static ADDRESS_TYPE convertToAddressType(byte value) {
            for (ADDRESS_TYPE addressType : ADDRESS_TYPE.values()) {
                if (addressType.value == value) {
                    return addressType;
                }
            }
            return null;
        }

    }

}
