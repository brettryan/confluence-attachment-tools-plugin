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
        System.out.println("Creating purge-old-attachment-job instance.");
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

    @Override
    public void doExecute(JobExecutionContext jec) throws JobExecutionException {
        PurgeOldAttachmentsJobDetail jd =
                (PurgeOldAttachmentsJobDetail) jec.getJobDetail();
        AttachmentManager attachmentManager = jd.getAttachmentManager();

        System.out.println("Purge old attachments started.");

        // To delete old attachments 1 week ago.
        Calendar dateFrom = Calendar.getInstance();
        dateFrom.add(Calendar.DAY_OF_MONTH, -7);
        Calendar modDate = Calendar.getInstance();

        if (attachmentManager == null) {
            System.out.println("attachment manager is null, why?");
            return;
        }

        PurgeAttachmentSettings dflt = settingSvc.getSettings(null);

        //TODO: Not sure if I can do this at the moment.
//        List<Space> spaces = spaceManager.getAllSpaces();
//        for (Space space : spaces) {
//            PurgeAttachmentSettings sng = getSettings(space, dflt);
//            processSpace(space, sng);
//        }


        //TODO: Need to work out processing by space instead of all at a time.
        // Assuming attm is an injected AttachmentManager object
        Iterator<Attachment> i =
                attachmentManager.getAttachmentDao().
                findLatestVersionsIterator();
        while (i.hasNext()) {
            Attachment a = i.next();
            PurgeAttachmentSettings stng = getSettings(a, dflt);

            if (stng == null) {
                continue;
            }

            if (a.getVersion() > 1) {
                // Change this to a rules based mechanism instead.
                List<Attachment> prior = attachmentManager.
                        getPreviousVersions(a);
                for (Attachment p : prior) {
                    if (p.getLastModificationDate() == null) {
                        continue;
                    }
                    modDate.setTime(p.getLastModificationDate());
                    if (dateFrom.after(modDate)) {
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

    //TODO: Not sure if I can do this at the moment.
    private void processSpace(Space space, PurgeAttachmentSettings sng) {
        //attachmentManager.getAttachmentDao().
        //spaceManager.get
        //Iterator<Attachment> i =
        //        attachmentManager.getAttachmentDao()
        //        .
        //        //findLatestVersionsIterator();
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

}
