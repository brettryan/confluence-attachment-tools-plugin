/*
 * ConfigurePurgeAttachmentsSpaceCondition.java    Jul 10 2012, 02:27
 *
 * Copyright 2012 Drunken Dev. All rights reserved.
 * Use is subject to license terms.
 */

package com.drunkendev.confluence.plugins.attachments;

import com.atlassian.confluence.plugin.descriptor.web.WebInterfaceContext;
import com.atlassian.confluence.plugin.descriptor.web.conditions.BaseConfluenceCondition;
import com.atlassian.confluence.security.Permission;
import com.atlassian.confluence.security.PermissionManager;


/**
 * Condition to show the purging options against a space.
 *
 * @author  Brett Ryan
 */
public class ConfigurePurgeAttachmentsSpaceCondition extends BaseConfluenceCondition {

    private PermissionManager permissionManager;

    /**
     * Creates a new {@code ConfigurePurgeAttachmentsSpaceCondition} instance.
     */
    public ConfigurePurgeAttachmentsSpaceCondition() {
    }

    public void setPermissionManager(PermissionManager permissionManager) {
        this.permissionManager = permissionManager;
    }

    @Override
    protected boolean shouldDisplay(WebInterfaceContext wic) {
        return wic.getUser() != null
                && wic.getSpace() != null
                && permissionManager.hasPermission(wic.getUser(),
                                                   Permission.ADMINISTER,
                                                   wic.getSpace());
    }

}
