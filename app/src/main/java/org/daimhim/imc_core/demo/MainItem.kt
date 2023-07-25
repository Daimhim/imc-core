package org.daimhim.imc_core.demo

data class MainItem(
    val name:String = "",
    val type:Int = 0,
    val content:String = "",
    val time:Long = System.currentTimeMillis(),
)