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
        // 刻意用 String.class 读取：XWiki 配置源底层是 commons-beanutils 的 ConvertUtils，
        // 其 IntegerConverter 对缺失/非法输入返回 0（**不是 null**），
        // 因此"value == null ? default : value"这种写法下默认值永远不会生效，
        // 缺失的整型配置会静默变成 0（consume-thread-min/max 缺失时会变成 0，
        // 直接传给 consumer.setConsumeThreadMin/Max(0) 会破坏消费者）。
        String value = this.configuration.getProperty(key, String.class);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            // 配错值时回落到默认值，而不是静默变成 0
            return defaultValue;
        }
    }

    private boolean getBoolean(String key, boolean defaultValue)
    {
        // 同理：BooleanConverter 对缺失/非法输入返回 false，不能靠 null 判断
        String value = this.configuration.getProperty(key, String.class);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        return defaultValue;
    }
}
