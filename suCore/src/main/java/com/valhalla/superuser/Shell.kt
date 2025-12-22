@file:Suppress("unused")

package com.valhalla.superuser

import androidx.annotation.IntDef
import com.valhalla.superuser.internal.BuilderImpl
import com.valhalla.superuser.internal.MainShell
import com.valhalla.superuser.internal.UiThreadHandler
import com.valhalla.superuser.internal.Utils
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * A class providing APIs to an interactive Unix shell.
 *
 *
 * Similar to threads where there is a special "main thread", `libsu` also has the
 * concept of the "main shell". For each process, there is a single globally shared
 * "main shell" that is constructed on-demand and cached.
 *
 *
 * To obtain/create the main shell, use the static `Shell.getShell(...)` methods.
 * Developers can use these high level APIs to access the main shell:
 *
 *  * [.cmd]
 *  * [.cmd]
 *
 */
abstract class Shell : Closeable {

    /* Preserve 2 due to historical reasons */
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(UNKNOWN, NON_ROOT_SHELL, ROOT_SHELL)
    internal annotation class Status

    /* Preserve (1 << 2) due to historical reasons */ /* Preserve (1 << 3) due to historical reasons */ /* Preserve (1 << 4) due to historical reasons */
    @Suppress("DEPRECATION")
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(flag = true, value = [FLAG_NON_ROOT_SHELL, FLAG_MOUNT_MASTER, FLAG_REDIRECT_STDERR])
    internal annotation class ConfigFlags

    /* ***************
     * Non-static APIs
     * ****************/
    /**
     * Return whether the shell is still alive.
     * @return `true` if the shell is still alive.
     */
    abstract val isAlive: Boolean

    /**
     * Execute a low-level [Task] using the shell. USE THIS METHOD WITH CAUTION!
     *
     *
     * This method exposes raw STDIN/STDOUT/STDERR directly to the developer. This is meant for
     * implementing low-level operations. The shell may stall if the buffer of STDOUT/STDERR
     * is full. It is recommended to use additional threads to consume STDOUT/STDERR in parallel.
     *
     *
     * STDOUT/STDERR is cleared before executing the task. No output from any previous tasks should
     * be left over. It is the developer's responsibility to make sure all operations are done;
     * the shell should be in idle and waiting for further input when the task returns.
     * @param task the desired task.
     * @throws IOException I/O errors when doing operations with STDIN/STDOUT/STDERR
     */
    @Throws(IOException::class)
    abstract fun execTask(task: Task)

    /**
     * Submits a low-level [Task] for execution in a queue of the shell.
     * @param task the desired task.
     * @see .execTask
     */
    abstract fun submitTask(task: Task)

    /**
     * Construct a new [Job] that uses the shell for execution.
     *
     *
     * Unlike [.cmd] and [.cmd], **NO**
     * output will be collected if the developer did not set the output destination with
     * [Job.to] or [Job.to].
     * @return a job that the developer can execute or submit later.
     */
    abstract fun newJob(): Job

    @get:Status
    abstract val status: Int

    val isRoot: Boolean
        /**
         * Return whether the shell has root access.
         * @return `true` if the shell has root access.
         */
        get() = this.status >= ROOT_SHELL

    /**
     * Wait for any current/pending tasks to finish before closing this shell
     * and release any system resources associated with the shell.
     *
     *
     * Blocks until all current/pending tasks have completed execution, or
     * the timeout occurs, or the current thread is interrupted,
     * whichever happens first.
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return `true` if this shell is terminated and
     * `false` if the timeout elapsed before termination, in which
     * the shell can still to be used afterwards.
     * @throws IOException if an I/O error occurs.
     * @throws InterruptedException if interrupted while waiting.
     */
    @Throws(IOException::class, InterruptedException::class)
    abstract fun waitAndClose(timeout: Long, unit: TimeUnit): Boolean

    /**
     * Wait indefinitely for any current/pending tasks to finish before closing this shell
     * and release any system resources associated with the shell.
     * @throws IOException if an I/O error occurs.
     */
    @Throws(IOException::class)
    fun waitAndClose() {
        while (true) {
            try {
                if (waitAndClose(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) break
            } catch (ignored: InterruptedException) {
            }
        }
    }

    /* **************
     * Nested classes
     * ***************/
    /**
     * Builder class for [Shell] instances.
     *
     *
     * Set the default builder for the main shell instance with
     * [.setDefaultBuilder], or directly use a builder object to create new
     * [Shell] instances.
     *
     *
     * Do not subclass this class! Use [.create] to get a new Builder object.
     */
    abstract class Builder {
        var utils: Utils? = null

        /**
         * Set the desired [Initializer]s.
         * @see Initializer
         *
         * @param classes the classes of desired initializers.
         * @return this Builder object for chaining of calls.
         */
        @SafeVarargs
        fun setInitializers(vararg classes: Class<out Initializer?>): Builder {
            (this as BuilderImpl).setInitializersImpl(classes)
            return this
        }

        /**
         * Set flags to control how a new `Shell` will be constructed.
         * @param flags the desired flags.
         * Value is either 0 or bitwise-or'd value of
         * [.FLAG_NON_ROOT_SHELL] or [.FLAG_MOUNT_MASTER]
         * @return this Builder object for chaining of calls.
         */
        abstract fun setFlags(@ConfigFlags flags: Int): Builder

        /**
         * Set the maximum time to wait for shell verification.
         *
         *
         * After the timeout occurs and the shell still has no response,
         * the shell process will be force-closed and throw [NoShellException].
         * @param timeout the maximum time to wait in seconds.
         * The default timeout is 20 seconds.
         * @return this Builder object for chaining of calls.
         */
        abstract fun setTimeout(timeout: Long): Builder

        /**
         * Set the commands that will be used to create a new `Shell`.
         * @param commands commands that will be passed to [Runtime.exec] to create
         * a new [Process].
         * @return this Builder object for chaining of calls.
         */
        abstract fun setCommands(vararg commands: String?): Builder

        /**
         * Combine all of the options that have been set and build a new `Shell` instance.
         *
         *
         * If not [.setCommands], there are 3 methods to construct a Unix shell;
         * if any method fails, it will fallback to the next method:
         *
         *  1. If [.FLAG_NON_ROOT_SHELL] is not set and [.FLAG_MOUNT_MASTER]
         * is set, construct a Unix shell by calling `su --mount-master`.
         * It may fail if the root implementation does not support mount master.
         *  1. If [.FLAG_NON_ROOT_SHELL] is not set, construct a Unix shell by calling
         * `su`. It may fail if the device is not rooted, or root permission is
         * not granted.
         *  1. Construct a Unix shell by calling `sh`. This would never fail in normal
         * conditions, but should it fail, it will throw [NoShellException]
         *
         * The developer should check the status of the returned `Shell` with
         * [.getStatus] since it may be constructed with calling `sh`.
         *
         *
         * If [.setCommands] is called, the provided commands will be used to
         * create a new [Process] directly. If the process fails to create, or the process
         * is not a valid shell, it will throw [NoShellException].
         * @return the created `Shell` instance.
         * @throws NoShellException impossible to construct a [Shell] instance, or
         * initialization failed when using the configured [Initializer]s.
         */
        abstract fun build(): Shell

        /**
         * Combine all of the options that have been set and build a new `Shell` instance
         * with the provided commands.
         * @param commands commands that will be passed to [Runtime.exec] to create
         * a new [Process].
         * @return the built `Shell` instance.
         * @throws NoShellException the provided command cannot create a [Shell] instance, or
         * initialization failed when using the configured [Initializer]s.
         */
        fun build(vararg commands: String?): Shell {
            return setCommands(*commands).build()
        }

        /**
         * Combine all of the options that have been set and build a new `Shell` instance
         * with the provided process.
         * @param process a shell [Process] that has already been created.
         * @return the built `Shell` instance.
         * @throws NoShellException the provided process is not a valid shell, or
         * initialization failed when using the configured [Initializer]s.
         */
        abstract fun build(process: Process?): Shell

        companion object {
            /**
             * Create a new [Builder].
             * @return a new Builder object.
             */
            fun create(): Builder {
                return BuilderImpl()
            }
        }
    }

    /**
     * The result of a [Job].
     */
    abstract class Result {
        /**
         * Get the output of STDOUT.
         * @return a list of strings that stores the output of STDOUT. Empty list if no output
         * is available.
         */
        abstract val out: MutableList<String?>

        /**
         * Get the output of STDERR.
         * @return a list of strings that stores the output of STDERR. Empty list if no output
         * is available.
         */
        abstract val err: MutableList<String?>

        /**
         * Get the return code of the job.
         * @return the return code of the last operation in the shell. If the job is executed
         * properly, the code should range from 0-255. If the job fails to execute, it will return
         * [.JOB_NOT_EXECUTED].
         */
        abstract val code: Int

        val isSuccess: Boolean
            /**
             * Whether the job succeeded.
             * `getCode() == 0`.
             * @return `true` if the return code is 0.
             */
            get() = this.code == 0

        companion object {
            /**
             * This code indicates that the job was not executed, and the outputs are all empty.
             * Constant value: {@value}.
             */
            const val JOB_NOT_EXECUTED: Int = -1
        }
    }

    /**
     * Represents a shell Job that could later be executed or submitted to background threads.
     *
     *
     * All operations added in [.add] and [.add] will be
     * executed in the order of addition.
     */
    abstract class Job {
        /**
         * Store output of STDOUT to a specific list.
         * @param stdout the list to store STDOUT. Pass `null` to omit all outputs.
         * @return this Job object for chaining of calls.
         */
        abstract fun to(stdout: MutableList<String?>?): Job

        /**
         * Store output of STDOUT and STDERR to specific lists.
         * @param stdout the list to store STDOUT. Pass `null` to omit STDOUT.
         * @param stderr the list to store STDERR. Pass `null` to omit STDERR.
         * @return this Job object for chaining of calls.
         */
        abstract fun to(stdout: MutableList<String?>?, stderr: MutableList<String?>?): Job

        /**
         * Add a new operation running commands.
         * @param cmds the commands to run.
         * @return this Job object for chaining of calls.
         */
        abstract fun add(vararg cmds: String): Job

        /**
         * Add a new operation serving an InputStream to STDIN.
         *
         *
         * This is NOT executing the script like `sh script.sh`.
         * This is similar to sourcing the script (`. script.sh`) as the
         * raw content of the script is directly fed into STDIN. If you call
         * `exit` in the script, **the shell will be killed and this
         * shell instance will no longer be alive!**
         * @param inputStream the InputStream to serve to STDIN.
         * The stream will be closed after consumption.
         * @return this Job object for chaining of calls.
         */
        abstract fun add(inputStream: InputStream): Job

        /**
         * Execute the job immediately and returns the result.
         * @return the result of the job.
         */
        abstract fun exec(): Result

        /**
         * Submit the job to an internal queue to run in the background.
         * The result will be returned with a callback running on the main thread.
         * @param cb the callback to receive the result of the job.
         */
        @JvmOverloads
        fun submit(cb: ResultCallback? = null) {
            submit(UiThreadHandler.executor, cb)
        }

        /**
         * Submit the job to an internal queue to run in the background.
         * The result will be returned with a callback executed by the provided executor.
         * @param executor the executor used to handle the result callback event.
         * Pass `null` to run the callback on the same thread executing the job.
         * @param cb the callback to receive the result of the job.
         */
        abstract fun submit(executor: Executor?, cb: ResultCallback?)

        /**
         * Submit the job to an internal queue to run in the background.
         * @return a [Future] to get the result of the job later.
         */
        abstract fun enqueue(): Future<Result?>
    }

    /**
     * The initializer when a new `Shell` is constructed.
     *
     *
     * This is an advanced feature. If you need to run specific operations when a new `Shell`
     * is constructed, extend this class, add your own implementation, and register it with
     * [Builder.setInitializers].
     * The concept is similar to `.bashrc`: run specific scripts/commands when the shell
     * starts up. [.onInit] will be called as soon as the shell is
     * constructed and tested as a valid shell.
     *
     *
     * An initializer will be constructed and the callbacks will be invoked each time a new
     * shell is created.
     */
    class Initializer {
        /**
         * Called when a new shell is constructed.
         * @param shell the newly constructed shell.
         * @return `false` when initialization fails, otherwise `true`.
         */
        fun onInit( shell: Shell): Boolean {
            return true
        }
    }

    /* **********
     * Interfaces
     * **********/
    /**
     * A task that can be executed by a shell with the method [.execTask].
     */
    interface Task {
        /**
         * This method will be called when a task is executed by a shell.
         * Calling [Closeable.close] on any stream is NOP (does nothing).
         * @param stdin the STDIN of the shell.
         * @param stdout the STDOUT of the shell.
         * @param stderr the STDERR of the shell.
         * @throws IOException I/O errors when doing operations with STDIN/STDOUT/STDERR
         */
        @Throws(IOException::class)
        fun run(
            stdin: OutputStream,
            stdout: InputStream,
            stderr: InputStream
        )

        /**
         * This method will be called when a shell is unable to execute this task.
         */
        fun shellDied() {}
    }

    /**
     * The callback used in [.getShell].
     */
    interface GetShellCallback {
        /**
         * @param shell the `Shell` obtained in the asynchronous operation.
         */
        fun onShell(shell: Shell)
    }

    /**
     * The callback to receive a result in [Job.submit].
     */
    interface ResultCallback {
        /**
         * @param out the result of the job.
         */
        fun onResult(out: Result)
    }

    companion object {
        /**
         * Shell status: Unknown. One possible result of [.getStatus].
         *
         *
         * Constant value {@value}.
         */
        const val UNKNOWN: Int = -1

        /**
         * Shell status: Non-root shell. One possible result of [.getStatus].
         *
         *
         * Constant value {@value}.
         */
        const val NON_ROOT_SHELL: Int = 0

        /**
         * Shell status: Root shell. One possible result of [.getStatus].
         *
         *
         * Constant value {@value}.
         */
        const val ROOT_SHELL: Int = 1

        /**
         * If set, create a non-root shell.
         *
         *
         * Constant value {@value}.
         */
        const val FLAG_NON_ROOT_SHELL: Int = (1 shl 0)

        /**
         * If set, create a root shell with the `--mount-master` option.
         *
         *
         * Constant value {@value}.
         */
        const val FLAG_MOUNT_MASTER: Int = (1 shl 1)

        /**
         * The [Executor] that manages all worker threads used in `libsu`.
         *
         *
         * Note: If the developer decides to replace the default Executor, keep in mind that
         * each `Shell` instance requires at least 3 threads to operate properly.
         */
        @JvmField
        var EXECUTOR: Executor = Executors.newCachedThreadPool()

        /**
         * Set to `true` to enable verbose logging throughout the library.
         */
        @JvmField
        var enableVerboseLogging: Boolean = false

        /**
         * This flag exists for compatibility reasons. DO NOT use unless necessary.
         *
         *
         * If enabled, STDERR outputs will be redirected to the STDOUT output list
         * when a [Job] is configured with [Job.to].
         * Since the `Shell.cmd(...)` methods are functionally equivalent to
         * `Shell.getShell().newJob().add(...).to(new ArrayList<>())`, this variable
         * also affects the behavior of those methods.
         *
         *
         * Note: The recommended way to redirect STDERR output to STDOUT is to assign the
         * same list to both STDOUT and STDERR with [Job.to].
         * The behavior of this flag is unintuitive and error prone.
         */
        @JvmField
        var enableLegacyStderrRedirection: Boolean = false

        /**
         * Override the default [Builder].
         *
         *
         * This shell builder will be used to construct the main shell.
         * Set this before the main shell is created anywhere in the program.
         */
        fun setDefaultBuilder(builder: Builder?) {
            MainShell.setBuilder(builder)
        }

        val shell: Shell
            /**
             * Get the main shell instance.
             *
             *
             * If [.getCachedShell] returns null, the default [Builder] will be used to
             * construct a new `Shell`.
             *
             *
             * Unless already cached, this method blocks until the main shell is created.
             * The process could take a very long time (e.g. root permission request prompt),
             * so be extra careful when calling this method from the main thread!
             *
             *
             * A good practice is to "preheat" the main shell during app initialization
             * (e.g. the splash screen) by either calling this method in a background thread or
             * calling [.getShell] so subsequent calls to this function
             * returns immediately.
             * @return the cached/created main shell instance.
             * @see Builder.build
             */
            get() = MainShell.get()

        /**
         * Get the main shell instance asynchronously via a callback.
         *
         *
         * If [.getCachedShell] returns null, the default [Builder] will be used to
         * construct a new `Shell` in a background thread.
         * The cached/created shell instance is returned to the callback on the main thread.
         * @param callback invoked when a shell is acquired.
         */
        fun getShell(callback: GetShellCallback) {
            MainShell.get(UiThreadHandler.executor, callback)
        }

        /**
         * Get the main shell instance asynchronously via a callback.
         *
         *
         * If [.getCachedShell] returns null, the default [Builder] will be used to
         * construct a new `Shell` in a background thread.
         * The cached/created shell instance is returned to the callback executed by provided executor.
         * @param executor the executor used to handle the result callback event.
         * If `null` is passed, the callback can run on any thread.
         * @param callback invoked when a shell is acquired.
         */
        fun getShell(executor: Executor?, callback: GetShellCallback) {
            MainShell.get(executor, callback)
        }

        val cachedShell: Shell?
            /**
             * Get the cached main shell.
             * @return a `Shell` instance. `null` can be returned either when
             * no main shell has been cached, or the cached shell is no longer active.
             */
            get() = MainShell.cached

        val isAppGrantedRoot: Boolean?
            /**
             * Whether the application has access to root.
             *
             *
             * This method returns `null` when it is currently unable to determine whether
             * root access has been granted to the application. A non-null value meant that the root
             * permission grant state has been accurately determined and finalized. The application
             * must have at least 1 root shell created to have this method return `true`.
             * This method will not block the calling thread; results will be returned immediately.
             * @return whether the application has access to root, or `null` when undetermined.
             */
            get() = Utils.isAppGrantedRoot

        /* ************
    * Static APIs
    * ************/
        /**
         * Create a pending [Job] of the main shell with commands.
         *
         *
         * This method can be treated as functionally equivalent to
         * `Shell.getShell().newJob().add(commands).to(new ArrayList<>())`, but the internal
         * implementation is specialized for this use case and does not run this exact code.
         * The developer can manually override output destination(s) with either
         * [Job.to] or [Job.to].
         *
         *
         * The main shell will NOT be requested until the developer invokes either
         * [Job.exec], [Job.enqueue], or `Job.submit(...)`. This makes it
         * possible to construct [Job]s before the program has created any root shell.
         * @return a job that the developer can execute or submit later.
         * @see Job.add
         */
        fun cmd(vararg commands: String): Job {
            return MainShell.newJob(*commands)
        }

        /**
         * Create a pending [Job] of the main shell with an [InputStream].
         *
         *
         * This method can be treated as functionally equivalent to
         * `Shell.getShell().newJob().add(in).to(new ArrayList<>())`, but the internal
         * implementation is specialized for this use case and does not run this exact code.
         * The developer can manually override output destination(s) with either
         * [Job.to] or [Job.to].
         *
         *
         * The main shell will NOT be requested until the developer invokes either
         * [Job.exec], [Job.enqueue], or `Job.submit(...)`. This makes it
         * possible to construct [Job]s before the program has created any root shell.
         * @see Job.add
         */
        fun cmd(`in`: InputStream): Job {
            return MainShell.newJob(`in`)
        }

        /* ***********
     * Deprecated
     * ***********/

        @Deprecated("Not used anymore")
        const val ROOT_MOUNT_MASTER: Int = 2

        /**
         * For compatibility, setting this flag will set [.enableLegacyStderrRedirection]
         * @see .enableLegacyStderrRedirection
         */
        @Deprecated(
            """not used anymore"""
        )
        const val FLAG_REDIRECT_STDERR: Int = (1 shl 3)

        /**
         * Whether the application has access to root.
         *
         *
         * This method would NEVER produce false negatives, but false positives can be returned before
         * actually constructing a root shell. A `false` returned is guaranteed to be
         * 100% accurate, while `true` may be returned if the device is rooted, but the user
         * did not grant root access to your application. However, after any root shell is constructed,
         * this method will accurately return `true`.
         * @return whether the application has access to root.
         */
        @Deprecated("please switch to {@link #isAppGrantedRoot()}")
        fun rootAccess(): Boolean {
            return isAppGrantedRoot == java.lang.Boolean.TRUE
        }
    }
}
