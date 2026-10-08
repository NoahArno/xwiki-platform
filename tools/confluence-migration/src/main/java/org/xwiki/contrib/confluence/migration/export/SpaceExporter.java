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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.xwiki.contrib.confluence.migration.attach.AttachmentPathRule;
import org.xwiki.contrib.confluence.migration.db.ConfluenceDb;
import org.xwiki.contrib.confluence.migration.export.ConfluenceLoader.AncestorRow;
import org.xwiki.contrib.confluence.migration.export.ConfluenceLoader.BodyRow;
import org.xwiki.contrib.confluence.migration.export.ConfluenceLoader.ContentRow;
import org.xwiki.contrib.confluence.migration.export.ConfluenceLoader.LabelRow;
import org.xwiki.contrib.confluence.migration.export.ConfluenceLoader.LabellingData;
import org.xwiki.contrib.confluence.migration.export.ConfluenceLoader.LabellingRow;
import org.xwiki.contrib.confluence.migration.export.ConfluenceLoader.PropertyRow;
import org.xwiki.contrib.confluence.migration.export.ConfluenceLoader.SpaceRow;
import org.xwiki.contrib.confluence.migration.policy.MigrationPolicy;

/**
 * 把一个 Confluence 空间导出成 XWiki 导入器可读的包目录。
 *
 * <pre>
 * &lt;输出根&gt;/&lt;SPACEKEY&gt;/
 *   entities.xml
 *   exportDescriptor.properties
 *   attachments/&lt;容器ID&gt;/&lt;附件ID&gt;/&lt;版本&gt;
 * </pre>
 *
 * <p>产出的是**已解压目录**而不是 zip：导入器的 {@code fromFile} 对目录是就地读取，
 * 对 zip 则会先全量解压到临时目录，因此目录形式省掉打包与解压两次全量 IO。</p>
 *
 * <h2>引用完整性</h2>
 * <p>只写出指向"本包内包含的实体"的引用。指向被排除对象（例如草稿里的页面、
 * 或不属于本空间的页面）的引用会被丢弃并记为告警——否则导入器会在解析时因为
 * 找不到目标而报错或产生断链，而且这类问题在导出阶段是静默的。</p>
 */
public final class SpaceExporter
{
    /** 附件在包内的子目录名。 */
    public static final String ATTACHMENTS_DIR = "attachments";

    /** 实体索引文件名。 */
    public static final String ENTITIES_FILE = "entities.xml";

    /** 描述文件名。 */
    public static final String DESCRIPTOR_FILE = "exportDescriptor.properties";

    private final ConfluenceDb db;

    private final Path attachmentsDir;

    /**
     * @param db 数据库访问对象
     * @param attachmentsDir Confluence 附件在文件系统上的根目录
     */
    public SpaceExporter(ConfluenceDb db, Path attachmentsDir)
    {
        this.db = db;
        this.attachmentsDir = attachmentsDir;
    }

    /**
     * 导出单个空间。
     *
     * @param spaceKey 空间 Key
     * @param outputRoot 输出根目录，实际包目录为 {@code <outputRoot>/<spaceKey>}
     * @param includeAttachments 是否复制附件实体与附件文件
     * @return 导出结果
     * @throws SQLException 数据库读取失败时
     * @throws IOException 写文件失败时
     */
    public ExportResult export(String spaceKey, Path outputRoot, boolean includeAttachments)
        throws SQLException, IOException
    {
        ConfluenceLoader loader = new ConfluenceLoader(db);
        List<String> warnings = new ArrayList<>();

        SpaceRow space = loader.loadSpace(spaceKey);
        if (space == null) {
            throw new IllegalArgumentException("找不到空间: " + spaceKey);
        }

        Path packageDirectory = outputRoot.resolve(spaceKey);
        Files.createDirectories(packageDirectory);

        // 排除规则是一份"已知坏值"清单，必然有盲区，而且盲区的后果是静默的。
        // 因此把该空间实际出现的状态取值一律摆出来核对，出现未预期取值就告警。
        Map<String, Long> statusDistribution = loader.loadStatusDistribution(space.id());
        List<String> unknownStatuses = statusDistribution.keySet().stream()
            .filter(status -> !MigrationPolicy.isKnownStatus(status))
            .toList();
        if (!unknownStatuses.isEmpty()) {
            warnings.add(String.format(
                "该空间出现了预期之外的内容状态 %s（实际分布: %s）。"
                    + "排除规则只认识 %s，因此这些条目可能被错误地包含或排除，请人工确认后再导入。",
                unknownStatuses, statusDistribution, MigrationPolicy.KNOWN_STATUSES));
        }

        List<ContentRow> contents = loader.loadContents(space.id());
        Set<Long> includedIds = contents.stream().map(ContentRow::id).collect(Collectors.toCollection(LinkedHashSet::new));

        List<ContentRow> pages = filterByType(contents, ConfluenceEntity.TYPE_PAGE);
        List<ContentRow> blogPosts = filterByType(contents, ConfluenceEntity.TYPE_BLOG_POST);
        List<ContentRow> descriptions = filterByType(contents, ConfluenceEntity.TYPE_SPACE_DESCRIPTION);
        List<ContentRow> attachments = filterByType(contents, ConfluenceEntity.TYPE_ATTACHMENT);

        if (!includeAttachments && !attachments.isEmpty()) {
            attachments = List.of();
        }

        // 容器不在包内的附件必须丢弃，否则导入器解析 containerContent 时会失败
        List<ContentRow> orphanAttachments = attachments.stream()
            .filter(attachment -> attachment.containerId() == null || !includedIds.contains(attachment.containerId()))
            .toList();
        if (!orphanAttachments.isEmpty()) {
            warnings.add(String.format(
                "%d 个附件的所属页面不在本次导出范围内（例如所属页面是草稿），已跳过。",
                orphanAttachments.size()));
            attachments = attachments.stream().filter(a -> !orphanAttachments.contains(a)).toList();
        }

        List<ContentRow> comments = loader.loadCommentsFor(includedIds);

        // 评论是单独查出来的，必须把它们的 ID 并入集合，否则评论的正文与属性会被漏掉
        comments.forEach(comment -> includedIds.add(comment.id()));

        Map<Long, List<Long>> commentIdsByContainer = comments.stream()
            .filter(comment -> comment.containerId() != null)
            .collect(Collectors.groupingBy(ContentRow::containerId,
                Collectors.mapping(ContentRow::id, Collectors.toList())));

        // 引用必须写明被引用对象真实的实体类与包名：正文的宿主可能是空间描述或评论，
        // 附件也可能挂在博客上。写死成 Page 会让导入器按错误的类型去解析引用。
        Map<Long, String> contentTypeById = new HashMap<>();
        contents.forEach(row -> contentTypeById.put(row.id(), row.contentType()));
        comments.forEach(row -> contentTypeById.put(row.id(), row.contentType()));

        List<Long> contentIds = List.copyOf(includedIds);
        Map<Long, List<BodyRow>> bodies = loader.loadBodies(contentIds);
        Map<Long, List<PropertyRow>> properties = loader.loadProperties(contentIds);
        Map<Long, List<AncestorRow>> ancestors = loader.loadAncestors(
            pages.stream().map(ContentRow::id).toList());
        LabellingData labellings = loader.loadLabellings(contentIds);

        Set<String> userKeys = collectUserKeys(space, contents, labellings);
        Map<String, String> userNames = loader.loadUserNames(userKeys);

        // 页面与博客的 parent 若不在包内，层级会断，需要显式告警而不是静默变成根页面
        long danglingParents = pages.stream()
            .filter(page -> page.parentId() != null && !includedIds.contains(page.parentId()))
            .count();
        if (danglingParents > 0) {
            warnings.add(String.format(
                "%d 个页面的父页面不在本次导出范围内，这些页面会变成所在空间的一级页面。", danglingParents));
        }

        Path entitiesFile = packageDirectory.resolve(ENTITIES_FILE);
        try (Writer fileWriter = Files.newBufferedWriter(entitiesFile, StandardCharsets.UTF_8);
            BufferedWriter bufferedWriter = new BufferedWriter(fileWriter, 1 << 16);
            EntitiesXmlWriter xml = new EntitiesXmlWriter(bufferedWriter)) {
            xml.start();
            writeSpace(xml, space);
            for (ContentRow description : descriptions) {
                writeSpaceDescription(xml, description, space, bodies, includedIds);
            }
            for (ContentRow page : pages) {
                writePage(xml, page, ConfluenceEntity.PAGE, bodies, properties, ancestors, includedIds);
            }
            for (ContentRow blogPost : blogPosts) {
                writePage(xml, blogPost, ConfluenceEntity.BLOG_POST, bodies, properties, ancestors, includedIds);
            }
            for (ContentRow attachment : attachments) {
                writeAttachment(xml, attachment, space, properties, commentIdsByContainer, contentTypeById);
            }
            for (ContentRow comment : comments) {
                writeComment(xml, comment, bodies, properties, includedIds, contentTypeById);
            }
            writeBodyContents(xml, bodies, includedIds, contentTypeById);
            writeContentProperties(xml, properties);
            writeLabels(xml, labellings, contentTypeById);
            writeUsers(xml, userNames);
            xml.end();
        }

        writeDescriptor(packageDirectory, space, includeAttachments);

        long copiedBytes = 0;
        long copiedAttachments = 0;
        if (includeAttachments) {
            AttachmentCopyResult copyResult = copyAttachments(packageDirectory, attachments);
            copiedBytes = copyResult.bytes();
            copiedAttachments = copyResult.attachments();
            warnings.addAll(copyResult.warnings());
        }

        return new ExportResult(spaceKey, packageDirectory, pages.size(), blogPosts.size(), copiedAttachments,
            copiedBytes, userNames.size(), warnings);
    }

    // ------------------------------------------------------------- 实体写出

    private void writeSpace(EntitiesXmlWriter xml, SpaceRow space) throws IOException
    {
        xml.beginObject(ConfluenceEntity.SPACE, ConfluenceEntity.PKG_SPACES);
        xml.id("id", space.id());
        xml.text("name", space.name());
        xml.text("key", space.key());
        xml.text("lowerKey", space.lowerKey());
        if (space.descriptionId() != null) {
            xml.reference("description", ConfluenceEntity.SPACE_DESCRIPTION, ConfluenceEntity.PKG_SPACES,
                space.descriptionId());
        }
        if (space.homePageId() != null) {
            xml.reference("homePage", ConfluenceEntity.PAGE, ConfluenceEntity.PKG_PAGES, space.homePageId());
        }
        if (space.creator() != null) {
            xml.referenceByKey("creator", ConfluenceEntity.CONFLUENCE_USER, ConfluenceEntity.PKG_USER,
                space.creator());
        }
        xml.date("creationDate", space.creationDate());
        if (space.lastModifier() != null) {
            xml.referenceByKey("lastModifier", ConfluenceEntity.CONFLUENCE_USER, ConfluenceEntity.PKG_USER,
                space.lastModifier());
        }
        xml.date("lastModificationDate", space.lastModDate());
        xml.text("spaceType", space.spaceType());
        xml.enumProperty("spaceStatus", "SpaceStatus", ConfluenceEntity.PKG_SPACES, space.spaceStatus());
        xml.endObject();
    }

    private void writeSpaceDescription(EntitiesXmlWriter xml, ContentRow description, SpaceRow space,
        Map<Long, List<BodyRow>> bodies, Set<Long> includedIds) throws IOException
    {
        xml.beginObject(ConfluenceEntity.SPACE_DESCRIPTION, ConfluenceEntity.PKG_SPACES);
        xml.id("id", description.id());
        xml.scalar("hibernateVersion", description.hibernateVersion());
        xml.text("title", description.title());
        xml.text("lowerTitle", description.lowerTitle());
        writeBodyReferences(xml, bodies, description.id());
        xml.scalar("version", description.version());
        xml.date("creationDate", description.creationDate());
        xml.date("lastModificationDate", description.lastModDate());
        xml.text("versionComment", description.versionComment());
        xml.text("contentStatus", description.contentStatus());
        xml.reference("space", ConfluenceEntity.SPACE, ConfluenceEntity.PKG_SPACES, space.id());
        writeCreatorAndLastModifier(xml, description);
        xml.endObject();
    }

    private void writePage(EntitiesXmlWriter xml, ContentRow page, String className, Map<Long, List<BodyRow>> bodies,
        Map<Long, List<PropertyRow>> properties, Map<Long, List<AncestorRow>> ancestors, Set<Long> includedIds)
        throws IOException
    {
        xml.beginObject(className, ConfluenceEntity.PKG_PAGES);
        xml.id("id", page.id());
        xml.scalar("hibernateVersion", page.hibernateVersion());
        xml.text("title", page.title());
        xml.text("lowerTitle", page.lowerTitle());
        writePropertyReferences(xml, properties, page.id());
        xml.scalar("version", page.version());
        xml.date("creationDate", page.creationDate());
        xml.date("lastModificationDate", page.lastModDate());
        xml.text("versionComment", page.versionComment());
        xml.text("contentStatus", page.contentStatus());
        if (page.spaceId() != null) {
            xml.reference("space", ConfluenceEntity.SPACE, ConfluenceEntity.PKG_SPACES, page.spaceId());
        }
        xml.scalar("position", page.position());
        if (page.parentId() != null && includedIds.contains(page.parentId())) {
            xml.reference("parent", ConfluenceEntity.PAGE, ConfluenceEntity.PKG_PAGES, page.parentId());
        }
        writeAncestors(xml, ancestors, page.id(), includedIds);
        writeBodyReferences(xml, bodies, page.id());
        xml.endObject();
    }

    private void writeAttachment(EntitiesXmlWriter xml, ContentRow attachment, SpaceRow space,
        Map<Long, List<PropertyRow>> properties, Map<Long, List<Long>> commentIdsByContainer,
        Map<Long, String> contentTypeById) throws IOException
    {
        xml.beginObject(ConfluenceEntity.ATTACHMENT, ConfluenceEntity.PKG_PAGES);
        xml.id("id", attachment.id());
        xml.scalar("hibernateVersion", attachment.hibernateVersion());
        xml.text("title", attachment.title());
        xml.text("lowerTitle", attachment.lowerTitle());
        writePropertyReferences(xml, properties, attachment.id());
        xml.scalar("version", attachment.version());
        xml.date("creationDate", attachment.creationDate());
        xml.date("lastModificationDate", attachment.lastModDate());
        xml.text("versionComment", attachment.versionComment());
        xml.text("contentStatus", attachment.contentStatus());
        if (attachment.containerId() != null) {
            xml.reference("containerContent",
                classOf(contentTypeById, attachment.containerId()),
                packageOf(contentTypeById, attachment.containerId()),
                attachment.containerId());
        }
        xml.reference("space", ConfluenceEntity.SPACE, ConfluenceEntity.PKG_SPACES, space.id());
        writeCreatorAndLastModifier(xml, attachment);
        // 参考包中该集合始终为空（Confluence 不把 ImageDetails 实体写进导出），
        // 因此这里也写空集合，不臆造嵌套结构。
        xml.beginCollection("imageDetailsDTO", "java.util.Set");
        xml.endCollection();
        List<Long> commentIds = commentIdsByContainer.get(attachment.id());
        if (commentIds != null && !commentIds.isEmpty()) {
            xml.beginCollection("comments", ConfluenceEntity.COLLECTION);
            for (Long commentId : commentIds) {
                xml.element(ConfluenceEntity.COMMENT, ConfluenceEntity.PKG_PAGES, commentId);
            }
            xml.endCollection();
        }
        xml.endObject();
    }

    private void writeComment(EntitiesXmlWriter xml, ContentRow comment, Map<Long, List<BodyRow>> bodies,
        Map<Long, List<PropertyRow>> properties, Set<Long> includedIds, Map<Long, String> contentTypeById)
        throws IOException
    {
        xml.beginObject(ConfluenceEntity.COMMENT, ConfluenceEntity.PKG_PAGES);
        xml.id("id", comment.id());
        xml.scalar("hibernateVersion", comment.hibernateVersion());
        xml.text("title", comment.title());
        xml.text("lowerTitle", comment.lowerTitle());
        writeBodyReferences(xml, bodies, comment.id());
        writePropertyReferences(xml, properties, comment.id());
        xml.scalar("version", comment.version());
        xml.date("creationDate", comment.creationDate());
        xml.date("lastModificationDate", comment.lastModDate());
        xml.text("versionComment", comment.versionComment());
        xml.text("contentStatus", comment.contentStatus());
        if (comment.containerId() != null && includedIds.contains(comment.containerId())) {
            xml.reference("containerContent",
                classOf(contentTypeById, comment.containerId()),
                packageOf(contentTypeById, comment.containerId()),
                comment.containerId());
        }
        xml.endObject();
    }

    private void writeBodyContents(EntitiesXmlWriter xml, Map<Long, List<BodyRow>> bodies, Set<Long> includedIds,
        Map<Long, String> contentTypeById) throws IOException
    {
        for (List<BodyRow> rows : bodies.values()) {
            for (BodyRow body : rows) {
                if (!includedIds.contains(body.contentId())) {
                    continue;
                }
                xml.beginObject(ConfluenceEntity.BODY_CONTENT, ConfluenceEntity.PKG_CORE);
                xml.id("id", body.id());
                xml.text("body", body.body());
                xml.reference("content", classOf(contentTypeById, body.contentId()),
                    packageOf(contentTypeById, body.contentId()), body.contentId());
                xml.scalar("bodyType", body.bodyTypeId());
                xml.endObject();
            }
        }
    }

    private void writeContentProperties(EntitiesXmlWriter xml, Map<Long, List<PropertyRow>> properties)
        throws IOException
    {
        for (List<PropertyRow> rows : properties.values()) {
            for (PropertyRow property : rows) {
                xml.beginObject(ConfluenceEntity.CONTENT_PROPERTY, ConfluenceEntity.PKG_CONTENT);
                xml.id("id", property.id());
                xml.text("name", property.name());
                // 参考包中这三个子属性是 211/211 全部出现（null 时写成自闭合空标签），
                // 因此无条件写出，保持格式一致
                xml.text("stringValue", property.stringValue());
                xml.scalar("longValue", property.longValue());
                xml.date("dateValue", property.dateValue());
                xml.endObject();
            }
        }
    }

    private void writeLabels(EntitiesXmlWriter xml, LabellingData data, Map<Long, String> contentTypeById)
        throws IOException
    {
        for (LabelRow label : data.labels()) {
            xml.beginObject(ConfluenceEntity.LABEL, ConfluenceEntity.PKG_LABELS);
            xml.id("id", label.id());
            xml.text("name", label.name());
            if (label.owner() != null) {
                xml.referenceByKey("owningUser", ConfluenceEntity.CONFLUENCE_USER, ConfluenceEntity.PKG_USER,
                    label.owner());
            }
            xml.text("namespace", label.namespace());
            xml.date("creationDate", label.creationDate());
            xml.date("lastModificationDate", label.lastModDate());
            xml.endObject();
        }

        for (LabellingRow labelling : data.labellings()) {
            xml.beginObject(ConfluenceEntity.LABELLING, ConfluenceEntity.PKG_LABELS);
            xml.id("id", labelling.id());
            xml.reference("label", ConfluenceEntity.LABEL, ConfluenceEntity.PKG_LABELS, labelling.labelId());
            xml.reference("content", classOf(contentTypeById, labelling.contentId()),
                packageOf(contentTypeById, labelling.contentId()), labelling.contentId());
            if (labelling.owner() != null) {
                xml.referenceByKey("owningUser", ConfluenceEntity.CONFLUENCE_USER, ConfluenceEntity.PKG_USER,
                    labelling.owner());
            }
            xml.date("creationDate", labelling.creationDate());
            xml.date("lastModificationDate", labelling.lastModDate());
            xml.scalar("labelableId", labelling.labelableId());
            xml.text("labelableType", labelling.labelableType());
            xml.endObject();
        }
    }

    private void writeUsers(EntitiesXmlWriter xml, Map<String, String> userNames) throws IOException
    {
        for (Map.Entry<String, String> entry : userNames.entrySet()) {
            xml.beginObject(ConfluenceEntity.CONFLUENCE_USER, ConfluenceEntity.PKG_USER);
            xml.idCdata("key", entry.getKey());
            xml.text("name", entry.getValue());
            xml.text("lowerName", entry.getValue() == null ? null : entry.getValue().toLowerCase());
            xml.scalar("email", null);
            xml.endObject();
        }
    }

    private void writeCreatorAndLastModifier(EntitiesXmlWriter xml, ContentRow row) throws IOException
    {
        if (row.creator() != null) {
            xml.referenceByKey("creator", ConfluenceEntity.CONFLUENCE_USER, ConfluenceEntity.PKG_USER, row.creator());
        }
        if (row.lastModifier() != null) {
            xml.referenceByKey("lastModifier", ConfluenceEntity.CONFLUENCE_USER, ConfluenceEntity.PKG_USER,
                row.lastModifier());
        }
    }

    /**
     * 写出指向正文的集合引用。
     *
     * <p>页面与博客通过 {@code bodyContents} 集合引用正文，而不是内联。</p>
     */
    private void writeBodyReferences(EntitiesXmlWriter xml, Map<Long, List<BodyRow>> bodies, long contentId)
        throws IOException
    {
        List<BodyRow> rows = bodies.get(contentId);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        xml.beginCollection("bodyContents", ConfluenceEntity.COLLECTION);
        for (BodyRow body : rows) {
            xml.element(ConfluenceEntity.BODY_CONTENT, ConfluenceEntity.PKG_CORE, body.id());
        }
        xml.endCollection();
    }

    private void writePropertyReferences(EntitiesXmlWriter xml, Map<Long, List<PropertyRow>> properties, long contentId)
        throws IOException
    {
        List<PropertyRow> rows = properties.get(contentId);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        xml.beginCollection("contentProperties", ConfluenceEntity.COLLECTION);
        for (PropertyRow property : rows) {
            xml.element(ConfluenceEntity.CONTENT_PROPERTY, ConfluenceEntity.PKG_CONTENT, property.id());
        }
        xml.endCollection();
    }

    private void writeAncestors(EntitiesXmlWriter xml, Map<Long, List<AncestorRow>> ancestors, long pageId,
        Set<Long> includedIds) throws IOException
    {
        List<AncestorRow> rows = ancestors.get(pageId);
        if (rows == null) {
            return;
        }
        List<AncestorRow> included = rows.stream().filter(row -> includedIds.contains(row.ancestorId())).toList();
        if (included.isEmpty()) {
            return;
        }
        xml.beginCollection("ancestors", ConfluenceEntity.LIST);
        for (AncestorRow row : included) {
            xml.element(ConfluenceEntity.PAGE, ConfluenceEntity.PKG_PAGES, row.ancestorId());
        }
        xml.endCollection();
    }

    /**
     * @param contentTypeById 内容 ID 到 {@code CONTENTTYPE} 的映射
     * @param contentId 内容 ID
     * @return 该内容对应的导出实体类名；无法判定时按 {@code Page} 处理
     */
    private static String classOf(Map<Long, String> contentTypeById, long contentId)
    {
        String className = ConfluenceEntity.classForContentType(contentTypeById.get(contentId));
        return className == null ? ConfluenceEntity.PAGE : className;
    }

    /**
     * @param contentTypeById 内容 ID 到 {@code CONTENTTYPE} 的映射
     * @param contentId 内容 ID
     * @return 该内容对应的导出实体包名；无法判定时按页面所在包处理
     */
    private static String packageOf(Map<Long, String> contentTypeById, long contentId)
    {
        String packageName = ConfluenceEntity.packageForContentType(contentTypeById.get(contentId));
        return packageName == null ? ConfluenceEntity.PKG_PAGES : packageName;
    }

    /**
     * 收集整个包里被引用到的用户 key。
     *
     * <p>实测发现真实实例中 {@code CONTENT.CREATOR} 可能整列为 {@code NULL}，
     * 因此不能只从内容行收集——空间、附件以及标签的 owner 同样会引用用户。</p>
     */
    private static Set<String> collectUserKeys(SpaceRow space, List<ContentRow> contents, LabellingData labellings)
    {
        Set<String> keys = new LinkedHashSet<>(ConfluenceLoader.collectUserKeys(contents));

        if (space.creator() != null) {
            keys.add(space.creator());
        }
        if (space.lastModifier() != null) {
            keys.add(space.lastModifier());
        }
        for (LabelRow label : labellings.labels()) {
            if (label.owner() != null) {
                keys.add(label.owner());
            }
        }
        for (LabellingRow labelling : labellings.labellings()) {
            if (labelling.owner() != null) {
                keys.add(labelling.owner());
            }
        }

        return keys;
    }

    private static List<ContentRow> filterByType(List<ContentRow> contents, String contentType)
    {
        return contents.stream().filter(row -> contentType.equals(row.contentType())).toList();
    }

    // ------------------------------------------------------------- 附件与描述

    private record AttachmentCopyResult(long attachments, long bytes, List<String> warnings)
    {
    }

    private AttachmentCopyResult copyAttachments(Path packageDirectory, List<ContentRow> attachments)
        throws IOException
    {
        List<String> warnings = new ArrayList<>();
        long totalBytes = 0;
        long copied = 0;
        int missing = 0;

        for (ContentRow attachment : attachments) {
            Path sourceFolder = attachmentsDir
                .resolve(AttachmentPathRule.relativePath(attachment.spaceId(), attachment.containerId(),
                    attachment.id(), attachment.version()))
                .getParent();

            if (sourceFolder == null || !Files.isDirectory(sourceFolder)) {
                missing++;
                continue;
            }

            Path targetFolder = packageDirectory.resolve(ATTACHMENTS_DIR)
                .resolve(String.valueOf(attachment.containerId()))
                .resolve(String.valueOf(attachment.id()));
            Files.createDirectories(targetFolder);

            try (DirectoryStream<Path> versions = Files.newDirectoryStream(sourceFolder)) {
                for (Path versionFile : versions) {
                    if (!Files.isRegularFile(versionFile)) {
                        continue;
                    }
                    Path target = targetFolder.resolve(versionFile.getFileName().toString());
                    Files.copy(versionFile, target, StandardCopyOption.REPLACE_EXISTING);
                    totalBytes += Files.size(target);
                }
            }
            copied++;
        }

        if (missing > 0) {
            warnings.add(String.format(
                "%d 个附件在文件系统上找不到文件，未复制。请确认附件目录配置正确。", missing));
        }

        return new AttachmentCopyResult(copied, totalBytes, warnings);
    }

    private void writeDescriptor(Path packageDirectory, SpaceRow space, boolean includeAttachments)
        throws IOException
    {
        Map<String, String> properties = new LinkedHashMap<>();
        // 导入器只读取 source（判定实例类型）与 spaceKey（空间导出去除冗余空间）
        properties.put("source", "server");
        properties.put("spaceKey", space.key());
        properties.put("exportType", "space");
        properties.put("backupAttachments", String.valueOf(includeAttachments));
        properties.put("createdByVersionNumber", "7.3.3");

        StringBuilder content = new StringBuilder();
        content.append("# Generated by confluence-db-migration\n");
        properties.forEach((key, value) -> content.append(key).append('=').append(value).append('\n'));

        Files.writeString(packageDirectory.resolve(DESCRIPTOR_FILE), content.toString(), StandardCharsets.UTF_8);
    }
}
