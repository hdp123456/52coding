# Redis延迟消息发送原理
2019年07月26日 19:30:00 刘晓伟

## 1.实现原理
（1）在Redis2.8之后的版本中，当我们将<key, value>对使用Redis缓存起来并设置缓存失效时间的时候，
会触发Redis的键事件通知，客户端订阅这个通知事件，服务端会将对应的通知事件发送给每个订阅的客户端，
然后客户端根据收到的通知，做相应的后续处理(例如：键过期时间通知对应的topic为:"__keyevent@0__:expired")

（2）因为开启键空间通知功能需要消耗一些 CPU ，所以在默认配置下，该功能处于关闭状态。

（3）可以通过修改 redis.conf 文件，开启键空间通知功能。redis.conf: notify-keyspace-events Ex
 
## 2. spring boot实现Redis延迟消息发送代码分析

```
/**
* 继承KeyExpirationEventMessageListener监听redis key失效事件。
*/
@Slf4j
@Component
public class RedisKeyExpirationListener extends KeyExpirationEventMessageListener {
    public RedisKeyExpirationListener(RedisMessageListenerContainer listenerContainer) {
        super(listenerContainer);
    }
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = message.toString();
        byte[] channel = message.getChannel();
        byte[] body = message.getBody();
        try {
            String channelStr = new String(channel, "UTF-8");
            String messageStr = new String(body, "UTF-8");
            log.info("expiredKey:{}", expiredKey);
            log.info("消息频道名称:{}", channelStr);
            log.info("消息内容是:{}", messageStr);
        } catch (UnsupportedEncodingException ex) {
            log.error("redisListener exception:{}", ex);
        }
    }
}

```


 

