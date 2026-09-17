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
package org.xwiki.oa.sync.internal.source;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xwiki.oa.sync.OASyncConfiguration;
import org.xwiki.oa.sync.model.OASyncParseResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutsourcingOASyncSource}: verifies the source wires the configured default organization
 * into the parser, which is the piece that cannot be covered by {@link OutsourcingFileParserTest}.
 *
 * @version $Id$
 */
class OutsourcingOASyncSourceTest
{
    @TempDir
    Path tempDir;

    private static final String SEP = "\u0006";

    private OutsourcingOASyncSource newSource(OASyncConfiguration configuration)
    {
        OutsourcingOASyncSource source = new OutsourcingOASyncSource();
        source.configuration = configuration;
        return source;
    }

    private Path writeBlankOrgFile() throws Exception
    {
        String[] f = new String[20];
        f[1] = "90001";
        f[9] = "1";
        f[18] = "  ";
        f[19] = "";
        Path file = this.tempDir.resolve("data.dat");
        Files.write(file, (String.join(SEP, f) + "\n").getBytes(Charset.forName("GBK")));
        return file;
    }

    @Test
    void parseFillsConfiguredDefaultOrgWhenOrgCodeIsBlank() throws Exception
    {
        OASyncConfiguration configuration = mock(OASyncConfiguration.class);
        when(configuration.getSourceEncoding("outsourcing")).thenReturn("GBK");
        when(configuration.getDefaultOrgCode()).thenReturn("9999");
        when(configuration.getDefaultOrgName()).thenReturn("其他机构");

        OASyncParseResult result = this.newSource(configuration).parse(writeBlankOrgFile());

        assertEquals(0, result.getErrors().size());
        assertEquals("9999_1", result.getRecords().get(0).getOrgCode());
        assertEquals("其他机构_外包组", result.getRecords().get(0).getOrgName());
    }

    @Test
    void parseFallsBackToBuiltInDefaultsForBlankOrg() throws Exception
    {
        OASyncConfiguration configuration = mock(OASyncConfiguration.class);
        when(configuration.getSourceEncoding("outsourcing")).thenReturn("GBK");
        when(configuration.getDefaultOrgCode()).thenCallRealMethod();
        when(configuration.getDefaultOrgName()).thenCallRealMethod();

        OASyncParseResult result = this.newSource(configuration).parse(writeBlankOrgFile());

        assertEquals(0, result.getErrors().size());
        assertEquals("0_1", result.getRecords().get(0).getOrgCode());
        assertEquals("未知机构_外包组", result.getRecords().get(0).getOrgName());
    }
}
