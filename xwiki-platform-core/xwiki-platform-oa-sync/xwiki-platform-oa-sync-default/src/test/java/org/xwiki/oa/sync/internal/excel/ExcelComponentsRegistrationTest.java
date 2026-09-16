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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不依赖 Spoon 的组件注册守卫。
 * <p>
 * XWiki 自带更严格的构建期检查（{@code ComponentAnnotationProcessor}，在 {@code spoon-main} 里默认启用），
 * 但本仓库的构建命令惯用 {@code -Dxwiki.spoon.skip=true} 把它跳过，此时"components.txt 漏注册或类名写错"
 * 就只剩运行期才暴露。本测试补一道不依赖 Spoon 的守卫。
 * <p>
 * 覆盖范围有限：只校验本模块 {@code components.txt} 中被点名的这几个 Excel 组件能否加载，
 * 不覆盖注解/roles/{@code @Singleton} 是否正确，也不覆盖其它条目的漏注册。
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
            assertNotNull(input, "classpath 上找不到 META-INF/components.txt");
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
