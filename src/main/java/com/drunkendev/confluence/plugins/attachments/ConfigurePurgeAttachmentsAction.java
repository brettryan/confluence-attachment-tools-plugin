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
public class ConfigurePurgeAttachmentsAction extends AbstractSpaceAction implements SpaceAware {

    private PurgeAttachmentsSettingsService settingSvc;
    private int mode;
    private boolean ageRuleEnabled;
    private int maxDaysOld;
    private boolean revisionCountRuleEnabled;
    private int maxRevisions;
    private boolean maxSizeRuleEnabled;
    private long maxTotalSize;
    private boolean reportOnly;
    private String reportEmailAddress;

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
        if (getAuthenticatedUser() == null) {
            return false;
        }
        if (getSpace() == null) {
            return permissionManager.isConfluenceAdministrator(getAuthenticatedUser());
        }
        return permissionManager.hasPermission(getAuthenticatedUser(),
                                               Permission.ADMINISTER,
                                               getSpace());
    }

    @Override
    public String doDefault() throws Exception {
        PurgeAttachmentSettings s = settingSvc.getSettings(getSpaceKey());
        if (s == null) {
            s = settingSvc.createDefault();
        }
        this.mode = s.getMode();
        this.ageRuleEnabled = s.isAgeRuleEnabled();
        this.maxDaysOld = s.getMaxDaysOld();
        this.revisionCountRuleEnabled = s.isRevisionCountRuleEnabled();
        this.maxRevisions = s.getMaxRevisions();
        this.maxSizeRuleEnabled = s.isMaxSizeRuleEnabled();
        this.maxTotalSize = s.getMaxTotalSize();
        this.reportOnly = s.isReportOnly();
        this.reportEmailAddress = s.getReportEmailAddress();
        return INPUT;
    }

    @Override
    public String execute() throws Exception {
        System.out.println("Saving settings: " + ageRuleEnabled);
        settingSvc.setSettings(getSpaceKey(),
                               new PurgeAttachmentSettings(mode,
                                                           ageRuleEnabled,
                                                           maxDaysOld,
                                                           revisionCountRuleEnabled,
                                                           maxRevisions,
                                                           maxSizeRuleEnabled,
                                                           maxTotalSize,
                                                           reportOnly,
                                                           reportEmailAddress));
        return super.execute();
    }

    //
    // Settings properties follow
    //
    /**
     * @return the mode
     */
    public int getMode() {
        return mode;
    }

    /**
     * @param mode the mode to set
     */
    public void setMode(int mode) {
        this.mode = mode;
    }

    /**
     * @return the ageRuleEnabled
     */
    public boolean isAgeRuleEnabled() {
        return ageRuleEnabled;
    }

    /**
     * @param ageRuleEnabled the ageRuleEnabled to set
     */
    public void setAgeRuleEnabled(boolean ageRuleEnabled) {
        this.ageRuleEnabled = ageRuleEnabled;
    }

    /**
     * @return the maxDaysOld
     */
    public int getMaxDaysOld() {
        return maxDaysOld;
    }

    /**
     * @param maxDaysOld the maxDaysOld to set
     */
    public void setMaxDaysOld(int maxDaysOld) {
        this.maxDaysOld = maxDaysOld;
    }

    /**
     * @return the revisionCountRuleEnabled
     */
    public boolean isRevisionCountRuleEnabled() {
        return revisionCountRuleEnabled;
    }

    /**
     * @param revisionCountRuleEnabled the revisionCountRuleEnabled to set
     */
    public void setRevisionCountRuleEnabled(boolean revisionCountRuleEnabled) {
        this.revisionCountRuleEnabled = revisionCountRuleEnabled;
    }

    /**
     * @return the maxRevisions
     */
    public int getMaxRevisions() {
        return maxRevisions;
    }

    /**
     * @param maxRevisions the maxRevisions to set
     */
    public void setMaxRevisions(int maxRevisions) {
        this.maxRevisions = maxRevisions;
    }

    /**
     * @return the maxSizeRuleEnabled
     */
    public boolean isMaxSizeRuleEnabled() {
        return maxSizeRuleEnabled;
    }

    /**
     * @param maxSizeRuleEnabled the maxSizeRuleEnabled to set
     */
    public void setMaxSizeRuleEnabled(boolean maxSizeRuleEnabled) {
        this.maxSizeRuleEnabled = maxSizeRuleEnabled;
    }

    /**
     * @return the maxTotalSize
     */
    public long getMaxTotalSize() {
        return maxTotalSize;
    }

    /**
     * @param maxTotalSize the maxTotalSize to set
     */
    public void setMaxTotalSize(long maxTotalSize) {
        this.maxTotalSize = maxTotalSize;
    }

    /**
     * @return the reportOnly
     */
    public boolean isReportOnly() {
        return reportOnly;
    }

    /**
     * @param reportOnly the reportOnly to set
     */
    public void setReportOnly(boolean reportOnly) {
        this.reportOnly = reportOnly;
    }

    /**
     * @return the reportEmailAddress
     */
    public String getReportEmailAddress() {
        return reportEmailAddress;
    }

    /**
     * @param reportEmailAddress the reportEmailAddress to set
     */
    public void setReportEmailAddress(String reportEmailAddress) {
        this.reportEmailAddress = reportEmailAddress;
    }

}
