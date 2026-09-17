package com.autoscript.bridge

/** 可注入时钟：让 TTL/到期逻辑在测试里不需要真实等待。 */
fun interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}