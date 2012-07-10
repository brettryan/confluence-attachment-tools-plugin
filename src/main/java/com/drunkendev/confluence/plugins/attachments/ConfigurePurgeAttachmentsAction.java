/*
 * ConfigurePurgeAttachmentsAction.java    Jul 9 2012, 12:37
 *
 * Copyright 2012 Drunken Dev. All rights reserved.
 * Use is subject to license terms.
 */

package com.drunkendev.confluence.plugins.attachments;

import com.atlassian.confluence.security.Permission;
import com.atlassian.confluence.spaces.actions.AbstractSpaceAction;
import com.atlassian.confluence.spaces.actions.SpaceAware;


/**
 * Action to configure global attachment purging.
 *
 * @author  Brett Ryan
 */
public class ConfigurePurgeAttachmentsAction extends AbstractSpaceAction
        implements SpaceAware {

    private PurgeAttachmentsSettingsService settingSvc;

    /**
     * Creates a new {@code ConfigurePurgeAttachmentsAction} instance.
     */
    public ConfigurePurgeAttachmentsAction() {
    }

    public void setPurgeAttachmentsSettingsService(PurgeAttachmentsSettingsService purgeAttachmentsSettingsService) {
        this.settingSvc = purgeAttachmentsSettingsService;
    }

    @Override
    public boolean isSpaceRequired() {
        return false;
    }

    @Override
    public boolean isViewPermissionRequired() {
        return true;
    }

    @Override
    public boolean isPermitted() {
        if (getRemoteUser() == null) {
            return false;
        }
        if (getSpace() == null) {
            return permissionManager.isConfluenceAdministrator(getRemoteUser());
        }
        return permissionManager.hasPermission(getRemoteUser(),
                                               Permission.ADMINISTER,
                                               getSpace());
    }

}
