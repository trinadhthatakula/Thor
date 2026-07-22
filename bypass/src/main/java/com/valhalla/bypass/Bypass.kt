// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.bypass

import android.annotation.SuppressLint
import android.content.Context
import android.util.Property
import dalvik.system.VMRuntime
import sun.misc.Unsafe
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

@SuppressLint("DiscouragedPrivateApi")
object Bypass {

    private var logger: ((String, Throwable?) -> Unit)? = null
    private val runtime: VMRuntime by lazy { VMRuntime.getRuntime() }

    // Unsafe bypass status and state
    private var unsafe: Unsafe? = null
    private var methodOffset: Long = 0
    private var classOffset: Long = 0
    private var artOffset: Long = 0
    private var methodsOffset: Long = 0
    private var iFieldOffset: Long = 0
    private var sFieldOffset: Long = 0
    private var artMethodSize: Long = 0
    private var artMethodBias: Long = 0
    private var artFieldSize: Long = 0
    private var artFieldBias: Long = 0

    private var isUnsafeBypassReady = false

    // Guards lazy, one-shot initialization of the Unsafe (HiddenApiBypass) offsets. The heavy
    // core-oj dex scan is deferred out of the object's init block so it never runs on the main
    // thread during class init, and so the on-disk offset cache (installed by init(context)) is
    // ready before the scan runs and can therefore persist the result.
    private val unsafeInitLock = Any()

    @Volatile
    private var unsafeInitAttempted = false

    // Property bypass (LSPass) state
    private val methodsProperty: Property<Class<*>, Array<Method>> by lazy {
        @Suppress("UNCHECKED_CAST")
        Property.of(Class::class.java, java.lang.reflect.Array.newInstance(Method::class.java, 0).javaClass, "DeclaredMethods") as Property<Class<*>, Array<Method>>
    }

    private val constructorsProperty: Property<Class<*>, Array<Constructor<*>>> by lazy {
        @Suppress("UNCHECKED_CAST")
        Property.of(Class::class.java, java.lang.reflect.Array.newInstance(Constructor::class.java, 0).javaClass, "DeclaredConstructors") as Property<Class<*>, Array<Constructor<*>>>
    }

    private val fieldsProperty: Property<Class<*>, Array<Field>> by lazy {
        @Suppress("UNCHECKED_CAST")
        Property.of(Class::class.java, java.lang.reflect.Array.newInstance(Field::class.java, 0).javaClass, "DeclaredFields") as Property<Class<*>, Array<Field>>
    }

    private var isPropertyBypassReady = false

    init {
        // Initialize LSPass (Property-based). This is cheap and safe to run eagerly here.
        try {
            methodsProperty
            constructorsProperty
            fieldsProperty
            isPropertyBypassReady = true
        } catch (e: Throwable) {
            logger?.invoke("Property bypass (LSPass) initialization failed", e)
        }

        // NOTE: The Unsafe (HiddenApiBypass) offsets are intentionally NOT computed here.
        // Resolving them can require an mmap + dex parse of the boot-classpath core-oj jar
        // (readOffsetDataDex), which is far too expensive to run on the main thread during
        // class initialization (startup latency / ANR risk on slow storage). It is deferred to
        // ensureUnsafeBypassReady(), invoked lazily on first actual use. Deferring it also lets
        // init(context) install the on-disk offset cache first, so a successful scan is persisted
        // and every later cold start reloads it and skips the scan entirely.
    }

    /**
     * Installs the on-disk offset cache used by the Unsafe bypass layer. Call this once, early in
     * Application.onCreate() and BEFORE the first bypass use (e.g. before [prepareThor]). Doing so
     * lets the first cold start persist the computed ART offsets and lets every later cold start
     * reload them, skipping the expensive core-oj dex scan. Safe to omit — the offsets are then
     * simply recomputed in-memory once per process.
     */
    fun init(context: Context) {
        Helper.enableOffsetCache(context)
    }

    /**
     * Lazily and idempotently initializes the Unsafe (HiddenApiBypass) layer: acquires the
     * [Unsafe] instance and resolves the ART field/method offsets, reusing the on-disk cache
     * installed via [init] when available (otherwise scanning core-oj once and persisting the
     * result). Returns whether the Unsafe bypass is usable.
     *
     * Thread-safe: the first caller performs the work under a lock and publishes the result via
     * the volatile [unsafeInitAttempted] flag; later callers observe it without locking. Note the
     * FIRST call still runs synchronously on its caller's thread: on a disk-cache MISS (fresh
     * install, cache cleared, or an OS/ART update invalidated it) ThorApplication triggers this
     * from prepareThor() during onCreate, so the one-time core-oj scan happens on the MAIN thread
     * for that launch. Subsequent launches read the persisted cache and skip the scan entirely.
     */
    private fun ensureUnsafeBypassReady(): Boolean {
        if (unsafeInitAttempted) return isUnsafeBypassReady
        synchronized(unsafeInitLock) {
            if (unsafeInitAttempted) return isUnsafeBypassReady
            try {
                val theUnsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
                theUnsafeField.isAccessible = true
                unsafe = theUnsafeField.get(null) as Unsafe

                var data = Helper.getCachedOffsetData()
                if (data == null) {
                    data = readOffsetDataIO()
                    Helper.setCachedOffsetData(data)
                }
                methodOffset = data[0]
                classOffset = data[1]
                artOffset = data[2]
                methodsOffset = data[3]
                iFieldOffset = data[4]
                sFieldOffset = data[5]

                val dataRT = readOffsetDataRT()
                artMethodSize = dataRT[0]
                artMethodBias = dataRT[1]
                artFieldSize = dataRT[2]
                artFieldBias = dataRT[3]

                isUnsafeBypassReady = true
            } catch (e: Throwable) {
                logger?.invoke("Unsafe bypass initialization failed", e)
                isUnsafeBypassReady = false
            }
            unsafeInitAttempted = true
            return isUnsafeBypassReady
        }
    }

    /**
     * Set a custom logger to trace bypass operations.
     */
    fun setLogger(logger: (String, Throwable?) -> Unit) {
        this.logger = logger
    }

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
     */
    fun addExemptions(vararg signatures: String) {
        synchronized(Helper.signaturePrefixes) {
            Helper.signaturePrefixes.addAll(signatures)
            val strings = Helper.signaturePrefixes.toTypedArray()
            setHiddenApiExemptions(*strings)
        }
    }

    /**
     * Convenience to exempt everything.
     */
    fun exemptAll() {
        addExemptions("L")
    }

    /**
     * Set hidden API exemptions using active bypass layers.
     */
    fun setHiddenApiExemptions(vararg signaturePrefixes: String): Boolean {
        // 1. Try Unsafe-based bypass first
        if (ensureUnsafeBypassReady()) {
            try {
                val runtimeObj = unsafeInvoke<Any>(VMRuntime::class.java, null, "getRuntime")
                unsafeInvoke<Any>(VMRuntime::class.java, runtimeObj, "setHiddenApiExemptions", signaturePrefixes)
                return true
            } catch (e: Exception) {
                logger?.invoke("Unsafe setHiddenApiExemptions failed", e)
            }
        }

        // 2. Try Property-based bypass (LSPass) second
        if (isPropertyBypassReady) {
            try {
                val runtimeObj = propertyInvoke<Any>(VMRuntime::class.java, null, "getRuntime")
                propertyInvoke<Any>(runtimeObj.javaClass, runtimeObj, "setHiddenApiExemptions", signaturePrefixes)
                return true
            } catch (e: Exception) {
                logger?.invoke("Property setHiddenApiExemptions failed", e)
            }
        }

        // 3. Fallback to direct VMRuntime call
        return try {
            runtime.setHiddenApiExemptions(*signaturePrefixes)
            true
        } catch (e: Exception) {
            logger?.invoke("Fallback setHiddenApiExemptions failed", e)
            false
        }
    }

    private fun readOffsetDataIO(): LongArray {
        try {
            return readOffsetDataDex()
        } catch (e: Exception) {
            logger?.invoke("Failed to read offset data from dex, falling back to classloader", e)
        }
        return readOffsetDataClassLoader()
    }

    private fun readOffsetDataDex(): LongArray {
        val scanner = DexFieldLayout()
        scanner.scanPath(Helper.getCoreOjPath())
        val executable = scanner.layoutOf(DexFieldLayout.EXECUTABLE)
        val methodHandle = scanner.layoutOf(DexFieldLayout.METHOD_HANDLE)
        val classClass = scanner.layoutOf(DexFieldLayout.CLASS)

        val data = LongArray(6)
        data[0] = executable.offsetOf("artMethod").toLong()
        data[1] = executable.offsetOf("declaringClass").toLong()
        data[2] = methodHandle.offsetOf("artFieldOrMethod").toLong()
        data[3] = classClass.offsetOf("methods").toLong()
        if (classClass.hasField("fields")) {
            data[4] = classClass.offsetOf("fields").toLong()
            data[5] = data[4]
        } else {
            data[4] = classClass.offsetOf("iFields").toLong()
            data[5] = classClass.offsetOf("sFields").toLong()
        }
        return data
    }

    private fun readOffsetDataClassLoader(): LongArray {
        val u = unsafe ?: throw IllegalStateException("Unsafe not initialized")
        val data = LongArray(6)
        data[0] = u.objectFieldOffset(Helper.Executable::class.java.getDeclaredField("artMethod"))
        data[1] = u.objectFieldOffset(Helper.Executable::class.java.getDeclaredField("declaringClass"))
        data[2] = u.objectFieldOffset(Helper.MethodHandle::class.java.getDeclaredField("artFieldOrMethod"))
        data[3] = u.objectFieldOffset(Helper.Class::class.java.getDeclaredField("methods"))
        try {
            data[4] = u.objectFieldOffset(Helper.Class::class.java.getDeclaredField("fields"))
            data[5] = data[4]
        } catch (e: NoSuchFieldException) {
            data[4] = u.objectFieldOffset(Helper.Class::class.java.getDeclaredField("iFields"))
            data[5] = u.objectFieldOffset(Helper.Class::class.java.getDeclaredField("sFields"))
        }
        return data
    }

    private fun readOffsetDataRT(): LongArray {
        val u = unsafe ?: throw IllegalStateException("Unsafe not initialized")
        val mA = Helper.NeverCall::class.java.getDeclaredMethod("a").apply { isAccessible = true }
        val mB = Helper.NeverCall::class.java.getDeclaredMethod("b").apply { isAccessible = true }
        val mhA = MethodHandles.lookup().unreflect(mA)
        val mhB = MethodHandles.lookup().unreflect(mB)
        val aAddr = u.getLong(mhA, artOffset)
        val bAddr = u.getLong(mhB, artOffset)
        val aMethods = u.getLong(Helper.NeverCall::class.java, methodsOffset)
        val artMethodSizeVal = bAddr - aAddr
        val artMethodBiasVal = aAddr - aMethods - artMethodSizeVal

        val fI = Helper.NeverCall::class.java.getDeclaredField("i").apply { isAccessible = true }
        val fJ = Helper.NeverCall::class.java.getDeclaredField("j").apply { isAccessible = true }
        val mhI = MethodHandles.lookup().unreflectGetter(fI)
        val mhJ = MethodHandles.lookup().unreflectGetter(fJ)
        val iAddr = u.getLong(mhI, artOffset)
        val jAddr = u.getLong(mhJ, artOffset)
        val iFields = u.getLong(Helper.NeverCall::class.java, iFieldOffset)
        val artFieldSizeVal = jAddr - iAddr
        val artFieldBiasVal = iAddr - iFields

        val data = LongArray(4)
        data[0] = artMethodSizeVal
        data[1] = artMethodBiasVal
        data[2] = artFieldSizeVal
        data[3] = artFieldBiasVal
        return data
    }

    @Throws(NoSuchMethodException::class, InvocationTargetException::class, IllegalAccessException::class)
    private fun <T> unsafeInvoke(
        clazz: Class<*>,
        instance: Any?,
        methodName: String,
        vararg args: Any?
    ): T {
        val u = unsafe ?: throw IllegalStateException("Unsafe not initialized")
        val stub = Helper.InvokeStub::class.java.getDeclaredMethod("invoke", Array<Any>::class.java)
        stub.isAccessible = true
        val methodsAddr = u.getLong(clazz, methodsOffset)
        if (methodsAddr == 0L) throw NoSuchMethodException("Cannot find matching method")
        val numMethods = u.getInt(methodsAddr)
        for (i in 0 until numMethods) {
            val methodAddr = methodsAddr + i * artMethodSize + artMethodBias
            u.putLong(stub, methodOffset, methodAddr)
            if (methodName == stub.name) {
                val params = stub.parameterTypes
                if (Helper.checkArgsForInvokeMethod(params, args)) {
                    @Suppress("UNCHECKED_CAST")
                    return stub.invoke(instance, arrayOf<Any?>(args)) as T
                }
            }
        }
        throw NoSuchMethodException("Cannot find matching method $methodName")
    }

    @Throws(NoSuchMethodException::class, IllegalAccessException::class, InvocationTargetException::class, InstantiationException::class)
    private fun <T> unsafeNewInstance(clazz: Class<*>, vararg args: Any?): T {
        val u = unsafe ?: throw IllegalStateException("Unsafe not initialized")
        val stub = Helper.InvokeStub::class.java.getDeclaredMethod("invoke", Array<Any>::class.java)
        val ctor = Helper.InvokeStub::class.java.getDeclaredConstructor(Array<Any>::class.java)
        ctor.isAccessible = true
        val methodsAddr = u.getLong(clazz, methodsOffset)
        if (methodsAddr == 0L) throw NoSuchMethodException("Cannot find matching constructor")
        val numMethods = u.getInt(methodsAddr)
        for (i in 0 until numMethods) {
            val methodAddr = methodsAddr + i * artMethodSize + artMethodBias
            u.putLong(stub, methodOffset, methodAddr)
            if ("<init>" == stub.name) {
                u.putLong(ctor, methodOffset, methodAddr)
                val params = ctor.parameterTypes
                if (Helper.checkArgsForInvokeMethod(params, args)) {
                    u.putObject(ctor, classOffset, clazz)
                    @Suppress("UNCHECKED_CAST")
                    return ctor.newInstance(arrayOf<Any?>(args)) as T
                }
            }
        }
        throw NoSuchMethodException("Cannot find matching constructor")
    }

    @Throws(NoSuchMethodException::class, InvocationTargetException::class, IllegalAccessException::class)
    private fun <T> propertyInvoke(
        clazz: Class<*>,
        instance: Any?,
        methodName: String,
        vararg args: Any?
    ): T {
        val declaredMethods = getDeclaredMethodsProperty(clazz)
        for (method in declaredMethods) {
            if (method.name == methodName) {
                val params = method.parameterTypes
                if (Helper.checkArgsForInvokeMethod(params, args)) {
                    method.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    return method.invoke(instance, *args) as T
                }
            }
        }
        throw NoSuchMethodException("Cannot find matching method $methodName")
    }

    @Throws(NoSuchMethodException::class, IllegalAccessException::class, InvocationTargetException::class, InstantiationException::class)
    private fun propertyNewInstance(clazz: Class<*>, vararg initargs: Any?): Any {
        val constructors = getDeclaredConstructorsProperty(clazz)
        for (constructor in constructors) {
            val params = constructor.parameterTypes
            if (Helper.checkArgsForInvokeMethod(params, initargs)) {
                constructor.isAccessible = true
                return constructor.newInstance(initargs)
            }
        }
        throw NoSuchMethodException("Cannot find matching constructor")
    }

    private fun getDeclaredMethodsProperty(clazz: Class<*>): List<Method> {
        return try {
            val array = methodsProperty.get(clazz) ?: emptyArray()
            array.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getDeclaredConstructorsProperty(clazz: Class<*>): List<Constructor<*>> {
        return try {
            val array = constructorsProperty.get(clazz) ?: emptyArray()
            array.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getDeclaredFieldsProperty(clazz: Class<*>): List<Field> {
        return try {
            val array = fieldsProperty.get(clazz) ?: emptyArray()
            array.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getDeclaredFieldsUnsafe(clazz: Class<*>): List<Field> {
        val list = ArrayList<Field>()
        list.addAll(getInstanceFieldsUnsafe(clazz))
        list.addAll(getStaticFieldsUnsafe(clazz))
        return list
    }

    private fun getInstanceFieldsUnsafe(clazz: Class<*>): List<Field> {
        if (clazz.isPrimitive || clazz.isArray) return emptyList()
        val u = unsafe ?: return emptyList()
        val mh = try {
            val fI = Helper.NeverCall::class.java.getDeclaredField("i").apply { isAccessible = true }
            MethodHandles.lookup().unreflectGetter(fI)
        } catch (e: Exception) {
            return emptyList()
        }
        val fieldsAddr = u.getLong(clazz, iFieldOffset)
        if (fieldsAddr == 0L) return emptyList()
        val numFields = u.getInt(fieldsAddr)
        val list = ArrayList<Field>(numFields)
        for (i in 0 until numFields) {
            val fieldAddr = fieldsAddr + i * artFieldSize + artFieldBias
            u.putLong(mh, artOffset, fieldAddr)
            val member = MethodHandles.reflectAs(Field::class.java, mh)
            if (!Modifier.isStatic(member.modifiers)) {
                list.add(member)
            }
        }
        return list
    }

    private fun getStaticFieldsUnsafe(clazz: Class<*>): List<Field> {
        if (clazz.isPrimitive || clazz.isArray) return emptyList()
        val u = unsafe ?: return emptyList()
        val mh = try {
            val fS = Helper.NeverCall::class.java.getDeclaredField("s").apply { isAccessible = true }
            MethodHandles.lookup().unreflectGetter(fS)
        } catch (e: Exception) {
            return emptyList()
        }
        val fieldsAddr = u.getLong(clazz, sFieldOffset)
        if (fieldsAddr == 0L) return emptyList()
        val numFields = u.getInt(fieldsAddr)
        val list = ArrayList<Field>(numFields)
        for (i in 0 until numFields) {
            val fieldAddr = fieldsAddr + i * artFieldSize + artFieldBias
            u.putLong(mh, artOffset, fieldAddr)
            val member = MethodHandles.reflectAs(Field::class.java, mh)
            if (Modifier.isStatic(member.modifiers)) {
                list.add(member)
            }
        }
        return list
    }

    /**
     * Advanced: Reflection-based invocation of hidden methods.
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
     */
    fun <T> invoke(
        clazz: Class<*>,
        instance: Any?,
        methodName: String,
        paramTypes: Array<out Class<*>>,
        vararg args: Any?
    ): T {
        // 1. Try standard reflection first
        val standardResult = runCatching {
            val method = getDeclaredMethod(clazz, methodName, *paramTypes)
            @Suppress("UNCHECKED_CAST")
            method.invoke(instance, *args) as T
        }
        if (standardResult.isSuccess) {
            return standardResult.getOrThrow()
        }

        // 2. Fallback to Unsafe-based invoke
        if (ensureUnsafeBypassReady()) {
            val unsafeResult = runCatching {
                unsafeInvoke<T>(clazz, instance, methodName, *args)
            }
            if (unsafeResult.isSuccess) {
                return unsafeResult.getOrThrow()
            }
        }

        // 3. Fallback to Property-based invoke
        if (isPropertyBypassReady) {
            val propertyResult = runCatching {
                propertyInvoke<T>(clazz, instance, methodName, *args)
            }
            if (propertyResult.isSuccess) {
                return propertyResult.getOrThrow()
            }
        }

        throw standardResult.exceptionOrNull() ?: NoSuchMethodException("Method $methodName not found on ${clazz.name}")
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
     */
    fun <T> newInstance(clazz: Class<*>, paramTypes: Array<out Class<*>>, vararg args: Any?): T {
        // 1. Try standard reflection first
        val standardResult = runCatching {
            val constructor = getDeclaredConstructor(clazz, *paramTypes)
            @Suppress("UNCHECKED_CAST")
            constructor.newInstance(*args) as T
        }
        if (standardResult.isSuccess) {
            return standardResult.getOrThrow()
        }

        // 2. Try Unsafe fallback
        if (ensureUnsafeBypassReady()) {
            val unsafeResult = runCatching {
                unsafeNewInstance<T>(clazz, *args)
            }
            if (unsafeResult.isSuccess) {
                return unsafeResult.getOrThrow()
            }
        }

        // 3. Try Property fallback
        if (isPropertyBypassReady) {
            val propertyResult = runCatching {
                @Suppress("UNCHECKED_CAST")
                propertyNewInstance(clazz, *args) as T
            }
            if (propertyResult.isSuccess) {
                return propertyResult.getOrThrow()
            }
        }

        throw standardResult.exceptionOrNull() ?: NoSuchMethodException("Constructor not found on ${clazz.name}")
    }

    private fun getDeclaredConstructor(
        clazz: Class<*>,
        vararg parameterTypes: Class<*>
    ): Constructor<*> {
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
     * Finds a method and ensures it is accessible, traversing the hierarchy.
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
            return when (provided) {
                Int::class.javaObjectType -> declared == Int::class.javaPrimitiveType
                Boolean::class.javaObjectType -> declared == Boolean::class.javaPrimitiveType
                Long::class.javaObjectType -> declared == Long::class.javaPrimitiveType
                Double::class.javaObjectType -> declared == Double::class.javaPrimitiveType
                Float::class.javaObjectType -> declared == Float::class.javaPrimitiveType
                Byte::class.javaObjectType -> declared == Byte::class.javaPrimitiveType
                Char::class.javaObjectType -> declared == Char::class.javaPrimitiveType
                Short::class.javaObjectType -> declared == Short::class.javaPrimitiveType
                else -> false
            }
        }
        return false
    }

    /**
     * Directly get a field bypassing access checks, traversing hierarchy.
     */
    fun <T> getField(instance: Any, fieldName: String): T {
        val target = if (instance is Class<*>) null else instance
        @Suppress("RedundantNullableReturnType") //might be null in some superclasses
        val clazz: Class<*>? = instance as? Class<*> ?: instance.javaClass

        // 1. Try standard reflection first
        val standardResult = runCatching {
            var current = clazz
            while (current != null) {
                try {
                    val field = current.getDeclaredField(fieldName)
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    return field.get(target) as T
                } catch (e: NoSuchFieldException) {
                    current = current.superclass
                }
            }
            throw NoSuchFieldException("Field $fieldName not found")
        }
        if (standardResult.isSuccess) {
            return standardResult.getOrThrow()
        }

        // 2. Try Unsafe fallback
        if (ensureUnsafeBypassReady()) {
            val unsafeResult = runCatching {
                var current = clazz
                while (current != null) {
                    try {
                        val fields = getDeclaredFieldsUnsafe(current)
                        val field = fields.find { it.name == fieldName } ?: throw NoSuchFieldException()
                        field.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        return field.get(target) as T
                    } catch (e: Exception) {
                        current = current.superclass
                    }
                }
                throw NoSuchFieldException()
            }
            if (unsafeResult.isSuccess) {
                return unsafeResult.getOrThrow()
            }
        }

        // 3. Try Property fallback
        if (isPropertyBypassReady) {
            val propertyResult = runCatching {
                var current = clazz
                while (current != null) {
                    try {
                        val fields = getDeclaredFieldsProperty(current)
                        val field = fields.find { it.name == fieldName } ?: throw NoSuchFieldException()
                        field.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        return field.get(target) as T
                    } catch (e: Exception) {
                        current = current.superclass
                    }
                }
                throw NoSuchFieldException()
            }
            if (propertyResult.isSuccess) {
                return propertyResult.getOrThrow()
            }
        }

        throw NoSuchFieldException("Field $fieldName not found on ${if (instance is Class<*>) instance.name else instance.javaClass.name}")
    }
}
