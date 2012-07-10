/*
 * PurgeOldAttachmentsJobDetail.java    Jul 9 2012, 07:06
 *
 * Copyright 2010 John Sands (Australia) Ltd. All rights reserved.
 * Use is subject to license terms. This is an unpublished work.
 */

package com.drunkendev.confluence.plugins.attachments;

import com.atlassian.confluence.pages.AttachmentManager;
import org.quartz.JobDetail;


/**
 *
 * @author  Brett Ryan
 */
public class PurgeAttachmentsJobDetail extends JobDetail {

    private AttachmentManager attachmentManager;

    /**
     * Creates a new {@code PurgeOldAttachmentsJobDetail} instance.
     */
    public PurgeAttachmentsJobDetail(AttachmentManager attachmentManager) {
        super();
        setName(PurgeAttachmentsJobDetail.class.getSimpleName());
        setJobClass(PurgeAttachmentsJob.class);
        this.attachmentManager = attachmentManager;
    }

    public AttachmentManager getAttachmentManager() {
        return attachmentManager;
    }

}
