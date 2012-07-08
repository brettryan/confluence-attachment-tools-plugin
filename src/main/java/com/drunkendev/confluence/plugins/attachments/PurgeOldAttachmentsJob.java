/*
 * PurgeOldAttachmentsJob.java    Jul 9 2012, 04:31
 *
 * Copyright 2010 John Sands (Australia) Ltd. All rights reserved.
 * Use is subject to license terms. This is an unpublished work.
 */

package com.drunkendev.confluence.plugins.attachments;

import com.atlassian.confluence.pages.Attachment;
import com.atlassian.confluence.pages.AttachmentManager;
import com.atlassian.quartz.jobs.AbstractJob;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Purge old attachment versions.
 *
 * @author  Brett Ryan
 */
public class PurgeOldAttachmentsJob extends AbstractJob {

    private static final Logger LOG =
            LoggerFactory.getLogger(PurgeOldAttachmentsJob.class);

    /**
     * Creates a new {@code PurgeAttachmentsJob} instance.
     */
    public PurgeOldAttachmentsJob() {
        System.out.println("Creating purge-old-attachment-job instance.");
    }

    @Override
    public void doExecute(JobExecutionContext jec) throws JobExecutionException {
        PurgeOldAttachmentsJobDetail jd =
                (PurgeOldAttachmentsJobDetail) jec.getJobDetail();
        AttachmentManager attachmentManager = jd.getAttachmentManager();

        System.out.println("Purge old attachments started.");

        // To delete old attachments 1 week ago.
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, 7);

        if (attachmentManager == null) {
            System.out.println("attachment manager is null, why?");
            return;
        }

        // Assuming attm is an injected AttachmentManager object
        Iterator<Attachment> i =
                attachmentManager.getAttachmentDao().
                findLatestVersionsIterator();
        while (i.hasNext()) {
            Attachment a = i.next();
            if (a.getVersion() > 1) {
                List<Attachment> prior = attachmentManager.
                        getPreviousVersions(a);
                for (Attachment p : prior) {
                    if (c.after(p.getLastModificationDate())) {
                        // Log removal
                        System.out.println("Would remove attachment: "
                                + p.getDisplayTitle() + " (" + p.getExportPath() + ")");
                        //attachmentManager.removeAttachmentFromServer(p);
                    }
                }
            }
        }

        System.out.println("Purge old attachments completed.");
    }

}
