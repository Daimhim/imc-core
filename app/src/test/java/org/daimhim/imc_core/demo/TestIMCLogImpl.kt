package org.daimhim.imc_core.demo

import org.daimhim.imc_core.IIMCLogFactory

class TestIMCLogImpl : IIMCLogFactory {
    override fun v(t: Throwable?) {
        v(t,"")
    }

    override fun v(message: String?, vararg args: Any?) {

        v(message,*args)
    }

    override fun v(t: Throwable?, message: String?, vararg args: Any?) {
        TODO("Not yet implemented")
    }

    override fun d(t: Throwable?) {
        TODO("Not yet implemented")
    }

    override fun d(message: String?, vararg args: Any?) {
        TODO("Not yet implemented")
    }

    override fun d(t: Throwable?, message: String?, vararg args: Any?) {
        TODO("Not yet implemented")
    }

    override fun i(t: Throwable?) {
        TODO("Not yet implemented")
    }

    override fun i(message: String?, vararg args: Any?) {
        TODO("Not yet implemented")
    }

    override fun i(t: Throwable?, message: String?, vararg args: Any?) {
        TODO("Not yet implemented")
    }

    override fun w(t: Throwable?) {
        TODO("Not yet implemented")
    }

    override fun w(message: String?, vararg args: Any?) {
        TODO("Not yet implemented")
    }

    override fun w(t: Throwable?, message: String?, vararg args: Any?) {
        TODO("Not yet implemented")
    }

    override fun e(t: Throwable?) {
        TODO("Not yet implemented")
    }

    override fun e(message: String?, vararg args: Any?) {
        TODO("Not yet implemented")
    }

    override fun e(t: Throwable?, message: String?, vararg args: Any?) {
        TODO("Not yet implemented")
    }

    override fun wtf(t: Throwable?) {
        TODO("Not yet implemented")
    }

    override fun wtf(message: String?, vararg args: Any?) {
        TODO("Not yet implemented")
    }

    override fun wtf(t: Throwable?, message: String?, vararg args: Any?) {
        TODO("Not yet implemented")
    }

    override fun log(priority: Int, t: Throwable?) {
        TODO("Not yet implemented")
    }

    override fun log(priority: Int, message: String?, vararg args: Any?) {
        TODO("Not yet implemented")
    }

    override fun log(priority: Int, t: Throwable?, message: String?, vararg args: Any?) {
        TODO("Not yet implemented")
    }

    override fun printlnStackTrace(tag: String?) {
        TODO("Not yet implemented")
    }
}