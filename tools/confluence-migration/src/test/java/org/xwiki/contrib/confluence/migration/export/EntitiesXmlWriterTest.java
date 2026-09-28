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

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntitiesXmlWriter} 的测试。
 *
 * <p>重点是 CDATA 里出现 {@code ]]>} 时的正确断开——这是唯一能让生成出来的 XML
 * 直接解析失败的内容形态，而它完全可能出现于 Confluence 页面正文中。</p>
 */
class EntitiesXmlWriterTest
{
    private String writeSample(String body, String title) throws Exception
    {
        StringWriter out = new StringWriter();
        try (EntitiesXmlWriter xml = new EntitiesXmlWriter(out)) {
            xml.start();
            xml.beginObject(ConfluenceEntity.PAGE, ConfluenceEntity.PKG_PAGES);
            xml.id("id", 42L);
            xml.text("title", title);
            xml.text("body", body);
            xml.scalar("version", 3);
            xml.scalar("position", null);
            xml.date("creationDate", LocalDateTime.of(2026, 1, 2, 3, 4, 5, 600_000_000));
            xml.endObject();
            xml.end();
        }
        return out.toString();
    }

    private Document parse(String xml) throws Exception
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    @Test
    @DisplayName("正文含 ]]> 时仍能解析出原文")
    void handlesCdataTerminatorInContent() throws Exception
    {
        String tricky = "before ]]> after";
        String xml = writeSample(tricky, "t");

        Document document = parse(xml);
        NodeList properties = document.getElementsByTagName("property");

        String body = null;
        for (int i = 0; i < properties.getLength(); i++) {
            Element element = (Element) properties.item(i);
            if ("body".equals(element.getAttribute("name"))) {
                body = element.getTextContent();
            }
        }

        assertEquals(tricky, body);
    }

    @Test
    @DisplayName("含中文与特殊字符的正文能原样往返")
    void roundTripsUnicodeContent() throws Exception
    {
        String content = "开发中心 <ac:link> & \"引号\" 换行\n第二行";
        String xml = writeSample(content, "标题");

        Document document = parse(xml);
        assertTrue(document.getElementsByTagName("object").getLength() > 0);

        NodeList properties = document.getElementsByTagName("property");
        StringBuilder found = new StringBuilder();
        for (int i = 0; i < properties.getLength(); i++) {
            Element element = (Element) properties.item(i);
            if ("body".equals(element.getAttribute("name"))) {
                found.append(element.getTextContent());
            }
        }

        assertEquals(content, found.toString());
    }

    @Test
    @DisplayName("null 标量属性写成自闭合空标签")
    void writesEmptyTagForNullScalar() throws Exception
    {
        String xml = writeSample("b", "t");

        assertTrue(xml.contains("<property name=\"position\"/>"),
            "null 值应写成自闭合标签，实际输出:\n" + xml);
    }

    @Test
    @DisplayName("日期按 Confluence 的格式写出")
    void writesConfluenceDateFormat() throws Exception
    {
        String xml = writeSample("b", "t");

        assertTrue(xml.contains("2026-01-02 03:04:05.600"), "日期格式不符，实际输出:\n" + xml);
    }

    @Test
    @DisplayName("根元素与对象标签正确闭合")
    void writesWellFormedDocument() throws Exception
    {
        String xml = writeSample("b", "t");

        Document document = parse(xml);
        assertNotNull(document.getDocumentElement());
        assertEquals("hibernate-generic", document.getDocumentElement().getTagName());
        assertEquals(1, document.getElementsByTagName("object").getLength());
    }
}
