package org.daimhim.imc_core

object IMCLog {
    private var imcLog:IIMCLogFactory? = null
    internal fun setIIMCLogFactory(imcLog:IIMCLogFactory?){
        this.imcLog = imcLog
    }
    fun testSetIIMCLogFactory(imcLog:IIMCLogFactory?){
        this.imcLog = imcLog
    }
    fun v(t: Throwable?){
        imcLog?.v(t)
    }

    fun v(message: String?, vararg args: Any?){
        imcLog?.v(message, *args)
    }

    fun v(t: Throwable?, message: String?, vararg args: Any?){
        imcLog?.v(t, message, *args)
    }

    fun d(t: Throwable?){
        imcLog?.d(t)
    }

    fun d(message: String?, vararg args: Any?){
        imcLog?.d(message, *args)
    }

    fun d(t: Throwable?, message: String?, vararg args: Any?){
        imcLog?.d(t, message, *args)
    }

    fun i(t: Throwable?){
        imcLog?.i(t)
    }

    fun i(message: String?, vararg args: Any?){
        imcLog?.i(message, *args)
    }

    fun i(t: Throwable?, message: String?, vararg args: Any?){
        imcLog?.i(t, message, *args)
    }

    fun w(t: Throwable?){
        imcLog?.w(t)
    }

    fun w(message: String?, vararg args: Any?){
        imcLog?.w(message, *args)
    }

    fun w(t: Throwable?, message: String?, vararg args: Any?){
        imcLog?.w(t, message, *args)
    }

    fun e(t: Throwable?){
        imcLog?.e(t)
    }

    fun e(message: String?, vararg args: Any?){
        imcLog?.e(message, *args)
    }

    fun e(t: Throwable?, message: String?, vararg args: Any?){
        imcLog?.e(t, message, *args)
    }

    fun wtf(t: Throwable?){
        imcLog?.wtf(t)
    }

    fun wtf(message: String?, vararg args: Any?){
        imcLog?.wtf(message, *args)
    }

    fun wtf(t: Throwable?, message: String?, vararg args: Any?){
        imcLog?.wtf(t, message, *args)
    }

    fun log(priority: Int, t: Throwable?){
        imcLog?.log(priority, t)
    }

    fun log(priority: Int, message: String?, vararg args: Any?){
        imcLog?.log(priority, message, *args)
    }

    fun log(priority: Int, t: Throwable?, message: String?, vararg args: Any?){
        imcLog?.log(priority, t, message, *args)
    }

    fun printlnStackTrace(tag: String?){
        imcLog?.printlnStackTrace(tag)
    }
}