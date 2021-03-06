package com.springboot.websocket.websocket;

import com.alibaba.fastjson.JSONObject;
import com.rabbitmq.client.*;
import com.springboot.websocket.config.BindingConfig;
import com.springboot.websocket.config.Constant;
import com.springboot.websocket.config.RabbitConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
/**
 * @author Mr.Liu
 */
@Component
@ServerEndpoint(value = "/websocket")
public class PushMsgServer {

    private static Logger log = LogManager.getLogger(PushMsgServer.class);
    /**
     * 手动注入rabbitmq配置信息和绑定信息
     */
    public static RabbitConfig rabbitConfig;
    public static BindingConfig bindingConfig;
    /**
     *  在线用户数量
     */
    private static int onlineCount = 0;

    /**
     * WebSocket连接管理
     */
    private  static ConcurrentHashMap<String,Session>webSocketConcurrentHashMap=new ConcurrentHashMap<>();
    /**
     *  当前的用户Id和用户的PId
     */
    private ConcurrentHashMap<String, String> userMap=new ConcurrentHashMap<>(4);

    /**
     * 连接打开调用的方法
     * @param session
     */
    @OnOpen
    public void onOpen(Session session) {

        String queryString = session.getQueryString();
        log.warn("PARAM:"+ queryString);
        splitQueryString(queryString);
        //静态信息获取
        String connectionId=Constant.USER_ID;

        String queueName=bindingConfig.queueName;
        String exchangeName=bindingConfig.exchangeName;
        String routingType=bindingConfig.routingType;
        String routingKey =bindingConfig.routingKey;

        //session信息保存
        webSocketConcurrentHashMap.put(userMap.get(connectionId),session);
        addOnlineCount();
        log.info(userMap.get(connectionId)+" New Connection join in！OnlineCount:" + getOnlineCount());
        try {
            ConnectionFactory connectionFactory = rabbitConfig.getConnectionFactory();
            log.warn("[x]RabbitMQ's Connection Info:"+rabbitConfig.toString());
            //创建一个连接
            Connection connection = connectionFactory.newConnection();
            //获取一个频道
            Channel channel = connection.createChannel();
            //如果消息队列不存在，新建消息队列
            channel.queueDeclare(queueName, true, false, false, null);
            //如果交换机不存在新建交换机
            channel.exchangeDeclare(exchangeName,routingType,true);
            //消息队列和交换机进行绑定
            channel.queueBind(queueName,exchangeName,routingKey);
            //每次从队列获取的数量,保证一次只分发一个
            channel.basicQos(1);
            log.info("[x] Waiting for messages.");
            //阻塞监听消息
            Consumer consumer = new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                    //处理监听得到的消息
                    String message = null;
                    try {
                        message = new String(body, "UTF-8");
                        log.info("[x] Received '" + message + "'");

                        JSONObject jsonObject =(JSONObject)JSONObject.parse(message);
                        String addressString =(String)jsonObject.get("address");
                        String[]  addresses= addressString.split(",");
                        List<String> address= Arrays.asList(addresses);
                        //消息处理逻辑
                        sendMessage(message,address);

                    } catch (UnsupportedEncodingException e) {
                        log.error(e.getMessage());
                        channel.abort();
                    } finally {
                        log.info("[x] Done.");
                        channel.basicAck(envelope.getDeliveryTag(), false);
                    }
                }
            };
            //autoAck是否自动回复，如果为true的话，每次生产者只要发送信息就会从内存中删除。
            boolean autoAck = false;
            //消息消费完成确认
            channel.basicConsume(queueName, autoAck, consumer);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (TimeoutException e) {
            e.printStackTrace();
        }
    }

    private void splitQueryString(String queryString) {
        String[] Infos = queryString.split("&");
        for (String info:Infos) {
            String[] str = info.split("=");
            if (str[0].equals(Constant.USER_ID)){
                userMap.put(Constant.USER_ID,str[1]);
            }
            if (str[0].equals(Constant.USER_PID)){
                userMap.put(Constant.USER_PID,str[1]);
            }
        }
    }

    /**
     * 连接关闭调用的方法
     */
    @OnClose
    public void onClose() {
        webSocketConcurrentHashMap.remove(userMap.get(Constant.USER_ID));
        subOnlineCount();    //在线数减1
        log.info(userMap.get(Constant.USER_ID)+"'s Connection was Closed！OnlineCount:" + getOnlineCount());
    }
    /**
     * 收到客户端消息后调用的方法,这里不会用到
     * @param message 客户端发送过来的消息
     * @param session 可选的参数,可以存一些与当前连接有关的信息
     */
    @OnMessage
    public void onMessage(String message, Session session) {
        //TODO nothing
        log.info("[x] From Client Message:" + message);
    }
    /**
     * 发生错误时调用的方法
     * @param session 可选参数
     * @param error
     */
    @OnError
    public void onError(Session session, Throwable error) {
        log.error("[x]ERROR!"+error.getMessage());
    }
    /**
     * 自定义的群发消息方法
     * @param message
     * @throws IOException
     */
    public void sendMessage(String message,List<String> address) throws IOException {

        if (webSocketConcurrentHashMap.size()!=0)
        {
            log.info("WebSocketCount:"+webSocketConcurrentHashMap.size());
            if (address.size()>0)
            {
                for (int i = 0; i < address.size(); i++) {
                    Session session = webSocketConcurrentHashMap.get(address.get(i));
                    if (null!=session)
                    {
                        log.warn(address.get(i)+"‘s session is not null!");
                        session.getBasicRemote().sendText(message);
                        log.info("[x] Push message:"+message);
                    }else{
                        log.warn(address.get(i)+"'s session is null!");
                    }
                }
            }

        }else {
            log.error("[x] Error:WebSocket onlineCount = 0!");
        }
    }

    public static synchronized int getOnlineCount() {
        return onlineCount;
    }

    public static synchronized void addOnlineCount() {
        PushMsgServer.onlineCount++;
    }

    public static synchronized void subOnlineCount() {
        PushMsgServer.onlineCount--;
    }
}
