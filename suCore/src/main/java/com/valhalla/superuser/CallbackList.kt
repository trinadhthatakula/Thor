package com.valhalla.superuser

import com.valhalla.superuser.internal.UiThreadHandler
import java.util.AbstractList
import java.util.concurrent.Executor

/**
 * An [AbstractList] that calls `onAddElement` when a new element is added to the list.
 *
 *
 * To simplify the API of [Shell], both STDOUT and STDERR will output to [List]s.
 * This class is useful if you want to trigger a callback every time [Shell]
 * outputs a new line.
 *
 *
 * The `CallbackList` itself does not have a data store. If you need one, you can provide a
 * base [List], and this class will delegate its calls to it.
 */
abstract class CallbackList<E>
/**
 * [.onAddElement] runs with the executor; no backing list.
 */ protected constructor(
    protected var mExecutor: Executor = UiThreadHandler.executor,
    protected var mBase: MutableList<E?>? = null
) : AbstractList<E?>() {
    /**
     * [.onAddElement] runs on the main thread; sets a backing list.
     */
    protected constructor(base: MutableList<E?>?) : this(UiThreadHandler.executor, base)

    /**
     * [.onAddElement] runs with the executor; sets a backing list.
     */
    /**
     * [.onAddElement] runs on the main thread; no backing list.
     */

    /**
     * The callback when a new element is added.
     *
     *
     * This method will be called after `add` is called.
     * Which thread it runs on depends on which constructor is used to construct the instance.
     * @param e the new element added to the list.
     */
    abstract fun onAddElement(e: E?)

    /**
     * @see List.get
     */
    override fun get(i: Int): E? {
        return if (mBase == null) null else mBase!![i]
    }

    override fun set(i: Int, s: E?): E? {
        return if (mBase == null) null else mBase!!.set(i, s)
    }

    override fun add(i: Int, s: E?) {
        if (mBase != null) mBase!!.add(i, s)
        mExecutor.execute(Runnable { onAddElement(s) })
    }

    override fun remove(o: E?): Boolean {
        return if (mBase == null) false else mBase!!.remove(o)
    }

    override fun removeAt(index: Int): E? {
        return if (mBase == null) null else mBase!!.removeAt(index)
    }

    override fun removeFirst(): E? {
        return if (mBase == null || mBase!!.isEmpty()) null else mBase!!.removeAt(0)
    }

    override var size: Int
        get() = if (mBase == null) 0 else mBase!!.size
        set(value) {
            if (mBase == null) mBase = ArrayList(value)
            else mBase!!.clear()
            for (i in 0 until value) {
                mBase!!.add(null)
            }
        }


}
