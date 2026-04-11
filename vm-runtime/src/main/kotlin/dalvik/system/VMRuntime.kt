package dalvik.system

class VMRuntime {
    companion object {
        @JvmStatic
        fun getRuntime(): VMRuntime = throw RuntimeException("Stub!")
    }

    fun setHiddenApiExemptions(vararg signatures: String) {}
}
