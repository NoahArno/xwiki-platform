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
package org.xwiki.contrib.confluence.migration.validation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.xwiki.contrib.confluence.migration.attach.AttachmentPathRule;
import org.xwiki.contrib.confluence.migration.export.ConfluenceEntity;
import org.xwiki.contrib.confluence.migration.export.SpaceExporter;

/**
 * 校验一个已生成的迁移包是否自洽。
 *
 * <p>这是本工具最重要的一道防线。生成包最容易出的问题不是"格式写错"，而是
 * <strong>指向了包内不存在的实体</strong>——例如附件指向被排除的草稿页、
 * 正文指向没写出来的宿主。这类问题导入器多半只记一条日志然后跳过，
 * 也就是<strong>静默丢数据</strong>。</p>
 *
 * <p>校验内容：</p>
 * <ol>
 *   <li>必需文件存在（{@code entities.xml}、{@code exportDescriptor.properties}）；</li>
 *   <li>每一条实体引用（{@code property class=} 与 {@code collection element}）
 *       都能在包内解析到目标实体；</li>
 *   <li>每个附件实体在 {@code attachments/} 下都有对应版本的文件。</li>
 * </ol>
 *
 * <p>用 StAX 流式解析，因此可以对任意大的包运行而不必把整个 XML 读进内存。</p>
 */
public final class PackageValidator
{
    private PackageValidator()
    {
        // utility class
    }

    /**
     * 校验结果。
     *
     * @param valid 是否通过
     * @param errors 具体问题描述
     * @param entityCounts 各类实体的数量
     * @param referenceCount 检查过的引用总数
     * @param attachmentCount 检查过的附件实体数
     */
    public record ValidationResult(boolean valid, List<String> errors, Map<String, Integer> entityCounts,
        int referenceCount, int attachmentCount)
    {
        /**
         * @return 人类可读的报告
         */
        public String render()
        {
            StringBuilder out = new StringBuilder();
            out.append("实体统计:\n");
            if (entityCounts.isEmpty()) {
                out.append("  (无)\n");
            }
            entityCounts.forEach((cls, count) -> out.append(String.format("  %-22s %d%n", cls, count)));
            out.append(String.format("%n检查引用 %d 条，附件实体 %d 个%n", referenceCount, attachmentCount));
            if (valid) {
                out.append("\n✓ 校验通过：所有引用都能在包内解析，附件文件齐全。\n");
            } else {
                out.append(String.format("%n✗ 校验失败，发现 %d 个问题：%n", errors.size()));
                errors.forEach(error -> out.append("  ! ").append(error).append('\n'));
            }
            return out.toString();
        }
    }

    /**
     * 校验包目录。
     *
     * @param packageDirectory 包目录
     * @return 校验结果
     * @throws IOException 读文件失败时
     */
    public static ValidationResult validate(Path packageDirectory) throws IOException
    {
        List<String> errors = new ArrayList<>();

        Path entitiesFile = packageDirectory.resolve(SpaceExporter.ENTITIES_FILE);
        Path descriptorFile = packageDirectory.resolve(SpaceExporter.DESCRIPTOR_FILE);

        if (!Files.isRegularFile(descriptorFile)) {
            errors.add("缺少 " + SpaceExporter.DESCRIPTOR_FILE);
        }
        if (!Files.isRegularFile(entitiesFile)) {
            errors.add("缺少 " + SpaceExporter.ENTITIES_FILE);
            return new ValidationResult(false, errors, Map.of(), 0, 0);
        }

        Scan scan = scan(entitiesFile);

        for (Reference reference : scan.references) {
            if (!scan.declaredKeys.contains(key(reference.className(), reference.idName(), reference.value()))) {
                errors.add(String.format("%s 引用了包内不存在的 %s(%s=%s)",
                    reference.owner(), reference.className(), reference.idName(), reference.value()));
            }
        }

        for (AttachmentInfo attachment : scan.attachments) {
            if (attachment.containerId() == null) {
                errors.add(String.format("附件 %d 没有 containerContent，无法确定其归属", attachment.id()));
                continue;
            }
            Path expected = packageDirectory.resolve(SpaceExporter.ATTACHMENTS_DIR)
                .resolve(String.valueOf(attachment.containerId()))
                .resolve(String.valueOf(attachment.id()))
                .resolve(String.valueOf(attachment.version()));
            if (!Files.isRegularFile(expected)) {
                errors.add(String.format("附件 %d 的实体已写出，但文件缺失（按规则应为 %s）",
                    attachment.id(),
                    AttachmentPathRule.relativePath(null, attachment.containerId(), attachment.id(),
                        attachment.version())));
            }
        }

        return new ValidationResult(errors.isEmpty(), errors, scan.counts, scan.references.size(),
            scan.attachments.size());
    }

    private static String key(String className, String idName, String value)
    {
        return className + '|' + idName + '|' + value;
    }

    private record Reference(String className, String idName, String value, String owner)
    {
    }

    private record AttachmentInfo(long id, Long containerId, int version)
    {
    }

    private static final class Scan
    {
        private final Set<String> declaredKeys = new HashSet<>();

        private final List<Reference> references = new ArrayList<>();

        private final List<AttachmentInfo> attachments = new ArrayList<>();

        private final Map<String, Integer> counts = new TreeMap<>();
    }

    /**
     * 用 StAX 流式扫描 {@code entities.xml}。
     *
     * <p>{@code <id>} 元素有两重含义：作为 {@code <object>} 的直接子元素时是主键；
     * 出现在 {@code <property class=..>} 或 {@code <element class=..>} 内部时是引用的一部分。
     * 这里靠"当前是否处在引用上下文中"（{@code pendingReferenceClass} 是否为 null）来区分。</p>
     */
    private static Scan scan(Path entitiesFile) throws IOException
    {
        Scan scan = new Scan();

        XMLInputFactory factory = XMLInputFactory.newInstance();
        // 防止 XXE：包虽由本工具生成，仍不应解析外部实体
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        String objectClass = null;
        String objectId = null;
        String pendingReferenceClass = null;
        String pendingPropertyName = null;
        Long containerId = null;
        Integer version = null;

        try (InputStream in = Files.newInputStream(entitiesFile)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            while (reader.hasNext()) {
                int event = reader.next();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "object":
                            objectClass = reader.getAttributeValue(null, "class");
                            objectId = null;
                            pendingReferenceClass = null;
                            pendingPropertyName = null;
                            containerId = null;
                            version = null;
                            scan.counts.merge(objectClass, 1, Integer::sum);
                            break;

                        case "id":
                            String idName = reader.getAttributeValue(null, "name");
                            String idValue = reader.getElementText().trim();
                            if (pendingReferenceClass != null) {
                                scan.references.add(new Reference(pendingReferenceClass, idName, idValue,
                                    objectClass + "(" + objectId + ")"));
                                if ("containerContent".equals(pendingPropertyName)
                                    && ConfluenceEntity.ATTACHMENT.equals(objectClass)) {
                                    containerId = parseLongOrNull(idValue);
                                }
                            } else {
                                scan.declaredKeys.add(key(objectClass, idName, idValue));
                                objectId = idValue;
                            }
                            break;

                        case "property":
                            String propertyName = reader.getAttributeValue(null, "name");
                            String propertyClass = reader.getAttributeValue(null, "class");
                            if (propertyClass == null) {
                                // 标量属性：只有版本号对附件校验有意义
                                String text = reader.getElementText().trim();
                                if ("version".equals(propertyName)
                                    && ConfluenceEntity.ATTACHMENT.equals(objectClass)) {
                                    version = parseIntOrNull(text);
                                }
                            } else {
                                pendingReferenceClass = propertyClass;
                                pendingPropertyName = propertyName;
                            }
                            break;

                        case "element":
                            pendingReferenceClass = reader.getAttributeValue(null, "class");
                            pendingPropertyName = null;
                            break;

                        default:
                            break;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "object":
                            if (ConfluenceEntity.ATTACHMENT.equals(objectClass) && objectId != null) {
                                scan.attachments.add(new AttachmentInfo(parseLongOrNull(objectId),
                                    containerId, version == null ? 1 : version));
                            }
                            objectClass = null;
                            objectId = null;
                            pendingReferenceClass = null;
                            pendingPropertyName = null;
                            containerId = null;
                            version = null;
                            break;

                        case "property", "element":
                            pendingReferenceClass = null;
                            pendingPropertyName = null;
                            break;

                        default:
                            break;
                    }
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new IOException("解析 " + entitiesFile + " 失败: " + e.getMessage(), e);
        }

        return scan;
    }

    private static Long parseLongOrNull(String value)
    {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseIntOrNull(String value)
    {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
