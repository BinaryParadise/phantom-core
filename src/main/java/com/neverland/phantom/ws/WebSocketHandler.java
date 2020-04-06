package com.neverland.phantom.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

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
        logger.info(session.getRemoteAddress().toString() + " closed.(" + status + ")");
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        ByteBuffer buffer = message.getPayload();
        byte type = buffer.get();
        if (buffer.get(0) == 0x1) {//无需握手、直接发送数据
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
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) throws Exception {
        logger.info("【" + (String) session.getAttributes().get("deviceid") + "】发来一个Pong");
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
        try {
            if (buffer.get() == 0x43) {//HTTP Proxy
                handleHTTPProxy(buffer, session);
            } else {
                handleClientCommand(buffer, session);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // HTTP代理连接目标地址
    private void handleHTTPProxy(ByteBuffer buffer, WebSocketSession session) throws IOException {
        byte[] dataBuff = new byte[buffer.limit()-1];
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
        buffer.position(4);
        ADDRESS_TYPE addressType = ADDRESS_TYPE.convertToAddressType(buffer.get());

        String targetAddress = "";
        switch (addressType) {
            case DOMAIN:
                // 如果是域名的话第一个字节表示域名的长度为n，紧接着n个字节表示域名
                int domainLength = buffer.get();
                byte[] dst1 = new byte[domainLength];
                buffer.get(dst1);
                targetAddress = new String(dst1);
                break;
            case IPV4:
                // 如果是ipv4的话使用固定的4个字节表示地址
                byte[] dst2 = new byte[4];
                buffer.get(dst2);
                targetAddress = ipAddressBytesToString(dst2);
                break;
            case IPV6:
                throw new RuntimeException("not support ipv6.");
        }

        int targetPort = buffer.getShort();

        Socket socket = (Socket) session.getAttributes().get("socket");
        if (socket == null) {
            try {
                socket = new Socket(targetAddress, targetPort);
            } catch (UnknownHostException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        session.getAttributes().put("socket", socket);
        byte[] forwardBuff = new byte[buffer.array().length - buffer.position()];
        buffer.get(forwardBuff);
        logger.info("开始转发数据..."+forwardBuff.length);
        BufferedOutputStream outputStream = new BufferedOutputStream(socket.getOutputStream());
        outputStream.write(forwardBuff);
        outputStream.flush();
        logger.info("转发完成，读取响应...");

        byte[] newBuffer = new byte[0];
        BufferedInputStream inputStream = new BufferedInputStream(socket.getInputStream());
        while (true) {
            byte[] readBf = new byte[256];
            int read = inputStream.read(readBf);
            if (read == -1) {
              break;
            }
            byte[] tmpBuff = new byte[newBuffer.length + read];
            System.arraycopy(newBuffer, 0, tmpBuff, 0, newBuffer.length);
            System.arraycopy(readBf, 0, tmpBuff, newBuffer.length, read);
            newBuffer = tmpBuff;
            if (read < 256) {//不能使用-1，这里是从网络读取会等待超时才返回
                break;
            }
        }
        logger.info("读取完成，开始回传..."+newBuffer.length);
        BinaryMessage binaryMessage = new BinaryMessage(newBuffer);
        session.sendMessage(binaryMessage);
        logger.info("代理完成:" + targetAddress + ":" + targetPort + " length:" + newBuffer.length);
        logger.debug(new String(binaryMessage.getPayload().array()));

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
