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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 生成 A、B 两台机器之间的清单文件（{@code manifest.csv}）。
 *
 * <p>拓扑是"生成器跑在 A（Confluence 那台），把包搬到 B 由 XWiki 导入"。
 * B 侧的导入程序需要知道：有哪些包、分别对应哪个空间、各自预期多少内容。
 * 预期数量是导入后核对用的——否则"导进去了但少了一半页面"这种事没有任何信号。</p>
 *
 * <h2>为什么 packageDir 用相对路径</h2>
 * <p>包从 A 搬到 B 之后，绝对路径必然不同（A 上是 {@code /data/migration/DS}，
 * B 上可能是 {@code /var/lib/xwiki/migration/DS}）。写绝对路径会让清单在搬运后失效，
 * 因此只记录相对于输出根目录的路径，由 B 侧拼上自己的根目录。</p>
 *
 * <h2>为什么清单里没有状态列</h2>
 * <p>清单是 A 的产物、描述"应该导入什么"，是一次性写定的；导入进度不写回清单，
 * 而是由 B 侧在导入后用这里预期的数量与 XWiki 现状实时对账。两者分开，
 * B 就不需要回写 A 的文件，也就不存在两边同时改同一份文件的问题。</p>
 */
public final class MigrationManifest
{
    /** 清单文件名，固定放在输出根目录下。 */
    public static final String FILE_NAME = "manifest.csv";

    /** 清单表头，同时是 A、B 之间的字段契约。 */
    public static final String HEADER =
        "spaceKey,packageDir,pageCount,blogPostCount,attachmentCount,attachmentBytes,exportedAt";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private MigrationManifest()
    {
        // utility class
    }

    /**
     * 写出清单。
     *
     * <p>每次调用都完整重写：行数只有空间数量级（几百行），重写比增量合并更不容易出错，
     * 而且中途失败时磁盘上始终是一份完整可读的清单，不会出现半行。</p>
     *
     * @param outputRoot 输出根目录，用于计算相对路径
     * @param results 本次导出的结果
     * @return 写出的清单文件路径
     * @throws IOException 写入失败时
     */
    public static Path write(Path outputRoot, List<ExportResult> results) throws IOException
    {
        Path manifestFile = outputRoot.resolve(FILE_NAME);

        // 按空间 Key 排序，保证同样的输入产出同样的清单，便于比对
        Map<String, ExportResult> sorted = new TreeMap<>();
        for (ExportResult result : results) {
            sorted.put(result.spaceKey(), result);
        }

        StringBuilder content = new StringBuilder(HEADER).append('\n');
        String timestamp = TIMESTAMP.format(LocalDateTime.now());
        for (ExportResult result : sorted.values()) {
            content.append(csv(result.spaceKey())).append(',')
                .append(csv(relativePackageDir(outputRoot, result))).append(',')
                .append(result.pageCount()).append(',')
                .append(result.blogPostCount()).append(',')
                .append(result.attachmentCount()).append(',')
                .append(result.attachmentBytes()).append(',')
                .append(timestamp)
                .append('\n');
        }

        Files.createDirectories(outputRoot);
        Files.writeString(manifestFile, content.toString(), StandardCharsets.UTF_8);
        return manifestFile;
    }

    /**
     * 读取清单，供 B 侧或测试使用。
     *
     * @param manifestFile 清单文件
     * @return 每行的字段值，按表头顺序
     * @throws IOException 读取失败时
     */
    public static List<Map<String, String>> read(Path manifestFile) throws IOException
    {
        List<String> lines = Files.readAllLines(manifestFile, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return List.of();
        }

        String[] header = splitCsv(lines.get(0));
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] values = splitCsv(line);
            Map<String, String> row = new TreeMap<>();
            for (int column = 0; column < header.length && column < values.length; column++) {
                row.put(header[column], values[column]);
            }
            rows.add(row);
        }
        return rows;
    }

    private static String relativePackageDir(Path outputRoot, ExportResult result)
    {
        Path packageDirectory = result.packageDirectory();
        String relative;
        try {
            relative = outputRoot.toAbsolutePath().relativize(packageDirectory.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            // 包不在输出根目录下（理论上不会发生），退回目录名
            relative = packageDirectory.getFileName() == null
                ? result.spaceKey()
                : packageDirectory.getFileName().toString();
        }
        // 统一用 / 分隔，避免 A、B 操作系统不同导致清单不可读
        return relative.replace('\\', '/');
    }

    private static String csv(String value)
    {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /**
     * 极简 CSV 拆分，支持双引号包裹与转义引号。
     */
    private static String[] splitCsv(String line)
    {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());

        return values.toArray(new String[0]);
    }
}
