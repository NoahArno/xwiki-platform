package org.xwiki.oa.sync.internal.excel;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 components.txt 里登记的 Excel 导入组件类真实存在且可加载。
 * 单测都是 new/mock 出来的，漏注册或类名写错只会在运行期暴露，这里用构建期断言兜住。
 */
class ExcelComponentsRegistrationTest
{
    private static final String PREFIX = "org.xwiki.oa.sync.internal.excel.";

    @Test
    void everyExcelComponentIsListedAndLoadable() throws Exception
    {
        List<String> declared = readComponents();
        List<String> expected = List.of(
            PREFIX + "OutsourcingExcelParser",
            PREFIX + "ExcelImportExecutor",
            PREFIX + "OutsourcingExcelImportService");
        for (String className : expected) {
            assertTrue(declared.contains(className), className + " 未登记进 components.txt");
            Class.forName(className);
        }
    }

    private List<String> readComponents() throws Exception
    {
        List<String> lines = new ArrayList<>();
        try (InputStream input = getClass().getClassLoader()
            .getResourceAsStream("META-INF/components.txt")) {
            assertTrue(input != null, "classpath 上找不到 META-INF/components.txt");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        lines.add(trimmed);
                    }
                }
            }
        }
        return lines;
    }
}
