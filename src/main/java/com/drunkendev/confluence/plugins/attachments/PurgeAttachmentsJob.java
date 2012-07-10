/*
 * PurgeAttachmentsJob.java    Jul 9 2012, 04:31
 *
 * Copyright 2012 Drunken Dev. All rights reserved.
 * Use is subject to license terms.
 */

package com.drunkendev.confluence.plugins.attachments;

import com.atlassian.confluence.pages.Attachment;
import com.atlassian.confluence.pages.AttachmentManager;
import com.atlassian.confluence.spaces.Space;
import com.atlassian.confluence.spaces.SpaceManager;
import com.atlassian.quartz.jobs.AbstractJob;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Purge old attachment versions.
 *
 * @author  Brett Ryan
 */
public class PurgeAttachmentsJob extends AbstractJob {

    private static final Logger LOG =
            LoggerFactory.getLogger(PurgeAttachmentsJob.class);
    private AttachmentManager attachmentManager;
    private SpaceManager spaceManager;
    private PurgeAttachmentsSettingsService settingSvc;

    /**
     * Creates a new {@code PurgeAttachmentsJob} instance.
     */
    public PurgeAttachmentsJob() {
        LOG.debug("Creating purge-old-attachment-job instance.");
    }

    public void setAttachmentManager(AttachmentManager attachmentManager) {
        this.attachmentManager = attachmentManager;
    }

    public void setSpaceManager(SpaceManager spaceManager) {
        this.spaceManager = spaceManager;
    }

    public void setPurgeAttachmentsSettingsService(PurgeAttachmentsSettingsService purgeAttachmentsSettingsService) {
        this.settingSvc = purgeAttachmentsSettingsService;
    }

    private PurgeAttachmentSettings getSettings(Attachment a, PurgeAttachmentSettings dflt) {
        if (a == null) {
            return null;
        }
        return getSettings(a.getSpace(), dflt);
    }

    private PurgeAttachmentSettings getSettings(Space space, PurgeAttachmentSettings dflt) {
        if (space == null) {
            return null;
        }
        PurgeAttachmentSettings sng = settingSvc.getSettings(space.getKey());
        if (sng != null && sng.getMode() == PurgeAttachmentSettings.MODE_DISABLED) {
            sng = null;
        } else if (sng == null || sng.getMode() == PurgeAttachmentSettings.MODE_GLOBAL) {
            sng = dflt;
        }
        return sng;
    }

    private Map<String, PurgeAttachmentSettings> getAllSpaceSettings() {
        PurgeAttachmentSettings dflt = settingSvc.getSettings(null);
        Map<String, PurgeAttachmentSettings> r = new HashMap<String, PurgeAttachmentSettings>();
        for (Space s : spaceManager.getAllSpaces()) {
            if (s.getKey() == null || r.containsKey(s.getKey())) {
                continue;
            }
            PurgeAttachmentSettings stg = getSettings(s, dflt);
            if (stg != null) {
                r.put(s.getKey(), stg);
            }
        }
        return r;
    }

    @Override
    public void doExecute(JobExecutionContext jec) throws JobExecutionException {
        LOG.info("Purge old attachments started.");

        if (attachmentManager == null) {
            LOG.error("attachment manager is null, why?");
            return;
        }

        processByAttachment();

        LOG.info("Purge old attachments completed.");
    }

    private void processByAttachment() {
        // To delete old attachments 1 week ago.

        Iterator<Attachment> i =
                attachmentManager.getAttachmentDao().
                findLatestVersionsIterator();

        Map<String, PurgeAttachmentSettings> sl = getAllSpaceSettings();

        while (i.hasNext()) {
            Attachment a = i.next();
            if (a.getVersion() > 1
                    && a.getSpace() != null
                    && sl.containsKey(a.getSpace().getKey())) {

                for (Attachment p : findDeletions(a, sl.get(a.getSpace().getKey()))) {
                    // Log removal
                    LOG.warn("Would remove attachment: "
                            + p.getDisplayTitle() + " (" + p.getExportPath() + ")");
                    //attachmentManager.removeAttachmentFromServer(p);
                }
            }
        }
    }

    private List<Attachment> findDeletions(Attachment a, PurgeAttachmentSettings stng) {
        List<Attachment> prior = attachmentManager.getPreviousVersions(a);
        if (prior.isEmpty()) {
            return Collections.<Attachment>emptyList();
        }
        Collections.sort(prior, new Comparator<Attachment>() {

            @Override
            public int compare(Attachment t, Attachment t1) {
                return t.getVersion() - t1.getVersion();
            }

        });

        int last = prior.size() - 1;
        int n = 0;
        if (stng.isRevisionCountRuleEnabled()) {
            n = filterRevisionCount(prior, stng.getMaxRevisions());
            if (n < last) {
                last = n;
            }
        }
        if (stng.isAgeRuleEnabled()) {
            n = filterAge(prior, stng.getMaxDaysOld());
            if (n < last) {
                last = n;
            }
        }
        if (stng.isMaxSizeRuleEnabled()) {
            n = filterSize(prior, stng.getMaxTotalSize());
            if (n < last) {
                last = n;
            }
        }
        return last >= 0 ? prior.subList(0, last) : Collections.<Attachment>emptyList();
    }

    private int filterRevisionCount(List<Attachment> prior, int maxRevisions) {
        return (maxRevisions <= prior.size() ? prior.size() : maxRevisions) - 1;
    }

    private int filterAge(List<Attachment> prior, int maxDaysOld) {
        Calendar dateFrom = Calendar.getInstance();
        dateFrom.add(Calendar.DAY_OF_MONTH, -7);
        Calendar modDate = Calendar.getInstance();

        for (int i = 0; i < prior.size(); i++) {
            modDate.setTime(prior.get(i).getLastModificationDate());
            if (dateFrom.after(modDate)) {
                return i - 1;
            }
        }
        return prior.size();
    }

    private int filterSize(List<Attachment> prior, long maxTotalSize) {
        long s = 0;

        for (int i = 0; i < prior.size(); i++) {
            s += prior.get(i).getFileSize();
            if (s > maxTotalSize) {
                return i - 1;
            }
        }
        return prior.size();
    }

}
