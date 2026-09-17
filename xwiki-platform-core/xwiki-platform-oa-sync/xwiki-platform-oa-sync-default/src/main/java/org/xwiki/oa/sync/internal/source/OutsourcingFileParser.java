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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import org.xwiki.oa.sync.model.OAUserRecord;
import org.xwiki.oa.sync.model.OASyncParseResult;

/**
 * Parses the IOA_NON_EMPLOYEE_WIKI file (业务外包用户): GBK content, fields separated by CHAR(06), 1-based indexes.
 * <ul>
 *   <li>序号2 = 工号（XWiki 用户名）</li>
 *   <li>序号10 = 账号状态（0=禁用，1=启用；无员工状态，仅凭账号状态决定是否禁用）</li>
 *   <li>序号19 = 归口管理部门机构编号；作为权限组唯一标识，编号后追加 {@code _1}</li>
 *   <li>序号20 = 归口管理部门机构中文名；作为权限组显示名，名称后追加 {@code _外包组}</li>
 * </ul>
 * 序号19 为空时不报错、不丢弃用户，改用构造时传入的默认机构（默认编号/名称）。
 *
 * @version $Id$
 */
public class OutsourcingFileParser
{
    /**
     * Field separator CHAR(06).
     */
    public static final char FIELD_SEPARATOR = '\u0006';

    private static final String ACCOUNT_STATUS_DISABLED = "0";

    private static final String ACCOUNT_STATUS_ENABLED = "1";

    /**
     * 需要取到序号20，因此最少 20 个字段。
     */
    private static final int REQUIRED_FIELDS = 20;

    private final String defaultOrgCode;

    private final String defaultOrgName;

    /**
     * @param defaultOrgCode 序号19 归口管理部门机构编号为空时使用的默认编号
     * @param defaultOrgName 序号20 归口管理部门机构名称为空时使用的默认名称
     */
    public OutsourcingFileParser(String defaultOrgCode, String defaultOrgName)
    {
        this.defaultOrgCode = defaultOrgCode;
        this.defaultOrgName = defaultOrgName;
    }

    /**
     * Parse a whole file.
     *
     * @param file the downloaded file
     * @param charset the file charset (GBK for the outsourcing file)
     * @return records plus per-line errors
     * @throws IOException if the file cannot be read
     */
    public OASyncParseResult parse(Path file, Charset charset) throws IOException
    {
        OASyncParseResult result = new OASyncParseResult();
        int lineNo = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                this.parseLine(line, lineNo, result);
            }
        }
        return result;
    }

    void parseLine(String line, int lineNo, OASyncParseResult result)
    {
        if (line.isEmpty()) {
            return;
        }
        String[] fields = line.split(String.valueOf(FIELD_SEPARATOR), -1);
        if (fields.length < REQUIRED_FIELDS) {
            result.getErrors().add("第 " + lineNo + " 行字段数不足: " + fields.length);
            return;
        }
        // 1-based index N == fields[N-1]
        String username = fields[1].trim();
        String acctStatus = fields[9].trim();
        String orgCode = fields[18].trim();
        String orgName = fields[19].trim();

        if (username.isEmpty()) {
            result.getErrors().add("第 " + lineNo + " 行员工工号为空");
            return;
        }
        if (!ACCOUNT_STATUS_ENABLED.equals(acctStatus) && !ACCOUNT_STATUS_DISABLED.equals(acctStatus)) {
            result.getErrors().add("第 " + lineNo + " 行账号状态无法判定: " + acctStatus);
            return;
        }
        // 归口管理部门机构编号为空时不再丢弃该用户，回落到默认机构：
        // 编号与名称同时取默认值，避免同一默认组被不同行的机构名反复改名。
        if (orgCode.isEmpty()) {
            orgCode = this.defaultOrgCode;
            orgName = this.defaultOrgName;
        } else if (orgName.isEmpty()) {
            orgName = this.defaultOrgName;
        }
        boolean active = ACCOUNT_STATUS_ENABLED.equals(acctStatus);
        // 机构编号/名称追加后缀，作为权限组唯一标识，避免与行员机构组（纯编号）冲突。
        result.getRecords().add(new OAUserRecord(username, orgCode + "_1", orgName + "_外包组", active));
    }
}
