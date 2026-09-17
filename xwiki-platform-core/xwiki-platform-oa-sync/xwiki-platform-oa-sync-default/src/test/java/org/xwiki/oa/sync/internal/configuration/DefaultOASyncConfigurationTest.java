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

import org.junit.jupiter.api.Test;
import org.xwiki.configuration.ConfigurationSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultOASyncConfiguration}.
 *
 * @version $Id$
 */
class DefaultOASyncConfigurationTest
{
    /**
     * 模拟真实配置源的"转换器自带默认值"行为。
     * <p>
     * XWiki 配置源底层是 commons-beanutils 的 {@code ConvertUtils}：缺失的键读成 {@code Integer} 会得到
     * <b>0</b>、读成 {@code Boolean} 会得到 <b>false</b>（而不是 {@code null}）。
     * 若这里用普通的 {@code mock(...)}（未 stub 的调用返回 null），
     * "缺失键导致默认值失效"这个真实缺陷在单测里就**无法复现**——第一版的回归测试就因此是假的。
     */
    private final ConfigurationSource source = mock(ConfigurationSource.class, invocation -> {
        if ("getProperty".equals(invocation.getMethod().getName())
            && invocation.getArguments().length == 2
            && invocation.getArgument(1) instanceof Class) {
            Class<?> type = invocation.getArgument(1);
            if (type == Integer.class) {
                return 0;
            }
            if (type == Boolean.class) {
                return false;
            }
        }
        return null;
    });

    private final DefaultOASyncConfiguration config = new DefaultOASyncConfiguration();

    @Test
    void readsFtpConfig()
    {
        this.config.configuration = this.source;
        when(this.source.getProperty("xwiki.oa-sync.ftp.host", String.class)).thenReturn("ftp.example.com");
        when(this.source.getProperty("xwiki.oa-sync.ftp.port", String.class)).thenReturn("2121");
        when(this.source.getProperty("xwiki.oa-sync.ftp.username", String.class)).thenReturn("u");
        when(this.source.getProperty("xwiki.oa-sync.ftp.password", String.class)).thenReturn("p");
        when(this.source.getProperty("xwiki.oa-sync.ftp.timeout-ms", String.class)).thenReturn("60000");

        assertEquals("ftp.example.com", this.config.getFtpHost());
        assertEquals(2121, this.config.getFtpPort());
        assertEquals("u", this.config.getFtpUsername());
        assertEquals("p", this.config.getFtpPassword());
        assertEquals(60000, this.config.getFtpTimeoutMs());
    }


    @Test
    void employeeDefaultsWithoutExplicitConfig()
    {
        this.config.configuration = this.source;
        assertEquals("/comm/bdpp/oa/%s", this.config.getSourceBasePath("employee"));
        assertEquals("IOA_EMPLOYEE_JGTY_%s.dat", this.config.getSourceFilePattern("employee"));
        assertEquals("GBK", this.config.getSourceEncoding("employee"));
        assertEquals(1, this.config.getSourceDateOffsetDays("employee"));
        assertFalse(this.config.isSourceEnabled("employee"));
    }

    @Test
    void outsourcingDefaultsWithoutExplicitConfig()
    {
        this.config.configuration = this.source;
        assertEquals("/comm/bdpp/oa/non/%s", this.config.getSourceBasePath("outsourcing"));
        assertEquals("IOA_NON_EMPLOYEE_WIKI_%s.dat", this.config.getSourceFilePattern("outsourcing"));
        assertEquals("GBK", this.config.getSourceEncoding("outsourcing"));
        assertEquals(1, this.config.getSourceDateOffsetDays("outsourcing"));
        assertFalse(this.config.isSourceEnabled("outsourcing"));
    }

    @Test
    void defaultOrgDefaultsWithoutExplicitConfig()
    {
        this.config.configuration = this.source;

        assertEquals("0", this.config.getDefaultOrgCode());
        assertEquals("未知机构", this.config.getDefaultOrgName());
    }

    @Test
    void explicitDefaultOrgConfigOverridesDefaults()
    {
        this.config.configuration = this.source;
        when(this.source.getProperty("xwiki.oa-sync.default-org.code", String.class)).thenReturn("9999");
        when(this.source.getProperty("xwiki.oa-sync.default-org.name", String.class)).thenReturn("其他机构");

        assertEquals("9999", this.config.getDefaultOrgCode());
        assertEquals("其他机构", this.config.getDefaultOrgName());
    }

    @Test
    void explicitOutsourcingConfigOverridesDefaults()
    {
        this.config.configuration = this.source;
        when(this.source.getProperty("xwiki.oa-sync.source.outsourcing.enabled", String.class)).thenReturn("true");
        when(this.source.getProperty("xwiki.oa-sync.source.outsourcing.base-path", String.class))
            .thenReturn("/custom/%s");
        when(this.source.getProperty("xwiki.oa-sync.source.outsourcing.file-pattern", String.class))
            .thenReturn("OTHER_%s.dat");

        assertTrue(this.config.isSourceEnabled("outsourcing"));
        assertEquals("/custom/%s", this.config.getSourceBasePath("outsourcing"));
        assertEquals("OTHER_%s.dat", this.config.getSourceFilePattern("outsourcing"));
    }

    @Test
    void readsPerSourceConfigWithDefaults()
    {
        this.config.configuration = this.source;
        when(this.source.getProperty("xwiki.oa-sync.source.employee.enabled", String.class)).thenReturn("true");
        when(this.source.getProperty("xwiki.oa-sync.source.employee.base-path", String.class))
            .thenReturn("/comm/bdpp/oa/%s");
        when(this.source.getProperty("xwiki.oa-sync.source.employee.file-pattern", String.class))
            .thenReturn("IOA_EMPLOYEE_JGTY_%s.dat");

        assertTrue(this.config.isSourceEnabled("employee"));
        assertEquals("/comm/bdpp/oa/%s", this.config.getSourceBasePath("employee"));
        assertEquals("IOA_EMPLOYEE_JGTY_%s.dat", this.config.getSourceFilePattern("employee"));
        assertEquals("GBK", this.config.getSourceEncoding("employee"));
        assertEquals(1, this.config.getSourceDateOffsetDays("employee"));
        assertFalse(this.config.isSourceEnabled("outsourcing"));
    }

    @Test
    void excelImportThreadsDefaultsToFour()
    {
        this.config.configuration = this.source;

        assertEquals(4, this.config.getExcelImportThreads());
    }

    @Test
    void excelImportThreadsIsClampedToValidRange()
    {
        this.config.configuration = this.source;

        when(this.source.getProperty("xwiki.oa-sync.excel.threads", String.class)).thenReturn("0");
        assertEquals(1, this.config.getExcelImportThreads());

        when(this.source.getProperty("xwiki.oa-sync.excel.threads", String.class)).thenReturn("99");
        assertEquals(8, this.config.getExcelImportThreads());

        when(this.source.getProperty("xwiki.oa-sync.excel.threads", String.class)).thenReturn("6");
        assertEquals(6, this.config.getExcelImportThreads());
    }

    @Test
    void intAndBooleanDefaultsActuallyApplyWhenTheKeyIsUnset()
    {
        this.config.configuration = this.source;

        // 回归：这些默认值在真实实例上曾经全部失效（缺失的整型配置被 beanutils 转成 0）
        assertEquals(21, this.config.getFtpPort());
        assertEquals(30000, this.config.getFtpTimeoutMs());
        assertEquals(1, this.config.getSourceDateOffsetDays("outsourcing"));
        assertEquals(4, this.config.getExcelImportThreads());
        assertFalse(this.config.isSourceEnabled("outsourcing"));
    }

    @Test
    void intConfigFallsBackToDefaultWhenBlankOrNotANumber()
    {
        this.config.configuration = this.source;

        when(this.source.getProperty("xwiki.oa-sync.excel.threads", String.class)).thenReturn("   ");
        assertEquals(4, this.config.getExcelImportThreads(), "空白应回落默认值 4");

        when(this.source.getProperty("xwiki.oa-sync.excel.threads", String.class)).thenReturn("not-a-number");
        assertEquals(4, this.config.getExcelImportThreads(), "非数字应回落默认值 4，而不是静默变 0");

        when(this.source.getProperty("xwiki.oa-sync.excel.threads", String.class)).thenReturn("6");
        assertEquals(6, this.config.getExcelImportThreads(), "显式配置 6 应生效");
    }

    @Test
    void booleanConfigFallsBackToDefaultWhenBlankOrNotABoolean()
    {
        this.config.configuration = this.source;

        when(this.source.getProperty("xwiki.oa-sync.source.outsourcing.enabled", String.class))
            .thenReturn("not-a-boolean");
        assertFalse(this.config.isSourceEnabled("outsourcing"), "非法布尔值应回落默认 false");

        when(this.source.getProperty("xwiki.oa-sync.source.outsourcing.enabled", String.class))
            .thenReturn("TRUE");
        assertTrue(this.config.isSourceEnabled("outsourcing"), "TRUE 应被识别为 true");
    }
}
