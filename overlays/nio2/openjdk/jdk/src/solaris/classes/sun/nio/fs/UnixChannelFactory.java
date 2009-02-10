/*
 * Copyright 2007-2008 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

package sun.nio.fs;

import java.io.FileDescriptor;
import java.util.Set;

import sun.nio.ch.FileChannelImpl;
import sun.nio.ch.ThreadPool;
import sun.nio.ch.SimpleAsynchronousFileChannelImpl;
import sun.misc.SharedSecrets;
import sun.misc.JavaIOFileDescriptorAccess;

import org.classpath.icedtea.java.nio.channels.AsynchronousFileChannel;
import org.classpath.icedtea.java.nio.channels.FileChannel;

import org.classpath.icedtea.java.nio.file.LinkOption;
import org.classpath.icedtea.java.nio.file.OpenOption;
import org.classpath.icedtea.java.nio.file.StandardOpenOption;

import static sun.nio.fs.UnixNativeDispatcher.*;
import static sun.nio.fs.UnixConstants.*;

/**
 * Factory for FileChannels and AsynchronousFileChannels
 */

class UnixChannelFactory {
    private static final JavaIOFileDescriptorAccess fdAccess =
        SharedSecrets.getJavaIOFileDescriptorAccess();

    private UnixChannelFactory() {
    }

    /**
     * Represents the flags from a user-supplied set of open options.
     */
    private static class Flags {
        boolean read;
        boolean write;
        boolean append;
        boolean truncateExisting;
        boolean noFollowLinks;
        boolean create;
        boolean createNew;
        boolean deleteOnClose;
        boolean sync;
        boolean dsync;

        static Flags toFlags(Set<? extends OpenOption> options) {
            Flags flags = new Flags();
            for (OpenOption option: options) {
                if (!(option instanceof StandardOpenOption)) {
                    if (option == LinkOption.NOFOLLOW_LINKS) {
                        flags.noFollowLinks = true;
                        continue;
                    }
                    if (option == null)
                        throw new NullPointerException();
                    throw new UnsupportedOperationException("Unsupported open option");
                }
                switch ((StandardOpenOption)option) {
                    case READ : flags.read = true; break;
                    case WRITE : flags.write = true; break;
                    case APPEND : flags.append = true; break;
                    case TRUNCATE_EXISTING : flags.truncateExisting = true; break;
                    case CREATE : flags.create = true; break;
                    case CREATE_NEW : flags.createNew = true; break;
                    case DELETE_ON_CLOSE : flags.deleteOnClose = true; break;
                    case SPARSE : /* ignore */ break;
                    case SYNC : flags.sync = true; break;
                    case DSYNC : flags.dsync = true; break;
                    default: throw new AssertionError("Should not get here");
                }
            }
            return flags;
        }
    }


    /**
     * Constructs a file channel from an existing (open) file descriptor
     */
    static FileChannel newFileChannel(int fd, boolean reading, boolean writing) {
        FileDescriptor fdObj = new FileDescriptor();
        fdAccess.set(fdObj, fd);
        return FileChannelImpl.open(fdObj, reading, writing, null);
    }

    /**
     * Constructs a file channel by opening a file using a dfd/path pair
     */
    static FileChannel newFileChannel(int dfd,
                                      UnixPath path,
                                      String pathForPermissionCheck,
                                      Set<? extends OpenOption> options,
                                      int mode)
        throws UnixException
    {
        Flags flags = Flags.toFlags(options);

        // default is reading; append => writing
        if (!flags.read && !flags.write) {
            if (flags.append) {
                flags.write = true;
            } else {
                flags.read = true;
            }
        }

        // validation
        if (flags.read && flags.append)
            throw new IllegalArgumentException("READ + APPEND not allowed");
        if (flags.append && flags.truncateExisting)
            throw new IllegalArgumentException("APPEND + TRUNCATE_EXISTING not allowed");

        FileDescriptor fdObj = open(dfd, path, pathForPermissionCheck, flags, mode);
        return FileChannelImpl.open(fdObj, flags.read, flags.write, null);
    }

    /**
     * Constructs a file channel by opening the given file.
     */
    static FileChannel newFileChannel(UnixPath path,
                                      Set<? extends OpenOption> options,
                                      int mode)
        throws UnixException
    {
        return newFileChannel(-1, path, null, options, mode);
    }

    /**
     * Constructs an asynchronous file channel by opening the given file.
     */
    static AsynchronousFileChannel newAsynchronousFileChannel(UnixPath path,
                                                              Set<? extends OpenOption> options,
                                                              int mode,
                                                              ThreadPool pool)
        throws UnixException
    {
        Flags flags = Flags.toFlags(options);

        // default is reading
        if (!flags.read && !flags.write) {
            flags.read = true;
        }

        // validation
        if (flags.append)
            throw new UnsupportedOperationException("APPEND not allowed");

        // for now assume that direct/raw I/O is not supported so return
        // "portable" AsynchronousFileChannel based on underlying file channel
        FileDescriptor fdObj = open(-1, path, null, flags, mode);
        return SimpleAsynchronousFileChannelImpl.open(fdObj, flags.read, flags.write, pool);
    }

    /**
     * Opens file based on parameters and options, returning a FileDescriptor
     * encapsulating the handle to the open file.
     */
    static FileDescriptor open(int dfd,
                               UnixPath path,
                               String pathForPermissionCheck,
                               Flags flags,
                               int mode)
        throws UnixException
    {
        // map to oflags
        int oflags;
        if (flags.read && flags.write) {
            oflags = O_RDWR;
        } else {
            oflags = (flags.write) ? O_WRONLY : O_RDONLY;
        }
        if (flags.write) {
            if (flags.truncateExisting)
                oflags |= O_TRUNC;
            if (flags.append)
                oflags |= O_APPEND;

            // create flags
            if (flags.createNew) {
                byte[] pathForSysCall = path.asByteArray();

                // throw exception if file name is "." to avoid confusing error
                if ((pathForSysCall[pathForSysCall.length-1] == '.') &&
                    (pathForSysCall.length == 1 ||
                    (pathForSysCall[pathForSysCall.length-2] == '/')))
                {
                    throw new UnixException(EEXIST);
                }
                oflags |= (O_CREAT | O_EXCL);
            } else {
                if (flags.create)
                    oflags |= O_CREAT;
            }
        }

        // follow links by default
        boolean followLinks = true;
        if (!flags.createNew && (flags.noFollowLinks || flags.deleteOnClose)) {
            followLinks = false;
            oflags |= O_NOFOLLOW;
        }

        if (flags.dsync)
            oflags |= O_DSYNC;
        if (flags.sync)
            oflags |= O_SYNC;

        // permission check before we open the file
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            if (pathForPermissionCheck == null)
                pathForPermissionCheck = path.getPathForPermissionCheck();
            if (flags.read)
                sm.checkRead(pathForPermissionCheck);
            if (flags.write)
                sm.checkWrite(pathForPermissionCheck);
            if (flags.deleteOnClose)
                sm.checkDelete(pathForPermissionCheck);
        }

        int fd;
        try {
            if (dfd >= 0) {
                fd = openat(dfd, path.asByteArray(), oflags, mode);
            } else {
                fd = UnixNativeDispatcher.open(path, oflags, mode);
            }
        } catch (UnixException x) {
            // Linux error can be EISDIR or EEXIST when file exists
            if (flags.createNew && (x.errno() == EISDIR)) {
                x.setError(EEXIST);
            }

            // handle ELOOP to avoid confusing message
            if (!followLinks && (x.errno() == ELOOP)) {
                x = new UnixException(x.getMessage() + " (NOFOLLOW_LINKS specified)");
            }

            throw x;
        }

        // unlink file immediately if delete on close. The spec is clear that
        // an implementation cannot guarantee to unlink the correct file when
        // replaced by an attacker after it is opened.
        if (flags.deleteOnClose) {
            try {
                if (dfd >= 0) {
                    unlinkat(dfd, path.asByteArray(), 0);
                } else {
                    unlink(path);
                }
            } catch (UnixException ignore) {
                // best-effort
            }
        }

        // create java.io.FileDescriptor
        FileDescriptor fdObj = new FileDescriptor();
        fdAccess.set(fdObj, fd);
        return fdObj;
    }
}
