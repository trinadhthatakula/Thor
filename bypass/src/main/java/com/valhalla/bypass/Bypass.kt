package com.valhalla.bypass

import android.annotation.SuppressLint
import dalvik.system.VMRuntime
import stub.sun.misc.Unsafe
import java.lang.reflect.Method

/**
 * Modern implementation of Hidden API Bypass for Thor.
 * Integrated directly as a core module to reduce external dependencies.
 */
@SuppressLint("DiscouragedPrivateApi")
object Bypass {

    private var logger: ((String, Throwable?) -> Unit)? = null

    /**
     * Set a custom logger to trace bypass operations without creating a direct
     * dependency on the app module's logger.
     */
    fun setLogger(logger: (String, Throwable?) -> Unit) {
        this.logger = logger
    }

    private val unsafe: Unsafe? by lazy {
        runCatching {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null) as Unsafe
        }.onFailure {
            logger?.invoke("Failed to initialize Unsafe", it)
        }.getOrNull()
    }

    private val runtime: VMRuntime by lazy { VMRuntime.getRuntime() }

    /**
     * Consolidates common exemptions used across the Thor project.
     * This avoids having to call addExemptions in multiple places.
     */
    fun prepareThor() {
        addExemptions(
            "Landroid/app",
            "Landroid/content/pm",
            "Landroid/hardware/input",
            "Lcom/android/internal/app"
        )
    }

    /**
     * Exempts specific signatures or the entire app from hidden API restrictions.
     * Use "L" to exempt everything (default).
     */
    fun addExemptions(vararg signatures: String) {
        runCatching {
            runtime.setHiddenApiExemptions(*signatures)
        }.onFailure {
            logger?.invoke("Failed to add exemptions for: ${signatures.joinToString()}", it)
        }
    }

    /**
     * Convenience to exempt everything (replicates original HiddenApiBypass.addHiddenApiExemptions("L"))
     */
    fun exemptAll() {
        addExemptions("L")
    }

    /**
     * Advanced: Reflection-based invocation of hidden methods without globally exempting.
     * Uses Unsafe to modify method flags if necessary.
     */
    fun invoke(
        clazz: Class<*>,
        instance: Any?,
        methodName: String,
        vararg args: Any?
    ): Any? {
        val method = getDeclaredMethod(clazz, methodName, *args.map { it?.javaClass ?: Any::class.java }.toTypedArray())
        return method?.invoke(instance, *args)
    }

    /**
     * Instantiates a class using reflection.
     */
    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        val constructor = clazz.getDeclaredConstructor(*args.map { it?.javaClass ?: Any::class.java }.toTypedArray())
        constructor.isAccessible = true
        return constructor.newInstance(*args)
    }

    /**
     * Finds a method and ensures it is accessible.
     */
    fun getDeclaredMethod(
        clazz: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? {
        return runCatching {
            clazz.getDeclaredMethod(name, *parameterTypes).apply {
                isAccessible = true
            }
        }.onFailure {
            logger?.invoke("Failed to get declared method $name on ${clazz.name}", it)
        }.getOrNull()
    }

    /**
     * Directly get a field using Unsafe bypassing all access checks.
     */
    fun getField(instance: Any, fieldName: String): Any? {
        val localUnsafe = unsafe ?: return null
        return runCatching {
            val field = instance.javaClass.getDeclaredField(fieldName)
            val offset = localUnsafe.objectFieldOffset(field)
            localUnsafe.getLong(instance, offset)
        }.onFailure {
            logger?.invoke("Failed to get field $fieldName via Unsafe on ${instance.javaClass.name}", it)
        }.getOrNull()
    }
}
