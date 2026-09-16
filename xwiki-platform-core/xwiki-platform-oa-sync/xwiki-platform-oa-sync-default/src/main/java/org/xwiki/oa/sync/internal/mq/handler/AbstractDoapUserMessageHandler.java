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

import javax.inject.Inject;
import javax.inject.Provider;

import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.oa.sync.internal.mq.DoapUserSynchronizer;
import org.xwiki.oa.sync.mq.DoapUserMessageHandler;
import org.xwiki.oa.sync.mq.model.DoapUserMessage;

import com.xpn.xwiki.XWikiContext;

/**
 * Base class for DOAP MQ tag handlers: runs the {@link DoapUserSynchronizer} inside a fresh XWiki
 * execution context, because the RocketMQ consume threads do not have a request context.
 *
 * @version $Id$
 */
public abstract class AbstractDoapUserMessageHandler implements DoapUserMessageHandler
{
    @Inject
    protected DoapUserSynchronizer synchronizer;

    @Inject
    protected ExecutionContextManager executionContextManager;

    @Inject
    protected Execution execution;

    @Inject
    protected Provider<XWikiContext> contextProvider;

    /**
     * 在独立的 XWiki 执行上下文中同步一条消息（RocketMQ 消费线程没有请求上下文）。
     *
     * @param message the message to sync
     * @throws Exception if the sync fails
     */
    protected void sync(DoapUserMessage message) throws Exception
    {
        ExecutionContext econtext = new ExecutionContext();
        this.executionContextManager.initialize(econtext);
        try {
            this.synchronizer.sync(message, this.contextProvider.get());
        } finally {
            // 与 ExcelImportExecutor 一致：removeContext 只清 ThreadLocal，不关 Hibernate session，
            // 因此必须先 cleanUp 释放连接，否则消费线程会持续泄漏数据库连接。
            // 清理是 best-effort，绝不能改变消息处理结果，故整个清理块都吞异常。
            try {
                XWikiContext context = this.contextProvider.get();
                if (context != null && context.getWiki() != null) {
                    context.getWiki().getStore().cleanUp(context);
                }
            } catch (Exception e) {
                // 清理是 best-effort，绝不能改变消息处理结果
            }
            this.execution.removeContext();
        }
    }
}
