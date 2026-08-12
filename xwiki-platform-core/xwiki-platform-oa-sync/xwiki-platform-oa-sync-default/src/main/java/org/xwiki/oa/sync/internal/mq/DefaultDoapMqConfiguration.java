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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.oa.sync.mq.DoapMqConfiguration;
import org.xwiki.oa.sync.mq.DoapMqConstants;

/**
 * Reads the DOAP MQ consumer configuration from {@code xwiki.cfg} (keys prefixed with
 * {@code xwiki.doap-mq.}).
 *
 * @version $Id$
 */
@Component(roles = DoapMqConfiguration.class)
@Singleton
public class DefaultDoapMqConfiguration implements DoapMqConfiguration
{
    /**
     * Default tags subscribed when no explicit tag list is configured.
     */
    static final List<String> DEFAULT_TAGS =
        List.of(DoapMqConstants.TAG_USER_CREATE, DoapMqConstants.TAG_USER_UPDATE, DoapMqConstants.TAG_USER_DELETE);

    @Inject
    @Named("xwikicfg")
    ConfigurationSource configuration;

    @Override
    public boolean isEnabled()
    {
        return getBoolean("xwiki.doap-mq.enabled", false);
    }

    @Override
    public String getNameServer()
    {
        return get("xwiki.doap-mq.name-server", "");
    }

    @Override
    public String getConsumerGroup()
    {
        return get("xwiki.doap-mq.consumer-group", "");
    }

    @Override
    public String getTopic()
    {
        return get("xwiki.doap-mq.topic", DoapMqConstants.TOPIC_DOAP_USER);
    }

    @Override
    public List<String> getTags()
    {
        String raw = get("xwiki.doap-mq.tags", "");
        if (raw.isBlank()) {
            return DEFAULT_TAGS;
        }
        List<String> tags = new ArrayList<>();
        for (String tag : raw.split(",")) {
            if (!tag.isBlank()) {
                tags.add(tag.trim());
            }
        }
        return tags.isEmpty() ? DEFAULT_TAGS : Collections.unmodifiableList(tags);
    }

    @Override
    public int getConsumeThreadMin()
    {
        return getInt("xwiki.doap-mq.consume-thread-min", 1);
    }

    @Override
    public int getConsumeThreadMax()
    {
        return getInt("xwiki.doap-mq.consume-thread-max", 8);
    }

    private String get(String key, String defaultValue)
    {
        String value = this.configuration.getProperty(key, String.class);
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    private int getInt(String key, int defaultValue)
    {
        Integer value = this.configuration.getProperty(key, Integer.class);
        return value == null ? defaultValue : value;
    }

    private boolean getBoolean(String key, boolean defaultValue)
    {
        Boolean value = this.configuration.getProperty(key, Boolean.class);
        return value == null ? defaultValue : value;
    }
}
