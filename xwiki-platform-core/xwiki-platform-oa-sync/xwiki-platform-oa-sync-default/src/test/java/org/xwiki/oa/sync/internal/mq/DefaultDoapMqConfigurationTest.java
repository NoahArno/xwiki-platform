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

import org.junit.jupiter.api.Test;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.oa.sync.mq.DoapMqConstants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultDoapMqConfiguration}.
 *
 * @version $Id$
 */
class DefaultDoapMqConfigurationTest
{
    private final ConfigurationSource source = mock(ConfigurationSource.class);

    private final DefaultDoapMqConfiguration config = new DefaultDoapMqConfiguration();

    @Test
    void readsMqConfig()
    {
        this.config.configuration = this.source;
        when(this.source.getProperty("xwiki.doap-mq.enabled", Boolean.class)).thenReturn(true);
        when(this.source.getProperty("xwiki.doap-mq.name-server", String.class))
            .thenReturn("10.0.0.1:9876;10.0.0.2:9876");
        when(this.source.getProperty("xwiki.doap-mq.consumer-group", String.class)).thenReturn("GID_XWIKI_DOAP");
        when(this.source.getProperty("xwiki.doap-mq.topic", String.class)).thenReturn("TOPIC_DOAP_USER");
        when(this.source.getProperty("xwiki.doap-mq.tags", String.class))
            .thenReturn("TAG_USER_CREATE,TAG_USER_UPDATE");
        when(this.source.getProperty("xwiki.doap-mq.consume-thread-min", Integer.class)).thenReturn(2);
        when(this.source.getProperty("xwiki.doap-mq.consume-thread-max", Integer.class)).thenReturn(16);

        assertTrue(this.config.isEnabled());
        assertEquals("10.0.0.1:9876;10.0.0.2:9876", this.config.getNameServer());
        assertEquals("GID_XWIKI_DOAP", this.config.getConsumerGroup());
        assertEquals("TOPIC_DOAP_USER", this.config.getTopic());
        assertEquals(List.of("TAG_USER_CREATE", "TAG_USER_UPDATE"), this.config.getTags());
        assertEquals(2, this.config.getConsumeThreadMin());
        assertEquals(16, this.config.getConsumeThreadMax());
    }

    @Test
    void usesDefaultsWhenNotConfigured()
    {
        this.config.configuration = this.source;
        assertFalse(this.config.isEnabled());
        assertEquals("", this.config.getNameServer());
        assertEquals(DoapMqConstants.TOPIC_DOAP_USER, this.config.getTopic());
        assertEquals(DefaultDoapMqConfiguration.DEFAULT_TAGS, this.config.getTags());
        assertEquals(1, this.config.getConsumeThreadMin());
        assertEquals(8, this.config.getConsumeThreadMax());
    }

    @Test
    void fallsBackToDefaultTagsOnBlankConfig()
    {
        this.config.configuration = this.source;
        when(this.source.getProperty("xwiki.doap-mq.tags", String.class)).thenReturn("  ");
        assertEquals(DefaultDoapMqConfiguration.DEFAULT_TAGS, this.config.getTags());
    }
}
