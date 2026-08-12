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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.oa.sync.mq.DoapMqConfiguration;
import org.xwiki.oa.sync.mq.model.DoapUserMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultDoapMqConsumer}.
 *
 * @version $Id$
 */
class DefaultDoapMqConsumerTest
{
    private final DoapMqConfiguration configuration = mock(DoapMqConfiguration.class);

    private final DoapUserMessageParser parser = mock(DoapUserMessageParser.class);

    private final DoapUserMessageDispatcher dispatcher = mock(DoapUserMessageDispatcher.class);

    private final TestConsumer consumer = new TestConsumer();

    @BeforeEach
    void setUp()
    {
        this.consumer.configuration = this.configuration;
        this.consumer.parser = this.parser;
        this.consumer.dispatcher = this.dispatcher;
    }

    @Test
    void onMessageParsesAndDispatches()
    {
        DoapUserMessage message = new DoapUserMessage("TOPIC_DOAP_USER", "TAG_USER_CREATE", "msg-1", 0, "{}",
            java.util.Map.of());
        when(this.parser.parse("TOPIC_DOAP_USER", "TAG_USER_CREATE", "msg-1", 0, "{}")).thenReturn(message);
        when(this.dispatcher.dispatch(message)).thenReturn(true);

        assertTrue(this.consumer.onMessage("TOPIC_DOAP_USER", "TAG_USER_CREATE", "msg-1", 0, "{}"));
        verify(this.dispatcher).dispatch(message);
    }

    @Test
    void onMessagePropagatesDispatchFailure()
    {
        DoapUserMessage message = new DoapUserMessage("TOPIC_DOAP_USER", "TAG_UNKNOWN", "msg-2", 1, "{}",
            java.util.Map.of());
        when(this.parser.parse("TOPIC_DOAP_USER", "TAG_UNKNOWN", "msg-2", 1, "{}")).thenReturn(message);
        when(this.dispatcher.dispatch(message)).thenReturn(false);

        assertFalse(this.consumer.onMessage("TOPIC_DOAP_USER", "TAG_UNKNOWN", "msg-2", 1, "{}"));
    }

    @Test
    void startSkipsWhenDisabled()
    {
        when(this.configuration.isEnabled()).thenReturn(false);
        this.consumer.start();
        assertFalse(this.consumer.connectCalled);
        assertFalse(this.consumer.isRunning());
    }

    @Test
    void startConnectsWhenEnabledAndIsIdempotent() throws Exception
    {
        when(this.configuration.isEnabled()).thenReturn(true);
        this.consumer.start();
        this.consumer.start();
        assertTrue(this.consumer.connectCalled);
        assertEquals(1, this.consumer.connectCount);
        assertTrue(this.consumer.isRunning());

        this.consumer.stop();
        assertFalse(this.consumer.isRunning());
        assertTrue(this.consumer.shutdownCalled);
    }

    /**
     * Exposes the protected lifecycle hooks for verification.
     */
    private static class TestConsumer extends DefaultDoapMqConsumer
    {
        private boolean connectCalled;

        private int connectCount;

        private boolean shutdownCalled;

        @Override
        protected void connectAndSubscribe()
        {
            this.connectCalled = true;
            this.connectCount++;
        }

        @Override
        protected void shutdownClient()
        {
            this.shutdownCalled = true;
        }
    }
}
