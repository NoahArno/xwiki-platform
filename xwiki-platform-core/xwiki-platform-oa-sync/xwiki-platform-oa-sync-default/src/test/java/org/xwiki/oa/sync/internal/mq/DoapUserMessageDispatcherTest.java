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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.oa.sync.mq.DoapMqConstants;
import org.xwiki.oa.sync.mq.DoapUserMessageHandler;
import org.xwiki.oa.sync.mq.model.DoapUserMessage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DoapUserMessageDispatcher}.
 *
 * @version $Id$
 */
class DoapUserMessageDispatcherTest
{
    private final ComponentManager componentManager = mock(ComponentManager.class);

    private final DoapUserMessageDispatcher dispatcher = new DoapUserMessageDispatcher();

    private final DoapUserMessageHandler createHandler = mock(DoapUserMessageHandler.class);

    private final DoapUserMessageHandler updateHandler = mock(DoapUserMessageHandler.class);

    private final DoapUserMessageHandler deleteHandler = mock(DoapUserMessageHandler.class);

    @BeforeEach
    void setUp() throws ComponentLookupException
    {
        this.dispatcher.componentManager = this.componentManager;
        when(this.createHandler.getTag()).thenReturn(DoapMqConstants.TAG_USER_CREATE);
        when(this.updateHandler.getTag()).thenReturn(DoapMqConstants.TAG_USER_UPDATE);
        when(this.deleteHandler.getTag()).thenReturn(DoapMqConstants.TAG_USER_DELETE);
        when(this.componentManager.getInstanceList(DoapUserMessageHandler.class))
            .thenReturn(List.of(this.createHandler, this.updateHandler, this.deleteHandler));
    }

    @Test
    void routesMessageToMatchingHandler() throws Exception
    {
        DoapUserMessage message = message(DoapMqConstants.TAG_USER_CREATE);

        assertTrue(this.dispatcher.dispatch(message));
        verify(this.createHandler).handle(message);
    }

    @Test
    void returnsFalseWhenNoHandlerMatches()
    {
        assertFalse(this.dispatcher.dispatch(message("TAG_UNKNOWN")));
    }

    @Test
    void returnsFalseWhenHandlerFails() throws Exception
    {
        doThrow(new IllegalStateException("boom")).when(this.updateHandler).handle(any(DoapUserMessage.class));

        assertFalse(this.dispatcher.dispatch(message(DoapMqConstants.TAG_USER_UPDATE)));
    }

    @Test
    void returnsFalseWhenHandlerLookupFails() throws ComponentLookupException
    {
        when(this.componentManager.getInstanceList(DoapUserMessageHandler.class))
            .thenThrow(new ComponentLookupException("no components"));

        assertFalse(this.dispatcher.dispatch(message(DoapMqConstants.TAG_USER_CREATE)));
    }

    private static DoapUserMessage message(String tag)
    {
        return new DoapUserMessage(DoapMqConstants.TOPIC_DOAP_USER, tag, "msg-1", 0, "{}", java.util.Map.of());
    }
}
