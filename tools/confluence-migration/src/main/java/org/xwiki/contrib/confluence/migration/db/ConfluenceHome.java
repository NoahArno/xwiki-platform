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
package org.xwiki.contrib.confluence.migration.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * 一个 Confluence Home 目录，以及从中解析出来的数据库配置与附件目录。
 *
 * <p>{@code confluence.cfg.xml} 中的 {@code attachments.dir} 通常写成
 * {@code ${confluenceHome}/attachments}，这里的 {@code ${confluenceHome}} 是 Confluence 的变量，
 * 需要在读取后替换为真实的 Home 路径。</p>
 *
 * @param home Confluence Home 目录
 * @param dbConfig 数据库配置
 * @param attachmentsDir 附件在文件系统上的存放目录
 */
public record ConfluenceHome(Path home, DbConfig dbConfig, Path attachmentsDir)
{
    /** Confluence 配置文件名。 */
    public static final String CONFLUENCE_CFG = "confluence.cfg.xml";

    /** 附件目录配置项。 */
    private static final String ATTACHMENTS_DIR_PROPERTY = "attachments.dir";

    /** Confluence Home 变量占位符。 */
    private static final String CONFLUENCE_HOME_VARIABLE = "${confluenceHome}";

    private static final String ATTR_NAME = "name";

    /**
     * 读取指定 Confluence Home 的配置。
     *
     * @param home Confluence Home 目录（即包含 {@code confluence.cfg.xml} 的目录）
     * @return 解析结果
     * @throws IOException 读取或解析失败时
     */
    public static ConfluenceHome read(Path home) throws IOException
    {
        Path cfg = home.resolve(CONFLUENCE_CFG);
        if (!Files.isRegularFile(cfg)) {
            throw new IOException("找不到 Confluence 配置文件: " + cfg.toAbsolutePath());
        }

        Map<String, String> properties = parseProperties(cfg);

        String url = properties.get("hibernate.connection.url");
        String username = properties.get("hibernate.connection.username");
        String password = properties.getOrDefault("hibernate.connection.password", "");

        if (url == null || username == null) {
            throw new IOException("配置文件缺少数据库连接信息: " + cfg.toAbsolutePath());
        }
        if (looksEncrypted(password)) {
            throw new IOException(String.format(
                "配置文件中的数据库密码看起来是加密的，本工具无法解密。请改用 --password 显式传入。"
                    + "（原始值前缀: %s）",
                password.substring(0, Math.min(12, password.length()))));
        }

        DbConfig dbConfig = new DbConfig(url, username, password);
        return new ConfluenceHome(home, dbConfig, resolveAttachmentsDir(home, properties));
    }

    /**
     * 解析附件目录。
     *
     * @param home Confluence Home
     * @param properties 配置项
     * @return 附件目录绝对路径；未配置时回退到 {@code <home>/attachments}
     */
    private static Path resolveAttachmentsDir(Path home, Map<String, String> properties)
    {
        String configured = properties.get(ATTACHMENTS_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return home.resolve("attachments");
        }
        return Path.of(configured.replace(CONFLUENCE_HOME_VARIABLE, home.toString())).normalize();
    }

    /**
     * @param value 配置值
     * @return 该值是否疑似被 Confluence 加密过
     */
    private static boolean looksEncrypted(String value)
    {
        return value.startsWith("{") || value.startsWith("ENC(") || value.startsWith("AES:");
    }

    private static Map<String, String> parseProperties(Path cfg) throws IOException
    {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 配置来自本机，但仍应禁用外部实体，避免 XXE
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(cfg.toFile());

            Map<String, String> properties = new LinkedHashMap<>();
            NodeList nodes = document.getElementsByTagName("property");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                properties.put(element.getAttribute(ATTR_NAME), element.getTextContent().trim());
            }
            return properties;
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("解析 Confluence 配置失败: " + cfg.toAbsolutePath(), e);
        }
    }
}
