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

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Arguments} 的测试。
 *
 * <p>其中"布尔开关不能吞掉后面的参数"是一条回归测试：早期实现把每个 {@code --key} 都当作
 * 需要取值，导致 {@code --all --output /x} 中 {@code --all} 把 {@code --output} 吃掉，
 * {@code /x} 变成非法位置参数。</p>
 */
class ArgumentsTest
{
    @Test
    @DisplayName("布尔开关不会吞掉后面的参数")
    void booleanFlagDoesNotConsumeNextArgument()
    {
        Arguments arguments = Arguments.parse(
            new String[] {"--all", "--output", "/mnt/migration"}, Set.of("all"));

        assertTrue(arguments.has("all"));
        assertEquals("/mnt/migration", arguments.require("output"));
    }

    @Test
    @DisplayName("缺省声明的开关按带值参数处理")
    void undeclaredFlagIsTreatedAsValueTaking()
    {
        Arguments arguments = Arguments.parse(
            new String[] {"--csv", "/tmp/x.csv"}, Set.of("all"));

        assertEquals("/tmp/x.csv", arguments.require("csv"));
    }

    @Test
    @DisplayName("支持 --key=value 形式")
    void supportsInlineValue()
    {
        Arguments arguments = Arguments.parse(new String[] {"--threshold-mib=500"}, Set.of());

        assertEquals(500L, arguments.getLong("threshold-mib", -1L));
    }

    @Test
    @DisplayName("多个开关与带值参数可以混用")
    void mixedFlagsAndValues()
    {
        Arguments arguments = Arguments.parse(
            new String[] {"--all", "--no-attachments", "--output", "/out", "--space", "DS"},
            Set.of("all", "no-attachments"));

        assertTrue(arguments.has("all"));
        assertTrue(arguments.has("no-attachments"));
        assertEquals("/out", arguments.require("output"));
        assertEquals("DS", arguments.require("space"));
    }

    @Test
    @DisplayName("未出现的开关返回 false")
    void absentFlag()
    {
        Arguments arguments = Arguments.parse(new String[] {"--output", "/out"}, Set.of("all"));

        assertFalse(arguments.has("all"));
    }

    @Test
    @DisplayName("位置参数被拒绝")
    void rejectsPositionalArgument()
    {
        assertThrows(IllegalArgumentException.class,
            () -> Arguments.parse(new String[] {"/mnt/migration"}, Set.of()));
    }

    @Test
    @DisplayName("带值参数缺少取值时被拒绝")
    void rejectsMissingValue()
    {
        assertThrows(IllegalArgumentException.class,
            () -> Arguments.parse(new String[] {"--output"}, Set.of()));
    }

    @Test
    @DisplayName("缺少必需参数时被拒绝")
    void rejectsMissingRequired()
    {
        Arguments arguments = Arguments.parse(new String[] {"--all"}, Set.of("all"));

        assertThrows(IllegalArgumentException.class, () -> arguments.require("output"));
    }
}
