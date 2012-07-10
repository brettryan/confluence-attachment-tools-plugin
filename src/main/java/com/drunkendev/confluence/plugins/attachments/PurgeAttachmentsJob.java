/*
 * PurgeAttachmentsJob.java    Jul 9 2012, 04:31
 *
 * Copyright 2012 Drunken Dev. All rights reserved.
 * Use is subject to license terms.
 */

package com.drunkendev.confluence.plugins.attachments;

import com.atlassian.confluence.mail.template.ConfluenceMailQueueItem;
import com.atlassian.confluence.pages.Attachment;
import com.atlassian.confluence.pages.AttachmentManager;
import com.atlassian.confluence.spaces.Space;
import com.atlassian.confluence.spaces.SpaceManager;
import com.atlassian.core.task.MultiQueueTaskManager;
import com.atlassian.mail.MailException;
import com.atlassian.quartz.jobs.AbstractJob;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang.StringUtils;
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
    private MultiQueueTaskManager mailQueueTaskManager;

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

    public void setMultiQueueTaskManager(MultiQueueTaskManager mailQueueTaskManager) {
        this.mailQueueTaskManager = mailQueueTaskManager;
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

    private Map<String, PurgeAttachmentSettings> getAllSpaceSettings(PurgeAttachmentSettings dflt) {
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

        Iterator<Attachment> i =
                attachmentManager.getAttachmentDao().
                findLatestVersionsIterator();

        PurgeAttachmentSettings systemSettings = settingSvc.getSettings(null);
        Map<String, PurgeAttachmentSettings> sl = getAllSpaceSettings(systemSettings);

        Map<String, List<MailLogEntry>> mailEntries = new HashMap<String, List<MailLogEntry>>();
        //List<MailLogEntry> mailEntries = new ArrayList<MailLogEntry>();

        while (i.hasNext()) {
            Attachment a = i.next();
            if (a.getVersion() > 1
                    && a.getSpace() != null
                    && sl.containsKey(a.getSpace().getKey())) {

                PurgeAttachmentSettings st = sl.get(a.getSpace().getKey());

                List<Attachment> toDelete = findDeletions(a, st);
                if (toDelete.size() > 0) {
                    for (Attachment p : toDelete) {
                        // Log removal
                        LOG.warn("Would remove attachment: "
                                + p.getDisplayTitle() + " (" + p.getExportPath() + ")");
                        //attachmentManager.removeAttachmentFromServer(p);
                    }
                    MailLogEntry mle = new MailLogEntry(a, toDelete);
                    if (st != systemSettings && StringUtils.isNotBlank(st.getReportEmailAddress())) {
                        if (!mailEntries.containsKey(st.getReportEmailAddress())) {
                            mailEntries.put(st.getReportEmailAddress(), new ArrayList<MailLogEntry>());
                        }
                        mailEntries.get(st.getReportEmailAddress()).add(mle);
                    }
                    //TODO: I know this will log twice if system email and space
                    //      email are the same, will fix later, just hacking atm.
                    if (StringUtils.isNotBlank(systemSettings.getReportEmailAddress())) {
                        if (!mailEntries.containsKey(systemSettings.getReportEmailAddress())) {
                            mailEntries.put(systemSettings.getReportEmailAddress(), new ArrayList<MailLogEntry>());
                        }
                        mailEntries.get(systemSettings.getReportEmailAddress()).add(mle);
                    }
                }
            }
        }
        try {
            mailResults(mailEntries);
        } catch (MailException ex) {
            LOG.error("Exception raised while trying to mail results.", ex);
        }

        LOG.info("Purge old attachments completed.");
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

    private void mailResults(Map<String, List<MailLogEntry>> mailEntries1) throws MailException {

        for (Map.Entry<String, List<MailLogEntry>> n : mailEntries1.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (MailLogEntry me : n.getValue()) {
                Attachment a = me.getAttachment();
                sb.append(a.getDisplayTitle()).append("\n");
                sb.append("Current Version: ").append(a.getAttachmentVersion());
                sb.append("\n");
            }
            ConfluenceMailQueueItem mail = new ConfluenceMailQueueItem(
                    n.getKey(),
                    "Purged attachments",
                    sb.toString(),
                    "text/plain");
            mailQueueTaskManager.getTaskQueue("mail").addTask(mail);
            LOG.debug("Mail Sent");
        }

    }


    /**
     *
     */
    private class MailLogEntry {

        private Attachment attachment;
        private List<Attachment> deleted;

        private MailLogEntry(Attachment a, List<Attachment> deleted) {
            this.attachment = a;
            this.deleted = deleted;
        }

        private Attachment getAttachment() {
            return attachment;
        }

    }

}
