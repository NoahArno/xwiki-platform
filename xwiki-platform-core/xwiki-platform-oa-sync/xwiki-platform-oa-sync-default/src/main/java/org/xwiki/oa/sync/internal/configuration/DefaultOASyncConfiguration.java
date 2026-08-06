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
package org.xwiki.oa.sync.internal.configuration;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.oa.sync.OASyncConfiguration;

/**
 * Reads OA sync configuration from {@code xwiki.cfg} (keys prefixed with {@code xwiki.oa-sync.}).
 *
 * @version $Id$
 */
@Component(roles = OASyncConfiguration.class)
@Singleton
public class DefaultOASyncConfiguration implements OASyncConfiguration
{
    @Inject
    @Named("xwikicfg")
    ConfigurationSource configuration;

    @Override
    public String getFtpHost()
    {
        return get("xwiki.oa-sync.ftp.host", "");
    }

    @Override
    public int getFtpPort()
    {
        return getInt("xwiki.oa-sync.ftp.port", 21);
    }

    @Override
    public String getFtpUsername()
    {
        return get("xwiki.oa-sync.ftp.username", "");
    }

    @Override
    public String getFtpPassword()
    {
        return get("xwiki.oa-sync.ftp.password", "");
    }

    @Override
    public int getFtpTimeoutMs()
    {
        return getInt("xwiki.oa-sync.ftp.timeout-ms", 30000);
    }

    @Override
    public boolean isSourceEnabled(String type)
    {
        return getBoolean("xwiki.oa-sync.source." + type + ".enabled", false);
    }

    @Override
    public String getSourceBasePath(String type)
    {
        return get("xwiki.oa-sync.source." + type + ".base-path", "");
    }

    @Override
    public String getSourceFilePattern(String type)
    {
        return get("xwiki.oa-sync.source." + type + ".file-pattern", "");
    }

    @Override
    public String getSourceEncoding(String type)
    {
        return get("xwiki.oa-sync.source." + type + ".encoding", "GBK");
    }

    @Override
    public int getSourceDateOffsetDays(String type)
    {
        return getInt("xwiki.oa-sync.source." + type + ".date-offset-days", 1);
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
