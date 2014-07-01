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
import com.atlassian.core.util.FileSize;
import com.atlassian.mail.MailException;
import com.atlassian.quartz.jobs.AbstractJob;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.ObjectUtils;
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

    private static final Logger LOG = LoggerFactory.getLogger(PurgeAttachmentsJob.class);
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
        if (sng.getMode() == PurgeAttachmentSettings.MODE_DISABLED) {
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
        Date started = new Date();

        PurgeAttachmentSettings systemSettings = settingSvc.getSettings();
        if (systemSettings == null) {
            systemSettings = settingSvc.createDefault();
        }
        Map<String, PurgeAttachmentSettings> sl = getAllSpaceSettings(systemSettings);

        Map<String, List<MailLogEntry>> mailEntries = new HashMap<String, List<MailLogEntry>>();
        //List<MailLogEntry> mailEntries = new ArrayList<MailLogEntry>();

        Iterator<Attachment> i = attachmentManager.getAttachmentDao().findLatestVersionsIterator();

        while (i.hasNext()) {
            Attachment att = i.next();

            if (att.getVersion() > 1 && att.getSpace() != null && sl.containsKey(att.getSpace().getKey())) {
                PurgeAttachmentSettings settings = sl.get(att.getSpace().getKey());

                List<Attachment> toDelete = findDeletions(att, settings);
                List<Integer> deletedVersions = new ArrayList<Integer>();
                for (Attachment tt : toDelete) {
                    deletedVersions.add(tt.getVersion());
                }
                if (toDelete.size() > 0) {
                    long spaceSaved = 0;
                    for (Attachment p : toDelete) {
                        if (settings.isReportOnly() || systemSettings.isReportOnly()) {
                        } else {
                            attachmentManager.removeAttachmentVersionFromServer(p);
                        }
                        spaceSaved += p.getFileSize();
                    }
                    MailLogEntry mle = new MailLogEntry(
                            att,
                            deletedVersions,
                            settings.isReportOnly() || systemSettings.isReportOnly(),
                            settings == systemSettings,
                            spaceSaved);
                    if (settings != systemSettings && StringUtils.isNotBlank(settings.getReportEmailAddress())) {
                        if (!mailEntries.containsKey(settings.getReportEmailAddress())) {
                            mailEntries.put(settings.getReportEmailAddress(), new ArrayList<MailLogEntry>());
                        }
                        mailEntries.get(settings.getReportEmailAddress()).add(mle);
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
        Date end = new Date();
        try {
            //mailResultsPlain(mailEntries);
            mailResultsHtml(mailEntries, started, end);
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
        int n;
        if (stng.isRevisionCountRuleEnabled()) {
            n = filterRevisionCount(prior, stng.getMaxRevisions());
            if (n > to) {
                to = n;
            }
        }
        if (stng.isAgeRuleEnabled()) {
            n = filterAge(prior, stng.getMaxDaysOld());
            if (n > to) {
                to = n;
            }
        }
        if (stng.isMaxSizeRuleEnabled()) {
            n = filterSize(prior, stng.getMaxTotalSize());
            if (n > to) {
                to = n;
            }
        }
        if (to == -1) {
            return Collections.<Attachment>emptyList();
        }
        if (to >= prior.size() - 1) {
            return prior;
        }
        return prior.subList(0, to + 1);
    }

    private int filterRevisionCount(List<Attachment> prior, int maxRevisions) {
        if (prior.size() > maxRevisions) {
            return (prior.size() - maxRevisions) - 1;
        } else {
            return -1;
        }
    }

    private int filterAge(List<Attachment> prior, int maxDaysOld) {
        Calendar dateFrom = Calendar.getInstance();
        dateFrom.add(Calendar.DAY_OF_MONTH, -(maxDaysOld));
        Calendar modDate = Calendar.getInstance();

        for (int i = prior.size() - 1; i >= 0; i--) {
            if (prior.get(i).getLastModificationDate() != null) {
                modDate.setTime(prior.get(i).getLastModificationDate());
                if (dateFrom.after(modDate)) {
                    return i;
                }
            }
        }

        return -1;
    }

    private int filterSize(List<Attachment> prior, long maxTotalSize) {
        long maxSizeKiB = maxTotalSize * 1024 * 1024;
        long total = 0;
        for (int i = prior.size() - 1; i >= 0; i--) {
            total += prior.get(i).getFileSize();
            if (total > maxSizeKiB) {
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

    private void mailResultsHtml(Map<String, List<MailLogEntry>> mailEntries1, Date started, Date ended) throws MailException {
        String p = settingsManager.getGlobalSettings().getBaseUrl();
        String subject = "Purged old attachments";

        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

        for (Map.Entry<String, List<MailLogEntry>> n : mailEntries1.entrySet()) {
            List<MailLogEntry> entries = n.getValue();

            Collections.sort(entries, new Comparator<MailLogEntry>() {
                @Override
                public int compare(MailLogEntry o1, MailLogEntry o2) {
                    Attachment a1 = o1.getAttachment();
                    Attachment a2 = o2.getAttachment();

                    int comp = ObjectUtils.compare(a1.getSpace().getName(), a2.getSpace().getName());
                    if (comp != 0) return comp;
                    comp = ObjectUtils.compare(a1.getDisplayTitle(), a2.getDisplayTitle());
                    if (comp != 0) return comp;
                    return 0;
                }
            });

            StringBuilder sb = new StringBuilder();

            sb.append("<!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"en-US\" lang=\"en-US\">");

            sb.append("<head>");
            //sb.append("<meta http-equiv=\"content-type\" content=\"text/html; charset=utf-8\" />")
            sb.append("<title>").append(subject).append("</title>");
            sb.append("<style type=\"text/css\">");
            sb.append("body { font-family: Helvetica, Arial, sans-serif; font-size: 10pt; width: 100%; color: #333; text-align: left; }");
            sb.append("a { color: #326ca6; text-decoration: none; }");
            sb.append("a:hover { color: #336ca6; text-decoration: underline; }");
            sb.append("a:active { color: #326ca6; }");
            sb.append("div.note { border: solid 1px #F0C000; padding: 5px; background-color: #FFFFCE; }");
            sb.append("table { border-collapse: collapse; padding: 0; border: 0 none; }");
            sb.append("th, td { padding: 5px 7px; border: solid 1px #ddd; text-align: left; vertical-align: top; color: #333; margin: 0; }");
            sb.append("th { background-color: #f0f0f0 }");
            sb.append("tr.deleted td { background-color: #FFE7E7; /* border: solid 1px #DF9898; */ }");
            sb.append("</style>");
            sb.append("</head>");

            sb.append("<body>");

            sb.append("<p>");
            sb.append("This message is to inform you that the following prior");
            sb.append(" attachment versions have been removed from confluence");
            sb.append(" in order to conserve space. Current versions have not");
            sb.append(" been deleted.");
            sb.append("</p>");

            sb.append("<p>");
            sb.append("Versions deleted are listed in the 'Versions Deleted'");
            sb.append(" column. Rows shown in red have been processed, all");
            sb.append(" other rows are in report-only mode.");
            sb.append("</p>");

            sb.append("<p><strong>Started</strong>: ")
                    .append(df.format(started))
                    .append(" <strong>Ended</strong>: ")
                    .append(df.format(ended))
                    .append("</p>");

            long deleted = 0;
            long report = 0;
            for (MailLogEntry me : entries) {
                if (me.isReportOnly()) {
                    report += me.getSpaceSaved();
                } else {
                    deleted += me.getSpaceSaved();
                }
            }
            if (deleted > 0) {
                sb.append("<p>");
                sb.append("A total of ").append(FileSize.format(deleted))
                        .append(" space has been reclaimed.");
                sb.append("</p>");
            }
            if (report > 0) {
                sb.append("<p>");
                sb.append("A total of ").append(FileSize.format(report))
                        .append(" can be reclaimed from those in report mode.");
                sb.append("</p>");
            }
            sb.append("<table>");

            sb.append("<thead>");
            sb.append("<tr>");
            sb.append("<th>").append("Space").append("</th>");
            sb.append("<th>").append("File Name").append("</th>");
            sb.append("<th>").append("Space Freed").append("</th>");
            //sb.append("<th>").append("Global Settings?").append("</th>");
            sb.append("<th>").append("Version").append("</th>");
            sb.append("<th>").append("Versions Deleted").append("</th>");
            sb.append("</tr>");
            sb.append("</thead>");

            sb.append("<tbody>");
            for (MailLogEntry me : entries) {
                Attachment a = me.getAttachment();

                sb.append("<tr");
                if (!me.isReportOnly()) {
                    sb.append(" class=\"deleted\"");
                }
                sb.append(">");

                sb.append("<td>");
                sb.append("<a href=\"").append(p).append(a.getSpace().getUrlPath()).append("\">")
                        .append(a.getSpace().getName()).append("</a>");
                sb.append("</td>");

                sb.append("<td>");
                sb.append("<a href=\"").append(p).append(a.getContent().getAttachmentsUrlPath()).append("\">")
                        .append(a.getDisplayTitle()).append("</a>");
                sb.append("</td>");

                sb.append("<td>").append(me.getSpaceSavedPretty()).append("</td>");

                //sb.append("<td>").append(me.isGlobalSettings() ? "Yes" : "No").append("</td>");
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

            sb.append("<p>");
            sb.append("This message has been sent by the confluence mail tools");
            sb.append(" - attachment purger.");
            sb.append("</p>");

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

        private final Attachment attachment;
        private final List<Integer> deletedVersions;
        private final boolean reportOnly;
        private final boolean globalSettings;
        private final long spaceSaved;

        private MailLogEntry(Attachment a, List<Integer> deletedVersions, boolean reportOnly, boolean globalSettings, long spaceSaved) {
            this.attachment = a;
            this.deletedVersions = deletedVersions;
            this.reportOnly = reportOnly;
            this.globalSettings = globalSettings;
            this.spaceSaved = spaceSaved;
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

        private long getSpaceSaved() {
            return spaceSaved;
        }

        private String getSpaceSavedPretty() {
            return FileSize.format(spaceSaved);
        }

    }

}
