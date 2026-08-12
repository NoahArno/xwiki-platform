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
package org.xwiki.oa.sync.internal.mq.handler;

import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.oa.sync.mq.DoapMqConstants;
import org.xwiki.oa.sync.mq.DoapUserMessageHandler;
import org.xwiki.oa.sync.mq.model.DoapUserMessage;

/**
 * 处理 {@code TAG_USER_CREATE}：知脉用户新增消息。
 * <p>
 * 统一交给 {@link org.xwiki.oa.sync.internal.mq.DoapUserSynchronizer} 处理：
 * 只同步 {@code type=outsourcing} 的外包用户，按 {@code staffId} 建号并加入
 * 「开发中心科技外包组」，{@code isDeleted=true} 或 {@code status=dismission} 时禁用。
 *
 * @version $Id$
 */
@Component(roles = DoapUserMessageHandler.class)
@Named(DoapMqConstants.TAG_USER_CREATE)
@Singleton
public class DoapUserCreateHandler extends AbstractDoapUserMessageHandler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DoapUserCreateHandler.class);

    @Override
    public String getTag()
    {
        return DoapMqConstants.TAG_USER_CREATE;
    }

    @Override
    public void handle(DoapUserMessage message) throws Exception
    {
        LOGGER.info("DOAP MQ 用户新增消息处理: msgId=[{}] 字段=[{}]", message.getMsgId(), message.getFields());
        sync(message);
    }
}
