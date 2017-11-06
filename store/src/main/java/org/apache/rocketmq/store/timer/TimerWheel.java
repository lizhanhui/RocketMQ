/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store.timer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.store.MappedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TimerWheel {

    private static final Logger log = LoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    public static final int BLANK = -1, IGNORE = -2;
    public final int ttlSecs;
    private String fileName;
    private final RandomAccessFile randomAccessFile;
    private final FileChannel fileChannel;
    private final MappedByteBuffer mappedByteBuffer;
    private final ByteBuffer byteBuffer;
    private final ThreadLocal<ByteBuffer> localBuffer = new ThreadLocal<ByteBuffer>() {
        @Override
        protected ByteBuffer initialValue() {
            return byteBuffer.duplicate();
        }
    };
    private final int wheelLength;

    public TimerWheel(String fileName, int ttlSecs) throws IOException {
        this.ttlSecs = ttlSecs;
        this.fileName = fileName;
        this.wheelLength = this.ttlSecs * 2 * Slot.SIZE;
        File file = new File(fileName);
        MappedFile.ensureDirOK(file.getParent());

        try {
            randomAccessFile = new RandomAccessFile(this.fileName, "rw");
            if (file.exists() && randomAccessFile.length() != 0 &&
                randomAccessFile.length() != wheelLength) {
                throw new RuntimeException(String.format("Timer wheel length:%d != expected:%s",
                    randomAccessFile.length(), wheelLength));
            }
            randomAccessFile.setLength(this.ttlSecs * 2 * Slot.SIZE);
            fileChannel = randomAccessFile.getChannel();
            mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, wheelLength);
            assert wheelLength == mappedByteBuffer.remaining();
            this.byteBuffer = ByteBuffer.allocateDirect(wheelLength);
            this.byteBuffer.put(mappedByteBuffer);
        } catch (FileNotFoundException e) {
            log.error("create file channel " + this.fileName + " Failed. ", e);
            throw e;
        } catch (IOException e) {
            log.error("map file " + this.fileName + " Failed. ", e);
            throw e;
        }
    }

    public void shutdown() {
        shutdown(true);
    }

    public void shutdown(boolean flush) {
        if (flush)
            this.flush();

        // unmap mappedByteBuffer
        MappedFile.clean(this.mappedByteBuffer);

        try {
            this.fileChannel.close();
        } catch (IOException e) {
            log.error("Shutdown error in timer wheel", e);
        }
    }

    public void flush() {
        ByteBuffer bf = localBuffer.get();
        bf.position(0);
        bf.limit(wheelLength);
        mappedByteBuffer.position(0);
        mappedByteBuffer.limit(wheelLength);
        for (int i = 0; i < wheelLength; i++) {
            if (bf.get(i) != mappedByteBuffer.get(i)) {
                mappedByteBuffer.put(i, bf.get(i));
            }
        }
        this.mappedByteBuffer.force();
    }

    public Slot getSlot(long timeSecs) {
        Slot slot = getRawSlot(timeSecs);
        if (slot.timeSecs != timeSecs) {
            return new Slot(-1, -1, -1);
        }
        return slot;
    }

    //testable
    public Slot getRawSlot(long timeSecs) {
        int slotIndex = (int) (timeSecs % (ttlSecs * 2));
        localBuffer.get().position(slotIndex * Slot.SIZE);
        return new Slot(localBuffer.get().getLong(), localBuffer.get().getLong(), localBuffer.get().getLong());
    }

    public void putSlot(long timeSecs, long firstPos, long lastPos) {
        int slotIndex = (int) (timeSecs % (ttlSecs * 2));
        localBuffer.get().position(slotIndex * Slot.SIZE);
        localBuffer.get().putLong(timeSecs);
        localBuffer.get().putLong(firstPos);
        localBuffer.get().putLong(lastPos);
    }

    public void reviseSlot(long timeSecs, long firstPos, long lastPos, boolean force) {
        int slotIndex = (int) (timeSecs % (ttlSecs * 2));
        localBuffer.get().position(slotIndex * Slot.SIZE);

        if (timeSecs != localBuffer.get().getLong()) {
            if (force) {
                putSlot(timeSecs, firstPos != IGNORE ? firstPos : lastPos, lastPos);
            }
        } else {
            if (IGNORE != firstPos) {
                localBuffer.get().putLong(firstPos);
            } else {
                localBuffer.get().getLong();
            }
            if (IGNORE != lastPos) {
                localBuffer.get().putLong(lastPos);
            }
        }
    }

    public long checkPhyPos(long timeSecs, long maxOffset) {
        long minFirst = Long.MAX_VALUE;
        int slotIndex = (int) (timeSecs % (ttlSecs * 2));
        for (int i = 0; i < ttlSecs * 2; i++) {
            slotIndex = (slotIndex + i) % (ttlSecs * 2);
            localBuffer.get().position(slotIndex * Slot.SIZE);
            if ((timeSecs + i) != localBuffer.get().getLong()) {
                continue;
            }
            long first = localBuffer.get().getLong();
            if (localBuffer.get().getLong() > maxOffset) {
                if (first < minFirst) {
                    minFirst = first;
                }
            }
        }
        return minFirst;
    }
}
