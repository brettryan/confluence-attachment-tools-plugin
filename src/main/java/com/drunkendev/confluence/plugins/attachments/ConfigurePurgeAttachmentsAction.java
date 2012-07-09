/*
 * ConfigurePurgeAttachmentsAction.java    Jul 9 2012, 12:37
 *
 * Copyright 2010 John Sands (Australia) Ltd. All rights reserved.
 * Use is subject to license terms. This is an unpublished work.
 */

package com.drunkendev.confluence.plugins.attachments;

import com.atlassian.confluence.core.Administrative;
import com.atlassian.confluence.core.ConfluenceActionSupport;


/**
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
