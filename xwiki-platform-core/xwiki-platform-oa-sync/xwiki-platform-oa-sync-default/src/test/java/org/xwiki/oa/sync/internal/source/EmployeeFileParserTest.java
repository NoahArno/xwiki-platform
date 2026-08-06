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
 * Unit tests for {@link EmployeeFileParser}.
 *
 * @version $Id$
 */
class EmployeeFileParserTest
{
    @TempDir
    Path tempDir;

    private static final String SEP = "\u0006";

    private final EmployeeFileParser parser = new EmployeeFileParser();

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

    private String[] fields24()
    {
        return new String[24];
    }

    @Test
    void parsesActiveEmployee() throws Exception
    {
        String[] f = this.fields24();
        f[1] = "10086";
        f[10] = "1";
        f[11] = "1";
        f[12] = "1";
        f[22] = "8801";
        f[23] = "科技部";

        OASyncParseResult result = this.parser.parse(writeFile(this.line(f)), StandardCharsets.UTF_8);
        assertEquals(0, result.getErrors().size());
        OAUserRecord r = result.getRecords().get(0);
        assertEquals("10086", r.getUsername());
        assertEquals("8801", r.getOrgCode());
        assertEquals("科技部", r.getOrgName());
        assertTrue(r.isActive());
    }

    @Test
    void disablesWhenAccountStatusZero() throws Exception
    {
        String[] f = this.fields24();
        f[1] = "10086";
        f[10] = "1";
        f[11] = "1";
        f[12] = "0";
        f[22] = "8801";
        f[23] = "科技部";

        OAUserRecord r = this.parser.parse(writeFile(this.line(f)), StandardCharsets.UTF_8).getRecords().get(0);
        assertFalse(r.isActive());
    }

    @Test
    void disablesWhenResigned() throws Exception
    {
        // 员工状态=2（离职），账号状态=1
        String[] f = this.fields24();
        f[1] = "10086";
        f[10] = "1";
        f[11] = "2";
        f[12] = "1";
        f[22] = "8801";
        f[23] = "科技部";

        OAUserRecord r = this.parser.parse(writeFile(this.line(f)), StandardCharsets.UTF_8).getRecords().get(0);
        assertFalse(r.isActive());
    }

    @Test
    void enablesResignedActiveStatuses() throws Exception
    {
        // 员工状态=101（在职），账号状态=1
        String[] f = this.fields24();
        f[1] = "10086";
        f[10] = "1";
        f[11] = "101";
        f[12] = "1";
        f[22] = "8801";
        f[23] = "科技部";

        OAUserRecord r = this.parser.parse(writeFile(this.line(f)), StandardCharsets.UTF_8).getRecords().get(0);
        assertTrue(r.isActive());
    }

    @Test
    void skipsNonAccountTypeOne() throws Exception
    {
        String[] f = this.fields24();
        f[1] = "10086";
        f[10] = "2";
        f[11] = "1";
        f[12] = "1";
        f[22] = "8801";
        f[23] = "科技部";

        OASyncParseResult result = this.parser.parse(writeFile(this.line(f)), StandardCharsets.UTF_8);
        assertTrue(result.getRecords().isEmpty());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    void recordsErrorOnTooFewFields() throws Exception
    {
        String[] f = new String[10];
        f[1] = "10086";

        OASyncParseResult result = this.parser.parse(writeFile(this.line(f)), StandardCharsets.UTF_8);
        assertTrue(result.getRecords().isEmpty());
        assertEquals(1, result.getErrors().size());
    }

    @Test
    void recordsErrorOnUnknownStatus() throws Exception
    {
        String[] f = this.fields24();
        f[1] = "10086";
        f[10] = "1";
        f[11] = "999";
        f[12] = "1";
        f[22] = "8801";
        f[23] = "科技部";

        OASyncParseResult result = this.parser.parse(writeFile(this.line(f)), StandardCharsets.UTF_8);
        assertTrue(result.getRecords().isEmpty());
        assertEquals(1, result.getErrors().size());
    }

    @Test
    void processesWhenAccountTypeBlank() throws Exception
    {
        // 真实 OA 文件里行员的账号类型(序号11)是留空的：空值应视为行员处理
        String[] f = this.fields24();
        f[1] = "10086";
        f[10] = "";
        f[11] = "101";
        f[12] = "1";
        f[22] = "8801";
        f[23] = "科技部";

        OASyncParseResult result = this.parser.parse(writeFile(this.line(f)), StandardCharsets.UTF_8);
        assertEquals(0, result.getErrors().size());
        assertEquals(1, result.getRecords().size());
        OAUserRecord r = result.getRecords().get(0);
        assertEquals("10086", r.getUsername());
        assertTrue(r.isActive());
    }
}
