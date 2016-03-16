/*
 * PurgeAttachmentSettings.java    Jul 10 2012, 05:40
 *
 * Copyright 2012 Drunken Dev. All rights reserved.
 * Use is subject to license terms.
 */

package com.drunkendev.confluence.plugins.attachments;

import java.io.Serializable;


/**
 * Holds settings for attachment processor.
 *
 * @author  Brett Ryan
 */
public class PurgeAttachmentSettings implements Serializable {

    public static final int MODE_DISABLED = 1;
    public static final int MODE_GLOBAL = 2;
    public static final int MODE_SPACE = 3;

    private static final long serialVersionUID = 1L;

    private int mode;
    private boolean ageRuleEnabled;
    private int maxDaysOld;
    private boolean revisionCountRuleEnabled;
    private int maxRevisions;
    private boolean maxSizeRuleEnabled;
    private long maxTotalSize;
    private boolean reportOnly;
    private String reportEmailAddress;
    private boolean sendPlainTextMail;

    private int deleteLimit;

    /**
     * Creates a new {@code PurgeAttachmentSettings} instance.
     */
    public PurgeAttachmentSettings() {
    }

    public PurgeAttachmentSettings(int mode,
                                   boolean ageRuleEnabled,
                                   int maxDaysOld,
                                   boolean revisionCountRuleEnabled,
                                   int maxRevisions,
                                   boolean maxSizeRuleEnabled,
                                   long maxTotalSize,
                                   boolean reportOnly,
                                   String reportEmailAddress,
                                   boolean sendPlainTextMail,
                                   int deleteLimit) {
        this.mode = mode;
        this.ageRuleEnabled = ageRuleEnabled;
        this.maxDaysOld = maxDaysOld;
        this.revisionCountRuleEnabled = revisionCountRuleEnabled;
        this.maxRevisions = maxRevisions;
        this.maxSizeRuleEnabled = maxSizeRuleEnabled;
        this.maxTotalSize = maxTotalSize;
        this.reportOnly = reportOnly;
        this.reportEmailAddress = reportEmailAddress;
        this.sendPlainTextMail = sendPlainTextMail;
        this.deleteLimit = deleteLimit;
    }

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

    public boolean isSendPlainTextMail() {
        return sendPlainTextMail;
    }

    public void setSendPlainTextMail(boolean sendPlainTextMail) {
        this.sendPlainTextMail = sendPlainTextMail;
    }

    public int getDeleteLimit() {
        return deleteLimit;
    }

    public void setDeleteLimit(int deleteLimit) {
        this.deleteLimit = deleteLimit;
    }

    @Override
    public String toString() {
        return "PurgeAttachmentSettings{" + "mode=" + mode + ", ageRuleEnabled=" + ageRuleEnabled + ", maxDaysOld=" + maxDaysOld + ", revisionCountRuleEnabled=" + revisionCountRuleEnabled + ", maxRevisions=" + maxRevisions + ", maxSizeRuleEnabled=" + maxSizeRuleEnabled + ", maxTotalSize=" + maxTotalSize + ", reportOnly=" + reportOnly + ", reportEmailAddress=" + reportEmailAddress + '}';
    }

}
