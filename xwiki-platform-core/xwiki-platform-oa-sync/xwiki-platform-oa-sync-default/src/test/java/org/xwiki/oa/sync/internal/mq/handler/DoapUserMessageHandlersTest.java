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

import java.util.Map;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.oa.sync.internal.mq.DoapUserSynchronizer;
import org.xwiki.oa.sync.mq.DoapMqConstants;
import org.xwiki.oa.sync.mq.model.DoapUserMessage;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the DOAP MQ tag handlers: they must match their tag and delegate to
 * {@link DoapUserSynchronizer} inside a fresh XWiki execution context.
 *
 * @version $Id$
 */
class DoapUserMessageHandlersTest
{
    private final DoapUserSynchronizer synchronizer = mock(DoapUserSynchronizer.class);

    private final ExecutionContextManager executionContextManager = mock(ExecutionContextManager.class);

    private final Execution execution = mock(Execution.class);

    private final Provider<XWikiContext> contextProvider = mock(Provider.class);

    private final XWikiContext context = mock(XWikiContext.class);

    @BeforeEach
    void setUp()
    {
        when(this.contextProvider.get()).thenReturn(this.context);
    }

    @Test
    void createHandlerMatchesCreateTagAndDelegates() throws Exception
    {
        DoapUserCreateHandler handler = new DoapUserCreateHandler();
        wire(handler);
        assertEquals(DoapMqConstants.TAG_USER_CREATE, handler.getTag());

        DoapUserMessage message = message();
        handler.handle(message);

        verify(this.synchronizer).sync(message, this.context);
        verify(this.execution).removeContext();
    }

    @Test
    void updateHandlerMatchesUpdateTagAndDelegates() throws Exception
    {
        DoapUserUpdateHandler handler = new DoapUserUpdateHandler();
        wire(handler);
        assertEquals(DoapMqConstants.TAG_USER_UPDATE, handler.getTag());

        DoapUserMessage message = message();
        handler.handle(message);

        verify(this.synchronizer).sync(message, this.context);
        verify(this.execution).removeContext();
    }

    @Test
    void deleteHandlerMatchesDeleteTagAndDelegates() throws Exception
    {
        DoapUserDeleteHandler handler = new DoapUserDeleteHandler();
        wire(handler);
        assertEquals(DoapMqConstants.TAG_USER_DELETE, handler.getTag());

        DoapUserMessage message = message();
        handler.handle(message);

        verify(this.synchronizer).sync(message, this.context);
        verify(this.execution).removeContext();
    }

    private void wire(AbstractDoapUserMessageHandler handler)
    {
        handler.synchronizer = this.synchronizer;
        handler.executionContextManager = this.executionContextManager;
        handler.execution = this.execution;
        handler.contextProvider = this.contextProvider;
    }

    private static DoapUserMessage message()
    {
        return new DoapUserMessage(DoapMqConstants.TOPIC_DOAP_USER, "tag", "msg-1", 0, "{}", Map.of());
    }
}
