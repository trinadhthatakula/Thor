package com.valhalla.thor.model.abxutils

import java.io.Closeable
import java.io.DataOutput
import java.io.Flushable
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Objects

class FastDataOutput(out: OutputStream?, bufferSize: Int) : DataOutput, Flushable, Closeable {

    private val mOut: OutputStream = Objects.requireNonNull<OutputStream>(out)

    private val mBuffer: ByteArray
    private val mBufferCap: Int
    private var mBufferPos = 0

    /**
     * Values that have been "interned" by [.writeInternedUTF].
     */
    private val mStringRefs = HashMap<String?, Short?>()

    init {
        require(bufferSize >= 8)
        mBuffer = ByteArray(bufferSize)
        mBufferCap = mBuffer.size
    }

    @Throws(IOException::class)
    private fun drain() {
        if (mBufferPos > 0) {
            mOut.write(mBuffer, 0, mBufferPos)
            mBufferPos = 0
        }
    }

    @Throws(IOException::class)
    override fun flush() {
        drain()
        mOut.flush()
    }

    @Throws(IOException::class)
    override fun close() {
        mOut.close()
    }

    @Throws(IOException::class)
    override fun write(b: Int) {
        writeByte(b)
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (mBufferCap < len) {
            drain()
            mOut.write(b, off, len)
        } else {
            if (mBufferCap - mBufferPos < len) drain()
            System.arraycopy(b, off, mBuffer, mBufferPos, len)
            mBufferPos += len
        }
    }

    @Throws(IOException::class)
    override fun writeUTF(s: String) {
        // Attempt to write directly to buffer space if there's enough room,
        // otherwise fall back to chunking into place
        if (mBufferCap - mBufferPos < 2 + s.length) drain()
        val b = s.toByteArray(StandardCharsets.UTF_8)
        writeShort(b.size)
        write(b, 0, b.size)
    }

    /**
     * Write a [String] value with the additional signal that the given
     * value is a candidate for being canonicalized, similar to
     * [String.intern].
     *
     *
     * Canonicalization is implemented by writing each unique string value once
     * the first time it appears, and then writing a lightweight `short`
     * reference when that string is written again in the future.
     */
    @Throws(IOException::class)
    fun writeInternedUTF(s: String?) {
        var ref = mStringRefs.get(s)
        if (ref != null) {
            writeShort(ref.toInt())
        } else {
            writeShort(FastDataOutput.Companion.MAX_UNSIGNED_SHORT)
            writeUTF(s!!)
            // We can only safely intern when we have remaining values; if we're
            // full we at least sent the string value above
            ref = mStringRefs.size.toShort()
            if (mStringRefs.size < FastDataOutput.Companion.MAX_UNSIGNED_SHORT) {
                mStringRefs.put(s, ref)
            }
        }
    }

    @Throws(IOException::class)
    override fun writeBoolean(v: Boolean) {
        writeByte(if (v) 1 else 0)
    }

    @Throws(IOException::class)
    override fun writeByte(v: Int) {
        if (mBufferCap - mBufferPos < 1) drain()
        mBuffer[mBufferPos++] = ((v) and 0xff).toByte()
    }

    @Throws(IOException::class)
    override fun writeShort(v: Int) {
        if (mBufferCap - mBufferPos < 2) drain()
        mBuffer[mBufferPos++] = ((v shr 8) and 0xff).toByte()
        mBuffer[mBufferPos++] = ((v) and 0xff).toByte()
    }

    @Throws(IOException::class)
    override fun writeChar(v: Int) {
        writeShort(v.toShort().toInt())
    }

    @Throws(IOException::class)
    override fun writeInt(v: Int) {
        if (mBufferCap - mBufferPos < 4) drain()
        mBuffer[mBufferPos++] = ((v shr 24) and 0xff).toByte()
        mBuffer[mBufferPos++] = ((v shr 16) and 0xff).toByte()
        mBuffer[mBufferPos++] = ((v shr 8) and 0xff).toByte()
        mBuffer[mBufferPos++] = ((v) and 0xff).toByte()
    }

    @Throws(IOException::class)
    override fun writeLong(v: Long) {
        if (mBufferCap - mBufferPos < 8) drain()
        var i = (v shr 32).toInt()
        mBuffer[mBufferPos++] = ((i shr 24) and 0xff).toByte()
        mBuffer[mBufferPos++] = ((i shr 16) and 0xff).toByte()
        mBuffer[mBufferPos++] = ((i shr 8) and 0xff).toByte()
        mBuffer[mBufferPos++] = ((i) and 0xff).toByte()
        i = v.toInt()
        mBuffer[mBufferPos++] = ((i shr 24) and 0xff).toByte()
        mBuffer[mBufferPos++] = ((i shr 16) and 0xff).toByte()
        mBuffer[mBufferPos++] = ((i shr 8) and 0xff).toByte()
        mBuffer[mBufferPos++] = ((i) and 0xff).toByte()
    }

    @Throws(IOException::class)
    override fun writeFloat(v: Float) {
        writeInt(v.toInt())
    }

    @Throws(IOException::class)
    override fun writeDouble(v: Double) {
        writeLong(v.toLong())
    }

    override fun writeBytes(s: String?) {
        // Callers should use writeUTF()
        throw UnsupportedOperationException()
    }

    override fun writeChars(s: String?) {
        // Callers should use writeUTF()
        throw UnsupportedOperationException()
    }

    companion object {
        private const val MAX_UNSIGNED_SHORT = 65535
    }
}