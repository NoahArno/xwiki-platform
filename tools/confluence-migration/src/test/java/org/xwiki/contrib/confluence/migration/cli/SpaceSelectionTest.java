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
package org.xwiki.contrib.confluence.migration.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移空间选择相关的测试。
 *
 * <p>面对 400+ 个空间，逐批导出、排除归档空间是常态操作。
 * 选择写错了不会报错，只会"静默少导一个空间"——所以这些解析逻辑必须有测试。</p>
 */
class SpaceSelectionTest
{
    @Test
    @DisplayName("同名参数可重复，取值全部保留")
    void collectsRepeatedArguments()
    {
        Arguments arguments = Arguments.parse(
            new String[] {"--space", "DS", "--space", "SPC", "--space", "HR"}, Set.of());

        assertEquals(List.of("DS", "SPC", "HR"), arguments.getAll("space"));
        assertEquals("HR", arguments.get("space"), "get 应返回最后一个");
    }

    @Test
    @DisplayName("逗号分隔列表会去空白并忽略空项")
    void splitsCommaSeparatedLists()
    {
        assertEquals(List.of("DS", "SPC", "HR"), Main.splitCommaSeparated("DS, SPC ,HR"));
        assertEquals(List.of(), Main.splitCommaSeparated(""));
        assertEquals(List.of("DS"), Main.splitCommaSeparated("DS,"));
    }

    @Test
    @DisplayName("从纯文本文件读空间列表，忽略空行与注释")
    void readsPlainTextSpaceFile(@TempDir Path tempDir) throws Exception
    {
        Path file = tempDir.resolve("spaces.txt");
        Files.writeString(file, """
            # 本次要迁移的空间
            DS

            SPC
            """);

        assertEquals(List.of("DS", "SPC"), Main.readSpaceKeysFromFile(file));
    }

    @Test
    @DisplayName("可直接喂体检产出的 CSV，取第一列并跳过表头")
    void readsInventoryCsv(@TempDir Path tempDir) throws Exception
    {
        Path file = tempDir.resolve("inventory.csv");
        Files.writeString(file, """
            spaceKey,spaceName,spaceStatus,spaceId,pages
            DS,Demonstration Space,CURRENT,98305,30
            SPC,开发中心,CURRENT,98306,2
            """);

        assertEquals(List.of("DS", "SPC"), Main.readSpaceKeysFromFile(file));
    }

    @Test
    @DisplayName("逗号两侧的空格被处理，不产生带空格的空间 Key")
    void trimsCsvFirstColumn(@TempDir Path tempDir) throws Exception
    {
        Path file = tempDir.resolve("inventory.csv");
        Files.writeString(file, "spaceKey,other\nDS ,x\n");

        List<String> keys = Main.readSpaceKeysFromFile(file);

        assertEquals(List.of("DS"), keys);
        assertTrue(keys.stream().noneMatch(key -> key.contains(" ")));
    }
}
