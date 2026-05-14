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
package org.xwiki.platform.spaces;

/**
 * Constants for the SpaceCode.SpaceClass XClass.
 *
 * @version $Id$
 * @since 18.1.0
 */
public final class SpaceConstants
{
    /**
     * The space where the SpaceClass is defined.
     */
    public static final String SPACE_CODE_SPACE = "SpaceCode";

    /**
     * The name of the SpaceClass document.
     */
    public static final String SPACE_CLASS_DOC = "SpaceClass";

    /**
     * Default list separators for SpaceClass field values.
     */
    public static final String DEFAULT_FIELDS_SEPARATOR = "|";

    /** Name of the name field. */
    public static final String FIELD_NAME = "name";

    /** Pretty name for the name field. */
    public static final String FIELDPN_NAME = "Space Name";

    /** Name of the key field. */
    public static final String FIELD_KEY = "key";

    /** Pretty name for the key field. */
    public static final String FIELDPN_KEY = "Space Key";

    /** Name of the description field. */
    public static final String FIELD_DESCRIPTION = "description";

    /** Pretty name for the description field. */
    public static final String FIELDPN_DESCRIPTION = "Description";

    /** Name of the icon field. */
    public static final String FIELD_ICON = "icon";

    /** Pretty name for the icon field. */
    public static final String FIELDPN_ICON = "Icon";

    /** Name of the owner field. */
    public static final String FIELD_OWNER = "owner";

    /** Pretty name for the owner field. */
    public static final String FIELDPN_OWNER = "Owner";

    /** Name of the type field. */
    public static final String FIELD_TYPE = "type";

    /** Type value for public spaces. */
    public static final String FIELD_VALUE_TYPE_PUBLIC = "public";

    /** Type value for private spaces. */
    public static final String FIELD_VALUE_TYPE_PRIVATE = "private";

    /** Type value for personal spaces. */
    public static final String FIELD_VALUE_TYPE_PERSONAL = "personal";

    /** List of possible type values. */
    public static final String FIELDL_TYPE = FIELD_VALUE_TYPE_PUBLIC + DEFAULT_FIELDS_SEPARATOR
        + FIELD_VALUE_TYPE_PRIVATE + DEFAULT_FIELDS_SEPARATOR
        + FIELD_VALUE_TYPE_PERSONAL;

    /** Pretty name for the type field. */
    public static final String FIELDPN_TYPE = "Type";

    /** Name of the homepage field. */
    public static final String FIELD_HOMEPAGE = "homepage";

    /** Pretty name for the homepage field. */
    public static final String FIELDPN_HOMEPAGE = "Home page";

    /**
     * Private constructor to prevent instantiation.
     */
    private SpaceConstants()
    {
    }
}
