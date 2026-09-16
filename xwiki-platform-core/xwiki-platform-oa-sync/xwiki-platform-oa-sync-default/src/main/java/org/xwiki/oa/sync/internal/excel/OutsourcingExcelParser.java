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

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import javax.inject.Singleton;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.xwiki.component.annotation.Component;

/**
 * 解析科技外包用户 Excel：只读第一个 sheet，第 1 行是表头，
 * 从 B 列取 {@code staff_id}，其余列全部忽略。
 * <p>
 * 行级问题不中断解析：非法行记入错误/警告，其余行照常返回。
 *
 * @version $Id$
 */
@Component(roles = OutsourcingExcelParser.class)
@Singleton
public class OutsourcingExcelParser
{
    /**
     * {@code staff_id} 所在列（0-based）：B 列。
     */
    public static final int STAFF_ID_COLUMN = 1;

    /**
     * 表头行号（0-based）：第 1 行。
     */
    public static final int HEADER_ROW_INDEX = 0;

    /**
     * 工号最大长度：xwikidoc 的文档名上限为 255。
     */
    private static final int MAX_STAFF_ID_LENGTH = 255;

    /**
     * XWiki 文档名中会导致嵌套空间或引用异常的字符。
     */
    private static final Pattern ILLEGAL_STAFF_ID = Pattern.compile("[.\\\\/:*?\"<>|\\s]");

    /**
     * @param input the uploaded workbook content
     * @return the parsed staff ids plus row-level errors and warnings
     * @throws IOException if the content is not a readable workbook, or has no sheet at all
     */
    public OutsourcingExcelParseResult parse(InputStream input) throws IOException
    {
        OutsourcingExcelParseResult result = new OutsourcingExcelParseResult();
        try (Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IOException("Excel 文件没有任何 sheet");
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Set<String> seen = new LinkedHashSet<>();
            for (int rowIndex = HEADER_ROW_INDEX + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                parseRow(sheet.getRow(rowIndex), rowIndex, formatter, seen, result);
            }
        }
        return result;
    }

    private void parseRow(Row row, int rowIndex, DataFormatter formatter, Set<String> seen,
        OutsourcingExcelParseResult result)
    {
        if (row == null || isBlankRow(row, formatter)) {
            return;
        }

        String staffId;
        try {
            Cell cell = row.getCell(STAFF_ID_COLUMN);
            staffId = cell == null ? "" : formatter.formatCellValue(cell).trim();
        } catch (Exception e) {
            result.getErrors().add("第 " + displayRow(rowIndex) + " 行读取失败: " + e.getMessage());
            return;
        }

        if (staffId.isEmpty()) {
            result.getErrors().add("第 " + displayRow(rowIndex) + " 行 staff_id 为空");
            return;
        }
        if (staffId.length() > MAX_STAFF_ID_LENGTH) {
            result.getErrors().add("第 " + displayRow(rowIndex) + " 行 staff_id 过长: "
                + staffId.length() + " 字符");
            return;
        }
        if (ILLEGAL_STAFF_ID.matcher(staffId).find()) {
            result.getErrors().add("第 " + displayRow(rowIndex) + " 行 staff_id 含非法字符: " + staffId);
            return;
        }
        if (!seen.add(staffId)) {
            result.getWarnings().add("第 " + displayRow(rowIndex) + " 行 staff_id 重复，已跳过: " + staffId);
            return;
        }
        result.getUsernames().add(staffId);
    }

    private boolean isBlankRow(Row row, DataFormatter formatter)
    {
        for (int column = row.getFirstCellNum(); column < row.getLastCellNum(); column++) {
            Cell cell = row.getCell(column);
            if (cell != null && !formatter.formatCellValue(cell).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private int displayRow(int rowIndex)
    {
        return rowIndex + 1;
    }
}
