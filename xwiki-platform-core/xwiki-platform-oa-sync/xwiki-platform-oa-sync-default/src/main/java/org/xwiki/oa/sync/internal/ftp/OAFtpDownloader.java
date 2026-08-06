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
package org.xwiki.oa.sync.internal.ftp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.oa.sync.OASyncConfiguration;

/**
 * Downloads a remote FTP file to a temporary file. The returned temp file must be deleted by the caller.
 * The FTP client is always logged out and disconnected in {@code finally}, preventing resource leaks.
 *
 * @version $Id$
 */
@Component(roles = OAFtpDownloader.class)
@Singleton
public class OAFtpDownloader
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OAFtpDownloader.class);

    /**
     * Test seam: supplies the {@link FTPClient} instance.
     */
    @FunctionalInterface
    interface FTPClientSupplier
    {
        FTPClient get();
    }

    @Inject
    OASyncConfiguration configuration;

    FTPClientSupplier clientFactory = FTPClient::new;

    /**
     * @param remotePath the remote file path to download
     * @return the temporary local file containing the remote content (caller must delete it)
     * @throws IOException if connect, login or retrieve fails
     */
    public Path download(String remotePath) throws IOException
    {
        FTPClient client = this.clientFactory.get();
        Path temp = null;
        try {
            client.setConnectTimeout(this.configuration.getFtpTimeoutMs());
            client.setDefaultTimeout(this.configuration.getFtpTimeoutMs());
            client.setDataTimeout(this.configuration.getFtpTimeoutMs());
            client.connect(this.configuration.getFtpHost(), this.configuration.getFtpPort());
            LOGGER.info("FTP 连接成功: {}:{}", this.configuration.getFtpHost(), this.configuration.getFtpPort());
            if (!client.login(this.configuration.getFtpUsername(), this.configuration.getFtpPassword())) {
                throw new IOException("FTP 登录失败: " + client.getReplyString());
            }
            client.setFileType(FTP.BINARY_FILE_TYPE);
            client.enterLocalPassiveMode();

            LOGGER.info("FTP 登录成功, 用户: {}", this.configuration.getFtpUsername());
            temp = Files.createTempFile("oa-sync-", ".dat");
            try (OutputStream out = Files.newOutputStream(temp)) {
                if (!client.retrieveFile(remotePath, out)) {
                    throw new IOException("FTP 下载失败 [" + remotePath + "]: " + client.getReplyString());
                }
            }
            LOGGER.info("FTP 下载完成: [{}] ({} 字节)", remotePath, Files.size(temp));
            return temp;
        } catch (IOException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // ignore cleanup failure
                }
            }
            throw e;
        } finally {
            try {
                client.logout();
            } catch (IOException e) {
                LOGGER.debug("FTP logout failed", e);
            }
            try {
                client.disconnect();
            } catch (IOException e) {
                LOGGER.debug("FTP disconnect failed", e);
            }
        }
    }
}
