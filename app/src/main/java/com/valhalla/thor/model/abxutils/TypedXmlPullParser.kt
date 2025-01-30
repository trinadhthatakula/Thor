@file:Suppress("unused")

package com.valhalla.thor.model.abxutils

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * Specialization of [XmlPullParser] which adds explicit methods to
 * support consistent and efficient conversion of primitive data types.
 */
interface TypedXmlPullParser : XmlPullParser {

    /**
     * @return index of requested attribute, otherwise `-1` if undefined
     */
    fun getAttributeIndex(namespace: String?, name: String): Int {
        val namespaceNull = (namespace == null)
        val count = attributeCount
        for (i in 0..<count) {
            if ((namespaceNull || namespace == getAttributeNamespace(i))
                && name == getAttributeName(i)
            ) {
                return i
            }
        }
        return -1
    }

    /**
     * @return index of requested attribute
     * @throws XmlPullParserException if the value is undefined
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeIndexOrThrow(namespace: String?, name: String): Int {
        val index = getAttributeIndex(namespace, name)
        if (index == -1) {
            throw XmlPullParserException("Missing attribute " + name)
        } else {
            return index
        }
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeBytesHex(index: Int): ByteArray

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeBytesBase64(index: Int): ByteArray

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeInt(index: Int): Int

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeIntHex(index: Int): Int

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeLong(index: Int): Long

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeLongHex(index: Int): Long

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeFloat(index: Int): Float

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeDouble(index: Int): Double

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeBoolean(index: Int): Boolean

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeBytesHex(
        namespace: String?,
        name: String
    ): ByteArray {
        return getAttributeBytesHex(getAttributeIndexOrThrow(namespace, name))
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeBytesBase64(
        namespace: String?,
        name: String
    ): ByteArray {
        return getAttributeBytesBase64(getAttributeIndexOrThrow(namespace, name))
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeInt(namespace: String?, name: String): Int {
        return getAttributeInt(getAttributeIndexOrThrow(namespace, name))
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeIntHex(namespace: String?, name: String): Int {
        return getAttributeIntHex(getAttributeIndexOrThrow(namespace, name))
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeLong(namespace: String?, name: String): Long {
        return getAttributeLong(getAttributeIndexOrThrow(namespace, name))
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeLongHex(namespace: String?, name: String): Long {
        return getAttributeLongHex(getAttributeIndexOrThrow(namespace, name))
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeFloat(namespace: String?, name: String): Float {
        return getAttributeFloat(getAttributeIndexOrThrow(namespace, name))
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeDouble(namespace: String?, name: String): Double {
        return getAttributeDouble(getAttributeIndexOrThrow(namespace, name))
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue]
     * @throws XmlPullParserException if the value is malformed or undefined
     */
    @Throws(XmlPullParserException::class)
    fun getAttributeBoolean(namespace: String?, name: String): Boolean {
        return getAttributeBoolean(getAttributeIndexOrThrow(namespace, name))
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue], otherwise
     * default value if the value is malformed or undefined
     */
    fun getAttributeBytesHex(
        namespace: String?,
        name: String, defaultValue: ByteArray?
    ): ByteArray? {
        val index = getAttributeIndex(namespace, name)
        if (index == -1) return defaultValue
        try {
            return getAttributeBytesHex(index)
        } catch (ignored: Exception) {
            return defaultValue
        }
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue], otherwise
     * default value if the value is malformed or undefined
     */
    fun getAttributeBytesBase64(
        namespace: String?,
        name: String, defaultValue: ByteArray?
    ): ByteArray? {
        val index = getAttributeIndex(namespace, name)
        if (index == -1) return defaultValue
        try {
            return getAttributeBytesBase64(index)
        } catch (ignored: Exception) {
            return defaultValue
        }
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue], otherwise
     * default value if the value is malformed or undefined
     */
    fun getAttributeInt(
        namespace: String?, name: String,
        defaultValue: Int
    ): Int {
        val index = getAttributeIndex(namespace, name)
        if (index == -1) return defaultValue
        try {
            return getAttributeInt(index)
        } catch (ignored: Exception) {
            return defaultValue
        }
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue], otherwise
     * default value if the value is malformed or undefined
     */
    fun getAttributeIntHex(
        namespace: String?, name: String,
        defaultValue: Int
    ): Int {
        val index = getAttributeIndex(namespace, name)
        if (index == -1) return defaultValue
        try {
            return getAttributeIntHex(index)
        } catch (ignored: Exception) {
            return defaultValue
        }
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue], otherwise
     * default value if the value is malformed or undefined
     */
    fun getAttributeLong(
        namespace: String?, name: String,
        defaultValue: Long
    ): Long {
        val index = getAttributeIndex(namespace, name)
        if (index == -1) return defaultValue
        try {
            return getAttributeLong(index)
        } catch (ignored: Exception) {
            return defaultValue
        }
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue], otherwise
     * default value if the value is malformed or undefined
     */
    fun getAttributeLongHex(
        namespace: String?, name: String,
        defaultValue: Long
    ): Long {
        val index = getAttributeIndex(namespace, name)
        if (index == -1) return defaultValue
        try {
            return getAttributeLongHex(index)
        } catch (ignored: Exception) {
            return defaultValue
        }
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue], otherwise
     * default value if the value is malformed or undefined
     */
    fun getAttributeFloat(
        namespace: String?, name: String,
        defaultValue: Float
    ): Float {
        val index = getAttributeIndex(namespace, name)
        if (index == -1) return defaultValue
        try {
            return getAttributeFloat(index)
        } catch (ignored: Exception) {
            return defaultValue
        }
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue], otherwise
     * default value if the value is malformed or undefined
     */
    fun getAttributeDouble(
        namespace: String?, name: String,
        defaultValue: Double
    ): Double {
        val index = getAttributeIndex(namespace, name)
        if (index == -1) return defaultValue
        try {
            return getAttributeDouble(index)
        } catch (ignored: Exception) {
            return defaultValue
        }
    }

    /**
     * @return decoded strongly-typed [.getAttributeValue], otherwise
     * default value if the value is malformed or undefined
     */
    fun getAttributeBoolean(
        namespace: String?, name: String,
        defaultValue: Boolean
    ): Boolean {
        val index = getAttributeIndex(namespace, name)
        if (index == -1) return defaultValue
        try {
            return getAttributeBoolean(index)
        } catch (ignored: Exception) {
            return defaultValue
        }
    }
}