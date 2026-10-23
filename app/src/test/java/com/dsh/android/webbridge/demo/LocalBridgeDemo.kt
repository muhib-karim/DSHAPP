package com.dsh.android.webbridge.demo

import com.dsh.android.webbridge.BridgeServer
import com.dsh.android.webbridge.WebChat
import com.dsh.android.webbridge.WebDelta
import java.io.File

/** Not a test: a local harness (uncommitted) that serves the real bridge with a scripted web chat. */
object LocalBridgeDemo {
    @JvmStatic fun main(args: Array<String>) {
        val log = File(args.getOrElse(0) { "/tmp/bridge-prompts.log" })
        val chat = object : WebChat {
            override fun complete(prompt: String, thinking: Boolean, search: Boolean, cancelled: () -> Boolean, onDelta: (WebDelta) -> Unit) {
                log.appendText("=== PROMPT (${prompt.length} chars) thinking=$thinking\n$prompt\n")
                onDelta(WebDelta.Thinking("Let me think about it."))
                if (prompt.contains("[Tool result call_")) {
                    onDelta(WebDelta.Text("The file says: " + prompt.substringAfterLast("[Tool result").lines().drop(1).take(2).joinToString(" ").take(200)))
                } else if (prompt.contains("<tool_call>") && prompt.contains("hello.txt")) {
                    val tool = Regex("- (read[a-z_]*|fs_read[a-z_]*|view[a-z_]*)").find(prompt)?.groupValues?.get(1) ?: "read"
                    onDelta(WebDelta.Text("I'll read it.\n<tool_call>{\"name\":\"$tool\",\"arguments\":{\"path\":\"hello.txt\"}}</tool_call>"))
                } else {
                    onDelta(WebDelta.Text("Hello from the DeepSeek web bridge."))
                }
                onDelta(WebDelta.Finished)
            }
        }
        val server = BridgeServer("demo-secret-0123456789", { chat }, ::println)
        server.start(3397)
        Thread.sleep(Long.MAX_VALUE)
    }
}
