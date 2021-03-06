/*
 * Copyright 2013 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.collections;

import net.openhft.lang.io.MappedStore;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Arrays;

public class SharedHashMapBuilder implements Cloneable {
    static final int HEADER_SIZE = 128;
    static final int SEGMENT_HEADER = 64;
    private static final byte[] MAGIC = "SharedHM".getBytes();
    private int segments = 128;
    private int entrySize = 128;
    int entriesPerSegment = 8 << 10;
    private int replicas = 0;
    private boolean transactional = false;

    public SharedHashMapBuilder segments(int segments) {
        this.segments = segments;
        return this;
    }

    @Override
    public SharedHashMapBuilder clone() {
        try {
            return (SharedHashMapBuilder) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    public int segments() {
        return segments;
    }

    public SharedHashMapBuilder entrySize(int entrySize) {
        this.entrySize = entrySize;
        return this;
    }

    public int entrySize() {
        return entrySize;
    }

    public SharedHashMapBuilder entries(long entries) {
        this.entriesPerSegment = (int) ((entries + segments - 1) / segments);
        return this;
    }

    public long entries() {
        return (long) entriesPerSegment * segments;
    }

    public SharedHashMapBuilder replicas(int replicas) {
        this.replicas = replicas;
        return this;
    }

    public int replicas() {
        return replicas;
    }

    public SharedHashMapBuilder transactional(boolean transactional) {
        this.transactional = transactional;
        return this;
    }

    public boolean transactional() {
        return transactional;
    }

    public SharedHashMap create(File file) throws IOException {
        SharedHashMapBuilder builder = null;
        for (int i = 0; i < 10; i++) {
            if (file.exists()) {
                builder = readFile(file);
                break;
            }
            if (file.createNewFile()) {
                newFile(file);
                builder = clone();
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }
        if (builder == null || !file.exists())
            throw new FileNotFoundException("Unable to create " + file);
        MappedStore ms = new MappedStore(file, FileChannel.MapMode.READ_WRITE, size());
        return new SharedHashMap(builder, file, ms);
    }

    private SharedHashMapBuilder readFile(File file) throws IOException {
        ByteBuffer bb = ByteBuffer.allocateDirect(HEADER_SIZE).order(ByteOrder.nativeOrder());
        FileInputStream fis = new FileInputStream(file);
        fis.getChannel().read(bb);
        fis.close();
        bb.flip();
        if (bb.remaining() <= 20) throw new IOException("File too small, corrupted? " + file);
        byte[] bytes = new byte[8];
        bb.get(bytes);
        if (!Arrays.equals(bytes, MAGIC)) throw new IOException("Unknown magic number, was " + new String(bytes, 0));
        SharedHashMapBuilder builder = new SharedHashMapBuilder();
        builder.segments(bb.getInt());
        builder.entriesPerSegment = bb.getInt();
        builder.entrySize(bb.getInt());
        builder.replicas(bb.getInt());
        builder.transactional(bb.get() == 'Y');
        if (segments() <= 0 || entriesPerSegment <= 0 || entrySize() <= 0)
            throw new IOException("Corrupt header for " + file);
        return builder;
    }

    private void newFile(File file) throws IOException {
        ByteBuffer bb = ByteBuffer.allocateDirect(HEADER_SIZE).order(ByteOrder.nativeOrder());
        bb.put(MAGIC);
        bb.putInt(segments);
        bb.putInt(entriesPerSegment);
        bb.putInt(entrySize);
        bb.putInt(replicas);
        bb.put((byte) (transactional ? 'Y' : 'N'));
        bb.flip();
        FileOutputStream fos = new FileOutputStream(file);
        fos.getChannel().write(bb);
        fos.close();
    }

    long size() {
        return HEADER_SIZE + segments * segmentSize();
    }

    int segmentSize() {
        return (SEGMENT_HEADER
                + entriesPerSegment * 2 * 8 // the IntIntMultiMap
                + (1 + replicas) * bitSetSize() // the free list and 0+ dirty lists.
                + entriesPerSegment * entrySize); // the actual entries used.
    }

    int bitSetSize() {
        return (entriesPerSegment + 63) / 64 * 8;
    }
}
