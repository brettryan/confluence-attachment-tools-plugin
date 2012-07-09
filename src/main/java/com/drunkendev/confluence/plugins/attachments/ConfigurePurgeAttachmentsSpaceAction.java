/*
 * ConfigurePurgeAttachmentsSpaceAction.java    Jul 10 2012, 02:49
 *
 * Copyright 2012 Drunken Dev. All rights reserved.
 * Use is subject to license terms.
 */

package com.drunkendev.confluence.plugins.attachments;

import com.atlassian.confluence.security.Permission;
import com.atlassian.confluence.spaces.actions.AbstractSpaceAction;
import com.atlassian.confluence.spaces.actions.SpaceAware;


/**
 * Action to configure space attachment purging.
 *
 * @author  Brett Ryan
 */
public class ConfigurePurgeAttachmentsSpaceAction
        extends AbstractSpaceAction implements SpaceAware {

    private PurgeAttachmentsSettingsService settingSvc;

    /**
     * Creates a new {@code ConfigurePurgeAttachmentsSpaceAction} instance.
     */
    public ConfigurePurgeAttachmentsSpaceAction() {
    }

    public void setPurgeAttachmentsSettingsService(PurgeAttachmentsSettingsService purgeAttachmentsSettingsService) {
        this.settingSvc = purgeAttachmentsSettingsService;
    }

    @Override
    public boolean isSpaceRequired() {
        return true;
    }

    @Override
    public boolean isViewPermissionRequired() {
        return true;
    }

    @Override
    public boolean isPermitted() {
        return getRemoteUser() != null
                && getSpace() != null
                && permissionManager.hasPermission(getRemoteUser(),
                                                   Permission.ADMINISTER,
                                                   getSpace());
    }

}
