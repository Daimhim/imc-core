package org.daimhim.imc_core

import timber.multiplatform.log.CustomTagTree
import timber.multiplatform.log.Tree

class TimberIMCLog(customTag:String = "IEngine") : IIMCLogFactory {
    private val tree: Tree = CustomTagTree(customTag)
    override fun v(t: Throwable?) {
        tree.v(t)
    }

    override fun v(message: String?, vararg args: Any?) {
        tree.v(message,*args)
    }

    override fun v(t: Throwable?, message: String?, vararg args: Any?) {
        tree.v(t,message,*args)
    }

    override fun d(t: Throwable?) {
        tree.d(t)
    }

    override fun d(message: String?, vararg args: Any?) {
        tree.d(message, *args)
    }

    override fun d(t: Throwable?, message: String?, vararg args: Any?) {
        tree.d(t, message, args)
    }

    override fun i(t: Throwable?) {
        tree.i(t)
    }

    override fun i(message: String?, vararg args: Any?) {
        tree.i(message, *args)
    }

    override fun i(t: Throwable?, message: String?, vararg args: Any?) {
        tree.i(t, message, *args)
    }

    override fun w(t: Throwable?) {
        tree.w(t)
    }

    override fun w(message: String?, vararg args: Any?) {
        tree.w(message, *args)
    }

    override fun w(t: Throwable?, message: String?, vararg args: Any?) {
        tree.w(t, message, *args)
    }

    override fun e(t: Throwable?) {
        tree.e(t)
    }

    override fun e(message: String?, vararg args: Any?) {
        tree.e(message, *args)
    }

    override fun e(t: Throwable?, message: String?, vararg args: Any?) {
        tree.e(t, message, *args)
    }

    override fun wtf(t: Throwable?) {
        tree.wtf(t)
    }

    override fun wtf(message: String?, vararg args: Any?) {
        tree.wtf(message, *args)
    }

    override fun wtf(t: Throwable?, message: String?, vararg args: Any?) {
        tree.wtf(t, message, *args)
    }

    override fun log(priority: Int, t: Throwable?) {
        tree.log(priority, t)
    }

    override fun log(priority: Int, message: String?, vararg args: Any?) {
        tree.log(priority, message, *args)
    }

    override fun log(priority: Int, t: Throwable?, message: String?, vararg args: Any?) {
        tree.log(priority, t, message, *args)
    }

    override fun printlnStackTrace(tag: String?) {
        tree.printlnStackTrace(tag)
    }
}