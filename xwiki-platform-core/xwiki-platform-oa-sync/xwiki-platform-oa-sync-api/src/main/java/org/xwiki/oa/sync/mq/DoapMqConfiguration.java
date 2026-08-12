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
package org.xwiki.oa.sync.mq;

import java.util.List;

import org.xwiki.component.annotation.Role;

/**
 * Configuration for the DOAP user data MQ consumer, read from {@code xwiki.cfg}.
 * <p>
 * Keys are prefixed with {@code xwiki.doap-mq.} (see
 * {@code DefaultDoapMqConfiguration}).
 *
 * @version $Id$
 */
@Role
public interface DoapMqConfiguration
{
    /**
     * @return whether the DOAP MQ consumer is enabled
     */
    boolean isEnabled();

    /**
     * @return the MQ cluster name server / endpoints (e.g. {@code 10.0.0.1:9876;10.0.0.2:9876}), may be empty
     */
    String getNameServer();

    /**
     * @return the consumer group id
     */
    String getConsumerGroup();

    /**
     * @return the topic to subscribe, defaults to {@link DoapMqConstants#TOPIC_DOAP_USER}
     */
    String getTopic();

    /**
     * @return the tags to subscribe, defaults to all {@code TAG_USER_*} tags
     */
    List<String> getTags();

    /**
     * @return the minimum number of message consume threads
     */
    int getConsumeThreadMin();

    /**
     * @return the maximum number of message consume threads
     */
    int getConsumeThreadMax();
}
