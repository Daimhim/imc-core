package org.daimhim.imc_core

import okhttp3.Request
import okio.ByteString

interface IEngine {
    /**
     * Start engine
     *  同步启动 会等到服务彻底打开
     * @param url
     * @return
     */
    fun engineOn(key:String)
    fun engineOn(request: Request,engineActionListener: IEngineActionListener? = null)
    /**
     * 熄火
     */
    fun engineOff()
    /**
     * 主动获取服务器状态
     */
    fun engineState():Int
    /**
     * 消息发送
     */
    fun send(byteString: ByteString)
    /**
     * 消息发送
     */
    fun send(text: String)

    /**
     * 订阅定制监听
     */
    fun addIMCListener(imcListener: V2IMCListener)
    /**
     * Remove i m c listener
     *
     * @param imcListener 待删除的监听
     */
    fun removeIMCListener(imcListener: V2IMCListener)
    /**
     * Add i m c socket listener
     *
     * @param imcSocketListener 待添加的消息接收拦截器
     */
    fun addIMCSocketListener(level:Int = IMCListenerManager.DEFAULT_LEVEL, imcSocketListener: V2IMCSocketListener<IEngine>)
    /**
     * Remove i m c socket listener
     *
     * @param imcSocketListener 待移除的消息接收拦截器
     */
    fun removeIMCSocketListener(imcSocketListener: V2IMCSocketListener<IEngine>)

    fun setIMCStatusListener(listener: IMCStatusListener?)

    /**
     * Set foreground
     *
     * @param foreground true 前台，false 后台
     */
    fun onChangeMode(mode:Int)
    fun onNetworkChange(networkState:Int)
}