@file:Suppress("unused")

package com.valhalla.thor.model.abxutils

import org.xmlpull.v1.XmlSerializer
import java.io.IOException

interface TypedXmlSerializer : XmlSerializer {
    /**
     * Functionally equivalent to [.attribute] but
     * with the additional signal that the given value is a candidate for being
     * canonical, similar to [String.intern].
     */
    @Throws(IOException::class)
    fun attributeInterned(
        namespace: String?, name: String?,
        value: String?
    ): XmlSerializer?

    /**
     * Encode the given strongly-typed value and serialize using
     * [.attribute].
     */
    @Throws(IOException::class)
    fun attributeBytesHex(
        namespace: String?, name: String?,
        value: ByteArray?
    ): XmlSerializer?

    /**
     * Encode the given strongly-typed value and serialize using
     * [.attribute].
     */
    @Throws(IOException::class)
    fun attributeBytesBase64(
        namespace: String?, name: String?,
        value: ByteArray?
    ): XmlSerializer?

    /**
     * Encode the given strongly-typed value and serialize using
     * [.attribute].
     */
    @Throws(IOException::class)
    fun attributeInt(
        namespace: String?, name: String?,
        value: Int
    ): XmlSerializer?

    /**
     * Encode the given strongly-typed value and serialize using
     * [.attribute].
     */
    @Throws(IOException::class)
    fun attributeIntHex(
        namespace: String?, name: String?,
        value: Int
    ): XmlSerializer?

    /**
     * Encode the given strongly-typed value and serialize using
     * [.attribute].
     */
    @Throws(IOException::class)
    fun attributeLong(
        namespace: String?, name: String?,
        value: Long
    ): XmlSerializer?

    /**
     * Encode the given strongly-typed value and serialize using
     * [.attribute].
     */
    @Throws(IOException::class)
    fun attributeLongHex(
        namespace: String?, name: String?,
        value: Long
    ): XmlSerializer?

    /**
     * Encode the given strongly-typed value and serialize using
     * [.attribute].
     */
    @Throws(IOException::class)
    fun attributeFloat(
        namespace: String?, name: String?,
        value: Float
    ): XmlSerializer?

    /**
     * Encode the given strongly-typed value and serialize using
     * [.attribute].
     */
    @Throws(IOException::class)
    fun attributeDouble(
        namespace: String?, name: String?,
        value: Double
    ): XmlSerializer?

    /**
     * Encode the given strongly-typed value and serialize using
     * [.attribute].
     */
    @Throws(IOException::class)
    fun attributeBoolean(
        namespace: String?, name: String?,
        value: Boolean
    ): XmlSerializer?
}