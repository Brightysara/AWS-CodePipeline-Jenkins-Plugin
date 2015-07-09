/*
 * Copyright 2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.codepipeline.jenkinsplugin;

import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.codepipeline.model.Artifact;
import com.amazonaws.services.codepipeline.model.ExecutionDetails;
import com.amazonaws.services.codepipeline.model.FailureDetails;
import com.amazonaws.services.codepipeline.model.FailureType;
import com.amazonaws.services.codepipeline.model.PutJobFailureResultRequest;
import com.amazonaws.services.codepipeline.model.PutJobSuccessResultRequest;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.SSEAwsKeyManagementParams;
import com.amazonaws.services.s3.model.UploadPartRequest;
import hudson.model.BuildListener;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class UploadTools {
    private final LoggingHelper logHelper;

    public UploadTools() {
        logHelper = new LoggingHelper();
    }

    public void putJobResult(
            final boolean buildSucceeded,
            final String errorMessage,
            final String actionID,
            final String jobID,
            final AWSClients aws,
            final BuildListener listener) {
        if (buildSucceeded) {
            logHelper.log(listener, "Build Succeeded. PutJobSuccessResult");

            final ExecutionDetails executionDetails = new ExecutionDetails();
            executionDetails.setExternalExecutionId(actionID);
            executionDetails.setSummary("Finished");

            final PutJobSuccessResultRequest request = new PutJobSuccessResultRequest();
            request.setJobId(jobID);
            request.setExecutionDetails(executionDetails);
            aws.getCodePipelineClient().putJobSuccessResult(request);
        }
        else {
            logHelper.log(listener, "Build Failed. PutJobFailureResult");

            final FailureDetails executionDetails = new FailureDetails();
            executionDetails.setExternalExecutionId(actionID);
            executionDetails.setMessage(errorMessage);
            executionDetails.setType(FailureType.JobFailed);

            final PutJobFailureResultRequest request = new PutJobFailureResultRequest();
            request.setJobId(jobID);
            request.setFailureDetails(executionDetails);
            aws.getCodePipelineClient().putJobFailureResult(request);
        }
    }

    public void uploadFile(
            final File file,
            final Artifact artifact,
            final CodePipelineStateModel.CompressionType compressionType,
            final BasicSessionCredentials temporaryCredentials,
            final AWSClients aws,
            final BuildListener listener)
            throws IOException, InterruptedException {
        logHelper.log(listener, "Uploading Artifact: " + artifact + ", file: " + file);

        final String bucketName = artifact.getLocation().getS3Location().getBucketName();
        final String objectKey  = artifact.getLocation().getS3Location().getObjectKey();
        final AmazonS3 amazonS3 = aws.getS3Client(temporaryCredentials);
        final List<PartETag> partETags = new ArrayList<>();

        final InitiateMultipartUploadRequest initiateMultipartUploadRequest
                = new InitiateMultipartUploadRequest(
                bucketName,
                artifact.getLocation().getS3Location().getObjectKey(),
                detectCompressionType(compressionType))
                    .withSSEAwsKeyManagementParams(new SSEAwsKeyManagementParams());

        final InitiateMultipartUploadResult initiateMultipartUploadResult
                = amazonS3.initiateMultipartUpload(initiateMultipartUploadRequest);

        final long contentLength = file.length();
        long filePosition = 0;
        long partSize = 5 * 1024 * 1024; // Set part size to 5 MB

        for (int i = 1; filePosition < contentLength; i++) {
            partSize = Math.min(partSize, (contentLength - filePosition));

            final UploadPartRequest uploadPartRequest = new UploadPartRequest()
                    .withBucketName(bucketName)
                    .withKey(objectKey)
                    .withUploadId(initiateMultipartUploadResult.getUploadId())
                    .withPartNumber(i)
                    .withFileOffset(filePosition)
                    .withFile(file)
                    .withPartSize(partSize);

            partETags.add(amazonS3.uploadPart(uploadPartRequest).getPartETag());

            filePosition += partSize;
        }

        final CompleteMultipartUploadRequest completeMultipartUpload
                = new CompleteMultipartUploadRequest(
                    bucketName,
                    objectKey,
                    initiateMultipartUploadResult.getUploadId(),
                    partETags);

        amazonS3.completeMultipartUpload(completeMultipartUpload);

        logHelper.log(listener, "Upload Successful");
    }

    public ObjectMetadata detectCompressionType(final CodePipelineStateModel.CompressionType type) {
        final ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setContentType("application/zip");

        switch (type) {
            case Tar:
                objectMetadata.setContentType("application/tar");
                break;
            case TarGz:
                objectMetadata.setContentType("application/gzip");
                break;
        }

        return objectMetadata;
    }
}
