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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OutsourcingExcelParser}.
 */
class OutsourcingExcelParserTest
{
    @TempDir
    Path tempDir;

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
        Path file = numericXlsx(new String[] { "姓名", "staff_id" },
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

    private OutsourcingExcelParseResult parse(Path file) throws IOException
    {
        try (InputStream input = Files.newInputStream(file)) {
            return this.parser.parse(input);
        }
    }

    private Path xlsx(String[]... rows) throws IOException
    {
        return write(new XSSFWorkbook(), rows);
    }

    private Path numericXlsx(String[]... rows) throws IOException
    {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] == null) {
                        continue;
                    }
                    // 工号列（B）刻意写成数字型单元格，验证 DataFormatter 的取数行为
                    if (c == OutsourcingExcelParser.STAFF_ID_COLUMN && r > 0) {
                        row.createCell(c).setCellValue(Double.parseDouble(rows[r][c]));
                    } else {
                        row.createCell(c).setCellValue(rows[r][c]);
                    }
                }
            }
            return save(workbook, "numeric.xlsx");
        }
    }

    private Path xls(String[]... rows) throws IOException
    {
        return write(new HSSFWorkbook(), rows);
    }

    private Path write(Workbook workbook, String[]... rows) throws IOException
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
            return save(wb, "test.xlsx");
        }
    }

    private Path save(Workbook workbook, String name) throws IOException
    {
        Path file = this.tempDir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            workbook.write(out);
        }
        return file;
    }
}
