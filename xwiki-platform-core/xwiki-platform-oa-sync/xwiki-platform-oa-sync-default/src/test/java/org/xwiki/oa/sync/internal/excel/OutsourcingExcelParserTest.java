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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutsourcingExcelParser}.
 */
class OutsourcingExcelParserTest
{
    @TempDir
    Path tempDir;

    private final AtomicInteger fileCounter = new AtomicInteger();

    private final OutsourcingExcelParser parser = new OutsourcingExcelParser();

    @Test
    void readsStaffIdsFromColumnBAndSkipsHeader() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id", "部门" },
            new String[] { "张三", "T001", "科技部" },
            new String[] { "李四", "T002", "科技部" });

        OutsourcingExcelParseResult result = parse(file);

        assertEquals(List.of("T001", "T002"), result.getUsernames());
        assertTrue(result.getErrors().isEmpty());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    void trimsWhitespaceAroundStaffId() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "  T001  " });

        assertEquals(List.of("T001"), parse(file).getUsernames());
    }

    @Test
    void readsNumericCellWithoutScientificNotation() throws Exception
    {
        Path file = xlsxWithStyledBColumn(
            (workbook, cell, rawValue) -> cell.setCellValue(Double.parseDouble(rawValue)),
            new String[] { "姓名", "staff_id" },
            new String[] { "张三", "10086" });

        assertEquals(List.of("10086"), parse(file).getUsernames());
    }

    @Test
    void deduplicatesRepeatedStaffIdAsWarning() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T001" },
            new String[] { "张三重复", "T001" });

        OutsourcingExcelParseResult result = parse(file);

        assertEquals(List.of("T001"), result.getUsernames());
        assertTrue(result.getErrors().isEmpty());
        assertEquals(1, result.getWarnings().size());
        assertTrue(result.getWarnings().get(0).contains("第 3 行"));
    }

    @Test
    void skipsBlankRowsSilently() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T001" },
            new String[] { null, null },
            new String[] { "李四", "T002" });

        OutsourcingExcelParseResult result = parse(file);

        assertEquals(List.of("T001", "T002"), result.getUsernames());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    void reportsRowWithContentButEmptyStaffIdAsError() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "" });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("第 2 行"));
    }

    @Test
    void rejectsStaffIdWithIllegalCharacters() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T.001" });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("非法字符"));
    }

    @Test
    void rejectsTooLongStaffId() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T".repeat(256) });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("过长"));
    }

    @Test
    void parsesLegacyXlsFormat() throws Exception
    {
        Path file = xls(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T001" });

        assertEquals(List.of("T001"), parse(file).getUsernames());
    }

    @Test
    void ignoresCellDisplayFormatForNumericStaffId() throws Exception
    {
        // 回归：数字型 + 千分位显示格式。DataFormatter 会渲染成 "10,086"，
        // 逗号不在非法字符集里，会被静默接受并建出错误用户，必须取原始数值文本。
        Path file = xlsxWithStyledBColumn((workbook, cell, rawValue) -> {
            cell.setCellValue(Double.parseDouble(rawValue));
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
            cell.setCellStyle(style);
        }, new String[] { "姓名", "staff_id" },
            new String[] { "张三", "10086" });

        assertEquals(List.of("10086"), parse(file).getUsernames());
    }

    @Test
    void rejectsNumericStaffIdBeyondExcelPrecision() throws Exception
    {
        // 回归：NumberToTextConverter 只输出 15 位有效数字，16 位数值会被静默四舍五入成
        // 另一个工号并建号成功，必须回读比对后拒绝
        Path file = xlsxWithStyledBColumn(
            (workbook, cell, rawValue) -> cell.setCellValue(Double.parseDouble(rawValue)),
            new String[] { "姓名", "staff_id" },
            new String[] { "张三", "1234567890123456" });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("精度"), result.getErrors().get(0));
    }

    @Test
    void rejectsDateFormattedStaffIdCell() throws Exception
    {
        Path file = xlsxWithStyledBColumn((workbook, cell, rawValue) -> {
            cell.setCellValue(Double.parseDouble(rawValue));
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            cell.setCellStyle(style);
        }, new String[] { "姓名", "staff_id" },
            new String[] { "张三", "45000" });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("日期格式"), result.getErrors().get(0));
    }

    @Test
    void rejectsFormulaStaffIdCell() throws Exception
    {
        Path file = xlsxWithStyledBColumn(
            (workbook, cell, rawValue) -> cell.setCellFormula("A2"),
            new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T001" });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("公式"), result.getErrors().get(0));
    }

    @Test
    void stripsFullWidthSpacesAroundStaffId() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "\u3000T001\u3000" });

        assertEquals(List.of("T001"), parse(file).getUsernames());
    }

    @Test
    void stripsNonBreakingSpacesAroundStaffId() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "\u00A0T001\u00A0" });

        assertEquals(List.of("T001"), parse(file).getUsernames());
    }

    @Test
    void rejectsNonBreakingSpaceInsideStaffId() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T\u00A0001" });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("非法字符"), result.getErrors().get(0));
    }

    @Test
    void acceptsStaffIdOf255CharactersAndRejects256() throws Exception
    {
        Path accepted = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T".repeat(255) });
        assertEquals(1, parse(accepted).getUsernames().size());

        Path rejected = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T".repeat(256) });
        OutsourcingExcelParseResult result = parse(rejected);
        assertTrue(result.getUsernames().isEmpty());
        assertTrue(result.getErrors().get(0).contains("过长"), result.getErrors().get(0));
    }

    @Test
    void ignoresDataOutsideTheFirstSheet() throws Exception
    {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet first = workbook.createSheet("第一个");
            Row firstHeader = first.createRow(0);
            firstHeader.createCell(0).setCellValue("姓名");
            firstHeader.createCell(1).setCellValue("staff_id");

            Sheet second = workbook.createSheet("第二个");
            Row secondHeader = second.createRow(0);
            secondHeader.createCell(0).setCellValue("姓名");
            secondHeader.createCell(1).setCellValue("staff_id");
            Row secondRow = second.createRow(1);
            secondRow.createCell(0).setCellValue("张三");
            secondRow.createCell(1).setCellValue("T999");

            assertTrue(parse(save(workbook, "two-sheets.xlsx")).getUsernames().isEmpty());
        }
    }

    @Test
    void convertsUncheckedFailureAfterTheWorkbookIsOpenedIntoIOException() throws Exception
    {
        // 回归：openWorkbook 只包住 WorkbookFactory.create；workbook 打开**之后**的 POI 读取
        // （POIXMLException / IllegalStateException / close() 异常等）仍可能抛 unchecked。
        // 若 parse 外层不统一兜底，service/REST 的 catch (IOException) 抓不到 →
        // HTTP 500 且不落 FAILURE 记录。这里用 mock workbook 精确模拟"已打开、随后抛 unchecked"。
        Workbook workbook = mock(Workbook.class);
        Sheet sheet = mock(Sheet.class);
        when(workbook.getNumberOfSheets()).thenReturn(1);
        when(workbook.getSheetAt(0)).thenReturn(sheet);
        when(sheet.getLastRowNum()).thenThrow(new IllegalStateException("POI 内部状态损坏"));

        try (MockedStatic<WorkbookFactory> factory = mockStatic(WorkbookFactory.class)) {
            factory.when(() -> WorkbookFactory.create(any(InputStream.class))).thenReturn(workbook);

            IOException failure = assertThrows(IOException.class,
                () -> this.parser.parse(new ByteArrayInputStream(new byte[0])));

            assertTrue(failure.getMessage().contains("解析 Excel 内容失败"), failure.getMessage());
        }
    }

    @Test
    void failsOnEmptyFile() throws Exception
    {
        Path file = this.tempDir.resolve("empty-bytes.xlsx");
        Files.write(file, new byte[0]);

        try (InputStream input = Files.newInputStream(file)) {
            assertThrows(IOException.class, () -> this.parser.parse(input));
        }
    }

    @Test
    void failsWhenContentIsNotAWorkbook() throws Exception
    {
        // 以 PK\x03\x04 开头的伪 zip：POI 会当成 OOXML 尝试打开并抛 unchecked 的
        // NotOfficeXmlFileException，正是必须被转成 IOException 的那类输入
        // （纯 ASCII 文本抛的本来就是 checked IOException，钉不住这个修复）
        byte[] fakeZip = { 'P', 'K', 3, 4, 20, 0, 0, 0, 8, 0, 'n', 'o', 't', ' ', 'a', ' ', 'z', 'i', 'p' };
        Path file = this.tempDir.resolve("fake-zip.xlsx");
        Files.write(file, fakeZip);

        try (InputStream input = Files.newInputStream(file)) {
            assertThrows(IOException.class, () -> this.parser.parse(input));
        }
    }

    @Test
    void failsWhenWorkbookHasNoSheet() throws Exception
    {
        Path file = this.tempDir.resolve("empty.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
            OutputStream out = Files.newOutputStream(file)) {
            workbook.write(out);
        }

        try {
            parse(file);
            org.junit.jupiter.api.Assertions.fail("应当抛出 IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("没有任何 sheet"));
        }
    }

    @Test
    void returnsNoUsernameWhenOnlyHeaderRowExists() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    void stripsLineAndParagraphSeparatorsAroundStaffId() throws Exception
    {
        Path lineSeparator = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T001\u2028" });
        assertEquals(List.of("T001"), parse(lineSeparator).getUsernames());

        Path paragraphSeparator = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T001\u2029" });
        assertEquals(List.of("T001"), parse(paragraphSeparator).getUsernames());
    }

    @Test
    void rejectsLineSeparatorInsideStaffId() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T001\u2028X" });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("非法字符"), result.getErrors().get(0));
    }

    @Test
    void rejectsNegativeNumericStaffId() throws Exception
    {
        Path file = xlsxWithStyledBColumn(
            (workbook, cell, rawValue) -> cell.setCellValue(Double.parseDouble(rawValue)),
            new String[] { "姓名", "staff_id" },
            new String[] { "张三", "-123" });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("正整数"), result.getErrors().get(0));
    }

    @Test
    void rejectsScientificNotationNumericStaffId() throws Exception
    {
        // 1e21 的原始 double 是精确的（精度校验通过），但 NumberToTextConverter 渲染成 "1E+21"，
        // 若不校验形状就会被静默当成工号
        Path file = xlsxWithStyledBColumn(
            (workbook, cell, rawValue) -> cell.setCellValue(Double.parseDouble(rawValue)),
            new String[] { "姓名", "staff_id" },
            new String[] { "张三", "1e21" });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("正整数"), result.getErrors().get(0));
    }

    @Test
    void rejectsBooleanStaffIdCell() throws Exception
    {
        Path file = xlsxWithStyledBColumn(
            (workbook, cell, rawValue) -> cell.setCellValue(Boolean.parseBoolean(rawValue)),
            new String[] { "姓名", "staff_id" },
            new String[] { "张三", "true" });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("布尔值"), result.getErrors().get(0));
    }

    @Test
    void rejectsErrorStaffIdCell() throws Exception
    {
        Path file = xlsxWithStyledBColumn(
            (workbook, cell, rawValue) -> cell.setCellErrorValue(FormulaError.NUM.getCode()),
            new String[] { "姓名", "staff_id" },
            new String[] { "张三", "ignored" });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("错误值"), result.getErrors().get(0));
    }

    @Test
    void skipsRowWhoseOnlyContentIsAnInvisibleSpace() throws Exception
    {
        // 空行口径与空工号口径统一：B 列只有不可见空格时整行按空行跳过（不记错误）
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { null, "\u00A0" },
            new String[] { null, "\u3000" },
            new String[] { "张三", "T001" });

        OutsourcingExcelParseResult result = parse(file);

        assertEquals(List.of("T001"), result.getUsernames());
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
    }

    private OutsourcingExcelParseResult parse(Path file) throws IOException
    {
        try (InputStream input = Files.newInputStream(file)) {
            return this.parser.parse(input);
        }
    }

    private Path xlsx(String[]... rows) throws IOException
    {
        return write(new XSSFWorkbook(), "test.xlsx", rows);
    }

    private Path xls(String[]... rows) throws IOException
    {
        return write(new HSSFWorkbook(), "legacy.xls", rows);
    }

    private Path xlsxWithStyledBColumn(CellStyler styler, String[]... rows) throws IOException
    {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] == null) {
                        continue;
                    }
                    Cell cell = row.createCell(c);
                    if (c == OutsourcingExcelParser.STAFF_ID_COLUMN && r > 0) {
                        styler.style(workbook, cell, rows[r][c]);
                    } else {
                        cell.setCellValue(rows[r][c]);
                    }
                }
            }
            return save(workbook, "styled.xlsx");
        }
    }

    private Path write(Workbook workbook, String fileName, String[]... rows) throws IOException
    {
        try (Workbook wb = workbook) {
            Sheet sheet = wb.createSheet("Sheet1");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] != null) {
                        row.createCell(c).setCellValue(rows[r][c]);
                    }
                }
            }
            return save(wb, fileName);
        }
    }

    private Path save(Workbook workbook, String name) throws IOException
    {
        Path file = this.tempDir.resolve(this.fileCounter.incrementAndGet() + "-" + name);
        try (OutputStream out = Files.newOutputStream(file)) {
            workbook.write(out);
        }
        return file;
    }

    /**
     * 用来自定义 B 列单元格的写入方式（数字格式 / 日期格式 / 公式）。
     */
    private interface CellStyler
    {
        void style(Workbook workbook, Cell cell, String rawValue);
    }
}
