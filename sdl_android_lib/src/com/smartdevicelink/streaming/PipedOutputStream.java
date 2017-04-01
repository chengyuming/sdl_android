package com.smartdevicelink.streaming;


import java.io.IOException;
import java.io.OutputStream;

public class PipedOutputStream extends OutputStream {
    private PipedInputStream sink;

    /**
     * Creates a piped output stream connected to the specified piped input stream
     * @param in
     * @throws IOException
     */
    public PipedOutputStream(PipedInputStream in) throws IOException {
        connect(in);
    }

    /**
     * Creates a piped output stream
     */
    public PipedOutputStream() {}

    /**
     * connected to the specified piped input stream
     * @param in
     * @throws IOException
     */
    public synchronized void connect(PipedInputStream in) throws IOException {
        if (in == null) {
            throw new NullPointerException();
        }else if ((this.sink != null) || (in.connected)) {
            throw new IOException("Already connected");
        }
        this.sink = in;
        in.in = -1;
        in.out = 0;
        in.connected = true;
    }

    /**
     * Write one byte into the piped
     * @param oneByte one byte of data
     * @throws IOException
     */
    public void write(int oneByte) throws IOException {
        if (this.sink == null) {
            throw new IOException("Pipe not connected");
        }
        this.sink.receive(oneByte);
    }

    /**
     * Write an array of bytes
     * @param bytes array of data
     * @param byteOffset offset of array
     * @param byteCount count of data
     * @throws IOException
     */
    public void write(byte[] bytes, int byteOffset, int byteCount) throws IOException {
        if (this.sink == null) {
            throw new IOException("Pipe not connected");
        }else if (bytes == null) {
            throw new NullPointerException();
        }else if ((byteOffset < 0) || (byteOffset > bytes.length) || (byteCount < 0)
                || (byteOffset + byteCount > bytes.length) || (byteOffset + byteCount < 0)) {
            throw new IndexOutOfBoundsException();
        }else if (byteCount == 0) {
            return;
        }
        this.sink.receive(bytes, byteOffset, byteCount);
    }

    /**
     * flush the output stream
     * @throws IOException
     */
    public synchronized void flush() throws IOException {
        if (this.sink != null) {
            synchronized (this.sink) {
                this.sink.notifyAll();
            }
        }
    }

    /**
     * Close the stream
     * @throws IOException
     */
    public void close() throws IOException {
        if (this.sink != null) {
            this.sink.receivedLast();
        }
    }
}
