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
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.NumberToTextConverter;
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
     * 工号最大长度。255 是一个刻意收紧的上限（xwikidoc 的 {@code XWD_NAME}/{@code XWD_FULLNAME}
     * 实际上限是 768），用于尽早拦下明显异常的输入，而不是数据库的硬限制。
     */
    private static final int MAX_STAFF_ID_LENGTH = 255;

    /**
     * XWiki 文档名中会导致嵌套空间或引用异常的字符，外加各类 Unicode 空格分隔符
     * （{@code \p{Zs}} 覆盖 {@code strip()} 去不掉的不换行空格 U+00A0 等）。
     */
    private static final Pattern ILLEGAL_STAFF_ID = Pattern.compile("[.\\\\/:*?\"<>|\\s\\p{Zs}]");

    /**
     * @param input the uploaded workbook content
     * @return the parsed staff ids plus row-level errors and warnings
     * @throws IOException if the content is not a readable workbook, or has no sheet at all
     */
    public OutsourcingExcelParseResult parse(InputStream input) throws IOException
    {
        OutsourcingExcelParseResult result = new OutsourcingExcelParseResult();
        try (Workbook workbook = openWorkbook(input)) {
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

    /**
     * 打开工作簿，并把 POI 对非法输入抛出的 unchecked 异常统一转成 {@link IOException}。
     * <p>
     * POI 对用户可上传的坏文件抛的是 {@code RuntimeException} 子类（空文件 →
     * {@code EmptyFileException}、非 Office 文件 → {@code NotOfficeXmlFileException}），
     * 若不转换，上游按"文件级失败"处理的分支（400 + FAILURE 同步记录）就抓不到，只会变成 500。
     */
    private Workbook openWorkbook(InputStream input) throws IOException
    {
        try {
            return WorkbookFactory.create(input);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("无法解析 Excel 文件: " + e.getMessage(), e);
        }
    }

    private void parseRow(Row row, int rowIndex, DataFormatter formatter, Set<String> seen,
        OutsourcingExcelParseResult result)
    {
        if (row == null) {
            return;
        }

        String staffId;
        try {
            if (isBlankRow(row, formatter)) {
                return;
            }
            staffId = readStaffId(row.getCell(STAFF_ID_COLUMN), formatter).strip();
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

    /**
     * 按单元格类型取工号。
     * <p>
     * 不能用 {@code DataFormatter} 一把梭：它按单元格**显示格式**渲染，会把语义完全不同的单元格
     * 伪装成"看起来合法"的工号并被静默接受，例如千分位格式的数字 10086 → {@code "10,086"}、
     * 日期格式 → {@code "2026-01-02"}、无缓存值的公式 → 公式文本。
     * 因此：公式与日期格式直接判为坏行；数值型取与格式无关的原始数值文本；其余才交给格式化器。
     *
     * @param cell the B-column cell (may be {@code null})
     * @param formatter the cell formatter
     * @return the raw staff id text
     */
    private String readStaffId(Cell cell, DataFormatter formatter)
    {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            throw new IllegalArgumentException("staff_id 单元格是公式，无法确定取值");
        }
        if (type == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                throw new IllegalArgumentException("staff_id 单元格是日期格式，无法确定取值");
            }
            return NumberToTextConverter.toText(cell.getNumericCellValue());
        }
        return formatter.formatCellValue(cell);
    }

    private boolean isBlankRow(Row row, DataFormatter formatter)
    {
        for (int column = Math.max(0, row.getFirstCellNum()); column < row.getLastCellNum(); column++) {
            Cell cell = row.getCell(column);
            if (cell != null && !formatter.formatCellValue(cell).strip().isEmpty()) {
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
