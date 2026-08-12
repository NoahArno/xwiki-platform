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
package org.xwiki.oa.sync.mq.model;

import java.util.Collections;
import java.util.Map;

/**
 * A DOAP user change message consumed from MQ.
 * <p>
 * The concrete message fields are not finalized yet, so the JSON body is kept
 * as a generic field map. Once the upstream contract is fixed, either add typed
 * getters here or map the fields in the tag handlers.
 *
 * @version $Id$
 */
public class DoapUserMessage
{
    private final String topic;

    private final String tag;

    private final String msgId;

    private final int reconsumeTimes;

    private final String body;

    private final Map<String, Object> fields;

    /**
     * @param topic the MQ topic
     * @param tag the MQ tag
     * @param msgId the MQ message id (for logging / dedup)
     * @param reconsumeTimes how many times this message has been re-consumed
     * @param body the raw message body
     * @param fields the JSON body parsed as a field map (may be empty when the body is not JSON)
     */
    public DoapUserMessage(String topic, String tag, String msgId, int reconsumeTimes, String body,
        Map<String, Object> fields)
    {
        this.topic = topic;
        this.tag = tag;
        this.msgId = msgId;
        this.reconsumeTimes = reconsumeTimes;
        this.body = body;
        this.fields = fields != null ? fields : Collections.emptyMap();
    }

    /**
     * @return the MQ topic
     */
    public String getTopic()
    {
        return this.topic;
    }

    /**
     * @return the MQ tag (create / update / delete)
     */
    public String getTag()
    {
        return this.tag;
    }

    /**
     * @return the MQ message id
     */
    public String getMsgId()
    {
        return this.msgId;
    }

    /**
     * @return how many times this message has been re-consumed
     */
    public int getReconsumeTimes()
    {
        return this.reconsumeTimes;
    }

    /**
     * @return the raw message body
     */
    public String getBody()
    {
        return this.body;
    }

    /**
     * @return the JSON body parsed as a field map (unmodifiable)
     */
    public Map<String, Object> getFields()
    {
        return this.fields;
    }

    /**
     * @param key the field name
     * @return the string value of the field, or {@code null} if absent / not a string
     */
    public String getString(String key)
    {
        Object value = this.fields.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * @param key the field name
     * @param defaultValue the value returned when the field is absent / not a string
     * @return the string value of the field
     */
    public String getString(String key, String defaultValue)
    {
        String value = getString(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * @param key the field name
     * @return the integer value of the field, or {@code null} if absent / not a number
     */
    public Integer getInteger(String key)
    {
        Object value = this.fields.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.valueOf(value.toString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * @param key the field name
     * @return the boolean value of the field, or {@code null} if absent / not a boolean
     */
    public Boolean getBoolean(String key)
    {
        Object value = this.fields.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value != null) {
            String text = value.toString().trim();
            if ("true".equalsIgnoreCase(text)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(text)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    @Override
    public String toString()
    {
        return "DoapUserMessage{topic='" + this.topic + '\''
            + ", tag='" + this.tag + '\''
            + ", msgId='" + this.msgId + '\''
            + ", reconsumeTimes=" + this.reconsumeTimes
            + ", fields=" + this.fields + '}';
    }
}
