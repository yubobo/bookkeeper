/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.bookkeeper.proto;

import static com.google.common.base.Charsets.UTF_8;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.net.BookieSocketAddress;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.GenericCallback;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.ReadEntryCallback;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.WriteCallback;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.bookkeeper.util.OrderedSafeExecutor;
import org.apache.bookkeeper.util.SafeRunnable;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
/**
 * Implements the client-side part of the BookKeeper protocol.
 *
 */
public class BookieClient {
    static final Logger LOG = LoggerFactory.getLogger(BookieClient.class);

    // This is global state that should be across all BookieClients
    final AtomicLong totalBytesOutstanding = new AtomicLong();

    final OrderedSafeExecutor executor;
    final ClientSocketChannelFactory channelFactory;
    final ConcurrentHashMap<BookieSocketAddress, PerChannelBookieClient> channels =
        new ConcurrentHashMap<BookieSocketAddress, PerChannelBookieClient>();
    final ScheduledExecutorService timeoutExecutor = Executors
            .newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
                    .setNameFormat("BKClient-TimeoutTaskExecutor-%d").build());
    private final ClientConfiguration conf;
    private volatile boolean closed;
    private final ReentrantReadWriteLock closeLock;
    private final StatsLogger statsLogger;

    public BookieClient(ClientConfiguration conf, ClientSocketChannelFactory channelFactory, OrderedSafeExecutor executor) {
        this(conf, channelFactory, executor, NullStatsLogger.INSTANCE);
    }

    public BookieClient(ClientConfiguration conf, ClientSocketChannelFactory channelFactory, OrderedSafeExecutor executor,
                        StatsLogger statsLogger) {
        this.conf = conf;
        this.channelFactory = channelFactory;
        this.executor = executor;
        this.closed = false;
        this.closeLock = new ReentrantReadWriteLock();
        this.statsLogger = statsLogger;
    }

    private int getRc(int rc) {
        if (BKException.Code.OK == rc) {
            return rc;
        } else {
            if (closed) {
                return BKException.Code.ClientClosedException;
            } else {
                return rc;
            }
        }
    }

    private PerChannelBookieClient lookupClient(BookieSocketAddress addr) {
        PerChannelBookieClient channel = channels.get(addr);

        if (channel == null) {
            closeLock.readLock().lock();
            try {
                if (closed) {
                    return null;
                }
                channel = new PerChannelBookieClient(conf, executor, channelFactory, addr, totalBytesOutstanding,
                        timeoutExecutor, statsLogger);
                PerChannelBookieClient prevChannel = channels.putIfAbsent(addr, channel);
                if (prevChannel != null) {
                    channel = prevChannel;
                }
            } finally {
                closeLock.readLock().unlock();
            }
        }

        return channel;
    }

    public void closeClients(Set<BookieSocketAddress> addrs) {
        closeLock.readLock().lock();
        try {
            final HashSet<PerChannelBookieClient> clients = new HashSet<PerChannelBookieClient>();
            for (BookieSocketAddress a : addrs) {
                PerChannelBookieClient c = channels.get(a);
                if (c != null) {
                    clients.add(c);
                }
            }

            if (clients.size() == 0) {
                return;
            }
            executor.submit(new SafeRunnable() {
                    @Override
                    public void safeRun() {
                        for (PerChannelBookieClient c : clients) {
                            c.disconnect();
                        }
                    }
                });
        } finally {
            closeLock.readLock().unlock();
        }
    }

    public void addEntry(final BookieSocketAddress addr, final long ledgerId, final byte[] masterKey,
            final long entryId,
            final ChannelBuffer toSend, final WriteCallback cb, final Object ctx, final int options) {
        closeLock.readLock().lock();
        try {
            final PerChannelBookieClient client = lookupClient(addr);
            if (client == null) {
                cb.writeComplete(getRc(BKException.Code.BookieHandleNotAvailableException),
                                 ledgerId, entryId, addr, ctx);
                return;
            }

            client.connectIfNeededAndDoOp(new GenericCallback<Void>() {
                @Override
                public void operationComplete(final int rc, Void result) {
                    if (rc != BKException.Code.OK) {
                        try {
                            executor.submitOrdered(ledgerId, new SafeRunnable() {
                                @Override
                                public void safeRun() {
                                    cb.writeComplete(rc, ledgerId, entryId, addr, ctx);
                                }
                            });
                        } catch (RejectedExecutionException re) {
                            cb.writeComplete(getRc(BKException.Code.InterruptedException),
                                    ledgerId, entryId, addr, ctx);
                        }
                        return;
                    }
                    client.addEntry(ledgerId, masterKey, entryId, toSend, cb, ctx, options);
                }
            });
        } finally {
            closeLock.readLock().unlock();
        }
    }

    public void readEntryAndFenceLedger(final BookieSocketAddress addr,
                                        final long ledgerId,
                                        final byte[] masterKey,
                                        final long entryId,
                                        final ReadEntryCallback cb,
                                        final Object ctx) {
        closeLock.readLock().lock();
        try {
            final PerChannelBookieClient client = lookupClient(addr);
            if (client == null) {
                cb.readEntryComplete(getRc(BKException.Code.BookieHandleNotAvailableException),
                                     ledgerId, entryId, null, ctx);
                return;
            }

            client.connectIfNeededAndDoOp(new GenericCallback<Void>() {
                @Override
                public void operationComplete(final int rc, Void result) {
                    if (rc != BKException.Code.OK) {
                        try {
                            executor.submitOrdered(ledgerId, new SafeRunnable() {
                                @Override
                                public void safeRun() {
                                    cb.readEntryComplete(rc, ledgerId, entryId, null, ctx);
                                }
                            });
                        } catch (RejectedExecutionException re) {
                            cb.readEntryComplete(getRc(BKException.Code.InterruptedException),
                                    ledgerId, entryId, null, ctx);
                        }
                        return;
                    }
                    client.readEntryAndFenceLedger(ledgerId, masterKey, entryId, cb, ctx);
                }
            });
        } finally {
            closeLock.readLock().unlock();
        }
    }

    public void readEntry(final BookieSocketAddress addr, final long ledgerId, final long entryId,
                          final ReadEntryCallback cb, final Object ctx) {
        closeLock.readLock().lock();
        try {
            final PerChannelBookieClient client = lookupClient(addr);
            if (client == null) {
                cb.readEntryComplete(getRc(BKException.Code.BookieHandleNotAvailableException),
                                     ledgerId, entryId, null, ctx);
                return;
            }

            client.connectIfNeededAndDoOp(new GenericCallback<Void>() {
                @Override
                public void operationComplete(final int rc, Void result) {
                    if (rc != BKException.Code.OK) {
                        try {
                            executor.submitOrdered(ledgerId, new SafeRunnable() {
                                @Override
                                public void safeRun() {
                                    cb.readEntryComplete(rc, ledgerId, entryId, null, ctx);
                                }
                            });
                        } catch (RejectedExecutionException re) {
                            cb.readEntryComplete(getRc(BKException.Code.InterruptedException),
                                    ledgerId, entryId, null, ctx);
                        }
                        return;
                    }
                    client.readEntry(ledgerId, entryId, cb, ctx);
                }
            });
        } finally {
            closeLock.readLock().unlock();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        closeLock.writeLock().lock();
        try {
            closed = true;
            for (PerChannelBookieClient channel: channels.values()) {
                channel.close();
            }
            channels.clear();
        } finally {
            closeLock.writeLock().unlock();
        }
        timeoutExecutor.shutdown();
        try {
            if (!timeoutExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("BKClient-TimeoutTaskExecutor did not shutdown cleanly!");
            }
        } catch (InterruptedException ie) {
            LOG.warn(
                    "Interrupted when shutting down BKClient-TimeoutTaskExecutor",
                    ie);
        }
    }

    private static class Counter {
        int i;
        int total;

        synchronized void inc() {
            i++;
            total++;
        }

        synchronized void dec() {
            i--;
            notifyAll();
        }

        synchronized void wait(int limit) throws InterruptedException {
            while (i > limit) {
                wait();
            }
        }

        synchronized int total() {
            return total;
        }
    }

    /**
     * @param args
     * @throws IOException
     * @throws NumberFormatException
     * @throws InterruptedException
     */
    public static void main(String[] args) throws NumberFormatException, IOException, InterruptedException {
        if (args.length != 3) {
            System.err.println("USAGE: BookieClient bookieHost port ledger#");
            return;
        }
        WriteCallback cb = new WriteCallback() {

            public void writeComplete(int rc, long ledger, long entry, BookieSocketAddress addr, Object ctx) {
                Counter counter = (Counter) ctx;
                counter.dec();
                if (rc != 0) {
                    System.out.println("rc = " + rc + " for " + entry + "@" + ledger);
                }
            }
        };
        Counter counter = new Counter();
        byte hello[] = "hello".getBytes(UTF_8);
        long ledger = Long.parseLong(args[2]);
        ThreadFactoryBuilder tfb = new ThreadFactoryBuilder();
        ClientSocketChannelFactory channelFactory = new NioClientSocketChannelFactory(
                Executors.newCachedThreadPool(tfb.setNameFormat(
                        "BookKeeper-NIOBoss-%d").build()),
                Executors.newCachedThreadPool(tfb.setNameFormat(
                        "BookKeeper-NIOWorker-%d").build()));
        OrderedSafeExecutor executor = new OrderedSafeExecutor(1,
                "BookieClientWorker");
        BookieClient bc = new BookieClient(new ClientConfiguration(), channelFactory, executor);
        BookieSocketAddress addr = new BookieSocketAddress(args[0], Integer.parseInt(args[1]));

        for (int i = 0; i < 100000; i++) {
            counter.inc();
            bc.addEntry(addr, ledger, new byte[0], i, ChannelBuffers.wrappedBuffer(hello), cb, counter, 0);
        }
        counter.wait(0);
        System.out.println("Total = " + counter.total());
        channelFactory.releaseExternalResources();
        executor.shutdown();
    }
}
