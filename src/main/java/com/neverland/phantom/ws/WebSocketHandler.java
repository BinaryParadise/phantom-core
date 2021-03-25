package com.neverland.phantom.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

public class WebSocketHandler extends BinaryWebSocketHandler {
    Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        logger.info(session.getRemoteAddress().toString() + " accepted.");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Socket socket = (Socket) session.getAttributes().get("socket");
        if (socket != null) {
            socket.close();
            session.getAttributes().remove("socket");
        }
        logger.info(session.getRemoteAddress().toString() + " closed.(" + status + ")");
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        ByteBuffer buffer = message.getPayload();
        COMMAND type = COMMAND.convertToCmd(buffer.get());
        if (type == COMMAND.Authorization) {
            messageHandlerAuthorization(buffer, session);
        } else {
            if (session.getAttributes().containsKey("id")) {
                switch (type) {
                    case Connect:
                        messageHandlerConnect(buffer, session);
                        break;
                    case Forward:
                        messageHandlerForward(buffer, session);
                        break;
                    default:
                        session.close(CloseStatus.PROTOCOL_ERROR);
                        break;
                }
            } else {
                session.close(CloseStatus.NOT_ACCEPTABLE.withReason("unauthorized"));
            }
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
     * 访问授权
     */
    void messageHandlerAuthorization(ByteBuffer buffer, WebSocketSession session) throws IOException {
        buffer.position(1);
        int idlength = buffer.get();
        byte[] dst1 = new byte[idlength];
        buffer.get(dst1);
        //授权ID
        String id = new String(dst1);
        //TODO:读取配置文件
        if (id.equalsIgnoreCase("3b8e10b8-8c7b-11eb-8dcd-0242ac130003")) {
            session.getAttributes().put("id", id);
            session.sendMessage(createMessage(COMMAND.Authorization, new byte[]{0x01}, 1));
        } else {
            session.close(CloseStatus.BAD_DATA.withReason("Data format error"));
        }
    }

    /**
     * 和目标服务器建立连接
     */
    void messageHandlerConnect(ByteBuffer buffer, WebSocketSession session) throws IOException {
        buffer.position(1);
        int addressLength = buffer.get();
        byte[] dst1 = new byte[addressLength];
        buffer.get(dst1);
        String targetAddress = new String(dst1);

        int targetPort = buffer.getShort() & 0xFFFF;

        Socket socket = (Socket) session.getAttributes().get("socket");
        if (socket == null) {
            try {
                socket = new Socket(targetAddress, targetPort);
                session.getAttributes().put("socket", socket);
                logger.info("请求连接至 " + socket.getRemoteSocketAddress().toString());
                session.sendMessage(createMessage(COMMAND.Connect, new byte[]{0x00}));
            } catch (UnknownHostException e) {
                e.printStackTrace();
                session.close(CloseStatus.PROTOCOL_ERROR);
            } catch (IOException e) {
                e.printStackTrace();
                session.close(CloseStatus.PROTOCOL_ERROR);
            }
        }
    }

    // 转发请求
    void messageHandlerForward(ByteBuffer buffer, WebSocketSession session) {
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
            try {
                byte[] forwardBuff = new byte[buffer.array().length - buffer.position()];
                buffer.get(forwardBuff);
                logger.info("转发请求[数据长度=" + forwardBuff.length + "]");
                BufferedOutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
                outputStream.write(forwardBuff);
                outputStream.flush();
                logger.info("转发完成，获取响应...");
            } catch (IOException exception) {
                logger.error(exception.getLocalizedMessage(), exception.getCause());
            }
            BufferedInputStream inputStream = new BufferedInputStream(socket.getInputStream());
            int receive = 0;
            while (true) {
                byte[] readBuffer = new byte[4096];
                int read = inputStream.read(readBuffer);
                if (read > 0) {
                    receive += read;
                    logger.info("数据回传中..." + read);
                    session.sendMessage(createMessage(COMMAND.Transport, readBuffer, read));
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

    BinaryMessage createMessage(COMMAND cmd, byte[] buffer) {
        return createMessage(cmd, buffer, buffer.length);
    }

    BinaryMessage createMessage(COMMAND cmd, byte[] buffer, int length) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(cmd.value);
        outputStream.write(buffer, 0, length);
        return new BinaryMessage(outputStream.toByteArray());
    }

    // convert ip address from 4 byte to string
    private String ipAddressBytesToString(byte[] ipAddressBytes) {
        // first convert to int avoid negative
        return (ipAddressBytes[0] & 0XFF) + "." + (ipAddressBytes[1] & 0XFF) + "." + (ipAddressBytes[2] & 0XFF) + "." + (ipAddressBytes[3] & 0XFF);
    }

    // 客户端命令
    public static enum COMMAND {
        Authorization((byte) 0X01, "Authorization"),
        Connect((byte) 0X02, "Connect"),
        Forward((byte) 0x03, "Forward Request"),
        Transport((byte) 0x04, "Transport Data"),
        Unsupport((byte) 0x00, "Unsupport");

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
