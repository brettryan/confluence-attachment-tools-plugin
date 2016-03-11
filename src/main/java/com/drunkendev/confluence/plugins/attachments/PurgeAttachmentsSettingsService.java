/*
 * PurgeAttachmentsSettingsService.java    Jul 10 2012, 05:19
 *
 * Copyright 2012 Drunken Dev. All rights reserved.
 * Use is subject to license terms.
 */

package com.drunkendev.confluence.plugins.attachments;

import com.atlassian.bandana.BandanaManager;
import com.atlassian.confluence.setup.bandana.ConfluenceBandanaContext;

import static org.apache.commons.lang3.StringUtils.isBlank;


/**
 *
 * @author  Brett Ryan
 */
public class PurgeAttachmentsSettingsService {

    private static final String KEY = "com.drunkendev.confluence.plugins.attachments.purge-settings";

    private final BandanaManager bandanaManager;

    /**
     * Creates a new {@code PurgeAttachmentsSettingsService} instance.
     */
    public PurgeAttachmentsSettingsService(BandanaManager bandanaManager) {
        this.bandanaManager = bandanaManager;
    }

    public void setSettings(String spaceKey, PurgeAttachmentSettings settings) {
        bandanaManager.setValue(isBlank(spaceKey)
                                ? new ConfluenceBandanaContext()
                                : new ConfluenceBandanaContext(spaceKey),
                                KEY,
                                settings);
    }

    public PurgeAttachmentSettings getSettings(String spaceKey) {
        return isBlank(spaceKey)
               ? getSettings()
               : (PurgeAttachmentSettings) bandanaManager.getValue(
                        new ConfluenceBandanaContext(spaceKey), KEY, false);
    }

    public PurgeAttachmentSettings getSettings() {
        return (PurgeAttachmentSettings) bandanaManager.getValue(
                new ConfluenceBandanaContext(), KEY, false);
    }

    public PurgeAttachmentSettings createDefault() {
        return new PurgeAttachmentSettings(PurgeAttachmentSettings.MODE_DISABLED,
                                           false, 0,
                                           false, 0,
                                           false, 0,
                                           true, null);
    }

    //TODO: Implement ability to remove all space contexts.
    public void deleteSettings(String spaceKey) {
        bandanaManager.removeValue(isBlank(spaceKey)
                                   ? new ConfluenceBandanaContext()
                                   : new ConfluenceBandanaContext(spaceKey),
                                   KEY);
    }

}
