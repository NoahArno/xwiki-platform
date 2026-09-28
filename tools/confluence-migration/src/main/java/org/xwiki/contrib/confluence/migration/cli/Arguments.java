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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 极简的 {@code --key value} 命令行参数解析。
 *
 * <p>刻意不引入第三方 CLI 库：参数很少，且希望这个工具能单 jar 运行。</p>
 */
final class Arguments
{
    private final Map<String, String> values = new LinkedHashMap<>();

    private Arguments()
    {
    }

    /**
     * 解析参数。
     *
     * @param args 命令行参数（不含子命令本身）
     * @param booleanFlags 不需要取值的开关名（不含 {@code --}）。
     *     必须显式声明，否则 {@code --all --output x} 会让 {@code --all} 把 {@code --output} 当作自己的值
     * @return 解析结果
     * @throws IllegalArgumentException 参数格式不合法时
     */
    static Arguments parse(String[] args, Set<String> booleanFlags)
    {
        Arguments arguments = new Arguments();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("无法识别的参数: " + arg);
            }
            String key = arg.substring(2);
            int equals = key.indexOf('=');
            if (equals >= 0) {
                arguments.values.put(key.substring(0, equals), key.substring(equals + 1));
                continue;
            }
            if (booleanFlags.contains(key)) {
                arguments.values.put(key, "");
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("参数缺少取值: " + arg);
            }
            arguments.values.put(key, args[++i]);
        }
        return arguments;
    }

    /**
     * @param key 参数名（不含 {@code --}）
     * @return 参数值，未提供时为 {@code null}
     */
    String get(String key)
    {
        return values.get(key);
    }

    /**
     * @param key 参数名
     * @param defaultValue 默认值
     * @return 参数值，未提供时为 {@code defaultValue}
     */
    String get(String key, String defaultValue)
    {
        return values.getOrDefault(key, defaultValue);
    }

    /**
     * @param key 参数名
     * @return 参数值
     * @throws IllegalArgumentException 未提供时
     */
    String require(String key)
    {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少必需参数: --" + key);
        }
        return value;
    }

    /**
     * @param key 参数名
     * @param defaultValue 默认值
     * @return 整数取值
     */
    long getLong(String key, long defaultValue)
    {
        String value = values.get(key);
        return value == null ? defaultValue : Long.parseLong(value);
    }

    /**
     * @param key 参数名
     * @return 布尔开关是否出现
     */
    boolean has(String key)
    {
        return values.containsKey(key);
    }
}
