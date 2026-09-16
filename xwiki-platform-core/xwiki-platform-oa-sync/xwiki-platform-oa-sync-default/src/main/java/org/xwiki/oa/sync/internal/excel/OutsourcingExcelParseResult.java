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
package org.xwiki.oa.sync.internal.excel;

import java.util.ArrayList;
import java.util.List;

/**
 * 外包 Excel 的解析产物：去重后的工号，以及逐行错误与警告。
 * <p>
 * 错误会让导入整体变成 {@code PARTIAL_FAILURE}；警告只记录，不影响状态。
 *
 * @version $Id$
 */
public class OutsourcingExcelParseResult
{
    private final List<String> usernames = new ArrayList<>();

    private final List<String> errors = new ArrayList<>();

    private final List<String> warnings = new ArrayList<>();

    /**
     * @return the deduplicated staff ids, in file order
     */
    public List<String> getUsernames()
    {
        return this.usernames;
    }

    /**
     * @return the row-level errors (these fail the import)
     */
    public List<String> getErrors()
    {
        return this.errors;
    }

    /**
     * @return the row-level warnings (informational, do not fail the import)
     */
    public List<String> getWarnings()
    {
        return this.warnings;
    }
}
