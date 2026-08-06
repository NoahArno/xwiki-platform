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
    private final ConfigurationSource source = mock(ConfigurationSource.class);

    private final DefaultOASyncConfiguration config = new DefaultOASyncConfiguration();

    @Test
    void readsFtpConfig()
    {
        this.config.configuration = this.source;
        when(this.source.getProperty("xwiki.oa-sync.ftp.host", String.class)).thenReturn("ftp.example.com");
        when(this.source.getProperty("xwiki.oa-sync.ftp.port", Integer.class)).thenReturn(2121);
        when(this.source.getProperty("xwiki.oa-sync.ftp.username", String.class)).thenReturn("u");
        when(this.source.getProperty("xwiki.oa-sync.ftp.password", String.class)).thenReturn("p");
        when(this.source.getProperty("xwiki.oa-sync.ftp.timeout-ms", Integer.class)).thenReturn(60000);

        assertEquals("ftp.example.com", this.config.getFtpHost());
        assertEquals(2121, this.config.getFtpPort());
        assertEquals("u", this.config.getFtpUsername());
        assertEquals("p", this.config.getFtpPassword());
        assertEquals(60000, this.config.getFtpTimeoutMs());
    }

    @Test
    void readsPerSourceConfigWithDefaults()
    {
        this.config.configuration = this.source;
        when(this.source.getProperty("xwiki.oa-sync.source.employee.enabled", Boolean.class)).thenReturn(true);
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
}
