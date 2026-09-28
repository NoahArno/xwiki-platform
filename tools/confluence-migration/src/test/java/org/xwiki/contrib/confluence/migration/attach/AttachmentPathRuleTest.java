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
package org.xwiki.contrib.confluence.migration.attach;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AttachmentPathRule} 的回归测试。
 *
 * <p>黄金基准来自一个真实的 Confluence 7.3.3 实例：把数据库里的
 * {@code (SPACEID, PAGEID, CONTENTID, VERSION)} 喂给本规则，结果必须与 Confluence
 * 自己在 {@code attachments.dir} 下写出的真实文件路径逐字符一致。</p>
 *
 * <p>Confluence 会自行清理草稿及其附件，因此夹具分为两部分：</p>
 * <ul>
 *   <li>{@link #liveSamples()} —— 可随时重新验证的样本</li>
 *   <li>{@link #recordedSamples()} —— 观测当时存在、随后被 Confluence 清理掉的样本，
 *       用于覆盖 {@code nonspaced}（无空间归属）这条分支</li>
 * </ul>
 */
class AttachmentPathRuleTest
{
    /**
     * 一条黄金基准：给定实体 ID 与版本号，以及 Confluence 实际写出的相对路径。
     */
    private record Sample(Long spaceId, long containerId, long attachmentId, int version, String expectedPath)
    {
    }

    /**
     * @return 由本地实例 DB 行 + 磁盘真实文件双向核对得到的样本
     */
    private static List<Sample> liveSamples()
    {
        // 由本地 Confluence 7.3.3 实例实测生成：
        // DB 行 (SPACEID, PAGEID, CONTENTID, VERSION) + 磁盘上真实存在的文件路径。
        // 全部均由 Confluence 自己写出，作为路径算法的黄金基准。
        return List.of(
            sample(98305L, 65562L, 65567L, 4, "ver003/55/98/98305/62/65/65562/65567/4"),
            sample(98305L, 65562L, 65568L, 1, "ver003/55/98/98305/62/65/65562/65568/1"),
            sample(98305L, 65552L, 65569L, 7, "ver003/55/98/98305/52/65/65552/65569/7"),
            sample(98305L, 65562L, 65570L, 1, "ver003/55/98/98305/62/65/65562/65570/1"),
            sample(98305L, 65563L, 65571L, 1, "ver003/55/98/98305/63/65/65563/65571/1"),
            sample(98305L, 65563L, 65572L, 1, "ver003/55/98/98305/63/65/65563/65572/1"),
            sample(98305L, 65563L, 65573L, 1, "ver003/55/98/98305/63/65/65563/65573/1"),
            sample(98305L, 65551L, 65574L, 2, "ver003/55/98/98305/51/65/65551/65574/2"),
            sample(98305L, 65564L, 65575L, 3, "ver003/55/98/98305/64/65/65564/65575/3"),
            sample(98305L, 65564L, 65576L, 2, "ver003/55/98/98305/64/65/65564/65576/2"),
            sample(98305L, 65563L, 65577L, 1, "ver003/55/98/98305/63/65/65563/65577/1"),
            sample(98305L, 65566L, 65578L, 4, "ver003/55/98/98305/66/65/65566/65578/4"),
            sample(98305L, 65566L, 65579L, 2, "ver003/55/98/98305/66/65/65566/65579/2"),
            sample(98305L, 65566L, 65580L, 6, "ver003/55/98/98305/66/65/65566/65580/6"),
            sample(98305L, 65552L, 65581L, 3, "ver003/55/98/98305/52/65/65552/65581/3"),
            sample(98305L, 65557L, 65582L, 1, "ver003/55/98/98305/57/65/65557/65582/1"),
            sample(98305L, 65550L, 65583L, 3, "ver003/55/98/98305/50/65/65550/65583/3"),
            sample(98305L, 65564L, 65584L, 4, "ver003/55/98/98305/64/65/65564/65584/4"),
            sample(98305L, 65552L, 65585L, 2, "ver003/55/98/98305/52/65/65552/65585/2"),
            sample(98305L, 65562L, 65587L, 1, "ver003/55/98/98305/62/65/65562/65587/1"),
            sample(98306L, 65591L, 65593L, 1, "ver003/56/98/98306/91/65/65591/65593/1"),
            sample(98306L, 65591L, 65594L, 1, "ver003/56/98/98306/91/65/65591/65594/1"),
            sample(98306L, 65591L, 65595L, 1, "ver003/56/98/98306/91/65/65591/65595/1"),
            sample(98306L, 65591L, 65596L, 1, "ver003/56/98/98306/91/65/65591/65596/1"),
            sample(98306L, 65591L, 65597L, 1, "ver003/56/98/98306/91/65/65591/65597/1"),
            sample(98306L, 65591L, 65598L, 1, "ver003/56/98/98306/91/65/65591/65598/1")
        );
    }

    /**
     * 覆盖 {@code nonspaced} 分支。
     *
     * <p>这 5 条观测自一个挂在草稿页（{@code CONTENTID=65601}、{@code SPACEID} 为 {@code NULL}）
     * 上的附件。该草稿随后被 Confluence 自身的清理任务删除，磁盘目录也随之消失——
     * 这恰好说明草稿不是稳定的迁移对象。规则本身是纯函数，样本仍然有效。</p>
     *
     * @return 观测记录下来的无空间归属样本
     */
    private static List<Sample> recordedSamples()
    {
        return List.of(
            sample(null, 65601L, 65602L, 1, "ver003/nonspaced/101/65/65601/65602/1"),
            sample(null, 65601L, 65603L, 1, "ver003/nonspaced/101/65/65601/65603/1"),
            sample(null, 65601L, 65604L, 1, "ver003/nonspaced/101/65/65601/65604/1"),
            sample(null, 65601L, 65605L, 1, "ver003/nonspaced/101/65/65601/65605/1"),
            sample(null, 65601L, 65606L, 1, "ver003/nonspaced/101/65/65601/65606/1")
        );
    }

    private static Sample sample(Long spaceId, long containerId, long attachmentId, int version, String expectedPath)
    {
        return new Sample(spaceId, containerId, attachmentId, version, expectedPath);
    }

    @Test
    @DisplayName("真实 Confluence 实例写出的附件路径必须逐字符复现")
    void reproducesRealAttachmentPaths()
    {
        for (Sample sample : liveSamples()) {
            assertEquals(
                sample.expectedPath(),
                AttachmentPathRule.relativePath(
                    sample.spaceId(), sample.containerId(), sample.attachmentId(), sample.version()),
                "路径不匹配，样本: " + sample);
        }
    }

    @Test
    @DisplayName("无空间归属的附件走 nonspaced 分支")
    void reproducesNonSpacedAttachmentPaths()
    {
        for (Sample sample : recordedSamples()) {
            assertEquals(
                sample.expectedPath(),
                AttachmentPathRule.relativePath(
                    sample.spaceId(), sample.containerId(), sample.attachmentId(), sample.version()),
                "路径不匹配，样本: " + sample);
        }
    }

    @Test
    @DisplayName("哈希分段与 Confluence 的 IdMultiPartHashGenerator(3, 250, 2) 一致")
    void hashPartsMatchesConfluenceAlgorithm()
    {
        // 5 位：低 3 位取模，再取高位余数
        assertEquals(List.of(50, 65), AttachmentPathRule.hashParts(65550L));
        assertEquals(List.of(101, 65), AttachmentPathRule.hashParts(65601L));
        assertEquals(List.of(55, 98), AttachmentPathRule.hashParts(98305L));
        assertEquals(List.of(56, 98), AttachmentPathRule.hashParts(98306L));

        // 4 位：先取低 3 位，再把剩下的 1 位作为第二段
        assertEquals(List.of(234, 1), AttachmentPathRule.hashParts(1234L));

        // 6 位：位置恰好落到 0 时走"剩余前缀"分支
        assertEquals(List.of(206, 123), AttachmentPathRule.hashParts(123456L));

        // 7 位：两段都取满 3 位，最高位被丢弃（与 Confluence 行为一致）
        assertEquals(List.of(67, 234), AttachmentPathRule.hashParts(1234567L));

        // 短 ID：不足两段时在**列表末尾**补 0（不是插到开头）
        assertEquals(List.of(42, 0), AttachmentPathRule.hashParts(42L));
        assertEquals(List.of(7, 0), AttachmentPathRule.hashParts(7L));
        assertEquals(List.of(0, 0), AttachmentPathRule.hashParts(0L));
    }

    @Test
    @DisplayName("负数 ID 被拒绝")
    void rejectsNegativeId()
    {
        assertThrows(IllegalArgumentException.class, () -> AttachmentPathRule.hashParts(-1L));
    }
}
