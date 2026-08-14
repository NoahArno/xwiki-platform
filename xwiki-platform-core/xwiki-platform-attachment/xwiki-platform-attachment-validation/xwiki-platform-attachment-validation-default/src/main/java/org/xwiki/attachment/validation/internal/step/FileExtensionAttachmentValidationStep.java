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
package org.xwiki.attachment.validation.internal.step;

import java.util.List;
import java.util.Locale;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import jakarta.ws.rs.core.Response;

import org.xwiki.attachment.AttachmentAccessWrapper;
import org.xwiki.attachment.validation.AttachmentValidationConfiguration;
import org.xwiki.attachment.validation.AttachmentValidationException;
import org.xwiki.attachment.validation.AttachmentValidationStep;
import org.xwiki.component.annotation.Component;

/**
 * Validate the attachment file extension based on configured lists of allowed and blocked extensions. Unlike the
 * mimetype validation, which is based on the content detection, this validation uses the file name extension and is
 * useful to enforce a strict allowlist (e.g. only "pdf", "docx", "png"...).
 *
 * @version $Id$
 * @since 18.1.0
 */
@Component
@Singleton
@Named(FileExtensionAttachmentValidationStep.HINT)
public class FileExtensionAttachmentValidationStep implements AttachmentValidationStep
{
    /**
     * This component hint.
     */
    public static final String HINT = "extension";

    @Inject
    private AttachmentValidationConfiguration attachmentValidationConfiguration;

    @Override
    public void validate(AttachmentAccessWrapper wrapper) throws AttachmentValidationException
    {
        String fileName = wrapper.getFileName();
        if (fileName == null) {
            return;
        }
        String extension = getExtension(fileName);
        List<String> allowedExtensions = this.attachmentValidationConfiguration.getAllowedExtensions();
        List<String> blockedExtensions = this.attachmentValidationConfiguration.getBlockedExtensions();
        boolean hasAllowedExtensions = !allowedExtensions.isEmpty();
        boolean hasBlockedExtensions = !blockedExtensions.isEmpty();

        // A file without an extension cannot match the allowlist, so it is rejected when an allowlist is configured.
        if (hasAllowedExtensions && (extension == null || !checkExtension(allowedExtensions, extension))
            || hasBlockedExtensions && extension != null && checkExtension(blockedExtensions, extension)) {
            throw new AttachmentValidationException(String.format("Invalid extension [%s]", extension),
                Response.Status.UNSUPPORTED_MEDIA_TYPE.getStatusCode(), "attachment.validation.extension.rejected",
                List.of(allowedExtensions, blockedExtensions), null);
        }
    }

    private String getExtension(String fileName)
    {
        int index = fileName.lastIndexOf('.');
        return index >= 0 && index < fileName.length() - 1 ? fileName.substring(index + 1) : null;
    }

    private boolean checkExtension(List<String> extensions, String extension)
    {
        return extensions.stream().anyMatch(configuredExtension -> {
            String normalized = configuredExtension.startsWith(".") ? configuredExtension.substring(1)
                : configuredExtension;
            return normalized.toLowerCase(Locale.ROOT).equals(extension.toLowerCase(Locale.ROOT));
        });
    }
}
