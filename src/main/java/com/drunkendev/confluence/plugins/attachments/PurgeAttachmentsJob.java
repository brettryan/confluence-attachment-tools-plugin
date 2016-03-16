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
import com.atlassian.confluence.spaces.SpaceManager;
import com.atlassian.confluence.spaces.SpaceStatus;
import com.atlassian.core.task.MultiQueueTaskManager;
import com.atlassian.core.util.FileSize;
import com.atlassian.mail.MailException;
import com.atlassian.quartz.jobs.AbstractJob;
import com.atlassian.sal.api.transaction.TransactionTemplate;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Comparator.comparing;
import static java.util.Comparator.comparingInt;
import static java.util.Comparator.naturalOrder;
import static java.util.Comparator.nullsFirst;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.apache.commons.lang3.StringUtils.repeat;


/**
 * Purge old attachment versions.
 *
 * @author  Brett Ryan
 */
public class PurgeAttachmentsJob extends AbstractJob {

    private static final Logger LOG = LoggerFactory.getLogger(PurgeAttachmentsJob.class);

    private static final Comparator<Attachment> COMP_ATTACHMENT_VERSION
            = nullsFirst(comparingInt(n -> n.getVersion()));
    private static final Comparator<MailLogEntry> COMP_MAILLOG_SPACE_TITLE
            = comparing((MailLogEntry n) -> n.getAttachment().getSpace().getName(), nullsFirst(naturalOrder()))
            .thenComparing((MailLogEntry n) -> n.getAttachment().getDisplayTitle(), nullsFirst(naturalOrder()));

    private static final int IDX_PRIOR_VERSIONS = 0;
    private static final int IDX_DELETED = 1;
    private static final int IDX_DELETED_TIME = 2;
    private static final int IDX_DELETE_AVAIL = 3;
    private static final int IDX_CURRENT_VERSIONS = 4;
    private static final int IDX_CURRENT_VISITED = 5;
    private static final int IDX_PROCESS_LIMIT = 6;

    private final AttachmentManager attachmentManager;
    private final SpaceManager spaceManager;
    private final PurgeAttachmentsSettingsService settingSvc;
    private final MultiQueueTaskManager mailQueueTaskManager;
    private final SettingsManager settingsManager;
    private final TransactionTemplate transactionTemplate;

    /**
     * Creates a new {@code PurgeAttachmentsJob} instance.
     */
    public PurgeAttachmentsJob(AttachmentManager attachmentManager,
                               SpaceManager spaceManager,
                               PurgeAttachmentsSettingsService purgeAttachmentsSettingsService,
                               MultiQueueTaskManager mailQueueTaskManager,
                               SettingsManager settingsManager,
                               TransactionTemplate transactionTemplate) {
        LOG.debug("Creating purge-old-attachment-job instance.");
        this.attachmentManager = attachmentManager;
        this.spaceManager = spaceManager;
        this.settingSvc = purgeAttachmentsSettingsService;
        this.mailQueueTaskManager = mailQueueTaskManager;
        this.settingsManager = settingsManager;
        this.transactionTemplate = transactionTemplate;
    }

    private PurgeAttachmentSettings getSettings(String key, PurgeAttachmentSettings dflt) {
        if (key == null) {
            return null;
        }
        PurgeAttachmentSettings sng = settingSvc.getSettings(key);

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

    private Map<String, PurgeAttachmentSettings> getAllSpaceSettings(PurgeAttachmentSettings defaultSetting) {
        return spaceManager.getAllSpaceKeys(SpaceStatus.CURRENT).stream()
                .filter(k -> k != null)
                .map(k -> new ImmutablePair<>(k, getSettings(k, defaultSetting)))
                .filter(n -> n.right != null)
                .collect(toMap(k -> k.left, k -> k.right));
    }

    private PurgeAttachmentSettings getSystemSettings() {
        PurgeAttachmentSettings res = settingSvc.getSettings();
        if (res == null) {
            res = settingSvc.createDefault();
        }
        return res;
    }

    @Override
    public void doExecute(JobExecutionContext jec) throws JobExecutionException {
        LOG.debug("Purge old attachments started.");
        LocalDateTime start = LocalDateTime.now();

        PurgeAttachmentSettings systemSettings = getSystemSettings();
        Map<String, PurgeAttachmentSettings> spaceSettings = getAllSpaceSettings(systemSettings);

        if (LOG.isDebugEnabled()) {
            LOG.debug("System settings: {}", systemSettings);
            spaceSettings.forEach((k, v) -> LOG.debug("Space Settings: {} -> {}", k, v));
        }

        Map<String, List<MailLogEntry>> mailEntries = new HashMap<>();

        long[] counters = new long[7];

        transactionTemplate.execute(() -> {
            Iterator<Attachment> attIter = attachmentManager.getAttachmentDao().findLatestVersionsIterator();

            while (attIter.hasNext()) {
                Attachment attachment = attIter.next();
                counters[IDX_CURRENT_VERSIONS]++;

                if (attachment.getVersion() > 1 && spaceSettings.containsKey(attachment.getSpaceKey())) {
                    counters[IDX_CURRENT_VISITED]++;

                    PurgeAttachmentSettings settings = spaceSettings.get(attachment.getSpaceKey());

                    List<Attachment> prior = attachmentManager.getPreviousVersions(attachment);
                    counters[IDX_PRIOR_VERSIONS] += prior.size();

                    List<Attachment> toDelete = findDeletions(prior, settings);
                    if (!toDelete.isEmpty()) {
                        boolean canUpdate;
                        if (!settings.isReportOnly() && !systemSettings.isReportOnly()) {
                            canUpdate = systemSettings.getDeleteLimit() == 0 ||
                                        counters[IDX_PROCESS_LIMIT] < systemSettings.getDeleteLimit();
                        } else {
                            canUpdate = false;
                        }

                        if (canUpdate) {
                            counters[IDX_PROCESS_LIMIT]++;
                        }

                        long spaceSaved = toDelete.stream().map(p -> {
                            LOG.debug("Attachment to remove {}", p.getId());
                            if (canUpdate) {
                                Instant s = Instant.now();
                                attachmentManager.removeAttachmentVersionFromServer(p);
                                counters[IDX_DELETED]++;
                                counters[IDX_DELETED_TIME] += Duration.between(s, Instant.now()).toMillis();
                            } else {
                                counters[IDX_DELETE_AVAIL]++;
                            }
                            return p.getFileSize();
                        }).reduce(0L, (a, b) -> a + b);

                        MailLogEntry mle = new MailLogEntry(
                                attachment,
                                toDelete.stream().map(Attachment::getVersion).collect(toList()),
                                !canUpdate,
                                settings == systemSettings,
                                spaceSaved);

                        if (settings != systemSettings && StringUtils.isNotBlank(settings.getReportEmailAddress())) {
                            if (!mailEntries.containsKey(settings.getReportEmailAddress())) {
                                mailEntries.put(settings.getReportEmailAddress(), new ArrayList<>());
                            }
                            mailEntries.get(settings.getReportEmailAddress()).add(mle);
                        }
                        //TODO: I know this will log twice if system email and space
                        //      email are the same, will fix later, just hacking atm.
                        if (isNotBlank(systemSettings.getReportEmailAddress())) {
                            if (!mailEntries.containsKey(systemSettings.getReportEmailAddress())) {
                                mailEntries.put(systemSettings.getReportEmailAddress(), new ArrayList<>());
                            }
                            mailEntries.get(systemSettings.getReportEmailAddress()).add(mle);
                        }
                    }
                }
            }

            LocalDateTime end = LocalDateTime.now();
            long ms = Duration.between(start, end).toMillis();

            LOG.info("{} processable prior versions found for {} attachments.",
                     counters[IDX_PRIOR_VERSIONS],
                     counters[IDX_CURRENT_VERSIONS]);
            if (counters[IDX_CURRENT_VISITED] > 0) {
                LOG.info("Visited {} attachments averaging {} ms per visit.",
                         counters[IDX_CURRENT_VISITED],
                         counters[IDX_CURRENT_VISITED] == 0 ? 0 : Math.round(ms / (double) counters[IDX_CURRENT_VISITED]));
            }
            if (counters[IDX_DELETED] > 0) {
                LOG.info("Deleted {} individual versions averaging {} ms per deletion.",
                         counters[IDX_DELETED],
                         Math.round(counters[IDX_DELETED_TIME] / (double) counters[IDX_DELETED]));
            }
            LOG.info("A further {} versions are available for deleting.",
                     counters[IDX_DELETE_AVAIL]);
            LOG.info("Attachment purging completed in {} ms.", ms);
            try {
                if (systemSettings.isSendPlainTextMail()) {
                    mailResultsPlain(mailEntries,
                                     start,
                                     end,
                                     counters);
                } else {
                    mailResultsHtml(mailEntries,
                                    start,
                                    end,
                                    counters);
                }
            } catch (MailException ex) {
                LOG.error("Exception raised while trying to mail results.", ex);
            }
            LOG.debug("Purge attachments complete.");
            return null;
        });
    }

    private List<Attachment> findDeletions(List<Attachment> prior, PurgeAttachmentSettings stng) {
        if (prior == null || prior.isEmpty()) {
            return Collections.<Attachment>emptyList();
        }
        Collections.sort(prior, COMP_ATTACHMENT_VERSION);

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

    private void mailResultsPlain(Map<String, List<MailLogEntry>> entries,
                                  LocalDateTime started,
                                  LocalDateTime ended,
                                  long[] counters) throws MailException {
        String p = settingsManager.getGlobalSettings().getBaseUrl();

        entries.forEach((emailAddress, entryList) -> {
            StringBuilder sb = new StringBuilder();

            sb.append("Started: ")
                    .append(started.format(DateTimeFormatter.ISO_DATE_TIME))
                    .append("\nEnded: ")
                    .append(ended.format(DateTimeFormatter.ISO_DATE_TIME)).append("\n\n");

            long deleted = 0;
            long report = 0;
            for (MailLogEntry me : entryList) {
                if (me.isReportOnly()) {
                    report += me.getSpaceSaved();
                } else {
                    deleted += me.getSpaceSaved();
                }
            }
            if (deleted > 0) {
                sb.append("A total of ").append(FileSize.format(deleted))
                        .append(" space has been reclaimed.\n");
            }
            if (report > 0) {
                sb.append("A total of ").append(FileSize.format(report))
                        .append(" can be reclaimed from those in report mode.\n");
            }

            sb.append("\n\n");

            String ps = null;
            for (MailLogEntry me : entryList) {
                Attachment a = me.getAttachment();
                if (ps == null || !ps.equalsIgnoreCase(a.getSpaceKey())) {
                    sb.append("\n");
                    ps = a.getSpaceKey();
                    String sp = ps + ":" + a.getSpace().getName() + " (" + p + a.getSpace().getUrlPath() + ")";
                    sb.append(sp).append('\n').append(repeat('-', sp.length())).append('\n');
                }

                sb.append(a.getDisplayTitle()).append(" (").append(a.getVersion()).append(") ");
                sb.append(me.isReportOnly() ? "TO_DELETE:" : "DELETED:");
                me.getDeletedVersions().stream().forEach(ver -> sb.append(" ").append(ver));
                sb.append(" [").append(me.getSpaceSavedPretty()).append("]\n");
            }


            sb.append("\n");
            sb.append(counters[IDX_PRIOR_VERSIONS])
                    .append("processable prior versions found for ")
                    .append(counters[IDX_CURRENT_VERSIONS]).append(" attachments.");

            long ms = Duration.between(started, ended).toMillis();
            if (counters[IDX_CURRENT_VISITED] > 0) {
                sb.append("Visited ").append(counters[IDX_CURRENT_VISITED])
                        .append(" attachments averaging ")
                        .append(counters[IDX_CURRENT_VISITED] == 0 ? 0 : Math.round(ms / (double) counters[IDX_CURRENT_VISITED]))
                        .append(" ms per visit.");
            }
            if (counters[IDX_DELETED] > 0) {
                sb.append("Deleted ").append(counters[IDX_DELETED])
                        .append(" individual versions averaging ")
                        .append(Math.round(counters[IDX_DELETED_TIME] / (double) counters[IDX_DELETED]))
                        .append(" ms per deletion.");
            }
            sb.append("A further ").append(counters[IDX_DELETE_AVAIL])
                    .append(" versions are available for deleting.");
            sb.append("Attachment purging completed in ").append(ms).append(" ms.");

            ConfluenceMailQueueItem mail = new ConfluenceMailQueueItem(
                    emailAddress,
                    "Purged attachments",
                    sb.toString(),
                    "text/plain");
            mailQueueTaskManager.getTaskQueue("mail").addTask(mail);
            LOG.debug("Mail Sent");
        });
    }

    private void mailResultsHtml(Map<String, List<MailLogEntry>> mailEntries1,
                                 LocalDateTime started,
                                 LocalDateTime ended,
                                 long[] counters) throws MailException {
        String p = settingsManager.getGlobalSettings().getBaseUrl();
        String subject = "Purged old attachments";

        mailEntries1.forEach((emailAddress, entryList) -> {
            Collections.sort(entryList, COMP_MAILLOG_SPACE_TITLE);

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
                    .append(started.format(DateTimeFormatter.ISO_DATE_TIME))
                    .append(" <strong>Ended</strong>: ")
                    .append(ended.format(DateTimeFormatter.ISO_DATE_TIME))
                    .append("</p>");

            long deleted = 0;
            long report = 0;
            for (MailLogEntry me : entryList) {
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
            for (MailLogEntry me : entryList) {
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
                sb.append("<a href=\"").append(p).append(a.getAttachmentsUrlPath()).append("\">")
                        .append(a.getDisplayTitle()).append("</a>");
                sb.append("</td>");

                sb.append("<td>").append(me.getSpaceSavedPretty()).append("</td>");

                //sb.append("<td>").append(me.isGlobalSettings() ? "Yes" : "No").append("</td>");
                sb.append("<td>").append(a.getVersion()).append("</td>");

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

            sb.append("<p>").append(counters[IDX_PRIOR_VERSIONS])
                    .append("processable prior versions found for ")
                    .append(counters[IDX_CURRENT_VERSIONS]).append(" attachments.</p>");

            long ms = Duration.between(started, ended).toMillis();
            if (counters[IDX_CURRENT_VISITED] > 0) {
                sb.append("<p>Visited ").append(counters[IDX_CURRENT_VISITED])
                        .append(" attachments averaging ")
                        .append(counters[IDX_CURRENT_VISITED] == 0 ? 0 : Math.round(ms / (double) counters[IDX_CURRENT_VISITED]))
                        .append(" ms per visit.</p>");
            }
            if (counters[IDX_DELETED] > 0) {
                sb.append("<p>Deleted ").append(counters[IDX_DELETED])
                        .append(" individual versions averaging ")
                        .append(Math.round(counters[IDX_DELETED_TIME] / (double) counters[IDX_DELETED]))
                        .append(" ms per deletion.</p>");
            }
            sb.append("<p>A further ").append(counters[IDX_DELETE_AVAIL])
                    .append(" versions are available for deleting.</p>");
            sb.append("<p>Attachment purging completed in ").append(ms).append(" ms.</p>");

            sb.append("<p>This message has been sent by Attachment Tools - Purge Attachment Versions</p>");

            sb.append("</body></html>");

            ConfluenceMailQueueItem mail = new ConfluenceMailQueueItem(
                    emailAddress,
                    subject,
                    sb.toString(),
                    ConfluenceMailQueueItem.MIME_TYPE_HTML);
            mailQueueTaskManager.getTaskQueue("mail").addTask(mail);
            LOG.debug("Mail Sent to: {}", emailAddress);
        });
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
