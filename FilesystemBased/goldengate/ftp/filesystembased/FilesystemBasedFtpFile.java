/**
 * Copyright 2009, Frederic Bregier, and individual contributors
 * by the @author tags. See the COPYRIGHT.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 3.0 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package goldengate.ftp.filesystembased;

import goldengate.common.command.exception.CommandAbstractException;
import goldengate.common.exception.FileEndOfTransferException;
import goldengate.common.exception.FileTransferException;
import goldengate.common.file.DataBlock;
import goldengate.common.file.filesystembased.FilesystemBasedFileImpl;
import goldengate.common.logging.GgInternalLogger;
import goldengate.common.logging.GgInternalLoggerFactory;
import goldengate.ftp.core.command.FtpArgumentCode.TransferStructure;
import goldengate.ftp.core.exception.FtpNoConnectionException;
import goldengate.ftp.core.session.FtpSession;

import java.util.concurrent.locks.ReentrantLock;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.Channels;

/**
 * Filesystem implementation of a FileInterface
 * 
 * @author Frederic Bregier
 * 
 */
public abstract class FilesystemBasedFtpFile extends FilesystemBasedFileImpl {
    /**
     * Internal Logger
     */
    private static final GgInternalLogger logger = GgInternalLoggerFactory
            .getLogger(FilesystemBasedFtpFile.class);

    /**
     * Retrieve lock
     */
    private final ReentrantLock retrieveLock = new ReentrantLock();

    /**
     * @param session
     * @param dir
     *            It is not necessary the directory that owns this file.
     * @param path
     * @param append
     * @throws CommandAbstractException
     */
    public FilesystemBasedFtpFile(FtpSession session,
            FilesystemBasedFtpDir dir, String path, boolean append)
            throws CommandAbstractException {
        super(session, dir, path, append);
    }

    @Override
    public long length() throws CommandAbstractException {
        long length = super.length();
        if (((FtpSession) getSession()).getDataConn()
                .isFileStreamBlockAsciiImage()) {
            long block = (long) Math.ceil((double) length /
                    (double) getSession().getBlockSize());
            length += (block + 3) * 3;
        }
        return length;
    }

    @Override
    public void trueRetrieve() {
        retrieveLock.lock();
        try {
            if (!isReady) {
                return;
            }
            // First check if ready to run from Control
            try {
                ((FtpSession) session).getDataConn().getFtpTransferControl()
                        .waitForDataNetworkHandlerReady();
            } catch (InterruptedException e) {
                // bad thing
                logger.warn("DataNetworkHandler was not ready", e);
                return;
            }

            Channel channel = ((FtpSession) session).getDataConn()
                    .getCurrentDataChannel();
            if (((FtpSession) session).getDataConn().getStructure() == TransferStructure.FILE) {
                // FileInterface
                DataBlock block = null;
                try {
                    block = readDataBlock();
                } catch (FileEndOfTransferException e) {
                    // Last block (in fact, previous block was the last one,
                    // but it could be aligned with the block size so not
                    // detected)
                    closeFile();
                    ((FtpSession) session).getDataConn()
                            .getFtpTransferControl().setPreEndOfTransfer();
                    return;
                }
                if (block == null) {
                    // Last block (in fact, previous block was the last one,
                    // but it could be aligned with the block size so not
                    // detected)
                    closeFile();
                    ((FtpSession) session).getDataConn()
                            .getFtpTransferControl().setPreEndOfTransfer();
                    return;
                }
                // While not last block
                ChannelFuture future = null;
                while (block != null && !block.isEOF()) {
                    future = Channels.write(channel, block);
                    // Test if channel is writable in order to prevent OOM
                    if (channel.isWritable()) {
                        try {
                            block = readDataBlock();
                        } catch (FileEndOfTransferException e) {
                            closeFile();
                            // Wait for last write
                            future.awaitUninterruptibly();
                            ((FtpSession) session).getDataConn()
                                    .getFtpTransferControl()
                                    .setPreEndOfTransfer();
                            return;
                        }
                    } else {
                        return;// Wait for the next InterestChanged
                    }
                }
                // Last block
                closeFile();
                if (block != null) {
                    future = Channels.write(channel, block);
                }
                // Wait for last write
                if (future != null) {
                    future.awaitUninterruptibly();
                    ((FtpSession) session).getDataConn()
                            .getFtpTransferControl().setPreEndOfTransfer();
                }
            } else {
                // Record
                DataBlock block = null;
                try {
                    block = readDataBlock();
                } catch (FileEndOfTransferException e) {
                    // Last block
                    closeFile();
                    ((FtpSession) session).getDataConn()
                            .getFtpTransferControl().setPreEndOfTransfer();
                    return;
                }
                if (block == null) {
                    // Last block
                    closeFile();
                    ((FtpSession) session).getDataConn()
                            .getFtpTransferControl().setPreEndOfTransfer();
                    return;
                }
                // While not last block
                ChannelFuture future = null;
                while (block != null && !block.isEOF()) {
                    future = Channels.write(channel, block);
                    // Test if channel is writable in order to prevent OOM
                    if (channel.isWritable()) {
                        try {
                            block = readDataBlock();
                        } catch (FileEndOfTransferException e) {
                            // Last block
                            closeFile();
                            // Wait for last write
                            future.awaitUninterruptibly();
                            ((FtpSession) session).getDataConn()
                                    .getFtpTransferControl()
                                    .setPreEndOfTransfer();
                            return;
                        }
                    } else {
                        return;// Wait for the next InterestChanged
                    }
                }
                // Last block
                closeFile();
                if (block != null) {
                    future = Channels.write(channel, block);
                }
                // Wait for last write
                if (future != null) {
                    future.awaitUninterruptibly();
                    ((FtpSession) session).getDataConn()
                            .getFtpTransferControl().setPreEndOfTransfer();
                }
            }
        } catch (FileTransferException e) {
            // An error occurs!
            ((FtpSession) session).getDataConn().getFtpTransferControl()
                    .setTransferAbortedFromInternal(true);
        } catch (FtpNoConnectionException e) {
            logger.error("Should not be", e);
            ((FtpSession) session).getDataConn().getFtpTransferControl()
                    .setTransferAbortedFromInternal(true);
        } catch (CommandAbstractException e) {
            logger.error("Should not be", e);
            ((FtpSession) session).getDataConn().getFtpTransferControl()
                    .setTransferAbortedFromInternal(true);
        } finally {
            retrieveLock.unlock();
        }
    }
}
