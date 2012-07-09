/*
 * ConfigurePurgeAttachmentsAction.java    Jul 9 2012, 12:37
 *
 * Copyright 2012 Drunken Dev. All rights reserved.
 * Use is subject to license terms.
 */

package com.drunkendev.confluence.plugins.attachments;

import com.atlassian.confluence.core.Administrative;
import com.atlassian.confluence.core.ConfluenceActionSupport;


/**
 * Action to configure global attachment purging.
 *
 * @author  Brett Ryan
 */
public class ConfigurePurgeAttachmentsAction extends ConfluenceActionSupport
        implements Administrative {

    /**
     * Creates a new {@code ConfigurePurgeAttachmentsAction} instance.
     */
    public ConfigurePurgeAttachmentsAction() {
    }

    public String input() {
        return INPUT;
    }

    @Override
    public boolean isPermitted() {
        //permissionManager.
        //permissionManager.hasPermission(user, Permission.EDIT, target);
        return super.isPermitted();

    }

}
