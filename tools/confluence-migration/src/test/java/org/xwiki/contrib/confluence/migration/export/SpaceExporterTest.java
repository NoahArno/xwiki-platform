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

import java.io.StringReader;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SpaceExporter} 的实体写出测试。
 *
 * <p>盯的是一个曾经静默发生过的回归：页面与博客的 {@code creator} / {@code lastModifier} 没有写进
 * {@code entities.xml}。导入器读不到这两个字段时不会报错，而是把作者回退成「执行导入的人」，
 * 结果是迁移后所有内容都变成同一个人创建的——只有逐页对账才看得出来。</p>
 *
 * <p>之所以能这么直接地测：这些写出方法不依赖数据库，只按传入的行数据拼 XML。</p>
 */
class SpaceExporterTest
{
    private static final String CREATOR_KEY = "402809d49efc8ad0019efcc07cde0000";

    private static final String MODIFIER_KEY = "402809d49efc8ad0019efcc0e33e0001";

    /**
     * 页面：creator / lastModifier 都要写出来。
     */
    @Test
    @DisplayName("页面与博客写出 creator 与 lastModifier")
    void writesPageCreatorAndLastModifier() throws Exception
    {
        Document document = writePage(ConfluenceEntity.PAGE, contentRow(CREATOR_KEY, MODIFIER_KEY));

        assertEquals(CREATOR_KEY, propertyKey(document, "Page", "creator"));
        assertEquals(MODIFIER_KEY, propertyKey(document, "Page", "lastModifier"));
    }

    /**
     * 博客走的是同一个写出方法，但实体类名不同，单独钉一下避免以后分叉。
     */
    @Test
    @DisplayName("博客文章同样写出 creator 与 lastModifier")
    void writesBlogPostCreatorAndLastModifier() throws Exception
    {
        Document document = writePage(ConfluenceEntity.BLOG_POST, contentRow(CREATOR_KEY, MODIFIER_KEY));

        assertEquals(CREATOR_KEY, propertyKey(document, "BlogPost", "creator"));
        assertEquals(MODIFIER_KEY, propertyKey(document, "BlogPost", "lastModifier"));
    }

    /**
     * Confluence 里 creator 为 NULL 的内容很常见（本项目的实测库里有整个空间的 creator 全为 NULL）。
     * 这种情况不写字段，导入器自然回退，而不是写一个空引用让引用完整性校验失败。
     */
    @Test
    @DisplayName("creator 为 NULL 时不写 creator 字段")
    void omitsCreatorWhenAbsent() throws Exception
    {
        Document document = writePage(ConfluenceEntity.PAGE, contentRow(null, null));

        assertNull(propertyKey(document, "Page", "creator"));
        assertNull(propertyKey(document, "Page", "lastModifier"));
    }

    /**
     * 评论同理：不写就会全挂在执行导入的人名下。
     */
    @Test
    @DisplayName("评论写出 creator 与 lastModifier")
    void writesCommentCreatorAndLastModifier() throws Exception
    {
        StringWriter out = new StringWriter();
        SpaceExporter exporter = new SpaceExporter(null, null);
        try (EntitiesXmlWriter xml = new EntitiesXmlWriter(out)) {
            xml.start();
            exporter.writeComment(xml, contentRow(CREATOR_KEY, MODIFIER_KEY), Map.of(), Map.of(), Set.of(),
                Map.of(), null);
            xml.end();
        }

        Document document = parse(out);
        assertEquals(CREATOR_KEY, propertyKey(document, "Comment", "creator"));
        assertEquals(MODIFIER_KEY, propertyKey(document, "Comment", "lastModifier"));
    }

    /**
     * 页面必须把整条评论线索（含各级回复）写在 {@code comments} 集合里：导入器只按这个集合
     * 去找评论，不写出来评论就进不去。
     */
    @Test
    @DisplayName("页面写出 comments 集合")
    void writesPageCommentsCollection() throws Exception
    {
        StringWriter out = new StringWriter();
        SpaceExporter exporter = new SpaceExporter(null, null);
        try (EntitiesXmlWriter xml = new EntitiesXmlWriter(out)) {
            xml.start();
            exporter.writePage(xml, contentRow(CREATOR_KEY, MODIFIER_KEY), ConfluenceEntity.PAGE,
                Map.of(), Map.of(), Map.of(), Set.of(), List.of(700L, 701L));
            xml.end();
        }

        Document document = parse(out);
        assertEquals(List.of("700", "701"), collectionIds(document, "comments"));
    }

    /** 一级评论不该写 parent。 */
    @Test
    @DisplayName("一级评论不写 parent")
    void omitsParentForTopLevelComment() throws Exception
    {
        Document document = writeComment(null);
        assertNull(propertyKey(document, "Comment", "parent"));
    }

    /** 回复要写出父评论 ID，导入器靠它填 replyto。 */
    @Test
    @DisplayName("回复写出 parent 指向父评论")
    void writesParentForReply() throws Exception
    {
        Document document = writeComment(65599L);
        assertEquals("65599", propertyKey(document, "Comment", "parent"));
    }

    /**
     * 评论的归属页面解析——这是实测确认过的 Confluence 语义：
     * 评论层级存在 {@code PAGEID} 上（不是 {@code PARENTID}），回复的 PAGEID 是父评论，
     * 而导入器只认页面作为评论的宿主。
     */
    @Test
    @DisplayName("回复沿父评论上溯，归到所属页面")
    void resolvesOwningPageThroughReplyChain()
    {
        Map<Long, ConfluenceLoader.ContentRow> contentById = new LinkedHashMap<>();
        contentById.put(100L, row(100L, "PAGE", null));
        contentById.put(200L, row(200L, "COMMENT", 100L));      // 一级评论挂在页面上
        contentById.put(201L, row(201L, "COMMENT", 200L));      // 回复挂在评论上
        contentById.put(202L, row(202L, "COMMENT", 201L));      // 回复的回复

        assertEquals(Long.valueOf(100L), SpaceExporter.resolveOwningPage(contentById.get(200L), contentById));
        assertEquals(Long.valueOf(100L), SpaceExporter.resolveOwningPage(contentById.get(201L), contentById));
        assertEquals(Long.valueOf(100L), SpaceExporter.resolveOwningPage(contentById.get(202L), contentById));
    }

    /** 附件上的评论也要归到附件所属的那个页面，否则导入器会把它丢掉。 */
    @Test
    @DisplayName("附件上的评论归到附件所属页面")
    void resolvesOwningPageForAttachmentComment()
    {
        Map<Long, ConfluenceLoader.ContentRow> contentById = new LinkedHashMap<>();
        contentById.put(100L, row(100L, "PAGE", null));
        contentById.put(300L, row(300L, "ATTACHMENT", 100L));   // 附件挂在页面上
        contentById.put(301L, row(301L, "COMMENT", 300L));      // 评论挂在附件上

        assertEquals(Long.valueOf(100L), SpaceExporter.resolveOwningPage(contentById.get(301L), contentById));
    }

    /** 容器不在包内（例如所属页面是草稿被排除）时不能瞎猜，返回 null。 */
    @Test
    @DisplayName("容器不在包内时返回 null")
    void resolvesOwningPageReturnsNullForDanglingContainer()
    {
        Map<Long, ConfluenceLoader.ContentRow> contentById = new LinkedHashMap<>();
        contentById.put(200L, row(200L, "COMMENT", 999L));

        assertNull(SpaceExporter.resolveOwningPage(contentById.get(200L), contentById));
    }

    /**
     * 回复的父评论有两种写法，实测确认过：UI 建的回复用 {@code PARENTCOMMENTID}，
     * 部分 API 写入的回复把父评论放在 {@code PAGEID} 上。两种都要认出来。
     */
    @Test
    @DisplayName("父评论解析兼容 PARENTCOMMENTID 与 PAGEID 两种写法")
    void resolvesParentCommentInBothShapes()
    {
        Map<Long, ConfluenceLoader.ContentRow> contentById = new LinkedHashMap<>();
        contentById.put(100L, row(100L, "PAGE", null));
        contentById.put(200L, row(200L, "COMMENT", 100L));                  // 一级评论：挂在页面上
        contentById.put(201L, row(201L, "COMMENT", 100L, 200L));            // 规范写法：PARENTCOMMENTID
        contentById.put(202L, row(202L, "COMMENT", 201L));                  // 非规范写法：PAGEID 指父评论

        assertNull(SpaceExporter.resolveParentComment(contentById.get(200L), contentById));
        assertEquals(Long.valueOf(200L), SpaceExporter.resolveParentComment(contentById.get(201L), contentById));
        assertEquals(Long.valueOf(201L), SpaceExporter.resolveParentComment(contentById.get(202L), contentById));
    }

    /**
     * 规范写法的回复，{@code PAGEID} 直接是页面，归属解析应当一步到位。
     */
    @Test
    @DisplayName("规范写法的回复直接归到页面")
    void resolvesOwningPageForCanonicalReply()
    {
        Map<Long, ConfluenceLoader.ContentRow> contentById = new LinkedHashMap<>();
        contentById.put(100L, row(100L, "PAGE", null));
        contentById.put(200L, row(200L, "COMMENT", 100L));
        contentById.put(201L, row(201L, "COMMENT", 100L, 200L));

        assertEquals(Long.valueOf(100L), SpaceExporter.resolveOwningPage(contentById.get(201L), contentById));
    }

    private static Document writeComment(Long parentCommentId) throws Exception
    {
        StringWriter out = new StringWriter();
        SpaceExporter exporter = new SpaceExporter(null, null);
        try (EntitiesXmlWriter xml = new EntitiesXmlWriter(out)) {
            xml.start();
            exporter.writeComment(xml, contentRow(CREATOR_KEY, MODIFIER_KEY), Map.of(), Map.of(), Set.of(),
                Map.of(), parentCommentId);
            xml.end();
        }
        return parse(out);
    }

    private static Document writePage(String className, ConfluenceLoader.ContentRow row) throws Exception
    {
        StringWriter out = new StringWriter();
        SpaceExporter exporter = new SpaceExporter(null, null);
        try (EntitiesXmlWriter xml = new EntitiesXmlWriter(out)) {
            xml.start();
            exporter.writePage(xml, row, className, Map.of(), Map.of(), Map.of(), Set.of(), List.of());
            xml.end();
        }
        return parse(out);
    }

    private static ConfluenceLoader.ContentRow row(long id, String contentType, Long containerId)
    {
        return row(id, contentType, containerId, null);
    }

    private static ConfluenceLoader.ContentRow row(long id, String contentType, Long containerId,
        Long parentCommentId)
    {
        return new ConfluenceLoader.ContentRow(id, contentType, "标题", "标题", 1, 1,
            null, null, null, null, "", "current", containerId, null, null, null, parentCommentId);
    }

    private static ConfluenceLoader.ContentRow contentRow(String creator, String lastModifier)
    {
        return new ConfluenceLoader.ContentRow(65591L, "PAGE", "标题", "标题", 1, 2,
            creator, LocalDateTime.of(2026, 1, 1, 0, 0), lastModifier, LocalDateTime.of(2026, 1, 2, 0, 0),
            "", "current", null, null, null, null, null);
    }

    private static Document parse(StringWriter out) throws Exception
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(out.toString())));
    }

    /**
     * @return 指定对象类型上某个引用属性的 key 文本；属性不存在时返回 {@code null}
     */
    private static String propertyKey(Document document, String className, String propertyName)
    {
        Element object = (Element) document.getElementsByTagName("object").item(0);
        assertEquals(className, object.getAttribute("class"));

        for (int i = 0; i < object.getChildNodes().getLength(); i++) {
            if (object.getChildNodes().item(i) instanceof Element property
                && "property".equals(property.getTagName())
                && propertyName.equals(property.getAttribute("name")))
            {
                Element id = (Element) property.getElementsByTagName("id").item(0);
                return id == null ? null : id.getTextContent();
            }
        }
        return null;
    }

    /**
     * @return 指定集合里的元素 ID 列表（按写出顺序）
     */
    private static List<String> collectionIds(Document document, String collectionName)
    {
        Element object = (Element) document.getElementsByTagName("object").item(0);
        for (int i = 0; i < object.getChildNodes().getLength(); i++) {
            if (object.getChildNodes().item(i) instanceof Element collection
                && "collection".equals(collection.getTagName())
                && collectionName.equals(collection.getAttribute("name")))
            {
                List<String> ids = new ArrayList<>();
                for (int j = 0; j < collection.getChildNodes().getLength(); j++) {
                    if (collection.getChildNodes().item(j) instanceof Element element) {
                        ids.add(((Element) element.getElementsByTagName("id").item(0)).getTextContent());
                    }
                }
                return ids;
            }
        }
        return List.of();
    }
}
