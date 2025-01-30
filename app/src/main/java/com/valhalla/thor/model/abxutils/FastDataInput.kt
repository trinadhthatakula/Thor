@file:Suppress("unused")

package com.valhalla.thor.model.abxutils

import java.io.Closeable
import java.io.DataInput
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.Objects

class FastDataInput(`in`: InputStream, bufferSize: Int) : DataInput, Closeable {
    private var mIn: InputStream?

    private val mBuffer: ByteArray
    private val mBufferCap: Int
    private var mBufferPos = 0
    private var mBufferLim = 0

    /**
     * Values that have been "interned" by [.readInternedUTF].
     */
    private var mStringRefCount = 0
    private var mStringRefs = arrayOfNulls<String>(32)

    init {
        mIn = Objects.requireNonNull<InputStream?>(`in`)
        require(bufferSize >= 8)
        mBuffer = ByteArray(bufferSize)
        mBufferCap = mBuffer.size
    }

    /**
     * Release a [FastDataInput] to potentially be recycled. You must not
     * interact with the object after releasing it.
     */
    fun release() {
        mIn = null
        mBufferPos = 0
        mBufferLim = 0
        mStringRefCount = 0
    }

    /**
     * Re-initializes the object for the new input.
     */
    private fun setInput(`in`: InputStream) {
        mIn = Objects.requireNonNull<InputStream?>(`in`)
        mBufferPos = 0
        mBufferLim = 0
        mStringRefCount = 0
    }

    @Throws(IOException::class)
    private fun fill(need: Int) {
        var need = need
        val remain = mBufferLim - mBufferPos
        System.arraycopy(mBuffer, mBufferPos, mBuffer, 0, remain)
        mBufferPos = 0
        mBufferLim = remain
        need -= remain
        while (need > 0) {
            val c = mIn!!.read(mBuffer, mBufferLim, mBufferCap - mBufferLim)
            if (c == -1) {
                throw EOFException()
            } else {
                mBufferLim += c
                need -= c
            }
        }
    }

    @Throws(IOException::class)
    override fun close() {
        mIn!!.close()
        release()
    }

    @Throws(IOException::class)
    override fun readFully(b: ByteArray) {
        readFully(b, 0, b.size)
    }

    @Throws(IOException::class)
    override fun readFully(b: ByteArray, off: Int, len: Int) {
        // Attempt to read directly from buffer space if there's enough room,
        // otherwise fall back to chunking into place
        var off = off
        var len = len
        if (mBufferCap >= len) {
            if (mBufferLim - mBufferPos < len) fill(len)
            System.arraycopy(mBuffer, mBufferPos, b, off, len)
            mBufferPos += len
        } else {
            val remain = mBufferLim - mBufferPos
            System.arraycopy(mBuffer, mBufferPos, b, off, remain)
            mBufferPos += remain
            off += remain
            len -= remain
            while (len > 0) {
                val c = mIn!!.read(b, off, len)
                if (c == -1) {
                    throw EOFException()
                } else {
                    off += c
                    len -= c
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun readUTF(): String {
        val len = readUnsignedShort()
        val tmp = ByteArray(len)
        readFully(tmp)
        return String(tmp)
    }

    /**
     * Read a [String] value with the additional signal that the given
     * value is a candidate for being canonicalized, similar to
     * [String.intern].
     *
     *
     * Canonicalization is implemented by writing each unique string value once
     * the first time it appears, and then writing a lightweight `short`
     * reference when that string is written again in the future.
     */
    @Throws(IOException::class)
    fun readInternedUTF(): String {
        val ref = readUnsignedShort()
        if (ref == FastDataInput.Companion.MAX_UNSIGNED_SHORT) {
            val s = readUTF()
            // We can only safely intern when we have remaining values; if we're
            // full we at least sent the string value above
            if (mStringRefCount < FastDataInput.Companion.MAX_UNSIGNED_SHORT) {
                if (mStringRefCount == mStringRefs.size) {
                    mStringRefs =
                        mStringRefs.copyOf<String?>(mStringRefCount + (mStringRefCount shr 1))
                }
                mStringRefs[mStringRefCount++] = s
            }
            return s
        } else {
            return mStringRefs[ref]!!
        }
    }

    @Throws(IOException::class)
    override fun readBoolean(): Boolean {
        return readByte().toInt() != 0
    }

    /**
     * Returns the same decoded value as [.readByte] but without
     * actually consuming the underlying data.
     */
    @Throws(IOException::class)
    fun peekByte(): Byte {
        if (mBufferLim - mBufferPos < 1) fill(1)
        return mBuffer[mBufferPos]
    }

    @Throws(IOException::class)
    override fun readByte(): Byte {
        if (mBufferLim - mBufferPos < 1) fill(1)
        return mBuffer[mBufferPos++]
    }

    @Throws(IOException::class)
    override fun readUnsignedByte(): Int {
        return readByte().toInt()
    }

    @Throws(IOException::class)
    override fun readShort(): Short {
        if (mBufferLim - mBufferPos < 2) fill(2)
        return (((mBuffer[mBufferPos++].toInt() and 0xff) shl 8) or
                ((mBuffer[mBufferPos++].toInt() and 0xff))).toShort()
    }

    @Throws(IOException::class)
    override fun readUnsignedShort(): Int {
        return readShort().toInt()
    }

    @Throws(IOException::class)
    override fun readChar(): Char {
        return Char(readShort().toUShort())
    }

    @Throws(IOException::class)
    override fun readInt(): Int {
        if (mBufferLim - mBufferPos < 4) fill(4)
        return (((mBuffer[mBufferPos++].toInt() and 0xff) shl 24) or
                ((mBuffer[mBufferPos++].toInt() and 0xff) shl 16) or
                ((mBuffer[mBufferPos++].toInt() and 0xff) shl 8) or
                ((mBuffer[mBufferPos++].toInt() and 0xff)))
    }

    @Throws(IOException::class)
    override fun readLong(): Long {
        if (mBufferLim - mBufferPos < 8) fill(8)
        val h = ((mBuffer[mBufferPos++].toInt() and 0xff) shl 24) or
                ((mBuffer[mBufferPos++].toInt() and 0xff) shl 16) or
                ((mBuffer[mBufferPos++].toInt() and 0xff) shl 8) or
                ((mBuffer[mBufferPos++].toInt() and 0xff))
        val l = ((mBuffer[mBufferPos++].toInt() and 0xff) shl 24) or
                ((mBuffer[mBufferPos++].toInt() and 0xff) shl 16) or
                ((mBuffer[mBufferPos++].toInt() and 0xff) shl 8) or
                ((mBuffer[mBufferPos++].toInt() and 0xff))
        return ((h.toLong()) shl 32L.toInt()) or ((l.toLong()) and 0xffffffffL)
    }

    @Throws(IOException::class)
    override fun readFloat(): Float {
        return (readInt().toFloat())
    }

    @Throws(IOException::class)
    override fun readDouble(): Double {
        return (readLong().toDouble())
    }

    override fun skipBytes(n: Int): Int {
        // Callers should read data piecemeal
        throw UnsupportedOperationException()
    }

    override fun readLine(): String? {
        // Callers should read data piecemeal
        throw UnsupportedOperationException()
    }

    companion object {
        private const val MAX_UNSIGNED_SHORT = 65535
    }
}