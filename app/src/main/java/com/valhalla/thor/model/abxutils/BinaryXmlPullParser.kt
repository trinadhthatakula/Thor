/*
 * Copyright (C) 2023 rosstonovsky
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.valhalla.thor.model.abxutils

import android.text.TextUtils
import android.util.Base64
import android.util.Xml
import com.valhalla.thor.model.abxutils.BinaryXmlSerializer.Companion.ATTRIBUTE
import com.valhalla.thor.model.abxutils.BinaryXmlSerializer.Companion.PROTOCOL_MAGIC_VERSION_0
import com.valhalla.thor.model.abxutils.BinaryXmlSerializer.Companion.TYPE_BOOLEAN_FALSE
import com.valhalla.thor.model.abxutils.BinaryXmlSerializer.Companion.TYPE_BOOLEAN_TRUE
import com.valhalla.thor.model.abxutils.BinaryXmlSerializer.Companion.TYPE_BYTES_BASE64
import com.valhalla.thor.model.abxutils.BinaryXmlSerializer.Companion.TYPE_BYTES_HEX
import com.valhalla.thor.model.abxutils.BinaryXmlSerializer.Companion.TYPE_DOUBLE
import com.valhalla.thor.model.abxutils.BinaryXmlSerializer.Companion.TYPE_FLOAT
import com.valhalla.thor.model.abxutils.BinaryXmlSerializer.Companion.TYPE_INT
import com.valhalla.thor.model.abxutils.BinaryXmlSerializer.Companion.TYPE_INT_HEX
import com.valhalla.thor.model.abxutils.BinaryXmlSerializer.Companion.TYPE_LONG
import com.valhalla.thor.model.abxutils.BinaryXmlSerializer.Companion.TYPE_LONG_HEX
import com.valhalla.thor.model.abxutils.BinaryXmlSerializer.Companion.TYPE_NULL
import com.valhalla.thor.model.abxutils.BinaryXmlSerializer.Companion.TYPE_STRING
import com.valhalla.thor.model.abxutils.BinaryXmlSerializer.Companion.TYPE_STRING_INTERNED
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlSerializer
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Objects

fun Xml.copy(input: XmlPullParser, output: XmlSerializer) {

}

class BinaryXmlPullParser : TypedXmlPullParser {

    var mIn: FastDataInput? = null
    private var mCurrentToken = XmlPullParser.START_DOCUMENT
    private var mCurrentDepth = 0
    private var mCurrentName: String? = null
    private var mCurrentText: String? = null

    /**
     * Pool of attributes parsed for the currently tag. All interactions should
     * be done via [.obtainAttribute], findAttribute(String),
     * and [.resetAttributes].
     */
    private var mAttributeCount = 0
    lateinit var attributes: Array<Attribute>
        private set

    @Throws(XmlPullParserException::class)
    override fun setInput(`is`: InputStream, encoding: String?) {
        if (encoding != null && !StandardCharsets.UTF_8.name()
                .equals(encoding, ignoreCase = true)
        ) {
            throw UnsupportedOperationException()
        }
        if (mIn != null) {
            try {
                mIn!!.close()
            } catch (e: IOException) {
                throw XmlPullParserException(e.toString())
            }
            mIn = null
        }
        mIn = FastDataInput(`is`, BUFFER_SIZE)
        mCurrentToken = XmlPullParser.START_DOCUMENT
        mCurrentDepth = 0
        mCurrentName = null
        mCurrentText = null
        mAttributeCount = 0
        this.attributes = Array<Attribute>(8, { Attribute() })
        for (i in attributes.indices) {
            this.attributes[i] = Attribute()
        }
        try {
            val magic = ByteArray(4)
            mIn!!.readFully(magic)
            if (!Arrays.equals(magic, PROTOCOL_MAGIC_VERSION_0)) {
                throw IOException("Unexpected magic " + bytesToHexString(magic))
            }
            // We're willing to immediately consume a START_DOCUMENT if present,
            // but we're okay if it's missing
            if (peekNextExternalToken() == XmlPullParser.START_DOCUMENT) {
                consumeToken()
            }
        } catch (e: IOException) {
            throw XmlPullParserException(e.toString())
        }
    }

    override fun setInput(`in`: Reader?) {
        throw UnsupportedOperationException()
    }

    @Throws(XmlPullParserException::class, IOException::class)
    override fun next(): Int {
        while (true) {
            val token = nextToken()
            when (token) {
                XmlPullParser.START_TAG, XmlPullParser.END_TAG, XmlPullParser.END_DOCUMENT -> return token
                XmlPullParser.TEXT -> {
                    consumeAdditionalText()
                    // Per interface docs, empty text regions are skipped
                    if (mCurrentText != null && mCurrentText!!.isNotEmpty()) {
                        return XmlPullParser.TEXT
                    }
                }
            }
        }
    }

    @Throws(XmlPullParserException::class, IOException::class)
    override fun nextToken(): Int {
        if (mCurrentToken == XmlPullParser.END_TAG) {
            mCurrentDepth--
        }
        var token: Int
        try {
            token = peekNextExternalToken()
            consumeToken()
        } catch (e: EOFException) {
            token = XmlPullParser.END_DOCUMENT
        }
        if (token == XmlPullParser.START_TAG) {
            // We need to peek forward to find the next external token so
            // that we parse all pending INTERNAL_ATTRIBUTE tokens
            peekNextExternalToken()
            mCurrentDepth++
        }
        mCurrentToken = token
        return token
    }

    /**
     * Peek at the next "external" token without consuming it.
     *
     *
     * External tokens, such as [.START_TAG], are expected by typical
     * [XmlPullParser] clients. In contrast, internal tokens, such as
     * ATTRIBUTE, are not expected by typical clients.
     *
     *
     * This method consumes any internal events until it reaches the next
     * external event.
     */
    @Throws(IOException::class, XmlPullParserException::class)
    private fun peekNextExternalToken(): Int {
        while (true) {
            val token = peekNextToken()
            if (token == ATTRIBUTE) {
                consumeToken()
                continue
            }
            return token
        }
    }

    /**
     * Peek at the next token in the underlying stream without consuming it.
     */
    @Throws(IOException::class)
    private fun peekNextToken(): Int {
        return mIn!!.peekByte().toInt() and 0x0f
    }

    @get:Throws(IOException::class)
    val nextEvent: Int
        get() = mIn!!.readByte().toInt()

    /**
     * Parse and consume the next token in the underlying stream.
     */
    @Throws(IOException::class, XmlPullParserException::class)
    private fun consumeToken() {
        val event = mIn!!.readByte().toInt()
        val token = event and 0x0f
        val type = event and 0xf0
        when (token) {
            ATTRIBUTE -> {
                val attr = obtainAttribute()
                attr.name = mIn!!.readInternedUTF()
                attr.type = type
                when (type) {
                    TYPE_NULL, TYPE_BOOLEAN_TRUE, TYPE_BOOLEAN_FALSE -> {}
                    TYPE_STRING -> attr.valueString = mIn!!.readUTF()
                    TYPE_STRING_INTERNED -> attr.valueString = mIn!!.readInternedUTF()
                    TYPE_BYTES_HEX, TYPE_BYTES_BASE64 -> {
                        val len = mIn!!.readUnsignedShort()
                        val res = ByteArray(len)
                        mIn!!.readFully(res)
                        attr.valueBytes = res
                    }

                    TYPE_INT, TYPE_INT_HEX -> attr.valueInt = mIn!!.readInt()
                    TYPE_LONG, TYPE_LONG_HEX -> attr.valueLong = mIn!!.readLong()
                    TYPE_FLOAT -> attr.valueFloat = mIn!!.readFloat()
                    TYPE_DOUBLE -> attr.valueDouble = mIn!!.readDouble()
                    else -> throw IOException("Unexpected data type $type")
                }
            }

            XmlPullParser.START_DOCUMENT, XmlPullParser.END_DOCUMENT -> {
                mCurrentName = null
                mCurrentText = null
                if (mAttributeCount > 0) resetAttributes()
            }

            XmlPullParser.START_TAG -> {
                mCurrentName = mIn!!.readInternedUTF()
                mCurrentText = null
                if (mAttributeCount > 0) resetAttributes()
            }

            XmlPullParser.END_TAG -> {
                mCurrentName = mIn!!.readInternedUTF()
                mCurrentText = null
            }

            XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.PROCESSING_INSTRUCTION, XmlPullParser.COMMENT, XmlPullParser.DOCDECL, XmlPullParser.IGNORABLE_WHITESPACE -> {
                mCurrentName = null
                mCurrentText = mIn!!.readUTF()
                if (mAttributeCount > 0) resetAttributes()
            }

            XmlPullParser.ENTITY_REF -> {
                mCurrentName = mIn!!.readUTF()
                mCurrentText = Companion.resolveEntity(mCurrentName!!)
                if (mAttributeCount > 0) resetAttributes()
            }

            else -> {
                throw IOException("Unknown token $token with type $type")
            }
        }
    }

    /**
     * When the current tag is [.TEXT], consume all subsequent "text"
     * events, as described by [.next]. When finished, the current event
     * will still be [.TEXT].
     */
    @Throws(IOException::class, XmlPullParserException::class)
    private fun consumeAdditionalText() {
        val combinedText = StringBuilder(mCurrentText?:"")
        while (true) {
            val token = peekNextExternalToken()
            when (token) {
                XmlPullParser.COMMENT, XmlPullParser.PROCESSING_INSTRUCTION ->                    // Quietly consumed
                    consumeToken()

                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    // Additional text regions collected
                    consumeToken()
                    combinedText.append(mCurrentText)
                }

                else -> {
                    // Next token is something non-text, so wrap things up
                    mCurrentToken = XmlPullParser.TEXT
                    mCurrentName = null
                    mCurrentText = combinedText.toString()
                    return
                }
            }
        }
    }

    @Throws(XmlPullParserException::class)
    override fun require(type: Int, namespace: String?, name: String?) {
        if (namespace != null && !namespace.isEmpty()) throw IllegalArgumentException()
        if (mCurrentToken != type || mCurrentName != name) {
            throw XmlPullParserException(positionDescription)
        }
    }

    @Throws(XmlPullParserException::class, IOException::class)
    override fun nextText(): String? {
        if (getEventType() != XmlPullParser.START_TAG) {
            throw XmlPullParserException(positionDescription)
        }
        var eventType = next()
        if (eventType == XmlPullParser.TEXT) {
            val result = getText()
            eventType = next()
            if (eventType != XmlPullParser.END_TAG) {
                throw XmlPullParserException(positionDescription)
            }
            return result
        } else if (eventType == XmlPullParser.END_TAG) {
            return ""
        } else {
            throw XmlPullParserException(positionDescription)
        }
    }

    @Throws(XmlPullParserException::class, IOException::class)
    override fun nextTag(): Int {
        var eventType = next()
        if (eventType == XmlPullParser.TEXT && isWhitespace) {
            eventType = next()
        }
        if (eventType != XmlPullParser.START_TAG && eventType != XmlPullParser.END_TAG) {
            throw XmlPullParserException(positionDescription)
        }
        return eventType
    }

    /**
     * Allocate and return a new [Attribute] associated with the tag being
     * currently processed. This will automatically grow the internal pool as
     * needed.
     */
    fun obtainAttribute(): Attribute {
        if (mAttributeCount == attributes.size) {
            val before = attributes.size
            val after = before + (before shr 1)
            this.attributes = attributes.copyOf(after) as Array<Attribute>
            for (i in before..<after) {
                this.attributes[i] = Attribute()
            }
        }
        return this.attributes[mAttributeCount++]
    }

    /**
     * Clear any [Attribute] instances that have been allocated by
     * [.obtainAttribute], returning them into the pool for recycling.
     */
    fun resetAttributes() {
        for (i in 0..<mAttributeCount) {
            this.attributes[i].reset()
        }
        mAttributeCount = 0
    }

    override fun getAttributeIndex(namespace: String?, name: String): Int {
        if (namespace != null && !namespace.isEmpty()) throw illegalNamespace()
        for (i in 0..<mAttributeCount) {
            if (this.attributes[i].name == name) {
                return i
            }
        }
        return -1
    }

    fun getAttributeValue(name: String): String? {
        val index = getAttributeIndex(null, name)
        if (index != -1) {
            return this.attributes[index].getValueString()
        } else {
            return null
        }
    }

    override fun getAttributeValue(namespace: String?, name: String): String? {
        val index = getAttributeIndex(namespace, name)
        if (index != -1) {
            return this.attributes[index].getValueString()
        } else {
            return null
        }
    }

    override fun getAttributeValue(index: Int): String? {
        return this.attributes[index].getValueString()
    }

    @Throws(XmlPullParserException::class)
    override fun getAttributeBytesHex(index: Int): ByteArray {
        return Objects.requireNonNull<ByteArray?>(this.attributes[index].valueBytesHex)
    }

    @Throws(XmlPullParserException::class)
    override fun getAttributeBytesBase64(index: Int): ByteArray {
        return Objects.requireNonNull<ByteArray?>(this.attributes[index].valueBytesBase64)
    }

    @Throws(XmlPullParserException::class)
    override fun getAttributeInt(index: Int): Int {
        return this.attributes[index].getValueInt()
    }

    @Throws(XmlPullParserException::class)
    override fun getAttributeIntHex(index: Int): Int {
        return this.attributes[index].valueIntHex
    }

    @Throws(XmlPullParserException::class)
    override fun getAttributeLong(index: Int): Long {
        return this.attributes[index].getValueLong()
    }

    @Throws(XmlPullParserException::class)
    override fun getAttributeLongHex(index: Int): Long {
        return this.attributes[index].valueLongHex
    }

    @Throws(XmlPullParserException::class)
    override fun getAttributeFloat(index: Int): Float {
        return this.attributes[index].getValueFloat()
    }

    @Throws(XmlPullParserException::class)
    override fun getAttributeDouble(index: Int): Double {
        return this.attributes[index].getValueDouble()
    }

    @Throws(XmlPullParserException::class)
    override fun getAttributeBoolean(index: Int): Boolean {
        return this.attributes[index].valueBoolean
    }

    override fun getText(): String? {
        return mCurrentText
    }

    override fun getTextCharacters(holderForStartAndLength: IntArray): CharArray {
        val chars = mCurrentText!!.toCharArray()
        holderForStartAndLength[0] = 0
        holderForStartAndLength[1] = chars.size
        return chars
    }

    override fun getInputEncoding(): String? {
        return StandardCharsets.UTF_8.name()
    }

    override fun getDepth(): Int {
        return mCurrentDepth
    }

    override fun getPositionDescription(): String {
        // Not very helpful, but it's the best information we have
        return "Token " + mCurrentToken + " at depth " + mCurrentDepth
    }

    override fun getLineNumber(): Int {
        return -1
    }

    override fun getColumnNumber(): Int {
        return -1
    }

    @Throws(XmlPullParserException::class)
    override fun isWhitespace(): Boolean {
        when (mCurrentToken) {
            XmlPullParser.IGNORABLE_WHITESPACE -> return true
            XmlPullParser.TEXT, XmlPullParser.CDSECT -> return !TextUtils.isGraphic(mCurrentText)
            else -> throw XmlPullParserException("Not applicable for token " + mCurrentToken)
        }
    }

    override fun getNamespace(): String? {
        when (mCurrentToken) {
            XmlPullParser.START_TAG, XmlPullParser.END_TAG ->                // Namespaces are unsupported
                return XmlPullParser.NO_NAMESPACE

            else -> return null
        }
    }

    override fun getName(): String? {
        return mCurrentName
    }

    override fun getPrefix(): String? {
        // Prefixes are not supported
        return null
    }

    @Throws(XmlPullParserException::class)
    override fun isEmptyElementTag(): Boolean {
        if (mCurrentToken == XmlPullParser.START_TAG) {
            try {
                return (peekNextExternalToken() == XmlPullParser.END_TAG)
            } catch (e: IOException) {
                throw XmlPullParserException(e.toString())
            }
        }
        throw XmlPullParserException("Not at START_TAG")
    }

    override fun getAttributeCount(): Int {
        return mAttributeCount
    }

    override fun getAttributeNamespace(index: Int): String {
        // Namespaces are unsupported
        return XmlPullParser.NO_NAMESPACE
    }

    override fun getAttributeName(index: Int): String? {
        return this.attributes[index].name
    }

    override fun getAttributePrefix(index: Int): String? {
        // Prefixes are not supported
        return null
    }

    override fun getAttributeType(index: Int): String {
        // Validation is not supported
        return "CDATA"
    }

    override fun isAttributeDefault(index: Int): Boolean {
        // Validation is not supported
        return false
    }

    override fun getEventType(): Int {
        return mCurrentToken
    }

    override fun getNamespaceCount(depth: Int): Int {
        // Namespaces are unsupported
        return 0
    }

    override fun getNamespacePrefix(pos: Int): String? {
        // Namespaces are unsupported
        throw UnsupportedOperationException()
    }

    override fun getNamespaceUri(pos: Int): String? {
        // Namespaces are unsupported
        throw UnsupportedOperationException()
    }

    override fun getNamespace(prefix: String?): String? {
        // Namespaces are unsupported
        throw UnsupportedOperationException()
    }

    override fun defineEntityReplacementText(entityName: String?, replacementText: String?) {
        // Custom entities are not supported
        throw UnsupportedOperationException()
    }

    override fun setFeature(name: String?, state: Boolean) {
        // Features are not supported
        throw UnsupportedOperationException()
    }

    override fun getFeature(name: String?): Boolean {
        // Features are not supported
        throw UnsupportedOperationException()
    }

    override fun setProperty(name: String?, value: Any?) {
        // Properties are not supported
        throw UnsupportedOperationException()
    }

    override fun getProperty(name: String?): Any? {
        // Properties are not supported
        throw UnsupportedOperationException()
    }

    /**
     * Holder representing a single attribute. This design enables object
     * recycling without resorting to autoboxing.
     *
     *
     * To support conversion between human-readable XML and binary XML, the
     * various accessor methods will transparently convert from/to
     * human-readable values when needed.
     */
    class Attribute {
        var name: String? = null
        var type: Int = 0
        var valueString: String? = null
        var valueBytes: ByteArray? = null
        var valueInt: Int = 0
        var valueLong: Long = 0
        var valueFloat: Float = 0f
        var valueDouble: Double = 0.0

        fun reset() {
            name = null
            valueString = null
            valueBytes = null
        }

        fun getValueString(): String? {
            when (type) {
                TYPE_NULL -> return null
                TYPE_STRING, TYPE_STRING_INTERNED -> return valueString
                TYPE_BYTES_HEX -> return bytesToHexString(valueBytes!!)
                TYPE_BYTES_BASE64 -> return Base64.encodeToString(valueBytes, Base64.NO_WRAP)
                TYPE_INT -> return valueInt.toString()
                TYPE_INT_HEX -> return valueInt.toString(16)
                TYPE_LONG -> return valueLong.toString()
                TYPE_LONG_HEX -> return valueLong.toString(16)
                TYPE_FLOAT -> return valueFloat.toString()
                TYPE_DOUBLE -> return valueDouble.toString()
                TYPE_BOOLEAN_TRUE -> return "true"
                TYPE_BOOLEAN_FALSE -> return "false"
                else ->                    // Unknown data type; null is the best we can offer
                    return null
            }
        }

        @get:Throws(XmlPullParserException::class)
        val valueBytesHex: ByteArray?
            get() {
                when (type) {
                    TYPE_NULL -> return null
                    TYPE_BYTES_HEX, TYPE_BYTES_BASE64 -> return valueBytes
                    TYPE_STRING, TYPE_STRING_INTERNED -> {
                        try {
                            return Companion.hexStringToBytes(valueString!!)
                        } catch (e: Exception) {
                            throw XmlPullParserException("Invalid attribute " + name + ": " + e)
                        }
                        throw XmlPullParserException("Invalid conversion from " + type)
                    }

                    else -> throw XmlPullParserException("Invalid conversion from " + type)
                }
            }

        @get:Throws(XmlPullParserException::class)
        val valueBytesBase64: ByteArray?
            get() {
                when (type) {
                    TYPE_NULL -> return null
                    TYPE_BYTES_HEX, TYPE_BYTES_BASE64 -> return valueBytes
                    TYPE_STRING, TYPE_STRING_INTERNED -> {
                        try {
                            return Base64.decode(
                                valueString,
                                Base64.NO_WRAP
                            )
                        } catch (e: Exception) {
                            throw XmlPullParserException("Invalid attribute $name: $e")
                        }
                        throw XmlPullParserException("Invalid conversion from $type")
                    }

                    else -> throw XmlPullParserException("Invalid conversion from $type")
                }
            }

        @Throws(XmlPullParserException::class)
        fun getValueInt(): Int {
            when (type) {
                TYPE_INT, TYPE_INT_HEX -> return valueInt
                TYPE_STRING, TYPE_STRING_INTERNED -> {
                    try {
                        return valueString!!.toInt()
                    } catch (e: Exception) {
                        throw XmlPullParserException("Invalid attribute $name: $e")
                    }
                    throw XmlPullParserException("Invalid conversion from $type")
                }

                else -> throw XmlPullParserException("Invalid conversion from $type")
            }
        }

        @get:Throws(XmlPullParserException::class)
        val valueIntHex: Int
            get() {
                when (type) {
                    TYPE_INT, TYPE_INT_HEX -> return valueInt
                    TYPE_STRING, TYPE_STRING_INTERNED -> {
                        try {
                            return valueString!!.toInt(16)
                        } catch (e: Exception) {
                            throw XmlPullParserException("Invalid attribute $name: $e")
                        }
                        throw XmlPullParserException("Invalid conversion from $type")
                    }

                    else -> throw XmlPullParserException("Invalid conversion from $type")
                }
            }

        @Throws(XmlPullParserException::class)
        fun getValueLong(): Long {
            when (type) {
                TYPE_LONG, TYPE_LONG_HEX -> return valueLong
                TYPE_STRING, TYPE_STRING_INTERNED -> {
                    try {
                        return valueString!!.toLong()
                    } catch (e: Exception) {
                        throw XmlPullParserException("Invalid attribute $name: $e")
                    }
                    throw XmlPullParserException("Invalid conversion from $type")
                }

                else -> throw XmlPullParserException("Invalid conversion from $type")
            }
        }

        @get:Throws(XmlPullParserException::class)
        val valueLongHex: Long
            get() {
                when (type) {
                    TYPE_LONG, TYPE_LONG_HEX -> return valueLong
                    TYPE_STRING, TYPE_STRING_INTERNED -> {
                        try {
                            return valueString!!.toLong(16)
                        } catch (e: Exception) {
                            throw XmlPullParserException("Invalid attribute $name: $e")
                        }
                        throw XmlPullParserException("Invalid conversion from $type")
                    }

                    else -> throw XmlPullParserException("Invalid conversion from $type")
                }
            }

        @Throws(XmlPullParserException::class)
        fun getValueFloat(): Float {
            when (type) {
                TYPE_FLOAT -> return valueFloat
                TYPE_STRING, TYPE_STRING_INTERNED -> {
                    try {
                        return valueString!!.toFloat()
                    } catch (e: Exception) {
                        throw XmlPullParserException("Invalid attribute $name: $e")
                    }
                    throw XmlPullParserException("Invalid conversion from $type")
                }

                else -> throw XmlPullParserException("Invalid conversion from $type")
            }
        }

        @Throws(XmlPullParserException::class)
        fun getValueDouble(): Double {
            when (type) {
                TYPE_DOUBLE -> return valueDouble
                TYPE_STRING, TYPE_STRING_INTERNED -> {
                    try {
                        return valueString!!.toDouble()
                    } catch (e: Exception) {
                        throw XmlPullParserException("Invalid attribute $name: $e")
                    }
                    throw XmlPullParserException("Invalid conversion from $type")
                }

                else -> throw XmlPullParserException("Invalid conversion from $type")
            }
        }

        @get:Throws(XmlPullParserException::class)
        val valueBoolean: Boolean
            get() {
                when (type) {
                    TYPE_BOOLEAN_TRUE -> return true
                    TYPE_BOOLEAN_FALSE -> return false
                    TYPE_STRING, TYPE_STRING_INTERNED -> if ("true".equals(
                            valueString,
                            ignoreCase = true
                        )
                    ) {
                        return true
                    } else if ("false".equals(valueString, ignoreCase = true)) {
                        return false
                    } else {
                        throw XmlPullParserException(
                            "Invalid attribute $name: $valueString"
                        )
                    }

                    else -> throw XmlPullParserException("Invalid conversion from $type")
                }
            }
    }


    fun illegalNamespace(): IllegalArgumentException {
        throw IllegalArgumentException("Namespaces are not supported")
    }

    companion object {
        private const val BUFFER_SIZE = 32768

        @Throws(XmlPullParserException::class)
        fun resolveEntity(entity: String): String {
            when (entity) {
                "lt" -> return "<"
                "gt" -> return ">"
                "amp" -> return "&"
                "apos" -> return "'"
                "quot" -> return "\""
            }
            if (entity.length > 1 && entity[0] == '#') {
                val c = entity.substring(1).toInt().toChar()
                return c.toString()
            }
            throw XmlPullParserException("Unknown entity $entity")
        }

        // NOTE: To support unbundled clients, we include an inlined copy
        // of hex conversion logic from HexDump below
        private val HEX_DIGITS = charArrayOf(
            '0',
            '1',
            '2',
            '3',
            '4',
            '5',
            '6',
            '7',
            '8',
            '9',
            'a',
            'b',
            'c',
            'd',
            'e',
            'f'
        )

        private fun toByte(c: Char): Int {
            if (c >= '0' && c <= '9') return (c.code - '0'.code)
            if (c >= 'A' && c <= 'F') return (c.code - 'A'.code + 10)
            if (c >= 'a' && c <= 'f') return (c.code - 'a'.code + 10)
            throw IllegalArgumentException("Invalid hex char '$c'")
        }

        fun bytesToHexString(value: ByteArray): String {
            val length = value.size
            val buf = CharArray(length * 2)
            var bufIndex = 0
            for (b in value) {
                buf[bufIndex++] = HEX_DIGITS[(b.toInt() ushr 4) and 0x0F]
                buf[bufIndex++] = HEX_DIGITS[b.toInt() and 0x0F]
            }
            return String(buf)
        }

        fun hexStringToBytes(value: String): ByteArray {
            val length = value.length
            require(length % 2 == 0) { "Invalid hex length $length" }
            val buffer = ByteArray(length / 2)
            var i = 0
            while (i < length) {
                buffer[i / 2] = ((toByte(value.get(i)) shl 4)
                        or toByte(value.get(i + 1))).toByte()
                i += 2
            }
            return buffer
        }
    }
}