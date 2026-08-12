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

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.oa.sync.mq.model.DoapUserMessage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Converts a raw MQ message into a {@link DoapUserMessage}, parsing the JSON body into a generic
 * field map. Unparseable bodies are kept as-is with an empty field map so they can still be logged.
 *
 * @version $Id$
 */
@Component(roles = DoapUserMessageParser.class)
@Singleton
public class DoapUserMessageParser
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>()
    {
    };

    /**
     * @param topic the MQ topic
     * @param tag the MQ tag
     * @param msgId the MQ message id
     * @param reconsumeTimes how many times the message has been re-consumed
     * @param body the raw message body
     * @return the parsed message
     */
    public DoapUserMessage parse(String topic, String tag, String msgId, int reconsumeTimes, String body)
    {
        return new DoapUserMessage(topic, tag, msgId, reconsumeTimes, body, parseBody(body));
    }

    private Map<String, Object> parseBody(String body)
    {
        if (body == null || body.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return OBJECT_MAPPER.readValue(body, MAP_TYPE);
        } catch (IOException e) {
            // 字段未定稿，保留原始 body 由上层记录日志，不阻断消费
            return Collections.emptyMap();
        }
    }
}
