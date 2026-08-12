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

import org.junit.jupiter.api.Test;
import org.xwiki.oa.sync.mq.model.DoapUserMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DoapUserMessageParser}.
 *
 * @version $Id$
 */
class DoapUserMessageParserTest
{
    private final DoapUserMessageParser parser = new DoapUserMessageParser();

    @Test
    void parsesJsonBodyIntoFields()
    {
        String body = "{\"username\":\"T00123\",\"orgCode\":\"ORG_TECH\",\"orgName\":\"科技外包一部\",\"active\":true}";
        DoapUserMessage message = this.parser.parse("TOPIC_DOAP_USER", "TAG_USER_CREATE", "msg-1", 0, body);

        assertEquals("TOPIC_DOAP_USER", message.getTopic());
        assertEquals("TAG_USER_CREATE", message.getTag());
        assertEquals("msg-1", message.getMsgId());
        assertEquals(0, message.getReconsumeTimes());
        assertEquals("T00123", message.getString("username"));
        assertEquals("ORG_TECH", message.getString("orgCode"));
        assertEquals("科技外包一部", message.getString("orgName"));
        assertEquals(Boolean.TRUE, message.getBoolean("active"));
    }

    @Test
    void keepsRawBodyWithEmptyFieldsForNonJsonBody()
    {
        DoapUserMessage message = this.parser.parse("TOPIC_DOAP_USER", "TAG_USER_UPDATE", "msg-2", 1, "not-json");

        assertEquals("not-json", message.getBody());
        assertTrue(message.getFields().isEmpty());
        assertNull(message.getString("username"));
    }

    @Test
    void handlesNullAndBlankBody()
    {
        assertTrue(this.parser.parse("TOPIC_DOAP_USER", "TAG_USER_DELETE", "msg-3", 0, null).getFields().isEmpty());
        assertTrue(this.parser.parse("TOPIC_DOAP_USER", "TAG_USER_DELETE", "msg-3", 0, "  ").getFields().isEmpty());
    }
}
