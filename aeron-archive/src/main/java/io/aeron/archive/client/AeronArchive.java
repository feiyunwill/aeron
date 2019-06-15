/*
 *  Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.archive.client;

import io.aeron.*;
import io.aeron.archive.codecs.ControlResponseCode;
import io.aeron.archive.codecs.ControlResponseDecoder;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.exceptions.ConcurrentConcludeException;
import io.aeron.exceptions.TimeoutException;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.LangUtil;
import org.agrona.SemanticVersion;
import org.agrona.concurrent.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static io.aeron.archive.client.ArchiveProxy.DEFAULT_RETRY_ATTEMPTS;
import static io.aeron.driver.Configuration.*;
import static java.util.concurrent.atomic.AtomicIntegerFieldUpdater.newUpdater;
import static org.agrona.SystemUtil.getDurationInNanos;
import static org.agrona.SystemUtil.getSizeAsInt;

/**
 * Client for interacting with a local or remote Aeron Archive that records and replays message streams.
 * <p>
 * This client provides a simple interaction model which is mostly synchronous and may not be optimal.
 * The underlying components such as the {@link ArchiveProxy} and the {@link ControlResponsePoller} or
 * {@link RecordingDescriptorPoller} may be used directly if a more asynchronous interaction is required.
 * <p>
 * Note: This class is threadsafe but the lock can be elided for single threaded access via {@link Context#lock(Lock)}
 * being set to {@link NoOpLock}.
 */
public class AeronArchive implements AutoCloseable
{
    /**
     * Represents a timestamp that has not been set. Can be used when the time is not known.
     */
    public static final long NULL_TIMESTAMP = Aeron.NULL_VALUE;

    /**
     * Represents a position that has not been set. Can be used when the position is not known.
     */
    public static final long NULL_POSITION = Aeron.NULL_VALUE;

    /**
     * Represents a length that has not been set. If null length is provided then replay the whole recorded stream.
     */
    public static final long NULL_LENGTH = Aeron.NULL_VALUE;

    private static final int FRAGMENT_LIMIT = 10;

    private boolean isClosed = false;
    private final long controlSessionId;
    private final long messageTimeoutNs;
    private final Context context;
    private final Aeron aeron;
    private final ArchiveProxy archiveProxy;
    private final IdleStrategy idleStrategy;
    private final ControlResponsePoller controlResponsePoller;
    private final RecordingDescriptorPoller recordingDescriptorPoller;
    private final RecordingSubscriptionDescriptorPoller recordingSubscriptionDescriptorPoller;
    private final Lock lock;
    private final NanoClock nanoClock;
    private final AgentInvoker aeronClientInvoker;

    AeronArchive(
        final Context ctx,
        final ControlResponsePoller controlResponsePoller,
        final ArchiveProxy archiveProxy,
        final RecordingDescriptorPoller recordingDescriptorPoller,
        final RecordingSubscriptionDescriptorPoller recordingSubscriptionDescriptorPoller,
        final long controlSessionId)
    {
        context = ctx;
        aeron = ctx.aeron();
        aeronClientInvoker = aeron.conductorAgentInvoker();
        idleStrategy = ctx.idleStrategy();
        messageTimeoutNs = ctx.messageTimeoutNs();
        lock = ctx.lock();
        nanoClock = aeron.context().nanoClock();
        this.controlResponsePoller = controlResponsePoller;
        this.archiveProxy = archiveProxy;
        this.recordingDescriptorPoller = recordingDescriptorPoller;
        this.recordingSubscriptionDescriptorPoller = recordingSubscriptionDescriptorPoller;
        this.controlSessionId = controlSessionId;
    }

    /**
     * Notify the archive that this control session is closed so it can promptly release resources then close the
     * local resources associated with the client.
     */
    public void close()
    {
        lock.lock();
        try
        {
            if (!isClosed)
            {
                isClosed = true;
                archiveProxy.closeSession(controlSessionId);

                if (!context.ownsAeronClient())
                {
                    CloseHelper.close(controlResponsePoller.subscription());
                    CloseHelper.close(archiveProxy.publication());
                }

                context.close();
            }
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Connect to an Aeron archive using a default {@link Context}. This will create a control session.
     *
     * @return the newly created Aeron Archive client.
     */
    public static AeronArchive connect()
    {
        return connect(new Context());
    }

    /**
     * Connect to an Aeron archive by providing a {@link Context}. This will create a control session.
     * <p>
     * Before connecting {@link Context#conclude()} will be called.
     * If an exception occurs then {@link Context#close()} will be called.
     *
     * @param ctx for connection configuration.
     * @return the newly created Aeron Archive client.
     */
    public static AeronArchive connect(final Context ctx)
    {
        Subscription subscription = null;
        Publication publication = null;
        AsyncConnect asyncConnect = null;
        try
        {
            ctx.conclude();

            final Aeron aeron = ctx.aeron();
            final long messageTimeoutNs = ctx.messageTimeoutNs();
            final long deadlineNs = aeron.context().nanoClock().nanoTime() + messageTimeoutNs;

            subscription = aeron.addSubscription(ctx.controlResponseChannel(), ctx.controlResponseStreamId());
            final ControlResponsePoller controlResponsePoller = new ControlResponsePoller(subscription);

            publication = aeron.addExclusivePublication(ctx.controlRequestChannel(), ctx.controlRequestStreamId());
            final ArchiveProxy archiveProxy = new ArchiveProxy(
                publication, ctx.idleStrategy(), aeron.context().nanoClock(), messageTimeoutNs, DEFAULT_RETRY_ATTEMPTS);

            asyncConnect = new AsyncConnect(ctx, controlResponsePoller, archiveProxy, deadlineNs);
            final IdleStrategy idleStrategy = ctx.idleStrategy();
            final AgentInvoker aeronClientInvoker = aeron.conductorAgentInvoker();

            AeronArchive aeronArchive;
            while (null == (aeronArchive = asyncConnect.poll()))
            {
                if (null != aeronClientInvoker)
                {
                    aeronClientInvoker.invoke();
                }
                idleStrategy.idle();
            }

            return aeronArchive;
        }
        catch (final ConcurrentConcludeException ex)
        {
            throw ex;
        }
        catch (final Exception ex)
        {
            if (!ctx.ownsAeronClient())
            {
                CloseHelper.quietClose(subscription);
                CloseHelper.quietClose(publication);
            }

            CloseHelper.quietClose(asyncConnect);
            ctx.close();

            throw ex;
        }
    }

    /**
     * Begin an attempt at creating a connection which can be completed by calling {@link AsyncConnect#poll()} until
     * it returns the client, before complete it will return null.
     *
     * @return the {@link AsyncConnect} that can be polled for completion.
     */
    public static AsyncConnect asyncConnect()
    {
        return asyncConnect(new Context());
    }

    /**
     * Begin an attempt at creating a connection which can be completed by calling {@link AsyncConnect#poll()} until
     * it returns the client, before complete it will return null.
     *
     * @param ctx for the archive connection.
     * @return the {@link AsyncConnect} that can be polled for completion.
     */
    public static AsyncConnect asyncConnect(final Context ctx)
    {
        Subscription subscription = null;
        Publication publication = null;
        try
        {
            ctx.conclude();

            final Aeron aeron = ctx.aeron();
            final long messageTimeoutNs = ctx.messageTimeoutNs();
            final long deadlineNs = aeron.context().nanoClock().nanoTime() + messageTimeoutNs;

            subscription = aeron.addSubscription(ctx.controlResponseChannel(), ctx.controlResponseStreamId());
            final ControlResponsePoller controlResponsePoller = new ControlResponsePoller(subscription);

            publication = aeron.addExclusivePublication(ctx.controlRequestChannel(), ctx.controlRequestStreamId());
            final ArchiveProxy archiveProxy = new ArchiveProxy(
                publication, ctx.idleStrategy(), aeron.context().nanoClock(), messageTimeoutNs, DEFAULT_RETRY_ATTEMPTS);

            return new AsyncConnect(ctx, controlResponsePoller, archiveProxy, deadlineNs);
        }
        catch (final Exception ex)
        {
            if (!ctx.ownsAeronClient())
            {
                CloseHelper.quietClose(subscription);
                CloseHelper.quietClose(publication);
            }

            ctx.close();

            throw ex;
        }
    }

    /**
     * Get the {@link Context} used to connect this archive client.
     *
     * @return the {@link Context} used to connect this archive client.
     */
    public Context context()
    {
        return context;
    }

    /**
     * The control session id allocated for this connection to the archive.
     *
     * @return control session id allocated for this connection to the archive.
     */
    public long controlSessionId()
    {
        return controlSessionId;
    }

    /**
     * The {@link ArchiveProxy} for send asynchronous messages to the connected archive.
     *
     * @return the {@link ArchiveProxy} for send asynchronous messages to the connected archive.
     */
    public ArchiveProxy archiveProxy()
    {
        return archiveProxy;
    }

    /**
     * Get the {@link ControlResponsePoller} for polling additional events on the control channel.
     *
     * @return the {@link ControlResponsePoller} for polling additional events on the control channel.
     */
    public ControlResponsePoller controlResponsePoller()
    {
        return controlResponsePoller;
    }

    /**
     * Get the {@link RecordingDescriptorPoller} for polling recording descriptors on the control channel.
     *
     * @return the {@link RecordingDescriptorPoller} for polling recording descriptors on the control channel.
     */
    public RecordingDescriptorPoller recordingDescriptorPoller()
    {
        return recordingDescriptorPoller;
    }

    /**
     * The {@link RecordingSubscriptionDescriptorPoller} for polling subscription descriptors on the control channel.
     *
     * @return the {@link RecordingSubscriptionDescriptorPoller} for polling subscription descriptors on the control
     * channel.
     */
    public RecordingSubscriptionDescriptorPoller recordingSubscriptionDescriptorPoller()
    {
        return recordingSubscriptionDescriptorPoller;
    }

    /**
     * Poll the response stream once for an error. If another message is present then it will be skipped over
     * so only call when not expecting another response.
     *
     * @return the error String otherwise null if no error is found.
     */
    public String pollForErrorResponse()
    {
        lock.lock();
        try
        {
            ensureOpen();

            if (controlResponsePoller.poll() != 0 && controlResponsePoller.isPollComplete())
            {
                if (controlResponsePoller.controlSessionId() == controlSessionId &&
                    controlResponsePoller.templateId() == ControlResponseDecoder.TEMPLATE_ID &&
                    controlResponsePoller.code() == ControlResponseCode.ERROR)
                {
                    return controlResponsePoller.errorMessage();
                }
            }

            return null;
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Check if an error has been returned for the control session and throw a {@link ArchiveException} if
     * {@link Context#errorHandler(ErrorHandler)} is not set.
     * <p>
     * To check for an error response without raising an exception then try {@link #pollForErrorResponse()}.
     *
     * @see #pollForErrorResponse()
     */
    public void checkForErrorResponse()
    {
        lock.lock();
        try
        {
            ensureOpen();

            if (controlResponsePoller.poll() != 0 && controlResponsePoller.isPollComplete())
            {
                if (controlResponsePoller.controlSessionId() == controlSessionId &&
                    controlResponsePoller.templateId() == ControlResponseDecoder.TEMPLATE_ID &&
                    controlResponsePoller.code() == ControlResponseCode.ERROR)
                {
                    final ArchiveException ex = new ArchiveException(
                        controlResponsePoller.errorMessage(), (int)controlResponsePoller.relevantId());

                    if (null != context.errorHandler())
                    {
                        context.errorHandler().onError(ex);
                    }
                    else
                    {
                        throw ex;
                    }
                }
            }
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Add a {@link Publication} and set it up to be recorded. If this is not the first,
     * i.e. {@link Publication#isOriginal()} is true,  then an {@link ArchiveException}
     * will be thrown and the recording not initiated.
     * <p>
     * This is a sessionId specific recording.
     *
     * @param channel  for the publication.
     * @param streamId for the publication.
     * @return the {@link Publication} ready for use.
     */
    public Publication addRecordedPublication(final String channel, final int streamId)
    {
        Publication publication = null;
        lock.lock();
        try
        {
            ensureOpen();

            publication = aeron.addPublication(channel, streamId);
            if (!publication.isOriginal())
            {
                throw new ArchiveException(
                    "publication already added for channel=" + channel + " streamId=" + streamId);
            }

            startRecording(ChannelUri.addSessionId(channel, publication.sessionId()), streamId, SourceLocation.LOCAL);
        }
        catch (final RuntimeException ex)
        {
            CloseHelper.quietClose(publication);
            throw ex;
        }
        finally
        {
            lock.unlock();
        }

        return publication;
    }

    /**
     * Add an {@link ExclusivePublication} and set it up to be recorded.
     * <p>
     * This is a sessionId specific recording.
     *
     * @param channel  for the publication.
     * @param streamId for the publication.
     * @return the {@link ExclusivePublication} ready for use.
     */
    public ExclusivePublication addRecordedExclusivePublication(final String channel, final int streamId)
    {
        ExclusivePublication publication = null;
        lock.lock();
        try
        {
            ensureOpen();

            publication = aeron.addExclusivePublication(channel, streamId);
            startRecording(ChannelUri.addSessionId(channel, publication.sessionId()), streamId, SourceLocation.LOCAL);
        }
        catch (final RuntimeException ex)
        {
            CloseHelper.quietClose(publication);
            throw ex;
        }
        finally
        {
            lock.unlock();
        }

        return publication;
    }

    /**
     * Start recording a channel and stream pairing.
     * <p>
     * Channels that include sessionId parameters are considered different than channels without sessionIds. If a
     * publication matches both a sessionId specific channel recording and a non-sessionId specific recording, it will
     * be recorded twice.
     *
     * @param channel        to be recorded.
     * @param streamId       to be recorded.
     * @param sourceLocation of the publication to be recorded.
     * @return the subscriptionId, i.e. {@link Subscription#registrationId()}, of the recording.
     */
    public long startRecording(final String channel, final int streamId, final SourceLocation sourceLocation)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.startRecording(channel, streamId, sourceLocation, correlationId, controlSessionId))
            {
                throw new ArchiveException("failed to send start recording request");
            }

            return pollForResponse(correlationId);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Extend an existing, non-active recording of a channel and stream pairing.
     * <p>
     * The channel must be configured for the initial position from which it will be extended. This can be done
     * with {@link ChannelUriStringBuilder#initialPosition(long, int, int)}. The details required to initialise can
     * be found by calling {@link #listRecording(long, RecordingDescriptorConsumer)}.
     *
     * @param recordingId    of the existing recording.
     * @param channel        to be recorded.
     * @param streamId       to be recorded.
     * @param sourceLocation of the publication to be recorded.
     * @return the subscriptionId, i.e. {@link Subscription#registrationId()}, of the recording.
     */
    public long extendRecording(
        final long recordingId, final String channel, final int streamId, final SourceLocation sourceLocation)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.extendRecording(
                channel, streamId, sourceLocation, recordingId, correlationId, controlSessionId))
            {
                throw new ArchiveException("failed to send extend recording request");
            }

            return pollForResponse(correlationId);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Stop recording for a channel and stream pairing.
     * <p>
     * Channels that include sessionId parameters are considered different than channels without sessionIds. Stopping
     * a recording on a channel without a sessionId parameter will not stop the recording of any sessionId specific
     * recordings that use the same channel and streamId.
     *
     * @param channel  to stop recording for.
     * @param streamId to stop recording for.
     */
    public void stopRecording(final String channel, final int streamId)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.stopRecording(channel, streamId, correlationId, controlSessionId))
            {
                throw new ArchiveException("failed to send stop recording request");
            }

            pollForResponse(correlationId);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Stop recording a sessionId specific recording that pertains to the given {@link Publication}.
     *
     * @param publication to stop recording for.
     */
    public void stopRecording(final Publication publication)
    {
        final String recordingChannel = ChannelUri.addSessionId(publication.channel(), publication.sessionId());

        stopRecording(recordingChannel, publication.streamId());
    }

    /**
     * Stop recording for a subscriptionId that has been returned from
     * {@link #startRecording(String, int, SourceLocation)} or
     * {@link #extendRecording(long, String, int, SourceLocation)}.
     *
     * @param subscriptionId is the {@link Subscription#registrationId()} for the recording in the archive.
     */
    public void stopRecording(final long subscriptionId)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.stopRecording(subscriptionId, correlationId, controlSessionId))
            {
                throw new ArchiveException("failed to send stop recording request");
            }

            pollForResponse(correlationId);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Start a replay for a length in bytes of a recording from a position. If the position is {@link #NULL_POSITION}
     * then the stream will be replayed from the start.
     * <p>
     * The lower 32-bits of the returned value contains the {@link Image#sessionId()} of the received replay. All
     * 64-bits are required to uniquely identify the replay when calling {@link #stopReplay(long)}. The lower 32-bits
     * can be obtained by casting the {@code long} value to an {@code int}.
     *
     * @param recordingId    to be replayed.
     * @param position       from which the replay should begin or {@link #NULL_POSITION} if from the start.
     * @param length         of the stream to be replayed. Use {@link Long#MAX_VALUE} to follow a live recording or
     *                       {@link #NULL_LENGTH} to replay the whole stream of unknown length.
     * @param replayChannel  to which the replay should be sent.
     * @param replayStreamId to which the replay should be sent.
     * @return the id of the replay session which will be the same as the {@link Image#sessionId()} of the received
     * replay for correlation with the matching channel and stream id in the lower 32 bits.
     */
    public long startReplay(
        final long recordingId,
        final long position,
        final long length,
        final String replayChannel,
        final int replayStreamId)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.replay(
                recordingId,
                position,
                length,
                replayChannel,
                replayStreamId,
                correlationId,
                controlSessionId))
            {
                throw new ArchiveException("failed to send replay request");
            }

            return pollForResponse(correlationId);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Start a replay for a length in bytes of a recording from a position bounded by a position counter.
     * If the position is {@link #NULL_POSITION} then the stream will be replayed from the start.
     * <p>
     * The lower 32-bits of the returned value contains the {@link Image#sessionId()} of the received replay. All
     * 64-bits are required to uniquely identify the replay when calling {@link #stopReplay(long)}. The lower 32-bits
     * can be obtained by casting the {@code long} value to an {@code int}.
     *
     * @param recordingId       to be replayed.
     * @param position          from which the replay should begin or {@link #NULL_POSITION} if from the start.
     * @param length            of the stream to be replayed. Use {@link Long#MAX_VALUE} to follow a live recording or
     *                          {@link #NULL_LENGTH} to replay the whole stream of unknown length.
     * @param limitCounterId    to use to bound replay.
     * @param replayChannel     to which the replay should be sent.
     * @param replayStreamId    to which the replay should be sent.
     * @return the id of the replay session which will be the same as the {@link Image#sessionId()} of the received
     * replay for correlation with the matching channel and stream id in the lower 32 bits.
     */
    public long startBoundedReplay(
        final long recordingId,
        final long position,
        final long length,
        final int limitCounterId,
        final String replayChannel,
        final int replayStreamId)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.boundedReplay(
                recordingId,
                position,
                length,
                limitCounterId,
                replayChannel,
                replayStreamId,
                correlationId,
                controlSessionId))
            {
                throw new ArchiveException("failed to send bounded replay request");
            }

            return pollForResponse(correlationId);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Stop a replay session.
     *
     * @param replaySessionId to stop replay for.
     */
    public void stopReplay(final long replaySessionId)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.stopReplay(replaySessionId, correlationId, controlSessionId))
            {
                throw new ArchiveException("failed to send stop replay request");
            }

            pollForResponse(correlationId);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Replay a length in bytes of a recording from a position and for convenience create a {@link Subscription}
     * to receive the replay. If the position is {@link #NULL_POSITION} then the stream will be replayed from the start.
     *
     * @param recordingId    to be replayed.
     * @param position       from which the replay should begin or {@link #NULL_POSITION} if from the start.
     * @param length         of the stream to be replayed or {@link Long#MAX_VALUE} to follow a live recording.
     * @param replayChannel  to which the replay should be sent.
     * @param replayStreamId to which the replay should be sent.
     * @return the {@link Subscription} for consuming the replay.
     */
    public Subscription replay(
        final long recordingId,
        final long position,
        final long length,
        final String replayChannel,
        final int replayStreamId)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final ChannelUri replayChannelUri = ChannelUri.parse(replayChannel);
            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.replay(
                recordingId,
                position,
                length,
                replayChannel,
                replayStreamId,
                correlationId,
                controlSessionId))
            {
                throw new ArchiveException("failed to send replay request");
            }

            final int replaySessionId = (int)pollForResponse(correlationId);
            replayChannelUri.put(CommonContext.SESSION_ID_PARAM_NAME, Integer.toString(replaySessionId));

            return aeron.addSubscription(replayChannelUri.toString(), replayStreamId);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Replay a length in bytes of a recording from a position and for convenience create a {@link Subscription}
     * to receive the replay. If the position is {@link #NULL_POSITION} then the stream will be replayed from the start.
     *
     * @param recordingId             to be replayed.
     * @param position                from which the replay should begin or {@link #NULL_POSITION} if from the start.
     * @param length                  of the stream to be replayed or {@link Long#MAX_VALUE} to follow a live recording.
     * @param replayChannel           to which the replay should be sent.
     * @param replayStreamId          to which the replay should be sent.
     * @param availableImageHandler   to be called when the replay image becomes available.
     * @param unavailableImageHandler to be called when the replay image goes unavailable.
     * @return the {@link Subscription} for consuming the replay.
     */
    public Subscription replay(
        final long recordingId,
        final long position,
        final long length,
        final String replayChannel,
        final int replayStreamId,
        final AvailableImageHandler availableImageHandler,
        final UnavailableImageHandler unavailableImageHandler)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final ChannelUri replayChannelUri = ChannelUri.parse(replayChannel);
            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.replay(
                recordingId,
                position,
                length,
                replayChannel,
                replayStreamId,
                correlationId,
                controlSessionId))
            {
                throw new ArchiveException("failed to send replay request");
            }

            final int replaySessionId = (int)pollForResponse(correlationId);
            replayChannelUri.put(CommonContext.SESSION_ID_PARAM_NAME, Integer.toString(replaySessionId));

            return aeron.addSubscription(
                replayChannelUri.toString(), replayStreamId, availableImageHandler, unavailableImageHandler);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * List all recording descriptors from a recording id with a limit of record count.
     * <p>
     * If the recording id is greater than the largest known id then nothing is returned.
     *
     * @param fromRecordingId at which to begin the listing.
     * @param recordCount     to limit for each query.
     * @param consumer        to which the descriptors are dispatched.
     * @return the number of descriptors found and consumed.
     */
    public int listRecordings(
        final long fromRecordingId, final int recordCount, final RecordingDescriptorConsumer consumer)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.listRecordings(fromRecordingId, recordCount, correlationId, controlSessionId))
            {
                throw new ArchiveException("failed to send list recordings request");
            }

            return pollForDescriptors(correlationId, recordCount, consumer);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * List recording descriptors from a recording id with a limit of record count for a given channelFragment and
     * stream id.
     * <p>
     * If the recording id is greater than the largest known id then nothing is returned.
     *
     * @param fromRecordingId at which to begin the listing.
     * @param recordCount     to limit for each query.
     * @param channelFragment for a contains match on the original channel stored with the archive descriptor.
     * @param streamId        to match.
     * @param consumer        to which the descriptors are dispatched.
     * @return the number of descriptors found and consumed.
     */
    public int listRecordingsForUri(
        final long fromRecordingId,
        final int recordCount,
        final String channelFragment,
        final int streamId,
        final RecordingDescriptorConsumer consumer)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.listRecordingsForUri(
                fromRecordingId,
                recordCount,
                channelFragment,
                streamId,
                correlationId,
                controlSessionId))
            {
                throw new ArchiveException("failed to send list recordings request");
            }

            return pollForDescriptors(correlationId, recordCount, consumer);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * List a recording descriptor for a single recording id.
     * <p>
     * If the recording id is greater than the largest known id then nothing is returned.
     *
     * @param recordingId at which to begin the listing.
     * @param consumer    to which the descriptors are dispatched.
     * @return the number of descriptors found and consumed.
     */
    public int listRecording(final long recordingId, final RecordingDescriptorConsumer consumer)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.listRecording(recordingId, correlationId, controlSessionId))
            {
                throw new ArchiveException("failed to send list recording request");
            }

            return pollForDescriptors(correlationId, 1, consumer);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Get the position recorded for an active recording. If no active recording then return {@link #NULL_POSITION}.
     *
     * @param recordingId of the active recording for which the position is required.
     * @return the recorded position for the active recording or {@link #NULL_POSITION} if recording not active.
     * @see #getStopPosition(long)
     */
    public long getRecordingPosition(final long recordingId)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.getRecordingPosition(recordingId, correlationId, controlSessionId))
            {
                throw new ArchiveException("failed to send get recording position request");
            }

            return pollForResponse(correlationId);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Get the stop position for a recording.
     *
     * @param recordingId of the active recording for which the position is required.
     * @return the stop position, or {@link #NULL_POSITION} if still active.
     * @see #getRecordingPosition(long)
     */
    public long getStopPosition(final long recordingId)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.getStopPosition(recordingId, correlationId, controlSessionId))
            {
                throw new ArchiveException("failed to send get stop position request");
            }

            return pollForResponse(correlationId);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Find the last recording that matches the given criteria.
     *
     * @param minRecordingId  to search back to.
     * @param channelFragment for a contains match on the original channel stored with the archive descriptor.
     * @param streamId        of the recording to match.
     * @param sessionId       of the recording to match.
     * @return the recordingId if found otherwise {@link Aeron#NULL_VALUE} if not found.
     */
    public long findLastMatchingRecording(
        final long minRecordingId, final String channelFragment, final int streamId, final int sessionId)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.findLastMatchingRecording(
                minRecordingId, channelFragment, streamId, sessionId, correlationId, controlSessionId))
            {
                throw new ArchiveException("failed to send find last matching recording request");
            }

            return pollForResponse(correlationId);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Truncate a stopped recording to a given position that is less than the stopped position. The provided position
     * must be on a fragment boundary. Truncating a recording to the start position effectively deletes the recording.
     *
     * @param recordingId of the stopped recording to be truncated.
     * @param position    to which the recording will be truncated.
     */
    public void truncateRecording(final long recordingId, final long position)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.truncateRecording(recordingId, position, correlationId, controlSessionId))
            {
                throw new ArchiveException("failed to send truncate recording request");
            }

            pollForResponse(correlationId);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * List active recording subscriptions in the archive. These are the result of requesting one of
     * {@link #startRecording(String, int, SourceLocation)} or a
     * {@link #extendRecording(long, String, int, SourceLocation)}. The returned subscription id can be used for
     * passing to {@link #stopRecording(long)}.
     *
     * @param pseudoIndex       in the active list at which to begin for paging.
     * @param subscriptionCount to get in a listing.
     * @param channelFragment   to do a contains match on the stripped channel URI. Empty string is match all.
     * @param streamId          to match on the subscription.
     * @param applyStreamId     true if the stream id should be matched.
     * @param consumer          for the matched subscription descriptors.
     * @return the count of matched subscriptions.
     */
    public int listRecordingSubscriptions(
        final int pseudoIndex,
        final int subscriptionCount,
        final String channelFragment,
        final int streamId,
        final boolean applyStreamId,
        final RecordingSubscriptionDescriptorConsumer consumer)
    {
        lock.lock();
        try
        {
            ensureOpen();

            final long correlationId = aeron.nextCorrelationId();

            if (!archiveProxy.listRecordingSubscriptions(
                pseudoIndex,
                subscriptionCount,
                channelFragment,
                streamId,
                applyStreamId,
                correlationId,
                controlSessionId))
            {
                throw new ArchiveException("failed to send list recording subscriptions request");
            }

            return pollForSubscriptionDescriptors(correlationId, subscriptionCount, consumer);
        }
        finally
        {
            lock.unlock();
        }
    }

    private void checkDeadline(final long deadlineNs, final String errorMessage, final long correlationId)
    {
        if (Thread.interrupted())
        {
            LangUtil.rethrowUnchecked(new InterruptedException());
        }

        if (deadlineNs - nanoClock.nanoTime() < 0)
        {
            throw new TimeoutException(errorMessage + " - correlationId=" + correlationId);
        }
    }

    private long pollForResponse(final long correlationId)
    {
        final long deadlineNs = nanoClock.nanoTime() + messageTimeoutNs;
        final ControlResponsePoller poller = controlResponsePoller;

        while (true)
        {
            pollNextResponse(correlationId, deadlineNs, poller);

            if (poller.controlSessionId() != controlSessionId ||
                poller.templateId() != ControlResponseDecoder.TEMPLATE_ID)
            {
                invokeAeronClient();
                continue;
            }

            final ControlResponseCode code = poller.code();
            if (ControlResponseCode.ERROR == code)
            {
                final ArchiveException ex = new ArchiveException("response for correlationId=" + correlationId +
                    ", error: " + poller.errorMessage(), (int)poller.relevantId());

                if (poller.correlationId() == correlationId)
                {
                    throw ex;
                }
                else if (context.errorHandler() != null)
                {
                    context.errorHandler().onError(ex);
                }
            }
            else if (poller.correlationId() == correlationId)
            {
                if (ControlResponseCode.OK != code)
                {
                    throw new ArchiveException("unexpected response code: " + code);
                }

                return poller.relevantId();
            }
        }
    }

    private void pollNextResponse(final long correlationId, final long deadlineNs, final ControlResponsePoller poller)
    {
        idleStrategy.reset();

        while (true)
        {
            final int fragments = poller.poll();

            if (poller.isPollComplete())
            {
                break;
            }

            if (fragments > 0)
            {
                continue;
            }

            if (!poller.subscription().isConnected())
            {
                throw new ArchiveException("subscription to archive is not connected");
            }

            checkDeadline(deadlineNs, "awaiting response", correlationId);
            idleStrategy.idle();
            invokeAeronClient();
        }
    }

    private int pollForDescriptors(
        final long correlationId, final int recordCount, final RecordingDescriptorConsumer consumer)
    {
        int existingRemainCount = recordCount;
        long deadlineNs = nanoClock.nanoTime() + messageTimeoutNs;
        final RecordingDescriptorPoller poller = recordingDescriptorPoller;
        poller.reset(correlationId, recordCount, consumer);
        idleStrategy.reset();

        while (true)
        {
            final int fragments = poller.poll();
            final int remainingRecordCount = poller.remainingRecordCount();

            if (poller.isDispatchComplete())
            {
                return recordCount - remainingRecordCount;
            }

            if (remainingRecordCount != existingRemainCount)
            {
                existingRemainCount = remainingRecordCount;
                deadlineNs = nanoClock.nanoTime() + messageTimeoutNs;
            }

            invokeAeronClient();

            if (fragments > 0)
            {
                continue;
            }

            if (!poller.subscription().isConnected())
            {
                throw new ArchiveException("subscription to archive is not connected");
            }

            checkDeadline(deadlineNs, "awaiting recording descriptors", correlationId);
            idleStrategy.idle();
        }
    }

    private int pollForSubscriptionDescriptors(
        final long correlationId, final int recordCount, final RecordingSubscriptionDescriptorConsumer consumer)
    {
        int existingRemainCount = recordCount;
        long deadlineNs = nanoClock.nanoTime() + messageTimeoutNs;
        final RecordingSubscriptionDescriptorPoller poller = recordingSubscriptionDescriptorPoller;
        poller.reset(correlationId, recordCount, consumer);
        idleStrategy.reset();

        while (true)
        {
            final int fragments = poller.poll();
            final int remainingSubscriptionCount = poller.remainingSubscriptionCount();

            if (poller.isDispatchComplete())
            {
                return recordCount - remainingSubscriptionCount;
            }

            if (remainingSubscriptionCount != existingRemainCount)
            {
                existingRemainCount = remainingSubscriptionCount;
                deadlineNs = nanoClock.nanoTime() + messageTimeoutNs;
            }

            invokeAeronClient();

            if (fragments > 0)
            {
                continue;
            }

            if (!poller.subscription().isConnected())
            {
                throw new ArchiveException("subscription to archive is not connected");
            }

            checkDeadline(deadlineNs, "awaiting subscription descriptors", correlationId);
            idleStrategy.idle();
        }
    }

    private void invokeAeronClient()
    {
        if (null != aeronClientInvoker)
        {
            aeronClientInvoker.invoke();
        }
    }

    private void ensureOpen()
    {
        if (isClosed)
        {
            throw new ArchiveException("client is closed");
        }
    }

    /**
     * Common configuration properties for communicating with an Aeron archive.
     */
    public static class Configuration
    {
        public static final int MAJOR_VERSION = 0;
        public static final int MINOR_VERSION = 1;
        public static final int PATCH_VERSION = 1;
        public static final int SEMANTIC_VERSION = SemanticVersion.compose(MAJOR_VERSION, MINOR_VERSION, PATCH_VERSION);

        /**
         * Timeout in nanoseconds when waiting on a message to be sent or received.
         */
        public static final String MESSAGE_TIMEOUT_PROP_NAME = "aeron.archive.message.timeout";

        /**
         * Timeout when waiting on a message to be sent or received.
         */
        public static final long MESSAGE_TIMEOUT_DEFAULT_NS = TimeUnit.SECONDS.toNanos(5);

        /**
         * Channel for sending control messages to an archive.
         */
        public static final String CONTROL_CHANNEL_PROP_NAME = "aeron.archive.control.channel";

        /**
         * Channel for sending control messages to an archive.
         */
        public static final String CONTROL_CHANNEL_DEFAULT = "aeron:udp?endpoint=localhost:8010";

        /**
         * Stream id within a channel for sending control messages to an archive.
         */
        public static final String CONTROL_STREAM_ID_PROP_NAME = "aeron.archive.control.stream.id";

        /**
         * Stream id within a channel for sending control messages to an archive.
         */
        public static final int CONTROL_STREAM_ID_DEFAULT = 10;

        /**
         * Channel for sending control messages to a driver local archive.
         */
        public static final String LOCAL_CONTROL_CHANNEL_PROP_NAME = "aeron.archive.local.control.channel";

        /**
         * Channel for sending control messages to a driver local archive. Default to IPC.
         */
        public static final String LOCAL_CONTROL_CHANNEL_DEFAULT = CommonContext.IPC_CHANNEL;

        /**
         * Stream id within a channel for sending control messages to a driver local archive.
         */
        public static final String LOCAL_CONTROL_STREAM_ID_PROP_NAME = "aeron.archive.local.control.stream.id";

        /**
         * Stream id within a channel for sending control messages to a driver local archive.
         */
        public static final int LOCAL_CONTROL_STREAM_ID_DEFAULT = 11;

        /**
         * Channel for receiving control response messages from an archive.
         */
        public static final String CONTROL_RESPONSE_CHANNEL_PROP_NAME = "aeron.archive.control.response.channel";

        /**
         * Channel for receiving control response messages from an archive.
         */
        public static final String CONTROL_RESPONSE_CHANNEL_DEFAULT = "aeron:udp?endpoint=localhost:8020";

        /**
         * Stream id within a channel for receiving control messages from an archive.
         */
        public static final String CONTROL_RESPONSE_STREAM_ID_PROP_NAME = "aeron.archive.control.response.stream.id";

        /**
         * Stream id within a channel for receiving control messages from an archive.
         */
        public static final int CONTROL_RESPONSE_STREAM_ID_DEFAULT = 20;

        /**
         * Channel for receiving progress events of recordings from an archive.
         */
        public static final String RECORDING_EVENTS_CHANNEL_PROP_NAME = "aeron.archive.recording.events.channel";

        /**
         * Channel for receiving progress events of recordings from an archive.
         * For production it is recommended that multicast or dynamic multi-destination-cast (MDC) is used to allow
         * for dynamic subscribers, an endpoint can be added to the subscription side for controlling port usage.
         */
        public static final String RECORDING_EVENTS_CHANNEL_DEFAULT =
            "aeron:udp?control-mode=dynamic|control=localhost:8030";

        /**
         * Stream id within a channel for receiving progress of recordings from an archive.
         */
        public static final String RECORDING_EVENTS_STREAM_ID_PROP_NAME = "aeron.archive.recording.events.stream.id";

        /**
         * Stream id within a channel for receiving progress of recordings from an archive.
         */
        public static final int RECORDING_EVENTS_STREAM_ID_DEFAULT = 30;

        /**
         * Sparse term buffer indicator for control streams.
         */
        public static final String CONTROL_TERM_BUFFER_SPARSE_PROP_NAME = "aeron.archive.control.term.buffer.sparse";

        /**
         * Overrides {@link io.aeron.driver.Configuration#TERM_BUFFER_SPARSE_FILE_PROP_NAME} for if term buffer files
         * are sparse on the control channel.
         */
        public static final boolean CONTROL_TERM_BUFFER_SPARSE_DEFAULT = true;

        /**
         * Term length for control streams.
         */
        public static final String CONTROL_TERM_BUFFER_LENGTH_PROP_NAME = "aeron.archive.control.term.buffer.length";

        /**
         * Low term length for control channel reflects expected low bandwidth usage.
         */
        public static final int CONTROL_TERM_BUFFER_LENGTH_DEFAULT = 64 * 1024;

        /**
         * MTU length for control streams.
         */
        public static final String CONTROL_MTU_LENGTH_PROP_NAME = "aeron.archive.control.mtu.length";

        /**
         * MTU to reflect default for the control streams.
         */
        public static final int CONTROL_MTU_LENGTH_DEFAULT = io.aeron.driver.Configuration.mtuLength();

        /**
         * The timeout in nanoseconds to wait for a message.
         *
         * @return timeout in nanoseconds to wait for a message.
         * @see #MESSAGE_TIMEOUT_PROP_NAME
         */
        public static long messageTimeoutNs()
        {
            return getDurationInNanos(MESSAGE_TIMEOUT_PROP_NAME, MESSAGE_TIMEOUT_DEFAULT_NS);
        }

        /**
         * Should term buffer files be sparse for control request and response streams.
         *
         * @return true if term buffer files should be sparse for control request and response streams.
         * @see #CONTROL_TERM_BUFFER_SPARSE_PROP_NAME
         */
        public static boolean controlTermBufferSparse()
        {
            final String propValue = System.getProperty(CONTROL_TERM_BUFFER_SPARSE_PROP_NAME);
            return null != propValue ? "true".equals(propValue) : CONTROL_TERM_BUFFER_SPARSE_DEFAULT;
        }

        /**
         * Term buffer length to be used for control request and response streams.
         *
         * @return term buffer length to be used for control request and response streams.
         * @see #CONTROL_TERM_BUFFER_LENGTH_PROP_NAME
         */
        public static int controlTermBufferLength()
        {
            return getSizeAsInt(CONTROL_TERM_BUFFER_LENGTH_PROP_NAME, CONTROL_TERM_BUFFER_LENGTH_DEFAULT);
        }

        /**
         * MTU length to be used for control request and response streams.
         *
         * @return MTU length to be used for control request and response streams.
         * @see #CONTROL_MTU_LENGTH_PROP_NAME
         */
        public static int controlMtuLength()
        {
            return getSizeAsInt(CONTROL_MTU_LENGTH_PROP_NAME, CONTROL_MTU_LENGTH_DEFAULT);
        }

        /**
         * The value {@link #CONTROL_CHANNEL_DEFAULT} or system property
         * {@link #CONTROL_CHANNEL_PROP_NAME} if set.
         *
         * @return {@link #CONTROL_CHANNEL_DEFAULT} or system property
         * {@link #CONTROL_CHANNEL_PROP_NAME} if set.
         */
        public static String controlChannel()
        {
            return System.getProperty(CONTROL_CHANNEL_PROP_NAME, CONTROL_CHANNEL_DEFAULT);
        }

        /**
         * The value {@link #CONTROL_STREAM_ID_DEFAULT} or system property
         * {@link #CONTROL_STREAM_ID_PROP_NAME} if set.
         *
         * @return {@link #CONTROL_STREAM_ID_DEFAULT} or system property
         * {@link #CONTROL_STREAM_ID_PROP_NAME} if set.
         */
        public static int controlStreamId()
        {
            return Integer.getInteger(CONTROL_STREAM_ID_PROP_NAME, CONTROL_STREAM_ID_DEFAULT);
        }

        /**
         * The value {@link #LOCAL_CONTROL_CHANNEL_DEFAULT} or system property
         * {@link #CONTROL_CHANNEL_PROP_NAME} if set.
         *
         * @return {@link #LOCAL_CONTROL_CHANNEL_DEFAULT} or system property
         * {@link #LOCAL_CONTROL_CHANNEL_PROP_NAME} if set.
         */
        public static String localControlChannel()
        {
            return System.getProperty(LOCAL_CONTROL_CHANNEL_PROP_NAME, LOCAL_CONTROL_CHANNEL_DEFAULT);
        }

        /**
         * The value {@link #LOCAL_CONTROL_STREAM_ID_DEFAULT} or system property
         * {@link #LOCAL_CONTROL_STREAM_ID_PROP_NAME} if set.
         *
         * @return {@link #LOCAL_CONTROL_STREAM_ID_DEFAULT} or system property
         * {@link #LOCAL_CONTROL_STREAM_ID_PROP_NAME} if set.
         */
        public static int localControlStreamId()
        {
            return Integer.getInteger(LOCAL_CONTROL_STREAM_ID_PROP_NAME, LOCAL_CONTROL_STREAM_ID_DEFAULT);
        }

        /**
         * The value {@link #CONTROL_RESPONSE_CHANNEL_DEFAULT} or system property
         * {@link #CONTROL_RESPONSE_CHANNEL_PROP_NAME} if set.
         *
         * @return {@link #CONTROL_RESPONSE_CHANNEL_DEFAULT} or system property
         * {@link #CONTROL_RESPONSE_CHANNEL_PROP_NAME} if set.
         */
        public static String controlResponseChannel()
        {
            return System.getProperty(CONTROL_RESPONSE_CHANNEL_PROP_NAME, CONTROL_RESPONSE_CHANNEL_DEFAULT);
        }

        /**
         * The value {@link #CONTROL_RESPONSE_STREAM_ID_DEFAULT} or system property
         * {@link #CONTROL_RESPONSE_STREAM_ID_PROP_NAME} if set.
         *
         * @return {@link #CONTROL_RESPONSE_STREAM_ID_DEFAULT} or system property
         * {@link #CONTROL_RESPONSE_STREAM_ID_PROP_NAME} if set.
         */
        public static int controlResponseStreamId()
        {
            return Integer.getInteger(CONTROL_RESPONSE_STREAM_ID_PROP_NAME, CONTROL_RESPONSE_STREAM_ID_DEFAULT);
        }

        /**
         * The value {@link #RECORDING_EVENTS_CHANNEL_DEFAULT} or system property
         * {@link #RECORDING_EVENTS_CHANNEL_PROP_NAME} if set.
         *
         * @return {@link #RECORDING_EVENTS_CHANNEL_DEFAULT} or system property
         * {@link #RECORDING_EVENTS_CHANNEL_PROP_NAME} if set.
         */
        public static String recordingEventsChannel()
        {
            return System.getProperty(RECORDING_EVENTS_CHANNEL_PROP_NAME, RECORDING_EVENTS_CHANNEL_DEFAULT);
        }

        /**
         * The value {@link #RECORDING_EVENTS_STREAM_ID_DEFAULT} or system property
         * {@link #RECORDING_EVENTS_STREAM_ID_PROP_NAME} if set.
         *
         * @return {@link #RECORDING_EVENTS_STREAM_ID_DEFAULT} or system property
         * {@link #RECORDING_EVENTS_STREAM_ID_PROP_NAME} if set.
         */
        public static int recordingEventsStreamId()
        {
            return Integer.getInteger(RECORDING_EVENTS_STREAM_ID_PROP_NAME, RECORDING_EVENTS_STREAM_ID_DEFAULT);
        }
    }

    /**
     * Specialised configuration options for communicating with an Aeron Archive.
     * <p>
     * The context will be owned by {@link AeronArchive} after a successful
     * {@link AeronArchive#connect(Context)} and closed via {@link AeronArchive#close()}.
     */
    public static class Context implements Cloneable
    {
        /**
         * Using an integer because there is no support for boolean. 1 is concluded, 0 is not concluded.
         */
        private static final AtomicIntegerFieldUpdater<Context> IS_CONCLUDED_UPDATER = newUpdater(
            Context.class, "isConcluded");
        private volatile int isConcluded;

        private long messageTimeoutNs = Configuration.messageTimeoutNs();
        private String recordingEventsChannel = AeronArchive.Configuration.recordingEventsChannel();
        private int recordingEventsStreamId = AeronArchive.Configuration.recordingEventsStreamId();
        private String controlRequestChannel = Configuration.controlChannel();
        private int controlRequestStreamId = Configuration.controlStreamId();
        private String controlResponseChannel = Configuration.controlResponseChannel();
        private int controlResponseStreamId = Configuration.controlResponseStreamId();
        private boolean controlTermBufferSparse = Configuration.controlTermBufferSparse();
        private int controlTermBufferLength = Configuration.controlTermBufferLength();
        private int controlMtuLength = Configuration.controlMtuLength();
        private IdleStrategy idleStrategy;
        private Lock lock;
        private String aeronDirectoryName = CommonContext.getAeronDirectoryName();
        private Aeron aeron;
        private ErrorHandler errorHandler;
        private boolean ownsAeronClient = false;

        /**
         * Perform a shallow copy of the object.
         *
         * @return a shallow copy of the object.
         */
        public Context clone()
        {
            try
            {
                return (Context)super.clone();
            }
            catch (final CloneNotSupportedException ex)
            {
                throw new RuntimeException(ex);
            }
        }

        /**
         * Conclude configuration by setting up defaults when specifics are not provided.
         */
        public void conclude()
        {
            if (0 != IS_CONCLUDED_UPDATER.getAndSet(this, 1))
            {
                throw new ConcurrentConcludeException();
            }

            if (null == aeron)
            {
                aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDirectoryName));
                ownsAeronClient = true;
            }

            if (null == idleStrategy)
            {
                idleStrategy = new BackoffIdleStrategy(
                    IDLE_MAX_SPINS, IDLE_MAX_YIELDS, IDLE_MIN_PARK_NS, IDLE_MAX_PARK_NS);
            }

            if (null == lock)
            {
                lock = new ReentrantLock();
            }

            final ChannelUri uri = ChannelUri.parse(controlRequestChannel);
            uri.put(CommonContext.TERM_LENGTH_PARAM_NAME, Integer.toString(controlTermBufferLength));
            uri.put(CommonContext.MTU_LENGTH_PARAM_NAME, Integer.toString(controlMtuLength));
            uri.put(CommonContext.SPARSE_PARAM_NAME, Boolean.toString(controlTermBufferSparse));
            controlRequestChannel = uri.toString();
        }

        /**
         * Set the message timeout in nanoseconds to wait for sending or receiving a message.
         *
         * @param messageTimeoutNs to wait for sending or receiving a message.
         * @return this for a fluent API.
         * @see Configuration#MESSAGE_TIMEOUT_PROP_NAME
         */
        public Context messageTimeoutNs(final long messageTimeoutNs)
        {
            this.messageTimeoutNs = messageTimeoutNs;
            return this;
        }

        /**
         * The message timeout in nanoseconds to wait for sending or receiving a message.
         *
         * @return the message timeout in nanoseconds to wait for sending or receiving a message.
         * @see Configuration#MESSAGE_TIMEOUT_PROP_NAME
         */
        public long messageTimeoutNs()
        {
            return messageTimeoutNs;
        }

        /**
         * Get the channel URI on which the recording events publication will publish.
         *
         * @return the channel URI on which the recording events publication will publish.
         */
        public String recordingEventsChannel()
        {
            return recordingEventsChannel;
        }

        /**
         * Set the channel URI on which the recording events publication will publish.
         * <p>
         * To support dynamic subscribers then this can be set to multicast or MDC (Multi-Destination-Cast) if
         * multicast cannot be supported for on the available the network infrastructure.
         *
         * @param recordingEventsChannel channel URI on which the recording events publication will publish.
         * @return this for a fluent API.
         * @see io.aeron.CommonContext#MDC_CONTROL_PARAM_NAME
         */
        public Context recordingEventsChannel(final String recordingEventsChannel)
        {
            this.recordingEventsChannel = recordingEventsChannel;
            return this;
        }

        /**
         * Get the stream id on which the recording events publication will publish.
         *
         * @return the stream id on which the recording events publication will publish.
         */
        public int recordingEventsStreamId()
        {
            return recordingEventsStreamId;
        }

        /**
         * Set the stream id on which the recording events publication will publish.
         *
         * @param recordingEventsStreamId stream id on which the recording events publication will publish.
         * @return this for a fluent API.
         */
        public Context recordingEventsStreamId(final int recordingEventsStreamId)
        {
            this.recordingEventsStreamId = recordingEventsStreamId;
            return this;
        }

        /**
         * Set the channel parameter for the control request channel.
         *
         * @param channel parameter for the control request channel.
         * @return this for a fluent API.
         * @see Configuration#CONTROL_CHANNEL_PROP_NAME
         */
        public Context controlRequestChannel(final String channel)
        {
            controlRequestChannel = channel;
            return this;
        }

        /**
         * Get the channel parameter for the control request channel.
         *
         * @return the channel parameter for the control request channel.
         * @see Configuration#CONTROL_CHANNEL_PROP_NAME
         */
        public String controlRequestChannel()
        {
            return controlRequestChannel;
        }

        /**
         * Set the stream id for the control request channel.
         *
         * @param streamId for the control request channel.
         * @return this for a fluent API
         * @see Configuration#CONTROL_STREAM_ID_PROP_NAME
         */
        public Context controlRequestStreamId(final int streamId)
        {
            controlRequestStreamId = streamId;
            return this;
        }

        /**
         * Get the stream id for the control request channel.
         *
         * @return the stream id for the control request channel.
         * @see Configuration#CONTROL_STREAM_ID_PROP_NAME
         */
        public int controlRequestStreamId()
        {
            return controlRequestStreamId;
        }

        /**
         * Set the channel parameter for the control response channel.
         *
         * @param channel parameter for the control response channel.
         * @return this for a fluent API.
         * @see Configuration#CONTROL_RESPONSE_CHANNEL_PROP_NAME
         */
        public Context controlResponseChannel(final String channel)
        {
            controlResponseChannel = channel;
            return this;
        }

        /**
         * Get the channel parameter for the control response channel.
         *
         * @return the channel parameter for the control response channel.
         * @see Configuration#CONTROL_RESPONSE_CHANNEL_PROP_NAME
         */
        public String controlResponseChannel()
        {
            return controlResponseChannel;
        }

        /**
         * Set the stream id for the control response channel.
         *
         * @param streamId for the control response channel.
         * @return this for a fluent API
         * @see Configuration#CONTROL_RESPONSE_STREAM_ID_PROP_NAME
         */
        public Context controlResponseStreamId(final int streamId)
        {
            controlResponseStreamId = streamId;
            return this;
        }

        /**
         * Get the stream id for the control response channel.
         *
         * @return the stream id for the control response channel.
         * @see Configuration#CONTROL_RESPONSE_STREAM_ID_PROP_NAME
         */
        public int controlResponseStreamId()
        {
            return controlResponseStreamId;
        }

        /**
         * Should the control streams use sparse file term buffers.
         *
         * @param controlTermBufferSparse for the control stream.
         * @return this for a fluent API.
         * @see Configuration#CONTROL_TERM_BUFFER_SPARSE_PROP_NAME
         */
        public Context controlTermBufferSparse(final boolean controlTermBufferSparse)
        {
            this.controlTermBufferSparse = controlTermBufferSparse;
            return this;
        }

        /**
         * Should the control streams use sparse file term buffers.
         *
         * @return true if the control stream should use sparse file term buffers.
         * @see Configuration#CONTROL_TERM_BUFFER_SPARSE_PROP_NAME
         */
        public boolean controlTermBufferSparse()
        {
            return controlTermBufferSparse;
        }

        /**
         * Set the term buffer length for the control streams.
         *
         * @param controlTermBufferLength for the control streams.
         * @return this for a fluent API.
         * @see Configuration#CONTROL_TERM_BUFFER_LENGTH_PROP_NAME
         */
        public Context controlTermBufferLength(final int controlTermBufferLength)
        {
            this.controlTermBufferLength = controlTermBufferLength;
            return this;
        }

        /**
         * Get the term buffer length for the control streams.
         *
         * @return the term buffer length for the control streams.
         * @see Configuration#CONTROL_TERM_BUFFER_LENGTH_PROP_NAME
         */
        public int controlTermBufferLength()
        {
            return controlTermBufferLength;
        }

        /**
         * Set the MTU length for the control streams.
         *
         * @param controlMtuLength for the control streams.
         * @return this for a fluent API.
         * @see Configuration#CONTROL_MTU_LENGTH_PROP_NAME
         */
        public Context controlMtuLength(final int controlMtuLength)
        {
            this.controlMtuLength = controlMtuLength;
            return this;
        }

        /**
         * Get the MTU length for the control streams.
         *
         * @return the MTU length for the control streams.
         * @see Configuration#CONTROL_MTU_LENGTH_PROP_NAME
         */
        public int controlMtuLength()
        {
            return controlMtuLength;
        }

        /**
         * Set the {@link IdleStrategy} used when waiting for responses.
         *
         * @param idleStrategy used when waiting for responses.
         * @return this for a fluent API.
         */
        public Context idleStrategy(final IdleStrategy idleStrategy)
        {
            this.idleStrategy = idleStrategy;
            return this;
        }

        /**
         * Get the {@link IdleStrategy} used when waiting for responses.
         *
         * @return the {@link IdleStrategy} used when waiting for responses.
         */
        public IdleStrategy idleStrategy()
        {
            return idleStrategy;
        }

        /**
         * Set the top level Aeron directory used for communication between the Aeron client and Media Driver.
         *
         * @param aeronDirectoryName the top level Aeron directory.
         * @return this for a fluent API.
         */
        public Context aeronDirectoryName(final String aeronDirectoryName)
        {
            this.aeronDirectoryName = aeronDirectoryName;
            return this;
        }

        /**
         * Get the top level Aeron directory used for communication between the Aeron client and Media Driver.
         *
         * @return The top level Aeron directory.
         */
        public String aeronDirectoryName()
        {
            return aeronDirectoryName;
        }

        /**
         * {@link Aeron} client for communicating with the local Media Driver.
         * <p>
         * This client will be closed when the {@link AeronArchive#close()} or {@link #close()} methods are called if
         * {@link #ownsAeronClient()} is true.
         *
         * @param aeron client for communicating with the local Media Driver.
         * @return this for a fluent API.
         * @see Aeron#connect()
         */
        public Context aeron(final Aeron aeron)
        {
            this.aeron = aeron;
            return this;
        }

        /**
         * {@link Aeron} client for communicating with the local Media Driver.
         * <p>
         * If not provided then a default will be established during {@link #conclude()} by calling
         * {@link Aeron#connect()}.
         *
         * @return client for communicating with the local Media Driver.
         */
        public Aeron aeron()
        {
            return aeron;
        }

        /**
         * Does this context own the {@link #aeron()} client and thus takes responsibility for closing it?
         *
         * @param ownsAeronClient does this context own the {@link #aeron()} client?
         * @return this for a fluent API.
         */
        public Context ownsAeronClient(final boolean ownsAeronClient)
        {
            this.ownsAeronClient = ownsAeronClient;
            return this;
        }

        /**
         * Does this context own the {@link #aeron()} client and thus takes responsibility for closing it?
         *
         * @return does this context own the {@link #aeron()} client and thus takes responsibility for closing it?
         */
        public boolean ownsAeronClient()
        {
            return ownsAeronClient;
        }

        /**
         * The {@link Lock} that is used to provide mutual exclusion in the {@link AeronArchive} client.
         * <p>
         * If the {@link AeronArchive} is used from only a single thread then the lock can be set to
         * {@link NoOpLock} to elide the lock overhead.
         *
         * @param lock that is used to provide mutual exclusion in the {@link AeronArchive} client.
         * @return this for a fluent API.
         */
        public Context lock(final Lock lock)
        {
            this.lock = lock;
            return this;
        }

        /**
         * Get the {@link Lock} that is used to provide mutual exclusion in the {@link AeronArchive} client.
         *
         * @return the {@link Lock} that is used to provide mutual exclusion in the {@link AeronArchive} client.
         */
        public Lock lock()
        {
            return lock;
        }

        /**
         * Handle errors returned asynchronously from the archive for a control session.
         *
         * @param errorHandler method to handle objects of type Throwable.
         * @return this for a fluent API.
         */
        public Context errorHandler(final ErrorHandler errorHandler)
        {
            this.errorHandler = errorHandler;
            return this;
        }

        /**
         * Get the error handler that will be called for asynchronous errors.
         *
         * @return the error handler that will be called for asynchronous errors.
         */
        public ErrorHandler errorHandler()
        {
            return errorHandler;
        }

        /**
         * Close the context and free applicable resources.
         * <p>
         * If {@link #ownsAeronClient()} is true then the {@link #aeron()} client will be closed.
         */
        public void close()
        {
            if (ownsAeronClient)
            {
                CloseHelper.close(aeron);
            }
        }
    }

    /**
     * Allows for the async establishment of a archive session.
     */
    public static class AsyncConnect implements AutoCloseable
    {
        private final Context ctx;
        private final ControlResponsePoller controlResponsePoller;
        private final ArchiveProxy archiveProxy;
        private final NanoClock nanoClock;
        private final long deadlineNs;
        private long correlationId = Aeron.NULL_VALUE;
        private int step = 0;

        AsyncConnect(
            final Context ctx,
            final ControlResponsePoller controlResponsePoller,
            final ArchiveProxy archiveProxy,
            final long deadlineNs)
        {
            this.ctx = ctx;
            this.controlResponsePoller = controlResponsePoller;
            this.archiveProxy = archiveProxy;
            this.nanoClock = ctx.aeron().context().nanoClock();
            this.deadlineNs = deadlineNs;
        }

        /**
         * Close any allocated resources.
         */
        public void close()
        {
            if (5 != step)
            {
                CloseHelper.close(controlResponsePoller.subscription());
                CloseHelper.close(archiveProxy.publication());
                ctx.close();
            }
        }

        /**
         * Poll for a complete connection.
         *
         * @return a new {@link AeronArchive} if successfully connected otherwise null.
         */
        public AeronArchive poll()
        {
            checkDeadline();
            AeronArchive aeronArchive = null;

            if (0 == step)
            {
                if (!archiveProxy.publication().isConnected())
                {
                    return null;
                }

                step(1);
            }

            if (1 == step)
            {
                correlationId = ctx.aeron().nextCorrelationId();

                step(2);
            }

            if (2 == step)
            {
                if (!archiveProxy.tryConnect(
                    ctx.controlResponseChannel(), ctx.controlResponseStreamId(), correlationId))
                {
                    return null;
                }

                step(3);
            }

            if (3 == step)
            {
                if (!controlResponsePoller.subscription().isConnected())
                {
                    return null;
                }

                step(4);
            }

            controlResponsePoller.poll();

            if (controlResponsePoller.isPollComplete() &&
                controlResponsePoller.correlationId() == correlationId &&
                controlResponsePoller.templateId() == ControlResponseDecoder.TEMPLATE_ID)
            {
                final ControlResponseCode code = controlResponsePoller.code();
                if (code != ControlResponseCode.OK)
                {
                    if (code == ControlResponseCode.ERROR)
                    {
                        throw new ArchiveException(
                            "error: " + controlResponsePoller.errorMessage(), (int)controlResponsePoller.relevantId());
                    }

                    throw new ArchiveException("unexpected response: code=" + code);
                }

                final long sessionId = controlResponsePoller.controlSessionId();
                final Subscription subscription = controlResponsePoller.subscription();
                final ErrorHandler errorHandler = ctx.errorHandler();

                aeronArchive = new AeronArchive(
                    ctx,
                    controlResponsePoller,
                    archiveProxy,
                    new RecordingDescriptorPoller(subscription, errorHandler, sessionId, FRAGMENT_LIMIT),
                    new RecordingSubscriptionDescriptorPoller(subscription, errorHandler, sessionId, FRAGMENT_LIMIT),
                    sessionId);

                step(5);
            }

            return aeronArchive;
        }

        private void step(final int step)
        {
            //System.out.println(this.step + " -> " + step);
            this.step = step;
        }

        private void checkDeadline()
        {
            if (Thread.interrupted())
            {
                LangUtil.rethrowUnchecked(new InterruptedException());
            }

            if (deadlineNs - nanoClock.nanoTime() < 0)
            {
                throw new TimeoutException("connect timeout for correlation id: " + correlationId + " step " + step);
            }
        }
    }
}
