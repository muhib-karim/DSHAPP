package com.dsh.android.web

import android.content.Context

/**
 * 建当前 flavor 的 Web 内核宿主。
 *
 * ⚠️ 这个函数**每个 flavor 源集各有一份**（`src/system/.../WebHostFactory.kt` 与
 *   `src/gecko/.../WebHostFactory.kt`），函数签名必须一致 —— 它是两个内核版本
 *   对 `MainActivity` 的唯一接缝。同名类在两个源集里不会冲突：AGP 一次只编译
 *   一个 flavor 的源集。
 */
fun createWebHost(context: Context, callbacks: WebHostCallbacks): WebHost =
    GeckoWebHost(context, callbacks)
