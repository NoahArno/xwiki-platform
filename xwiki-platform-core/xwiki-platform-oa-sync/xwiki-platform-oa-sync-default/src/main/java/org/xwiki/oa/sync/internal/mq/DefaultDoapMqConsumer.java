/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.oa.sync.internal.mq;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLifecycleException;
import org.xwiki.component.phase.Disposable;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.oa.sync.mq.DoapMqConfiguration;
import org.xwiki.oa.sync.mq.model.DoapUserMessage;

/**
 * DOAP 用户数据 MQ 消费者（架子）。
 * <p>
 * 生命周期：应用就绪后由 {@link DoapMqApplicationReadyListener} 调用 {@link #start()}，
 * 关闭时调用 {@link #stop()}。消息回调统一走 {@link #onMessage(String, String, String, int, String)}
 * → {@link DoapUserMessageParser} → {@link DoapUserMessageDispatcher}。
 * <p>
 * 使用 Apache RocketMQ（{@code DefaultMQPushConsumer}）订阅集群 topic {@code TOPIC_DOAP_USER}，
 * 按配置的 tags（默认 {@code TAG_USER_CREATE||TAG_USER_UPDATE||TAG_USER_DELETE}）过滤消息，
 * 回调统一走 {@link #onMessage(String, String, String, int, String)}。
 *
 * @version $Id$
 */
@Component(roles = DefaultDoapMqConsumer.class)
@Singleton
public class DefaultDoapMqConsumer implements Initializable, Disposable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultDoapMqConsumer.class);

    @Inject
    DoapMqConfiguration configuration;

    @Inject
    DoapUserMessageParser parser;

    @Inject
    DoapUserMessageDispatcher dispatcher;

    private DefaultMQPushConsumer pushConsumer;

    private final AtomicBoolean started = new AtomicBoolean(false);

    @Override
    public void initialize() throws InitializationException
    {
        LOGGER.info("DOAP MQ 消费者组件初始化: enabled=[{}] topic=[{}] tags=[{}] nameServer=[{}] group=[{}]",
            this.configuration.isEnabled(), this.configuration.getTopic(), this.configuration.getTags(),
            this.configuration.getNameServer(), this.configuration.getConsumerGroup());
    }

    /**
     * Start consuming messages. Idempotent: subsequent calls while already started are ignored.
     */
    public synchronized void start()
    {
        if (this.started.get()) {
            return;
        }
        if (!this.configuration.isEnabled()) {
            LOGGER.info("DOAP MQ 消费未启用 (xwiki.doap-mq.enabled=false)，跳过启动");
            return;
        }
        this.started.set(true);
        try {
            connectAndSubscribe();
        } catch (Exception e) {
            LOGGER.error("DOAP MQ 消费者启动失败", e);
            this.started.set(false);
        }
    }

    /**
     * @return whether the consumer has been started (and has not been stopped)
     */
    public boolean isRunning()
    {
        return this.started.get();
    }

    /**
     * Stop consuming messages and release the MQ client. Idempotent.
     */
    public synchronized void stop()
    {
        if (!this.started.compareAndSet(true, false)) {
            return;
        }
        try {
            shutdownClient();
        } catch (Exception e) {
            LOGGER.error("DOAP MQ 消费者关闭失败", e);
        }
    }

    @Override
    public void dispose() throws ComponentLifecycleException
    {
        stop();
    }

    /**
     * MQ 消息回调入口：解析并分发消息。
     *
     * @param topic the MQ topic
     * @param tag the MQ tag
     * @param msgId the MQ message id
     * @param reconsumeTimes how many times the message has been re-consumed
     * @param body the raw message body
     * @return {@code true} 消费成功（ACK），{@code false} 消费失败（交给 MQ 重试）
     */
    public boolean onMessage(String topic, String tag, String msgId, int reconsumeTimes, String body)
    {
        LOGGER.info("DOAP MQ 收到消息: topic=[{}] tag=[{}] msgId=[{}] reconsumeTimes=[{}] body=[{}]",
            topic, tag, msgId, reconsumeTimes, body);
        DoapUserMessage message = this.parser.parse(topic, tag, msgId, reconsumeTimes, body);
        if (message.getFields().isEmpty() && (body != null && !body.isBlank())) {
            LOGGER.warn("DOAP MQ 消息体不是合法 JSON, 原始内容将无法按字段处理: msgId=[{}] body=[{}]", msgId, body);
        }
        return this.dispatcher.dispatch(message);
    }

    /**
     * 连接 RocketMQ 集群并订阅 topic/tags（推送模式）。
     * <p>
     * 按 {@link DoapMqConfiguration#getTopic()} 订阅，tags 用 {@code ||} 拼接为订阅过滤表达式；
     * 每条消息回调 {@link #onMessage(String, String, String, int, String)}，
     * {@code true} 返回 {@code CONSUME_SUCCESS}（ACK），{@code false} 返回 {@code RECONSUME_LATER}（重试）。
     */
    protected void connectAndSubscribe() throws Exception
    {
        String group = this.configuration.getConsumerGroup();
        String topic = this.configuration.getTopic();
        List<String> tags = this.configuration.getTags();
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("xwiki.doap-mq.consumer-group 未配置");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("xwiki.doap-mq.topic 未配置");
        }

        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(group);
        String nameServer = this.configuration.getNameServer();
        if (nameServer != null && !nameServer.isBlank()) {
            consumer.setNamesrvAddr(nameServer);
        }
        consumer.setConsumeThreadMin(this.configuration.getConsumeThreadMin());
        consumer.setConsumeThreadMax(this.configuration.getConsumeThreadMax());

        String tagFilter = String.join("||", tags);
        consumer.subscribe(topic, tagFilter);
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (MessageExt message : messages) {
                boolean success = onMessage(message.getTopic(), message.getTags(), message.getMsgId(),
                    message.getReconsumeTimes(), new String(message.getBody(), StandardCharsets.UTF_8));
                if (!success) {
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        this.pushConsumer = consumer;
        LOGGER.info("DOAP MQ 消费已启动: group=[{}] topic=[{}] tags=[{}] nameServer=[{}]", group, topic, tagFilter,
            nameServer);
    }

    /**
     * 关闭 RocketMQ 消费者连接。
     */
    protected void shutdownClient() throws Exception
    {
        if (this.pushConsumer != null) {
            this.pushConsumer.shutdown();
            this.pushConsumer = null;
        }
        LOGGER.info("DOAP MQ 消费者已停止");
    }
}
