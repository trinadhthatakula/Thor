package com.valhalla.bypass

import dalvik.system.PathClassLoader
import java.lang.invoke.MethodHandle
import java.lang.reflect.Executable

internal class CoreOjClassLoader : PathClassLoader(getCoreOjPath(), null) {

    override fun loadClass(name: String): Class<*> {
        if (Any::class.java.name == name) {
            return Any::class.java
        }
        try {
            return findClass(name)
        } catch (ignored: ClassNotFoundException) {
            // no class file in jar before art moved to apex.
        }
        when (name) {
            Executable::class.java.name -> return Helper.Executable::class.java
            MethodHandle::class.java.name -> return Helper.MethodHandle::class.java
            Class::class.java.name -> return Helper.Class::class.java
        }
        return super.loadClass(name)
    }

    companion object {
        @JvmStatic
        fun getCoreOjPath(): String {
            val bootClassPath = System.getProperty("java.boot.class.path", "") ?: ""
            return bootClassPath.split(":".toRegex(), 2).toTypedArray()[0]
        }
    }
}
