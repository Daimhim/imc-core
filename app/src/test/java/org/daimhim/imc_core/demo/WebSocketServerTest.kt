package org.daimhim.imc_core.demo

import org.java_websocket.drafts.Draft_6455
import org.java_websocket.enums.Opcode
import org.java_websocket.framing.PingFrame
import org.java_websocket.framing.PongFrame
import org.java_websocket.server.WebSocketServer
import org.junit.Test
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.regex.Pattern
import kotlin.experimental.and

class WebSocketServerTest {
    private var outputStream: OutputStream? = null
    private val writeQueue =  LinkedBlockingQueue<ByteBuffer>()
    private val writeRunnable = Runnable {
        println("无限写入启动")
        while (true){
            val take = writeQueue.take()
            outputStream?.write(take.array())
            outputStream?.flush()
        }
    }

    //    private val readRunnable = Runnable {
//
//    }
    @Test
    fun addition_isCorrect() {
        val serverSocket = ServerSocket(3390)
        val accept = serverSocket.accept()
        println("ServerSocket 启动")
        outputStream = accept.getOutputStream()

        val input = accept.getInputStream()
        val scanner = Scanner(input, Charsets.UTF_8.name())
        val data = scanner.useDelimiter("\\r\\n\\r\\n").next();
        val get = Pattern.compile("^GET").matcher(data);
        val match = Pattern.compile("Sec-WebSocket-Key: (.*)").matcher(data);
        match.find();
        val response = ("HTTP/1.1 101 Switching Protocols\r\n"
                + "Connection: Upgrade\r\n"
                + "Upgrade: websocket\r\n"
                + "Sec-WebSocket-Accept: "
                + Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest(
                (match.group(1)!!
                    .plus("258EAFA5-E914-47DA-95CA-C5AB0DC85B11")).toByteArray(Charsets.UTF_8)
            )
        )
                + "\r\n\r\n").toByteArray(Charsets.UTF_8)
        outputStream?.write(response, 0, response.size)

        Thread(writeRunnable).start()
        val rawbuffer = ByteArray(16384)
        var readBytes: Int
        while (input.read(rawbuffer).apply { readBytes = this } != -1) {
            decode(ByteBuffer.wrap(rawbuffer,0,readBytes))
        }
    }

    private val draft = Draft_6455()
    fun decode(buffer: ByteBuffer) {
        println("WebSocketServerTest.decode")
        val translateFrame = draft.translateFrame(buffer)
        val first = translateFrame.first()
        println("first:${first::class.java.name}")
        if (first is PingFrame){
            val pongFrame = PongFrame()
            val createBinaryFrame = draft.createBinaryFrame(pongFrame)
            writeQueue.add(createBinaryFrame)
        }
    }

}