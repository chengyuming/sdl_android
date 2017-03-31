package com.smartdevicelink.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

public class PipedInputStream extends InputStream {
    boolean closedByWriter = false;
    volatile boolean closedByReader = false;
    boolean connected = false;
    Thread readSide;
    Thread writeSide;
    private static final int DEFAULT_PIPE_SIZE = 1024;
    protected static final int PIPE_SIZE = 1024;
    protected byte[] buffer;
    /**
     * in == -1 implies the buffer is empty, in == out implies the buffer is full
     */
    protected int in = -1;
    protected int out = 0;

    /**
     * Create a PipedInputStream which connect to a PipedOutputStream
     * @param out the output stream
     * @throws IOException
     */
    public PipedInputStream(PipedOutputStream out) throws IOException {
        this(out, PIPE_SIZE);
    }

    /**
     * Create a PipedInputStream which connect to a PipedOutputStream with specified buffer size
     * @param out the output stream
     * @param pipeSize size of the pipe's buffer
     * @throws IOException
     */
    public PipedInputStream(PipedOutputStream out, int pipeSize) throws IOException {
        initPipe(pipeSize);
        connect(out);
    }

    public PipedInputStream() {
        initPipe(PIPE_SIZE);
    }

    public PipedInputStream(int pipeSize) {
        initPipe(pipeSize);
    }

    private void initPipe(int pipeSize) {
        if (pipeSize <= 0) {
            throw new IllegalArgumentException("Pipe Size <= 0");
        }
        this.buffer = new byte[pipeSize];
    }

    public void connect(PipedOutputStream out)
            throws IOException {
        out.connect(this);
    }

    /**
     * Receives a byte of data
     * @param oneByte data
     * @throws IOException
     */
    protected synchronized void receive(int oneByte) throws IOException {
        checkStateForReceive();
        this.writeSide = Thread.currentThread();
        if (this.in == this.out) {
            awaitSpace();
        }
        if (this.in < 0) {
            this.in = 0;
            this.out = 0;
        }
        this.buffer[this.in++] = (byte) (oneByte & 0xFF);
        if (this.in >= this.buffer.length) {
            this.in = 0;
        }
    }

    /**
     * Receives an array of bytes
     * @param bytes data
     * @param byteOffset offset of the data
     * @param byteCount count of data
     * @throws IOException
     */
    synchronized void receive(byte[] bytes, int byteOffset, int byteCount) throws IOException {
        checkStateForReceive();
        this.writeSide = Thread.currentThread();
        int bytesToTransfer = byteCount;
        while (bytesToTransfer > 0) {
            if (this.in == this.out) {
                awaitSpace();
            }
            int nextTransferBytes = 0;
            if (this.out < this.in) {
                nextTransferBytes = this.buffer.length - this.in;
            } else if (this.in < this.out) {
                if (this.in == -1) {
                    this.in = this.out = 0;
                    nextTransferBytes = this.buffer.length - this.in;
                } else {
                    nextTransferBytes = this.out - this.in;
                }
            }
            if (nextTransferBytes > bytesToTransfer) {
                nextTransferBytes = bytesToTransfer;
            }
            System.arraycopy(bytes, byteOffset, this.buffer, this.in, nextTransferBytes);
            bytesToTransfer -= nextTransferBytes;
            byteOffset += nextTransferBytes;
            this.in += nextTransferBytes;
            if (this.in >= this.buffer.length) {
                this.in = 0;
            }
        }
        notifyAll();
    }

    /**
     * Check the state of piped stream
     * @throws IOException
     */
    private void checkStateForReceive() throws IOException {
        if (!this.connected) {
            throw new IOException("Pipe not connected");
        }
        if (this.closedByWriter || this.closedByReader) {
            throw new IOException("Pipe closed");
        }
        if (this.readSide != null && !this.readSide.isAlive()) {
            throw new IOException("Read end dead");
        }
    }

    private void awaitSpace() throws IOException {
        while (this.in == this.out) {
            checkStateForReceive();

            notifyAll();
            try {
                wait(1000);
            } catch (InterruptedException localInterruptedException) {
                throw new InterruptedIOException();
            }
        }
    }

    synchronized void receivedLast() {
        this.closedByWriter = true;
        notifyAll();
    }

    /**
     * Read the next byte of data
     * @return next byte of data, -1 if the end of the stream is reached
     * @throws IOException
     */
    public synchronized int read() throws IOException {
        if (!this.connected) {
            throw new IOException("Pipe not connected");
        }else if (this.closedByReader) {
            throw new IOException("Pipe closed");
        }else if (this.writeSide != null && !this.writeSide.isAlive() && !this.closedByWriter && this.in < 0) {
            throw new IOException("Write end dead");
        }
        this.readSide = Thread.currentThread();
        /**
         * Test last thread to be writing on this PipedInputStream. If
         * writeSide dies an IOException of "Pipe broken" will be thrown in read()
         */
        int testCount = 2;
        while (this.in < 0) {
            if (this.closedByWriter) {
                return -1;
            }
            if (this.writeSide != null && !this.writeSide.isAlive()) {
                testCount--;
                if (testCount < 0) {
                    throw new IOException("Pipe broken");
                }
            }
            notifyAll();
            try {
                wait(1000);
            }
            catch (InterruptedException localInterruptedException) {
                throw new InterruptedIOException();
            }
        }
        int oneByte = this.buffer[this.out++] & 0xFF;
        if (this.out >= this.buffer.length) {
            this.out = 0;
        }
        if (this.in == this.out) {
            this.in = -1;
        }
        return oneByte;
    }

    /**
     * Read bytes of data
     * @param bytes read buffer
     * @param byteOffset start offset of the read buffer
     * @param byteCount max number of bytes read
     * @return total number of bytes read
     * @throws IOException
     */
    public synchronized int read(byte[] bytes, int byteOffset, int byteCount) throws IOException {
        if (bytes == null) {
            throw new NullPointerException();
        }else if (byteOffset < 0 || byteCount < 0 || byteCount > bytes.length - byteOffset) {
            throw new IndexOutOfBoundsException();
        }
        if (byteCount == 0) {
            return 0;
        }
        int oneByte = read();
        if (oneByte < 0) {
            return -1;
        }
        bytes[byteOffset] = (byte)oneByte;
        int totalCopied = 1;
        while (this.in >= 0 && byteCount > 1) {
            int available;
            if (this.in > this.out) {
                available = Math.min(this.buffer.length - this.out, this.in - this.out);
            } else {
                available = this.buffer.length - this.out;
            }
            if (available > byteCount - 1) {
                available = byteCount - 1;
            }
            System.arraycopy(this.buffer, this.out, bytes, byteOffset + totalCopied, available);
            this.out += available;
            totalCopied += available;
            byteCount -= available;
            if (this.out >= this.buffer.length) {
                this.out = 0;
            }
            if (this.in == this.out) {
                this.in = -1;
            }
        }
        notifyAll();
        return totalCopied;
    }

    /**
     * Return the number of bytes that buffer has
     * @return
     * @throws IOException
     */
    public synchronized int available() throws IOException {
        if (this.in < 0) {
            return 0;
        }
        if (this.in == this.out) {
            return this.buffer.length;
        }
        if (this.in > this.out) {
            return this.in - this.out;
        }
        return this.in + this.buffer.length - this.out;
    }

    /**
     * close the stream
     * @throws IOException
     */
    public void close() throws IOException {
        this.closedByReader = true;
        synchronized (this) {
            this.in = -1;
        }
    }
}
