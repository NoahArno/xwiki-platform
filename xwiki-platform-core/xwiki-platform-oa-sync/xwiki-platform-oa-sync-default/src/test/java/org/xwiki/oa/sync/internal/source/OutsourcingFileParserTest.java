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

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xwiki.oa.sync.model.OAUserRecord;
import org.xwiki.oa.sync.model.OASyncParseResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OutsourcingFileParser}.
 *
 * @version $Id$
 */
class OutsourcingFileParserTest
{
    @TempDir
    Path tempDir;

    private static final String SEP = "\u0006";

    private final OutsourcingFileParser parser = new OutsourcingFileParser();

    private Path writeFile(String... lines) throws IOException
    {
        Path file = this.tempDir.resolve("data.dat");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    private String line(String... fields)
    {
        return String.join(SEP, fields);
    }

    private String[] fields20()
    {
        return new String[20];
    }

    @Test
    void parsesActiveOutsourcingUserWithSuffixedOrg() throws Exception
    {
        String[] f = this.fields20();
        f[1] = "90001";          // 序号2 工号
        f[9] = "1";              // 序号10 账号状态=启用
        f[18] = "7701";          // 序号19 归口管理部门机构编号
        f[19] = "运营外包一部";    // 序号20 归口管理部门机构中文名

        OASyncParseResult result = this.parser.parse(writeFile(this.line(f)), StandardCharsets.UTF_8);
        assertEquals(0, result.getErrors().size());
        OAUserRecord r = result.getRecords().get(0);
        assertEquals("90001", r.getUsername());
        assertEquals("7701_1", r.getOrgCode());
        assertEquals("运营外包一部_外包组", r.getOrgName());
        assertTrue(r.isActive());
    }

    @Test
    void disablesWhenAccountStatusZero() throws Exception
    {
        String[] f = this.fields20();
        f[1] = "90002";
        f[9] = "0";              // 序号10 账号状态=禁用
        f[18] = "7702";
        f[19] = "运营外包二部";

        OAUserRecord r = this.parser.parse(writeFile(this.line(f)), StandardCharsets.UTF_8).getRecords().get(0);
        assertEquals("7702_1", r.getOrgCode());
        assertEquals("运营外包二部_外包组", r.getOrgName());
        assertFalse(r.isActive());
    }

    @Test
    void parsesGbkEncodedFile() throws Exception
    {
        String[] f = this.fields20();
        f[1] = "90003";
        f[9] = "1";
        f[18] = "7703";
        f[19] = "运营外包三部";

        Path file = this.tempDir.resolve("gbk.dat");
        Files.write(file, (this.line(f) + "\n").getBytes(Charset.forName("GBK")));

        OASyncParseResult result = this.parser.parse(file, Charset.forName("GBK"));
        assertEquals(0, result.getErrors().size());
        assertEquals("运营外包三部_外包组", result.getRecords().get(0).getOrgName());
    }

    @Test
    void recordsErrorOnTooFewFields() throws Exception
    {
        String[] f = new String[10];
        f[1] = "90004";

        OASyncParseResult result = this.parser.parse(writeFile(this.line(f)), StandardCharsets.UTF_8);
        assertTrue(result.getRecords().isEmpty());
        assertEquals(1, result.getErrors().size());
    }

    @Test
    void recordsErrorOnUnknownAccountStatus() throws Exception
    {
        String[] f = this.fields20();
        f[1] = "90005";
        f[9] = "9";              // 未知状态
        f[18] = "7705";
        f[19] = "运营外包五部";

        OASyncParseResult result = this.parser.parse(writeFile(this.line(f)), StandardCharsets.UTF_8);
        assertTrue(result.getRecords().isEmpty());
        assertEquals(1, result.getErrors().size());
    }

    @Test
    void recordsErrorOnBlankUsername() throws Exception
    {
        String[] f = this.fields20();
        f[1] = "   ";
        f[9] = "1";
        f[18] = "7706";
        f[19] = "运营外包六部";

        OASyncParseResult result = this.parser.parse(writeFile(this.line(f)), StandardCharsets.UTF_8);
        assertTrue(result.getRecords().isEmpty());
        assertEquals(1, result.getErrors().size());
    }

    @Test
    void recordsErrorOnBlankOrgCode() throws Exception
    {
        String[] f = this.fields20();
        f[1] = "90007";
        f[9] = "1";
        f[18] = "  ";
        f[19] = "运营外包七部";

        OASyncParseResult result = this.parser.parse(writeFile(this.line(f)), StandardCharsets.UTF_8);
        assertTrue(result.getRecords().isEmpty());
        assertEquals(1, result.getErrors().size());
    }
}
