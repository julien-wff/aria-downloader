package com.julien_wff.aria_android.aria2

internal interface NativeBackend {
    fun open(options: Array<String>)
    fun add(id: String, url: String, options: Array<String>)
    fun pause(id: String)
    fun resume(id: String)
    fun remove(id: String)
    fun update(id: String, options: Array<String>)
    fun tick()
    /** status, completed, total, speed, error; all counters cross JNI as longs. */
    fun snapshot(id: String): LongArray
    fun shutdown()
}

internal class JniBackend : NativeBackend {
    companion object {
        init { System.loadLibrary("aria_jni") }
    }
    external override fun open(options: Array<String>)
    external override fun add(id: String, url: String, options: Array<String>)
    external override fun pause(id: String)
    external override fun resume(id: String)
    external override fun remove(id: String)
    external override fun update(id: String, options: Array<String>)
    external override fun tick()
    external override fun snapshot(id: String): LongArray
    external override fun shutdown()
}
