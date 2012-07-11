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
import com.atlassian.confluence.setup.settings.SettingsManager;
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
    private SettingsManager settingsManager;

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

    public void setSettingsManager(SettingsManager settingsManager) {
        this.settingsManager = settingsManager;
    }

    private PurgeAttachmentSettings getSettings(Space space, PurgeAttachmentSettings dflt) {
        if (space == null) {
            return null;
        }
        PurgeAttachmentSettings sng = settingSvc.getSettings(space.getKey());

        // Use global.
        if (sng == null || sng.getMode() == PurgeAttachmentSettings.MODE_GLOBAL) {
            return dflt;
        }

        // Explicitely disabled.
        if (sng != null && sng.getMode() == PurgeAttachmentSettings.MODE_DISABLED) {
            sng = null;
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

        PurgeAttachmentSettings systemSettings = settingSvc.getSettings();
        if (systemSettings == null) {
            systemSettings = settingSvc.createDefault();
        }
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
                List<Integer> deletedVersions = new ArrayList<Integer>();
                for (Attachment tt : toDelete) {
                    deletedVersions.add(tt.getVersion());
                }
                if (toDelete.size() > 0) {
                    for (Attachment p : toDelete) {
                        if (st.isReportOnly() || systemSettings.isReportOnly()) {
                        } else {
                            attachmentManager.removeAttachmentVersionFromServer(p);
                        }
                    }
                    MailLogEntry mle = new MailLogEntry(a, deletedVersions, st.isReportOnly() || systemSettings.isReportOnly(), st == systemSettings);
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
            //mailResultsPlain(mailEntries);
            mailResultsHtml(mailEntries);
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

        int to = -1;
        int n = 0;
        if (stng.isRevisionCountRuleEnabled()) {
            n = filterRevisionCount(prior, stng.getMaxRevisions());
            if (n > to) {
                to = n;
            }
        }
        if (stng.isAgeRuleEnabled()) {
            n = filterAge(prior, stng.getMaxDaysOld());
            if (n < to) {
                to = n;
            }
        }
        if (stng.isMaxSizeRuleEnabled()) {
            n = filterSize(prior, stng.getMaxTotalSize());
            if (n < to) {
                to = n;
            }
        }
        if (to >= 0 && to < prior.size()) {
            return prior.subList(0, to);
        } else {
            return Collections.<Attachment>emptyList();
        }
    }

    private int filterRevisionCount(List<Attachment> prior, int maxRevisions) {
        if (prior.size() > maxRevisions) {
            return (prior.size() - maxRevisions) + 1;
        } else {
            return -1;
        }
    }

    private int filterAge(List<Attachment> prior, int maxDaysOld) {
        Calendar dateFrom = Calendar.getInstance();
        dateFrom.add(Calendar.DAY_OF_MONTH, -(maxDaysOld));
        Calendar modDate = Calendar.getInstance();

        for (int i = 0; i < prior.size(); i++) {
            modDate.setTime(prior.get(i).getLastModificationDate());
            if (dateFrom.after(modDate)) {
                return i;
            }
        }
        return -1;
    }

    private int filterSize(List<Attachment> prior, long maxTotalSize) {
        long s = 0;
        long m = maxTotalSize * 1024;

        for (int i = 0; i < prior.size(); i++) {
            s += prior.get(i).getFileSize();
            if (s > m) {
                return i;
            }
        }
        return -1;
    }

    private void mailResultsPlain(Map<String, List<MailLogEntry>> mailEntries1) throws MailException {
        String p = settingsManager.getGlobalSettings().getBaseUrl();

        for (Map.Entry<String, List<MailLogEntry>> n : mailEntries1.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (MailLogEntry me : n.getValue()) {
                Attachment a = me.getAttachment();

                sb
                        .append(a.getDisplayTitle()).append("\n")
                        .append("    URL: ").append(p).append(a.getContent().getAttachmentsUrlPath()).append("\n")
                        .append("    File Size: ").append(a.getNiceFileSize()).append("\n")
                        .append("    Space: ").append(a.getSpace().getName()).append("\n")
                        .append("    Space URL: ").append(p).append(a.getSpace().getUrlPath()).append("\n")
                        .append("    Report Only: ").append(me.isReportOnly() ? "Yes" : "No").append("\n")
                        .append("    Global Settings: ").append(me.isGlobalSettings() ? "Yes" : "No").append("\n")
                        .append("    Current Version: ").append(a.getAttachmentVersion()).append("\n");

                sb.append("    Versions Deleted: ");
                int c = 0;
                for (Integer dl : me.getDeletedVersions()) {
                    if (c++ > 0) {
                        sb.append(", ");
                    }
                    sb.append(dl);
                }
                sb.append("\n\n");
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

    private void mailResultsHtml(Map<String, List<MailLogEntry>> mailEntries1) throws MailException {
        String p = settingsManager.getGlobalSettings().getBaseUrl();
        String subject = "Purged old attachments";

        for (Map.Entry<String, List<MailLogEntry>> n : mailEntries1.entrySet()) {
            StringBuilder sb = new StringBuilder();
            sb.append("<table>");

            sb.append("<thead>");
            sb.append("<tr>");
            sb.append("<td>").append("Display Title").append("</td>");
            sb.append("<td>").append("Current File Size").append("</td>");
            sb.append("<td>").append("Space").append("</td>");
            sb.append("<td>").append("Report Only?").append("</td>");
            sb.append("<td>").append("Using Global Settings?").append("</td>");
            sb.append("<td>").append("Current Version").append("</td>");
            sb.append("<td>").append("Versions Deleted").append("</td>");
            sb.append("</tr>");
            sb.append("</thead>");

            sb.append("<tbody>");
            for (MailLogEntry me : n.getValue()) {
                Attachment a = me.getAttachment();

                sb.append("<tr>");

                sb.append("<td>");
                sb.append("<a href=\"").append(p).append(a.getContent().getAttachmentsUrlPath()).append("\">")
                        .append(a.getDisplayTitle()).append("</a>");
                sb.append("</td>");

                sb.append("<td>").append(a.getNiceFileSize()).append("</td>");

                sb.append("<td>");
                sb.append("<a href=\"").append(p).append(a.getSpace().getUrlPath()).append("\">")
                        .append(a.getSpace().getName()).append("</a>");
                sb.append("</td>");

                sb.append("<td>").append(me.isReportOnly() ? "Yes" : "No").append("</td>");
                sb.append("<td>").append(me.isGlobalSettings() ? "Yes" : "No").append("</td>");
                sb.append("<td>").append(a.getAttachmentVersion()).append("</td>");

                sb.append("<td>");
                int c = 0;
                for (Integer dl : me.getDeletedVersions()) {
                    if (c++ > 0) {
                        sb.append(", ");
                    }
                    sb.append(dl);
                }
                sb.append("</td>");

                sb.append("</tr>");
            }
            sb.append("</tbody></table>");

            sb.insert(0, "<!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"en-US\" lang=\"en-US\">")
                    .append("<head>")
                    //.append("<meta http-equiv=\"content-type\" content=\"text/html; charset=utf-8\" />")
                    .append("<title>").append(subject).append("</title>")
                    .append("</head>")
                    .append("<body style='font-family: Helvetica, Arial, sans-serif; font-size: 10pt;'>");
            sb.append("</body></html>");

            ConfluenceMailQueueItem mail = new ConfluenceMailQueueItem(
                    n.getKey(),
                    subject,
                    sb.toString(),
                    ConfluenceMailQueueItem.MIME_TYPE_HTML);
            mailQueueTaskManager.getTaskQueue("mail").addTask(mail);
            LOG.debug("Mail Sent");
        }
    }


    /**
     *
     */
    private class MailLogEntry {

        private Attachment attachment;
        private List<Integer> deletedVersions;
        private boolean reportOnly;
        private boolean globalSettings;

        private MailLogEntry(Attachment a, List<Integer> deletedVersions, boolean reportOnly, boolean globalSettings) {
            this.attachment = a;
            this.deletedVersions = deletedVersions;
            this.reportOnly = reportOnly;
            this.globalSettings = globalSettings;
        }

        private Attachment getAttachment() {
            return attachment;
        }

        private List<Integer> getDeletedVersions() {
            return deletedVersions;
        }

        private boolean isReportOnly() {
            return reportOnly;
        }

        private boolean isGlobalSettings() {
            return globalSettings;
        }

    }

}
