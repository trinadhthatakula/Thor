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
    fun <T> invoke(
        clazz: Class<*>,
        instance: Any?,
        methodName: String,
        vararg args: Any?
    ): T {
        val paramTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        return invoke(clazz, instance, methodName, paramTypes, *args)
    }

    /**
     * Advanced: Reflection-based invocation with explicit parameter types.
     * @throws NoSuchMethodException if the method cannot be resolved.
     */
    fun <T> invoke(
        clazz: Class<*>,
        instance: Any?,
        methodName: String,
        paramTypes: Array<out Class<*>>,
        vararg args: Any?
    ): T {
        val method = getDeclaredMethod(clazz, methodName, *paramTypes)
        @Suppress("UNCHECKED_CAST")
        return method.invoke(instance, *args) as T
    }

    /**
     * Instantiates a class using reflection.
     */
    fun <T> newInstance(clazz: Class<*>, vararg args: Any?): T {
        val paramTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        return newInstance(clazz, paramTypes, *args)
    }

    /**
     * Instantiates a class using reflection with explicit parameter types.
     * @throws NoSuchMethodException if the constructor cannot be resolved.
     */
    fun <T> newInstance(clazz: Class<*>, paramTypes: Array<out Class<*>>, vararg args: Any?): T {
        val constructor = getDeclaredConstructor(clazz, *paramTypes)
        @Suppress("UNCHECKED_CAST")
        return constructor.newInstance(*args) as T
    }

    private fun getDeclaredConstructor(
        clazz: Class<*>,
        vararg parameterTypes: Class<*>
    ): java.lang.reflect.Constructor<*> {
        val exactConstructor = runCatching {
            clazz.getDeclaredConstructor(*parameterTypes).apply {
                isAccessible = true
            }
        }.getOrNull()
        if (exactConstructor != null) return exactConstructor

        // Fallback for compatible types (primitives, subtypes, nulls)
        return runCatching {
            clazz.declaredConstructors.find { constructor ->
                constructor.parameterCount == parameterTypes.size &&
                        constructor.parameterTypes.zip(parameterTypes).all { (declared, provided) ->
                            isCompatible(declared, provided)
                        }
            }?.apply { isAccessible = true }
        }.getOrNull() ?: throw NoSuchMethodException("Constructor not found on ${clazz.name}")
    }

    /**
     * Finds a method and ensures it is accessible.
     * Traverses the class hierarchy to find methods in superclasses.
     * @throws NoSuchMethodException if the method cannot be resolved.
     */
    fun getDeclaredMethod(
        clazz: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method {
        var current: Class<*>? = clazz
        while (current != null) {
            val exactMethod = runCatching {
                current.getDeclaredMethod(name, *parameterTypes).apply {
                    isAccessible = true
                }
            }.getOrNull()
            if (exactMethod != null) return exactMethod

            // Fallback for compatible types (primitives, subtypes, nulls)
            val fallbackMethod = runCatching {
                current.declaredMethods.find { method ->
                    method.name == name &&
                            method.parameterCount == parameterTypes.size &&
                            method.parameterTypes.zip(parameterTypes).all { (declared, provided) ->
                                isCompatible(declared, provided)
                            }
                }?.apply { isAccessible = true }
            }.getOrNull()
            if (fallbackMethod != null) return fallbackMethod

            current = current.superclass
        }
        throw NoSuchMethodException("Method $name not found on ${clazz.name}")
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
     * Supports both instance and static fields (by passing the Class as instance).
     * Traverses the class hierarchy to find fields in superclasses.
     * @throws NoSuchFieldException if the field cannot be found.
     */
    fun <T> getField(instance: Any, fieldName: String): T {
        val target = if (instance is Class<*>) null else instance
        var clazz: Class<*>? = instance as? Class<*> ?: instance.javaClass
        
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                return field.get(target) as T
            } catch (e: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException("Field $fieldName not found on ${if (instance is Class<*>) instance.name else instance.javaClass.name}")
    }
}
