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

/**
 * Confluence 导出格式中用到的实体类名与包名。
 *
 * <p>取值全部来自真实导出包的实测结果。生成的包要能被 XWiki 的 {@code confluence+xml}
 * 输入流读取，这些字符串必须与 Confluence 自己写出的一致——导入器是按
 * {@code class} 属性分派的（见 {@code ConfluenceXMLPackage} 的 {@code OBJECT_TYPE_*} 常量）。</p>
 */
public final class ConfluenceEntity
{
    // ---- 包名 ----

    /** 空间相关实体所在包。 */
    public static final String PKG_SPACES = "com.atlassian.confluence.spaces";

    /** 页面/附件/评论相关实体所在包。 */
    public static final String PKG_PAGES = "com.atlassian.confluence.pages";

    /** 正文与内容基类所在包。 */
    public static final String PKG_CORE = "com.atlassian.confluence.core";

    /** 标签相关实体所在包。 */
    public static final String PKG_LABELS = "com.atlassian.confluence.labels";

    /** 内容属性所在包。 */
    public static final String PKG_CONTENT = "com.atlassian.confluence.content";

    /** 用户实体所在包。 */
    public static final String PKG_USER = "com.atlassian.confluence.user";

    // ---- 类名 ----

    /** 空间。 */
    public static final String SPACE = "Space";

    /** 空间描述（空间的正文载体）。 */
    public static final String SPACE_DESCRIPTION = "SpaceDescription";

    /** 页面。 */
    public static final String PAGE = "Page";

    /** 博客。 */
    public static final String BLOG_POST = "BlogPost";

    /** 附件。 */
    public static final String ATTACHMENT = "Attachment";

    /** 评论。 */
    public static final String COMMENT = "Comment";

    /** 正文。 */
    public static final String BODY_CONTENT = "BodyContent";

    /** 标签。 */
    public static final String LABEL = "Label";

    /** 标签与内容的关联。 */
    public static final String LABELLING = "Labelling";

    /** 内容属性。 */
    public static final String CONTENT_PROPERTY = "ContentProperty";

    /** Confluence 用户（按 key 标识）。 */
    public static final String CONFLUENCE_USER = "ConfluenceUserImpl";

    // ---- 内容类型（CONTENT.CONTENTTYPE） ----

    /** 内容类型：页面。 */
    public static final String TYPE_PAGE = "PAGE";

    /** 内容类型：博客。 */
    public static final String TYPE_BLOG_POST = "BLOGPOST";

    /** 内容类型：附件。 */
    public static final String TYPE_ATTACHMENT = "ATTACHMENT";

    /** 内容类型：评论。 */
    public static final String TYPE_COMMENT = "COMMENT";

    /** 内容类型：空间描述。 */
    public static final String TYPE_SPACE_DESCRIPTION = "SPACEDESCRIPTION";

    // ---- 集合的 Java 类名 ----

    /** 普通集合。 */
    public static final String COLLECTION = "java.util.Collection";

    /** 有序列表（例如页面祖先）。 */
    public static final String LIST = "java.util.List";

    private ConfluenceEntity()
    {
        // constants only
    }

    /**
     * @param contentType {@code CONTENT.CONTENTTYPE} 的取值
     * @return 对应的导出实体类名，未知类型返回 {@code null}
     */
    public static String classForContentType(String contentType)
    {
        if (contentType == null) {
            return null;
        }
        return switch (contentType) {
            case TYPE_PAGE -> PAGE;
            case TYPE_BLOG_POST -> BLOG_POST;
            case TYPE_ATTACHMENT -> ATTACHMENT;
            case TYPE_COMMENT -> COMMENT;
            case TYPE_SPACE_DESCRIPTION -> SPACE_DESCRIPTION;
            default -> null;
        };
    }

    /**
     * @param contentType {@code CONTENT.CONTENTTYPE} 的取值
     * @return 对应的导出实体包名，未知类型返回 {@code null}
     */
    public static String packageForContentType(String contentType)
    {
        if (contentType == null) {
            return null;
        }
        return switch (contentType) {
            case TYPE_PAGE, TYPE_BLOG_POST, TYPE_ATTACHMENT, TYPE_COMMENT -> PKG_PAGES;
            case TYPE_SPACE_DESCRIPTION -> PKG_SPACES;
            default -> null;
        };
    }
}
