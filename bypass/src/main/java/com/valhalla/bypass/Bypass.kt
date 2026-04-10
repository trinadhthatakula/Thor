package com.valhalla.bypass

import android.annotation.SuppressLint
import dalvik.system.VMRuntime
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
     * Ensure [prepareThor] or [exemptAll] is called first to allow hidden API access.
     */
    fun invoke(
        clazz: Class<*>,
        instance: Any?,
        methodName: String,
        vararg args: Any?
    ): Any? {
        val paramTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        return invoke(clazz, instance, methodName, paramTypes, *args)
    }

    /**
     * Advanced: Reflection-based invocation with explicit parameter types.
     */
    fun invoke(
        clazz: Class<*>,
        instance: Any?,
        methodName: String,
        paramTypes: Array<out Class<*>>,
        vararg args: Any?
    ): Any? {
        val method = getDeclaredMethod(clazz, methodName, *paramTypes)
        return method?.invoke(instance, *args)
    }

    /**
     * Instantiates a class using reflection.
     */
    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        val paramTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        return newInstance(clazz, paramTypes, *args)
    }

    /**
     * Instantiates a class using reflection with explicit parameter types.
     */
    fun newInstance(clazz: Class<*>, paramTypes: Array<out Class<*>>, vararg args: Any?): Any {
        val constructor = clazz.getDeclaredConstructor(*paramTypes)
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
        val exactMethod = runCatching {
            clazz.getDeclaredMethod(name, *parameterTypes).apply {
                isAccessible = true
            }
        }.getOrNull()
        if (exactMethod != null) return exactMethod

        // Fallback for compatible types (primitives, subtypes, nulls)
        return runCatching {
            clazz.declaredMethods.find { method ->
                method.name == name &&
                        method.parameterCount == parameterTypes.size &&
                        method.parameterTypes.zip(parameterTypes).all { (declared, provided) ->
                            isCompatible(declared, provided)
                        }
            }?.apply { isAccessible = true }
        }.onFailure {
            logger?.invoke("Failed to get declared method $name on ${clazz.name}", it)
        }.getOrNull()
    }

    private fun isCompatible(declared: Class<*>, provided: Class<*>): Boolean {
        if (provided == Any::class.java) return !declared.isPrimitive
        if (declared.isAssignableFrom(provided)) return true
        if (declared.isPrimitive) {
            val primitiveName = declared.name
            return when (provided.name) {
                "java.lang.Integer" -> primitiveName == "int"
                "java.lang.Boolean" -> primitiveName == "boolean"
                "java.lang.Long" -> primitiveName == "long"
                "java.lang.Double" -> primitiveName == "double"
                "java.lang.Float" -> primitiveName == "float"
                "java.lang.Byte" -> primitiveName == "byte"
                "java.lang.Character" -> primitiveName == "char"
                "java.lang.Short" -> primitiveName == "short"
                else -> false
            }
        }
        return false
    }

    /**
     * Directly get a field bypassing access checks.
     */
    fun getField(instance: Any, fieldName: String): Any? {
        return runCatching {
            val field = instance.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(instance)
        }.onFailure {
            logger?.invoke("Failed to get field $fieldName on ${instance.javaClass.name}", it)
        }.getOrNull()
    }
}
