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

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.oa.sync.mq.DoapUserMessageHandler;
import org.xwiki.oa.sync.mq.model.DoapUserMessage;

/**
 * Routes a consumed message to the {@link DoapUserMessageHandler} matching its tag.
 * A handler failure or a missing handler is logged and reported as failure so the
 * message can be retried by MQ.
 *
 * @version $Id$
 */
@Component(roles = DoapUserMessageDispatcher.class)
@Singleton
public class DoapUserMessageDispatcher
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DoapUserMessageDispatcher.class);

    @Inject
    ComponentManager componentManager;

    /**
     * @param message the message to route
     * @return {@code true} if a matching handler processed the message successfully, {@code false} otherwise
     */
    public boolean dispatch(DoapUserMessage message)
    {
        List<DoapUserMessageHandler> handlers;
        try {
            handlers = this.componentManager.getInstanceList(DoapUserMessageHandler.class);
        } catch (ComponentLookupException e) {
            LOGGER.error("无法获取 DOAP MQ 消息处理器列表, 消息将重试: msgId=[{}] tag=[{}]", message.getMsgId(), message.getTag(),
                e);
            return false;
        }
        for (DoapUserMessageHandler handler : handlers) {
            if (handler.getTag().equals(message.getTag())) {
                try {
                    handler.handle(message);
                    LOGGER.info("DOAP MQ 消息处理成功: tag=[{}] msgId=[{}]", message.getTag(), message.getMsgId());
                    return true;
                } catch (Exception e) {
                    LOGGER.error("DOAP MQ 消息处理失败, 消息将重试: tag=[{}] msgId=[{}]", message.getTag(), message.getMsgId(),
                        e);
                    return false;
                }
            }
        }
        LOGGER.warn("没有找到处理 tag=[{}] 的 DOAP MQ 消息处理器, msgId=[{}]", message.getTag(), message.getMsgId());
        return false;
    }
}
