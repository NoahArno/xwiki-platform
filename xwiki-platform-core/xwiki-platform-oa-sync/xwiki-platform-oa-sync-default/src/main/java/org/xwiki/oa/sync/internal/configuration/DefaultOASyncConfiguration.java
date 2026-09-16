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
        return get("xwiki.oa-sync.source." + type + ".base-path", defaultSourceBasePath(type));
    }

    @Override
    public String getSourceFilePattern(String type)
    {
        return get("xwiki.oa-sync.source." + type + ".file-pattern", defaultSourceFilePattern(type));
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

    private String defaultSourceBasePath(String type)
    {
        if ("employee".equals(type)) {
            return "/comm/bdpp/oa/%s";
        }
        if ("outsourcing".equals(type)) {
            return "/comm/bdpp/oa/non/%s";
        }
        return "";
    }

    private String defaultSourceFilePattern(String type)
    {
        if ("employee".equals(type)) {
            return "IOA_EMPLOYEE_JGTY_%s.dat";
        }
        if ("outsourcing".equals(type)) {
            return "IOA_NON_EMPLOYEE_WIKI_%s.dat";
        }
        return "";
    }

    // String 的转换器对缺失键返回 null，语义正常，因此这里不需要改。
    private String get(String key, String defaultValue)
    {
        String value = this.configuration.getProperty(key, String.class);
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    @Override
    public int getExcelImportThreads()
    {
        int threads = getInt("xwiki.oa-sync.excel.threads", 4);
        return Math.max(1, Math.min(threads, 8));
    }

    private int getInt(String key, int defaultValue)
    {
        // 刻意用 String.class 读取：XWiki 配置源底层是 commons-beanutils 的 ConvertUtils，
        // 其 IntegerConverter 对缺失/非法输入返回 0（**不是 null**），
        // 因此"value == null ? default : value"这种写法下默认值永远不会生效，
        // 缺失的整型配置会静默变成 0（真实实例上 xwiki.oa-sync.excel.threads 就是这样变成 1 的）。
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
