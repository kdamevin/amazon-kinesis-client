/*
 * Copyright 2014 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Amazon Software License (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://aws.amazon.com/asl/
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.services.kinesis.multilang;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.amazonaws.services.kinesis.clientlibrary.exceptions.InvalidStateException;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.ShutdownReason;
import com.amazonaws.services.kinesis.clientlibrary.types.InitializationInput;
import com.amazonaws.services.kinesis.clientlibrary.types.ProcessRecordsInput;
import com.amazonaws.services.kinesis.multilang.messages.CheckpointMessage;
import com.amazonaws.services.kinesis.multilang.messages.InitializeMessage;
import com.amazonaws.services.kinesis.multilang.messages.Message;
import com.amazonaws.services.kinesis.multilang.messages.ProcessRecordsMessage;
import com.amazonaws.services.kinesis.multilang.messages.ShutdownMessage;
import com.amazonaws.services.kinesis.multilang.messages.StatusMessage;

import lombok.extern.apachecommons.CommonsLog;

/**
 * An implementation of the multi language protocol.
 */
@CommonsLog
class MultiLangProtocol {

    private MessageReader messageReader;
    private MessageWriter messageWriter;
    private final InitializationInput initializationInput;

    /**
     * Constructor.
     * 
     * @param messageReader
     *            A message reader.
     * @param messageWriter
     *            A message writer.
     * @param initializationInput
     *            information about the shard this processor is starting to process
     */
    MultiLangProtocol(MessageReader messageReader, MessageWriter messageWriter,
            InitializationInput initializationInput) {
        this.messageReader = messageReader;
        this.messageWriter = messageWriter;
        this.initializationInput = initializationInput;
    }

    /**
     * Writes an {@link InitializeMessage} to the child process's STDIN and waits for the child process to respond with
     * a {@link StatusMessage} on its STDOUT.
     * 
     * @return Whether or not this operation succeeded.
     */
    boolean initialize() {
        /*
         * Call and response to child process.
         */
        Future<Boolean> writeFuture = messageWriter.writeInitializeMessage(initializationInput);
        return waitForStatusMessage(InitializeMessage.ACTION, null, writeFuture);

    }

    /**
     * Writes a {@link ProcessRecordsMessage} to the child process's STDIN and waits for the child process to respond
     * with a {@link StatusMessage} on its STDOUT.
     * 
     * @param processRecordsInput
     *            The records, and associated metadata, to process.
     * @return Whether or not this operation succeeded.
     */
    boolean processRecords(ProcessRecordsInput processRecordsInput) {
        Future<Boolean> writeFuture = messageWriter.writeProcessRecordsMessage(processRecordsInput);
        return waitForStatusMessage(ProcessRecordsMessage.ACTION, processRecordsInput.getCheckpointer(), writeFuture);
    }

    /**
     * Writes a {@link ShutdownMessage} to the child process's STDIN and waits for the child process to respond with a
     * {@link StatusMessage} on its STDOUT.
     * 
     * @param checkpointer A checkpointer.
     * @param reason Why this processor is being shutdown.
     * @return Whether or not this operation succeeded.
     */
    boolean shutdown(IRecordProcessorCheckpointer checkpointer, ShutdownReason reason) {
        Future<Boolean> writeFuture = messageWriter.writeShutdownMessage(reason);
        return waitForStatusMessage(ShutdownMessage.ACTION, checkpointer, writeFuture);
    }

    /**
     * Waits for a {@link StatusMessage} for a particular action. If a {@link CheckpointMessage} is received, then this
     * method will attempt to checkpoint with the provided {@link IRecordProcessorCheckpointer}. This method returns
     * true if writing to the child process succeeds and the status message received back was for the correct action and
     * all communications with the child process regarding checkpointing were successful. Note that whether or not the
     * checkpointing itself was successful is not the concern of this method. This method simply cares whether it was
     * able to successfully communicate the results of its attempts to checkpoint.
     * 
     * @param action
     *            What action is being waited on.
     * @param checkpointer
     *            the checkpointer from the process records, or shutdown request
     * @param writeFuture
     *            The writing task.
     * @return Whether or not this operation succeeded.
     */
    private boolean waitForStatusMessage(String action, IRecordProcessorCheckpointer checkpointer,
            Future<Boolean> writeFuture) {
        boolean statusWasCorrect = waitForStatusMessage(action, checkpointer);

        // Examine whether or not we failed somewhere along the line.
        try {
            boolean writerIsStillOpen = writeFuture.get();
            return statusWasCorrect && writerIsStillOpen;
        } catch (InterruptedException e) {
            log.error(String.format("Interrupted while writing %s message for shard %s", action,
                    initializationInput.getShardId()));
            return false;
        } catch (ExecutionException e) {
            log.error(
                    String.format("Failed to write %s message for shard %s", action, initializationInput.getShardId()),
                    e);
            return false;
        }
    }

    /**
     * Waits for status message and verifies it against the expectation
     * 
     * @param action
     *            What action is being waited on.
     * @param checkpointer
     *            the original process records request
     * @return Whether or not this operation succeeded.
     */
    private boolean waitForStatusMessage(String action, IRecordProcessorCheckpointer checkpointer) {
        StatusMessage statusMessage = null;
        while (statusMessage == null) {
            Future<Message> future = this.messageReader.getNextMessageFromSTDOUT();
            try {
                Message message = future.get();
                // Note that instanceof doubles as a check against a value being null
                if (message instanceof CheckpointMessage) {
                    boolean checkpointWriteSucceeded = checkpoint((CheckpointMessage) message, checkpointer).get();
                    if (!checkpointWriteSucceeded) {
                        return false;
                    }
                } else if (message instanceof StatusMessage) {
                    statusMessage = (StatusMessage) message;
                }
            } catch (InterruptedException e) {
                log.error(String.format("Interrupted while waiting for %s message for shard %s", action,
                        initializationInput.getShardId()));
                return false;
            } catch (ExecutionException e) {
                log.error(String.format("Failed to get status message for %s action for shard %s", action,
                        initializationInput.getShardId()), e);
                return false;
            }
        }
        return this.validateStatusMessage(statusMessage, action);
    }

    /**
     * Utility for confirming that the status message is for the provided action.
     * 
     * @param statusMessage The status of the child process.
     * @param action The action that was being waited on.
     * @return Whether or not this operation succeeded.
     */
    private boolean validateStatusMessage(StatusMessage statusMessage, String action) {
        log.info("Received response " + statusMessage + " from subprocess while waiting for " + action
                + " while processing shard " + initializationInput.getShardId());
        return !(statusMessage == null || statusMessage.getResponseFor() == null || !statusMessage.getResponseFor()
                .equals(action));

    }

    /**
     * Attempts to checkpoint with the provided {@link IRecordProcessorCheckpointer} at the sequence number in the
     * provided {@link CheckpointMessage}. If no sequence number is provided, i.e. the sequence number is null, then
     * this method will call {@link IRecordProcessorCheckpointer#checkpoint()}. The method returns a future representing
     * the attempt to write the result of this checkpoint attempt to the child process.
     * 
     * @param checkpointMessage A checkpoint message.
     * @param checkpointer A checkpointer.
     * @return Whether or not this operation succeeded.
     */
    private Future<Boolean> checkpoint(CheckpointMessage checkpointMessage, IRecordProcessorCheckpointer checkpointer) {
        String sequenceNumber = checkpointMessage.getSequenceNumber();
        Long subSequenceNumber = checkpointMessage.getSubSequenceNumber();
        try {
            if (checkpointer != null) {
                log.debug(logCheckpointMessage(sequenceNumber, subSequenceNumber));
                if (sequenceNumber != null) {
                    if (subSequenceNumber != null) {
                        checkpointer.checkpoint(sequenceNumber, subSequenceNumber);
                    } else {
                        checkpointer.checkpoint(sequenceNumber);
                    }
                } else {
                    checkpointer.checkpoint();
                }
                return this.messageWriter.writeCheckpointMessageWithError(sequenceNumber, subSequenceNumber, null);
            } else {
                String message =
                        String.format("Was asked to checkpoint at %s but no checkpointer was provided for shard %s",
                                sequenceNumber, initializationInput.getShardId());
                log.error(message);
                return this.messageWriter.writeCheckpointMessageWithError(sequenceNumber, subSequenceNumber,
                        new InvalidStateException(
                        message));
            }
        } catch (Throwable t) {
            return this.messageWriter.writeCheckpointMessageWithError(sequenceNumber, subSequenceNumber, t);
        }
    }

    private String logCheckpointMessage(String sequenceNumber, Long subSequenceNumber) {
        return String.format("Attempting to checkpoint shard %s @ sequence number %s, and sub sequence number %s",
                initializationInput.getShardId(), sequenceNumber, subSequenceNumber);
    }

}
