/**
 * Copyright 2016-2017 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.k3po.nukleus.ext.internal.behavior;

import static org.reaktivity.k3po.nukleus.ext.internal.behavior.NullChannelBuffer.NULL_BUFFER;

import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferFactory;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelSink;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.kaazing.k3po.driver.internal.netty.bootstrap.channel.AbstractChannel;
import org.kaazing.k3po.driver.internal.netty.channel.ChannelAddress;
import org.reaktivity.k3po.nukleus.ext.internal.behavior.types.ListFW;
import org.reaktivity.k3po.nukleus.ext.internal.behavior.types.stream.RegionFW;

public abstract class NukleusChannel extends AbstractChannel<NukleusChannelConfig>
{
    static final ChannelBufferFactory NATIVE_BUFFER_FACTORY = NukleusByteOrder.NATIVE.toBufferFactory();

    private final AtomicBoolean closing;

    private long sourceId;
    private long sourceAuth;
    private long targetId;
    private long targetAuth;

    final NukleusReaktor reaktor;
    final Deque<MessageEvent> writeRequests;

    long transferAddressBase;
    final MutableDirectBuffer writeBuffer;

    private NukleusExtensionKind readExtKind;
    private ChannelBuffer readExtBuffer;

    private NukleusExtensionKind writeExtKind;
    private ChannelBuffer writeExtBuffer;

    private ChannelFuture beginOutputFuture;
    private ChannelFuture beginInputFuture;

    private long ackIndex;
    private long transferIndex;
    private int ackCount;

    private long ackIndexHighMark;
    private int ackIndexProgress;

    private int transferCapacity;
    private final long capacityMask;

    NukleusChannel(
        NukleusServerChannel parent,
        ChannelFactory factory,
        ChannelPipeline pipeline,
        ChannelSink sink,
        NukleusReaktor reaktor)
    {
        super(parent, factory, pipeline, sink, new DefaultNukleusChannelConfig());

        this.reaktor = reaktor;
        this.writeRequests = new LinkedList<>();
        this.targetId = ((long) getId()) | 0x8000000000000000L;

        this.transferCapacity = 32 * 1024; // TODO: configurable
        this.transferIndex = 0L; // TODO: configurable
        this.ackIndex = 0L; // TODO: configurable
        this.capacityMask = transferCapacity - 1;
        this.transferAddressBase = -1L;
        this.writeBuffer = new UnsafeBuffer(new byte[0]);

        this.closing = new AtomicBoolean();
    }

    public void acquireWriteMemory()
    {
        if (transferAddressBase == -1L)
        {
            final long address = reaktor.acquire(transferCapacity);
            if (address == -1L)
            {
                throw new IllegalStateException("Unable to allocate memory block: " + transferCapacity);
            }
            this.transferAddressBase = address;
            this.writeBuffer.wrap(reaktor.resolve(address), transferCapacity);
        }
    }

    public void releaseWriteMemory()
    {
        if (transferAddressBase != -1L)
        {
            reaktor.release(transferAddressBase, transferCapacity);
            transferAddressBase = -1L;
        }
    }

    @Override
    public NukleusChannelAddress getLocalAddress()
    {
        return (NukleusChannelAddress) super.getLocalAddress();
    }

    @Override
    public NukleusChannelAddress getRemoteAddress()
    {
        return (NukleusChannelAddress) super.getRemoteAddress();
    }

    @Override
    protected void setBound()
    {
        super.setBound();
    }

    @Override
    protected void setConnected()
    {
        super.setConnected();
    }

    public boolean isClosing()
    {
        return closing.get();
    }

    public boolean setClosing()
    {
        return closing.compareAndSet(false, true);
    }

    @Override
    protected boolean isReadClosed()
    {
        return super.isReadClosed();
    }

    @Override
    protected boolean isWriteClosed()
    {
        return super.isWriteClosed();
    }

    @Override
    protected boolean setReadClosed()
    {
        return super.setReadClosed();
    }

    @Override
    protected boolean setWriteClosed()
    {
        releaseWriteMemory();
        return super.setWriteClosed();
    }

    @Override
    protected boolean setReadAborted()
    {
        return super.setReadAborted();
    }

    @Override
    protected boolean setWriteAborted()
    {
        releaseWriteMemory();
        return super.setWriteAborted();
    }

    @Override
    protected boolean setClosed()
    {
        releaseWriteMemory();
        return super.setClosed();
    }

    @Override
    protected void setRemoteAddress(ChannelAddress remoteAddress)
    {
        super.setRemoteAddress(remoteAddress);
    }

    @Override
    protected void setLocalAddress(ChannelAddress localAddress)
    {
        super.setLocalAddress(localAddress);
    }

    @Override
    public String toString()
    {
        ChannelAddress localAddress = this.getLocalAddress();
        String description = localAddress != null ? localAddress.toString() : super.toString();
        return String.format("%s [sourceId=%d, targetId=%d]", description, sourceId, targetId);
    }

    public void sourceId(
        long sourceId)
    {
        this.sourceId = sourceId;
    }

    public long sourceId()
    {
        return sourceId;
    }

    public long targetId()
    {
        return targetId;
    }

    public void sourceAuth(
        long sourceAuth)
    {
        this.sourceAuth = sourceAuth;
    }

    public long sourceAuth()
    {
        return sourceAuth;
    }

    public void targetAuth(
        long targetAuth)
    {
        this.targetAuth = targetAuth;
    }

    public long targetAuth()
    {
        return targetAuth;
    }

    public ChannelFuture beginOutputFuture()
    {
        if (beginOutputFuture == null)
        {
            beginOutputFuture = Channels.future(this);
        }

        return beginOutputFuture;
    }

    public ChannelFuture beginInputFuture()
    {
        if (beginInputFuture == null)
        {
            beginInputFuture = Channels.future(this);
        }

        return beginInputFuture;
    }

    public ChannelBuffer writeExtBuffer(
        NukleusExtensionKind writeExtKind,
        boolean readonly)
    {
        if (this.writeExtKind != writeExtKind)
        {
            if (readonly)
            {
                return ChannelBuffers.EMPTY_BUFFER;
            }
            else
            {
                if (writeExtBuffer == null)
                {
                    writeExtBuffer = getConfig().getBufferFactory().getBuffer(8192);
                }
                else
                {
                    writeExtBuffer.clear();
                }
                this.writeExtKind = writeExtKind;
            }
        }

        return writeExtBuffer;
    }

    public ChannelBuffer readExtBuffer(
        NukleusExtensionKind readExtKind)
    {
        if (this.readExtKind != readExtKind)
        {
            if (readExtBuffer == null)
            {
                readExtBuffer = getConfig().getBufferFactory().getBuffer(8192);
            }
            else
            {
                readExtBuffer.clear();
            }
            this.readExtKind = readExtKind;
        }

        return readExtBuffer;
    }

    public void acknowledge(
        ListFW<RegionFW> regions)
    {
        ackCount++;

        regions.forEach(this::acknowledge);
    }

    public boolean hasAcknowledged()
    {
        return ackCount != 0;
    }

    public int writableBytes()
    {
        return transferCapacity - (int)(transferIndex - ackIndex);
    }

    public void flushBytes(
        ListFW.Builder<RegionFW.Builder, RegionFW> regions,
        ChannelBuffer writeBuf,
        int writableBytes,
        long streamId)
    {
        if (writeBuf != NULL_BUFFER)
        {
            final ByteBuffer byteBuffer = writeBuf.toByteBuffer();
            final int transferOffset = (int) (transferIndex & capacityMask);
            final int transferLimit = transferOffset + writableBytes;

            if (transferLimit <= transferCapacity)
            {
                writeBuffer.putBytes(transferOffset, byteBuffer, writableBytes);

                regions.item(r -> r.address(transferAddressBase + transferOffset)
                                   .length(writableBytes)
                                   .streamId(streamId));
            }
            else
            {
                int writableBytes0 = transferCapacity - transferOffset;
                int writableBytes1 = writableBytes - writableBytes0;

                writeBuffer.putBytes(transferOffset, byteBuffer, writableBytes0);
                writeBuffer.putBytes(0, byteBuffer, writableBytes1);

                regions.item(r -> r.address(transferAddressBase + transferOffset)
                                   .length(writableBytes0)
                                   .streamId(streamId));
                regions.item(r -> r.address(transferAddressBase)
                                   .length(writableBytes1)
                                   .streamId(streamId));
            }

            transferIndex += writableBytes;
            writeBuf.skipBytes(writableBytes);
        }
    }

    private void acknowledge(
        RegionFW region)
    {
        final long address = region.address();
        final int length = region.length();

        final int ackOffset = (int) (ackIndex & capacityMask);
        final long epochIndex = (address >= ackOffset ? ackIndex : ackIndex + transferCapacity) & ~capacityMask;
        final long regionIndex = epochIndex | (address & capacityMask);
        assert regionIndex >= ackIndex;
        assert regionIndex <= transferIndex;

        ackIndexHighMark = Math.max(ackIndexHighMark, regionIndex + length);
        ackIndexProgress += length;

        final long ackIndexCandidate = ackIndex + ackIndexProgress;
        assert ackIndexCandidate <= ackIndexHighMark;

        if (ackIndexCandidate == ackIndexHighMark)
        {
            ackIndex = ackIndexCandidate;
            ackIndexHighMark = ackIndexCandidate;
            ackIndexProgress = 0;
        }
    }
}
