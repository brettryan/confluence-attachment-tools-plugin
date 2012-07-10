/*
 * PurgeAttachmentsSettingsService.java    Jul 10 2012, 05:19
 *
 * Copyright 2012 Drunken Dev. All rights reserved.
 * Use is subject to license terms.
 */

package com.drunkendev.confluence.plugins.attachments;

import com.atlassian.bandana.BandanaManager;
import com.atlassian.confluence.setup.bandana.ConfluenceBandanaContext;
import org.apache.commons.lang.StringUtils;


/**
 *
 * @author  Brett Ryan
 */
public class PurgeAttachmentsSettingsService {

    private BandanaManager bandanaManager;
    private static final String KEY = "com.drunkendev.confluence.plugins.attachments.purge-settings";

    /**
     * Creates a new {@code PurgeAttachmentsSettingsService} instance.
     */
    public PurgeAttachmentsSettingsService(BandanaManager bandanaManager) {
        this.bandanaManager = bandanaManager;
    }

    public void setSettings(String spaceKey, PurgeAttachmentSettings settings) {
        if (StringUtils.isBlank(spaceKey)) {
            // Global settings.
            bandanaManager.setValue(
                    new ConfluenceBandanaContext(), KEY, settings);
        } else {
            // Space settings.
            bandanaManager.setValue(
                    new ConfluenceBandanaContext(spaceKey), KEY, settings);
        }
    }

    public PurgeAttachmentSettings getSettings(String spaceKey) {
        PurgeAttachmentSettings settings;
        if (StringUtils.isBlank(spaceKey)) {
            settings = (PurgeAttachmentSettings) bandanaManager.getValue(
                    new ConfluenceBandanaContext(), KEY, false);
        } else {
            settings = (PurgeAttachmentSettings) bandanaManager.getValue(
                    new ConfluenceBandanaContext(spaceKey), KEY, false);
        }

        if (settings == null) {
            settings = new PurgeAttachmentSettings();
        }
        return settings;
    }

}
