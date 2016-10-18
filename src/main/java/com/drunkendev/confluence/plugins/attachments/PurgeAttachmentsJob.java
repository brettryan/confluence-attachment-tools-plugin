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
import com.atlassian.confluence.pages.persistence.dao.AttachmentDao;
import com.atlassian.confluence.setup.settings.SettingsManager;
import com.atlassian.confluence.spaces.SpaceManager;
import com.atlassian.confluence.spaces.SpaceStatus;
import com.atlassian.core.task.MultiQueueTaskManager;
import com.atlassian.core.util.FileSize;
import com.atlassian.mail.MailException;
import com.atlassian.sal.api.transaction.TransactionTemplate;
import com.atlassian.scheduler.JobRunner;
import com.atlassian.scheduler.JobRunnerRequest;
import com.atlassian.scheduler.JobRunnerResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Comparator.comparing;
import static java.util.Comparator.comparingInt;
import static java.util.Comparator.naturalOrder;
import static java.util.Comparator.nullsFirst;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.apache.commons.lang3.StringUtils.equalsIgnoreCase;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.StringUtils.repeat;


/**
 * Purge old attachment versions.
 *
 * @author  Brett Ryan
 */
public class PurgeAttachmentsJob implements JobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(PurgeAttachmentsJob.class);

    private static final Comparator<Attachment> COMP_ATTACHMENT_VERSION
            = nullsFirst(comparingInt(n -> n.getVersion()));
    private static final Comparator<MailLogEntry> COMP_MAILLOG_SPACE_TITLE
            = comparing((MailLogEntry n) -> n.getSpaceName(), nullsFirst(naturalOrder()))
                    .thenComparing((MailLogEntry n) -> n.getDisplayTitle(), nullsFirst(naturalOrder()));

    private static final int BATCH_SIZE = 50;

    private static final int IDX_PRIOR_VERSIONS = 0;
    private static final int IDX_DELETED = 1;
    private static final int IDX_DELETED_TIME = 2;
    private static final int IDX_DELETE_AVAIL = 3;
    private static final int IDX_CURRENT_VERSIONS = 4;
    private static final int IDX_CURRENT_VISITED = 5;
    private static final int IDX_PROCESS_LIMIT = 6;
    private static final int IDX_BATCHES = 7;
    private static final int COUNTER_ARRAY_SIZE = 8;

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

    public static <T> ImmutablePair<Duration, T> time(Supplier<T> r) {
        Instant start = Instant.now();
        T res = r.get();
        return new ImmutablePair<>(Duration.between(start, Instant.now()), res);
    }

    public static Duration time(Runnable r) {
        Instant start = Instant.now();
        r.run();
        return Duration.between(start, Instant.now());
    }

    @Override
    public JobRunnerResponse runJob(JobRunnerRequest req) {
        LOG.info("Purge attachment revisions started.");
        try {
            LocalDateTime start = LocalDateTime.now();

            PurgeAttachmentSettings systemSettings = getSystemSettings();
            Map<String, PurgeAttachmentSettings> spaceSettings = getAllSpaceSettings(systemSettings);

            if (LOG.isDebugEnabled()) {
                LOG.debug("System settings: {}", systemSettings);
                spaceSettings.forEach((k, v) -> LOG.debug("Space Settings: {} -> {}", k, v));
            }

            Map<String, List<MailLogEntry>> mailEntries = new HashMap<>();

            long[] counters = new long[COUNTER_ARRAY_SIZE];

            ImmutablePair<Duration, ArrayDeque<Long>> findAll
                    = time(() -> attachmentManager.getAttachmentDao().findAll()
                    .stream()
                    .map(Attachment::getId).collect(toCollection(ArrayDeque::new)));
            LOG.debug("Got {} attachments in {}.", findAll.right.size(), findAll.left);

            ArrayDeque<Long> ids = findAll.right;

            while (!ids.isEmpty() && !req.isCancellationRequested()) {
                LOG.debug("Processing batch {}; {} atttachments remain", ++counters[IDX_BATCHES], ids.size());
                transactionTemplate.execute(() -> {
                    AttachmentDao dao = attachmentManager.getAttachmentDao();
                    for (int i = 0; i < BATCH_SIZE && !ids.isEmpty() && !req.isCancellationRequested(); i++) {
                        Attachment attachment = attachmentManager.getAttachment(ids.poll());
                        process(attachment,
                                counters,
                                spaceSettings.get(attachment.getSpaceKey()),
                                systemSettings,
                                dao,
                                mailEntries);
                    }
                    return null;
                });
            }

            if (req.isCancellationRequested()) {
                LOG.warn("Attachment purging has been cancelled.");
            }

            LocalDateTime end = LocalDateTime.now();
            long ms = Duration.between(start, end).toMillis();

            LOG.info("{} prior versions visited for {} attachments.",
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

            if (systemSettings.isSendPlainTextMail()) {
                mailResultsPlain(mailEntries,
                                 start,
                                 end,
                                 counters,
                                 req.isCancellationRequested());
            } else {
                mailResultsHtml(mailEntries,
                                start,
                                end,
                                counters,
                                req.isCancellationRequested());
            }
        } catch (MailException ex) {
            LOG.error("Exception raised while trying to mail results.", ex);
            return JobRunnerResponse.failed("Task completed but could not email.");
        } catch (Throwable ex) {
            LOG.error("Purge attachment revisions failed: {}", ex.getMessage(), ex);
            return JobRunnerResponse.failed(ex);
        }
        LOG.info("Purge attachment revisions completed.");
        return JobRunnerResponse.success();
    }

    private void process(Attachment attachment,
                         long[] counters,
                         PurgeAttachmentSettings settings,
                         PurgeAttachmentSettings systemSettings,
                         AttachmentDao dao,
                         Map<String, List<MailLogEntry>> mailEntries) {
        counters[IDX_CURRENT_VERSIONS]++;

        if (attachment.getVersion() == 1) {
            LOG.trace("Skipping only attachment version {}", attachment.getId());
            return;
        }

        if (settings == null) {
            settings = systemSettings;
        }

        counters[IDX_CURRENT_VISITED]++;

        List<Attachment> prior = attachmentManager.getPreviousVersions(attachment);
        counters[IDX_PRIOR_VERSIONS] += prior.size();

        List<Attachment> toDelete = findDeletions(prior, settings);
        Set<Integer> badVersions = toDelete.stream()
                .filter(n -> n.getVersion() >= attachment.getVersion())
                .map(n -> n.getVersion())
                .collect(toSet());
        if (badVersions.size() > 0) {
            LOG.error("Attachment with versions to delete > current version: {}:{} :- {} ({}) :: {}",
                      attachment.getSpaceKey(),
                      attachment.getSpace().getName(),
                      attachment.getDisplayTitle(),
                      attachment.getVersion(),
                      badVersions);
        } else if (!toDelete.isEmpty()) {
            boolean canUpdate
                    = !settings.isReportOnly() &&
                      !systemSettings.isReportOnly() &&
                      (systemSettings.getDeleteLimit() == 0 ||
                       counters[IDX_PROCESS_LIMIT] < systemSettings.getDeleteLimit());

            if (canUpdate) {
                counters[IDX_PROCESS_LIMIT]++;
            }

            long spaceSaved = toDelete.stream().map(p -> {
                LOG.debug("Attachment to remove {}", p.getId());
                if (canUpdate) {
                    Duration dur = time(() -> dao.removeAttachmentVersionFromServer(p));
                    counters[IDX_DELETED]++;
                    counters[IDX_DELETED_TIME] += dur.toMillis();
                } else {
                    counters[IDX_DELETE_AVAIL]++;
                }
                return p.getFileSize();
            }).reduce(0L, (a, b) -> a + b);

            if (isNotBlank(settings.getReportEmailAddress()) || isNotBlank(systemSettings.getReportEmailAddress())) {
                MailLogEntry mle = new MailLogEntry(
                        attachment,
                        toDelete.stream().map(Attachment::getVersion).collect(toList()),
                        !canUpdate,
                        settings == systemSettings,
                        spaceSaved);

                if (isNotBlank(settings.getReportEmailAddress())) {
                    if (!mailEntries.containsKey(settings.getReportEmailAddress())) {
                        mailEntries.put(settings.getReportEmailAddress(), new ArrayList<>());
                    }
                    mailEntries.get(settings.getReportEmailAddress()).add(mle);
                }
                if (isNotBlank(systemSettings.getReportEmailAddress()) && !equalsIgnoreCase(settings.getReportEmailAddress(), systemSettings.getReportEmailAddress())) {
                    if (!mailEntries.containsKey(systemSettings.getReportEmailAddress())) {
                        mailEntries.put(systemSettings.getReportEmailAddress(), new ArrayList<>());
                    }
                    mailEntries.get(systemSettings.getReportEmailAddress()).add(mle);
                }
            }

        }
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
        return prior.size() > maxRevisions
               ? (prior.size() - maxRevisions) - 1
               : -1;
    }

    public static LocalDateTime toLocalDateTime(Date value) {
        return toLocalDateTime(value, ZoneId.systemDefault());
    }

    public static LocalDateTime toLocalDateTime(Date value, ZoneId zoneId) {
        // NOTE: java.sql.Date does not support toInstant. To prevent an UnsupportedOperationException
        // do not use toInstant on dates.
        //return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), zoneId);
        return value == null ? null
               : LocalDateTime.ofInstant(Instant.ofEpochMilli(value.getTime()),
                                         zoneId);
    }

    private int filterAge(List<Attachment> prior, int maxDaysOld) {
        LocalDateTime from = LocalDateTime.now().minusDays(maxDaysOld);

        for (int i = prior.size() - 1; i >= 0; i--) {
            if (prior.get(i).getLastModificationDate() != null) {
                LocalDateTime mod = toLocalDateTime(prior.get(i).getLastModificationDate());
                if (from.isAfter(mod)) {
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
                                  long[] counters,
                                  boolean cancellationRequested) throws MailException {
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
            if (cancellationRequested) {
                sb.append("CANCELLED: Job has had an early cancellation request.");
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
                if (ps == null || !ps.equalsIgnoreCase(me.getSpaceKey())) {
                    sb.append("\n");
                    ps = me.getSpaceKey();
                    String sp = ps + ":" + me.getSpaceName() + " (" + p + me.getSpaceUrlPath() + ")";
                    sb.append(sp).append('\n').append(repeat('-', sp.length())).append('\n');
                }

                sb.append(me.getDisplayTitle()).append(" (").append(me.getVersion()).append(") ");
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

    private void mailResultsHtml(Map<String, List<MailLogEntry>> mailEntries,
                                 LocalDateTime started,
                                 LocalDateTime ended,
                                 long[] counters,
                                 boolean cancellationRequested) throws MailException {
        String p = settingsManager.getGlobalSettings().getBaseUrl();
        String subject = "Purged old attachments";

        mailEntries.forEach((emailAddress, entryList) -> {
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

            if (cancellationRequested) {
                sb.append("<p><strong>CANCELLED</strong>: Job has had an early cancellation request.</p>");
            }
            sb.append("<p><strong>Started</strong>: ")
                    .append(started.format(DateTimeFormatter.ISO_DATE_TIME))
                    .append("<br/><strong>Ended</strong>: ")
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
                sb.append("<tr");
                if (!me.isReportOnly()) {
                    sb.append(" class=\"deleted\"");
                }
                sb.append(">");

                sb.append("<td>");
                sb.append("<a href=\"").append(p).append(me.getSpaceUrlPath()).append("\">")
                        .append(me.getSpaceName()).append("</a>");
                sb.append("</td>");

                sb.append("<td>");
                sb.append("<a href=\"").append(p).append(me.getAttachmentsUrlPath()).append("\">")
                        .append(me.getDisplayTitle()).append("</a>");
                sb.append("</td>");

                sb.append("<td>").append(me.getSpaceSavedPretty()).append("</td>");

                //sb.append("<td>").append(me.isGlobalSettings() ? "Yes" : "No").append("</td>");
                sb.append("<td>").append(me.getVersion()).append("</td>");

                sb.append("<td>").append(me.getDeletedVersionsRanged()).append("</td>");

                sb.append("</tr>");
            }
            sb.append("</tbody></table>");

            sb.append("<p>").append(counters[IDX_PRIOR_VERSIONS])
                    .append(" prior versions found for ")
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
            if (counters[IDX_DELETE_AVAIL] > 0) {
                sb.append("<p>A further ").append(counters[IDX_DELETE_AVAIL])
                        .append(" versions are available for deleting.</p>");
            }
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

        private final String spaceKey;
        private final String spaceName;
        private final String spaceUrlPath;

        private final String displayTitle;
        private final String attachmentsUrlPath;
        private final int version;

        private final List<Integer> deletedVersions;
        private final boolean reportOnly;
        private final boolean globalSettings;
        private final long spaceSaved;

        private MailLogEntry(Attachment a,
                             List<Integer> deletedVersions,
                             boolean reportOnly,
                             boolean globalSettings,
                             long spaceSaved) {
            this.spaceKey = a.getSpaceKey();
            this.spaceName = a.getSpace() == null ? null : a.getSpace().getName();
            this.spaceUrlPath = a.getSpace() == null ? null : a.getSpace().getUrlPath();

            this.displayTitle = a.getDisplayTitle();
            this.attachmentsUrlPath = a.getAttachmentsUrlPath();
            this.version = a.getVersion();

            this.deletedVersions = deletedVersions;
            this.reportOnly = reportOnly;
            this.globalSettings = globalSettings;
            this.spaceSaved = spaceSaved;
        }

        public String getSpaceKey() {
            return spaceKey;
        }

        public String getSpaceName() {
            return spaceName;
        }

        public String getSpaceUrlPath() {
            return spaceUrlPath;
        }

        public String getDisplayTitle() {
            return displayTitle;
        }

        public String getAttachmentsUrlPath() {
            return attachmentsUrlPath;
        }

        public int getVersion() {
            return version;
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

        private void append(StringBuilder res, int a, int b) {
            if (a == -1 || b == -1) {
                return;
            }
            if (res.length() > 0) {
                res.append(", ");
            }
            if (a == b) {
                res.append(a);
//            } else if (b - a == 1) {
//                res.append(a).append(", ").append(b);
            } else {
                res.append("[").append(a).append("-").append(b).append("]");
            }
        }

        public String getDeletedVersionsRanged() {
            StringBuilder res = new StringBuilder();

            int first = -1;
            int prior = -1;
            for (int i : deletedVersions) {
                if (first == -1) {
                    first = prior = i;
                } else if (prior - i > 1) {
                    append(res, first, prior);
                    first = prior = i;
                } else {
                    prior = i;
                }
            }
            append(res, first, prior);

            return res.toString();
        }

    }

}
