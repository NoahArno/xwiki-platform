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
package org.xwiki.contrib.confluence.migration.export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MigrationManifest} 的测试。
 *
 * <p>清单是 A、B 两台机器之间唯一的契约：A 写、B 读。写错了不会报错，
 * 只会让 B 去导一个不存在的目录，或者核对数量时对不上。因此格式必须有测试钉住。</p>
 */
class MigrationManifestTest
{
    private static ExportResult result(Path packageDirectory, String spaceKey)
    {
        return new ExportResult(spaceKey, packageDirectory, 10, 2, 20, 786432, 2, List.of());
    }

    @Test
    @DisplayName("写出的清单能原样读回")
    void roundTrips(@TempDir Path tempDir) throws Exception
    {
        Path pkg = Files.createDirectories(tempDir.resolve("DS"));
        MigrationManifest.write(tempDir, List.of(result(pkg, "DS")));

        List<Map<String, String>> rows = MigrationManifest.read(tempDir.resolve(MigrationManifest.FILE_NAME));

        assertEquals(1, rows.size());
        Map<String, String> row = rows.get(0);
        assertEquals("DS", row.get("spaceKey"));
        assertEquals("10", row.get("pageCount"));
        assertEquals("2", row.get("blogPostCount"));
        assertEquals("20", row.get("attachmentCount"));
        assertEquals("786432", row.get("attachmentBytes"));
    }

    @Test
    @DisplayName("包目录记相对路径，搬运到另一台机器后仍然有效")
    void recordsRelativePackageDirectory(@TempDir Path tempDir) throws Exception
    {
        Path pkg = Files.createDirectories(tempDir.resolve("DS"));
        MigrationManifest.write(tempDir, List.of(result(pkg, "DS")));

        String packageDir = MigrationManifest.read(tempDir.resolve(MigrationManifest.FILE_NAME))
            .get(0).get("packageDir");

        assertEquals("DS", packageDir);
        assertTrue(!packageDir.startsWith("/"), "不应写出绝对路径，否则搬到 B 之后失效: " + packageDir);
    }

    @Test
    @DisplayName("按空间 Key 排序，同样输入产出同样清单")
    void sortsBySpaceKey(@TempDir Path tempDir) throws Exception
    {
        Path spc = Files.createDirectories(tempDir.resolve("SPC"));
        Path ds = Files.createDirectories(tempDir.resolve("DS"));

        // 故意用非字典序传入
        MigrationManifest.write(tempDir, List.of(result(spc, "SPC"), result(ds, "DS")));

        List<Map<String, String>> rows = MigrationManifest.read(tempDir.resolve(MigrationManifest.FILE_NAME));

        assertEquals("DS", rows.get(0).get("spaceKey"));
        assertEquals("SPC", rows.get(1).get("spaceKey"));
    }

    @Test
    @DisplayName("含逗号的字段被正确转义并读回")
    void escapesCsvSpecialCharacters(@TempDir Path tempDir) throws Exception
    {
        Path pkg = Files.createDirectories(tempDir.resolve("DS"));
        MigrationManifest.write(tempDir, List.of(result(pkg, "DS,with\"quote")));

        List<Map<String, String>> rows = MigrationManifest.read(tempDir.resolve(MigrationManifest.FILE_NAME));

        assertEquals("DS,with\"quote", rows.get(0).get("spaceKey"));
    }

    @Test
    @DisplayName("无结果时也写出表头，B 侧读取不会因为空文件出错")
    void writesHeaderWhenEmpty(@TempDir Path tempDir) throws Exception
    {
        Path manifest = MigrationManifest.write(tempDir, List.of());

        String content = Files.readString(manifest);
        assertTrue(content.startsWith(MigrationManifest.HEADER), content);
        assertTrue(MigrationManifest.read(manifest).isEmpty());
    }
}
