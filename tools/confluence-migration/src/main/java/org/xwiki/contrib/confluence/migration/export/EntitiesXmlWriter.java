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
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 按 Confluence 导出格式写出 {@code entities.xml}。
 *
 * <p>目标格式是 Confluence 的 Hibernate-generic XML dump：</p>
 * <pre>
 * &lt;hibernate-generic datetime="..."&gt;
 *   &lt;object class="Page" package="com.atlassian.confluence.pages"&gt;
 *     &lt;id name="id"&gt;123&lt;/id&gt;
 *     &lt;property name="title"&gt;&lt;![CDATA[标题]]&gt;&lt;/property&gt;
 *   &lt;/object&gt;
 * &lt;/hibernate-generic&gt;
 * </pre>
 *
 * <p>刻意手写而不用 {@code XMLStreamWriter}：需要精确控制 CDATA、引用与集合的形态，
 * 且要能处理任意大的空间而不在内存里拼树。</p>
 *
 * <p>该写入器同时实现 {@link AutoCloseable}，用 try-with-resources 保证标签配平错误
 * 能被及时发现——标签不配平会让导入器直接解析失败。</p>
 */
public final class EntitiesXmlWriter implements AutoCloseable
{
    /** Confluence 导出中日期时间的格式。 */
    public static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final String INDENT_UNIT = "    ";

    private final Writer writer;

    private int depth;

    /**
     * @param writer 目标输出流
     */
    public EntitiesXmlWriter(Writer writer)
    {
        this.writer = writer;
    }

    /**
     * 写出 XML 声明与根元素。
     *
     * @throws IOException 写入失败时
     */
    public void start() throws IOException
    {
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        writer.write("<hibernate-generic datetime=\"" + DATE_FORMAT.format(LocalDateTime.now()) + "\">\n");
        depth = 1;
    }

    /**
     * 写出根元素结束标签并关闭。
     *
     * @throws IOException 写入失败时
     */
    public void end() throws IOException
    {
        writer.write("</hibernate-generic>\n");
        depth = 0;
    }

    /**
     * 开始一个实体对象。
     *
     * @param className Hibernate 实体类名
     * @param packageName 实体所在包
     * @throws IOException 写入失败时
     */
    public void beginObject(String className, String packageName) throws IOException
    {
        indent();
        writer.write("<object class=\"" + className + "\" package=\"" + packageName + "\">\n");
        depth++;
    }

    /**
     * 结束当前实体对象。
     *
     * @throws IOException 写入失败时
     */
    public void endObject() throws IOException
    {
        depth--;
        indent();
        writer.write("</object>\n");
    }

    /**
     * 写出数值型主键：{@code <id name="id">123</id>}。
     *
     * @param name 主键名
     * @param value 主键值
     * @throws IOException 写入失败时
     */
    public void id(String name, long value) throws IOException
    {
        indent();
        writer.write("<id name=\"" + name + "\">" + value + "</id>\n");
    }

    /**
     * 写出字符串型主键：{@code <id name="key"><![CDATA[..]]></id>}。
     *
     * @param name 主键名
     * @param value 主键值
     * @throws IOException 写入失败时
     */
    public void idCdata(String name, String value) throws IOException
    {
        indent();
        writer.write("<id name=\"" + name + "\">");
        writeCdata(value);
        writer.write("</id>\n");
    }

    /**
     * 写出标量属性（数字、日期、布尔等）。
     *
     * @param name 属性名
     * @param value 属性值；{@code null} 会写成自闭合空标签
     * @throws IOException 写入失败时
     */
    public void scalar(String name, Object value) throws IOException
    {
        indent();
        if (value == null) {
            writer.write("<property name=\"" + name + "\"/>\n");
            return;
        }
        writer.write("<property name=\"" + name + "\">" + value + "</property>\n");
    }

    /**
     * 写出字符串属性，内容用 CDATA 包裹。
     *
     * @param name 属性名
     * @param value 属性值；{@code null} 会写成自闭合空标签
     * @throws IOException 写入失败时
     */
    public void text(String name, String value) throws IOException
    {
        indent();
        if (value == null) {
            writer.write("<property name=\"" + name + "\"/>\n");
            return;
        }
        writer.write("<property name=\"" + name + "\">");
        writeCdata(value);
        writer.write("</property>\n");
    }

    /**
     * 写出日期属性，格式为 Confluence 的 {@code yyyy-MM-dd HH:mm:ss.SSS}。
     *
     * @param name 属性名
     * @param value 日期时间；{@code null} 会写成自闭合空标签
     * @throws IOException 写入失败时
     */
    public void date(String name, LocalDateTime value) throws IOException
    {
        scalar(name, value == null ? null : DATE_FORMAT.format(value));
    }

    /**
     * 写出枚举属性，形如
     * {@code <property name="spaceStatus" enum-class="SpaceStatus" package="..">CURRENT</property>}。
     *
     * @param name 属性名
     * @param enumClass 枚举类名
     * @param packageName 枚举所在包
     * @param value 枚举字面量
     * @throws IOException 写入失败时
     */
    public void enumProperty(String name, String enumClass, String packageName, String value) throws IOException
    {
        indent();
        if (value == null) {
            writer.write("<property name=\"" + name + "\"/>\n");
            return;
        }
        writer.write("<property name=\"" + name + "\" enum-class=\"" + enumClass
            + "\" package=\"" + packageName + "\">" + value + "</property>\n");
    }

    /**
     * 写出对另一个实体的引用（数值主键）。
     *
     * @param name 属性名
     * @param className 被引用实体类名
     * @param packageName 被引用实体所在包
     * @param id 被引用实体主键
     * @throws IOException 写入失败时
     */
    public void reference(String name, String className, String packageName, long id) throws IOException
    {
        indent();
        writer.write("<property name=\"" + name + "\" class=\"" + className
            + "\" package=\"" + packageName + "\">");
        writer.write("<id name=\"id\">" + id + "</id>");
        writer.write("</property>\n");
    }

    /**
     * 写出对另一个实体的引用（字符串主键，例如 {@code ConfluenceUserImpl} 的 key）。
     *
     * @param name 属性名
     * @param className 被引用实体类名
     * @param packageName 被引用实体所在包
     * @param key 被引用实体主键
     * @throws IOException 写入失败时
     */
    public void referenceByKey(String name, String className, String packageName, String key) throws IOException
    {
        indent();
        writer.write("<property name=\"" + name + "\" class=\"" + className
            + "\" package=\"" + packageName + "\">");
        writer.write("<id name=\"key\">");
        writeCdata(key);
        writer.write("</id>");
        writer.write("</property>\n");
    }

    /**
     * 开始一个集合。
     *
     * @param name 集合属性名
     * @param collectionClass 集合的 Java 类名
     * @throws IOException 写入失败时
     */
    public void beginCollection(String name, String collectionClass) throws IOException
    {
        indent();
        writer.write("<collection name=\"" + name + "\" class=\"" + collectionClass + "\">");
        depth++;
    }

    /**
     * 结束当前集合。
     *
     * @throws IOException 写入失败时
     */
    public void endCollection() throws IOException
    {
        depth--;
        indent();
        writer.write("</collection>\n");
    }

    /**
     * 写出集合中的一个元素引用。
     *
     * @param className 元素实体类名
     * @param packageName 元素实体所在包
     * @param id 元素主键
     * @throws IOException 写入失败时
     */
    public void element(String className, String packageName, long id) throws IOException
    {
        indent();
        writer.write("<element class=\"" + className + "\" package=\"" + packageName + "\">");
        writer.write("<id name=\"id\">" + id + "</id>");
        writer.write("</element>\n");
    }

    private void indent() throws IOException
    {
        writer.write(INDENT_UNIT.repeat(depth));
    }

    /**
     * 写出 CDATA，并正确处理内容中出现的 {@code ]]>}。
     *
     * <p>{@code ]]>} 不能原样出现在 CDATA 段里，标准做法是在其间断开：
     * {@code ]]} 结束当前段，再开一个新段，于是 {@code ]]>} 写成 {@code ]]]]><![CDATA[>}。</p>
     */
    private void writeCdata(String value) throws IOException
    {
        writer.write("<![CDATA[");
        writer.write(value.replace("]]>", "]]]]><![CDATA[>"));
        writer.write("]]>");
    }

    @Override
    public void close() throws IOException
    {
        writer.close();
    }
}
