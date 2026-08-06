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
import java.util.Set;

import org.xwiki.oa.sync.model.OAUserRecord;
import org.xwiki.oa.sync.model.OASyncParseResult;

/**
 * Parses the IOA_EMPLOYEE_JGTY file: GBK content, fields separated by CHAR(06), 1-based field indexes.
 *
 * @version $Id$
 */
public class EmployeeFileParser
{
    /**
     * Field separator CHAR(06).
     */
    public static final char FIELD_SEPARATOR = '\u0006';

    private static final String ACCOUNT_TYPE_EMPLOYEE = "1";

    private static final Set<String> ACTIVE_EMP_STATUSES = Set.of("1", "10", "101", "102", "103");

    private static final Set<String> RESIGNED_EMP_STATUSES = Set.of("2", "3", "4");

    private static final int REQUIRED_FIELDS = 24;

    enum UserActiveState
    {
        ACTIVE, DISABLED, UNDEFINED
    }

    /**
     * Parse a whole file.
     *
     * @param file the downloaded file
     * @param charset the file charset (GBK for the employee file)
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
        if (!ACCOUNT_TYPE_EMPLOYEE.equals(fields[10].trim())) {
            // 只处理账号类型=1
            return;
        }
        String username = fields[1].trim();
        String empStatus = fields[11].trim();
        String acctStatus = fields[12].trim();
        String orgCode = fields[22].trim();
        String orgName = fields[23].trim();

        if (username.isEmpty()) {
            result.getErrors().add("第 " + lineNo + " 行员工工号为空");
            return;
        }

        UserActiveState state = computeState(empStatus, acctStatus);
        if (state == UserActiveState.UNDEFINED) {
            result.getErrors().add("第 " + lineNo + " 行状态无法判定: 员工状态=" + empStatus + ", 账号状态=" + acctStatus);
            return;
        }
        result.getRecords().add(new OAUserRecord(username, orgCode, orgName, state == UserActiveState.ACTIVE));
    }

    static UserActiveState computeState(String empStatus, String acctStatus)
    {
        if ("0".equals(acctStatus)) {
            return UserActiveState.DISABLED;
        }
        if (!"1".equals(acctStatus)) {
            return UserActiveState.UNDEFINED;
        }
        if (ACTIVE_EMP_STATUSES.contains(empStatus)) {
            return UserActiveState.ACTIVE;
        }
        if (RESIGNED_EMP_STATUSES.contains(empStatus)) {
            return UserActiveState.DISABLED;
        }
        return UserActiveState.UNDEFINED;
    }
}
