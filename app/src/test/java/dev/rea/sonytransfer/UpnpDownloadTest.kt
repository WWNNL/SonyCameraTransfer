package dev.rea.sonytransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/**
 * 下载稳定性测试：模拟相机「传到一半断流」与「用户取消」，验证断点续传/取消逻辑。
 * 用裸 ServerSocket 起一个极简 HTTP 服务（JDK 的 com.sun.net.httpserver 在单测 classpath 里不可用）。
 */
class UpnpDownloadTest {

    /** 相机第一段只发一半就断流 → 第二次带 Range 请求续传，最终文件应完整。 */
    @Test
    fun resumesIncompleteDownloadWithRange() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val half = data.size / 2
        val requests = ArrayList<Int>()
        val server = RawHttpServer { index, headers, out ->
            synchronized(requests) { requests.add(index) }
            if (index == 1) {
                // 声明完整长度却只发一半：模拟相机侧中断
                writeHead(out, 200, data.size.toLong())
                out.write(data, 0, half)
            } else {
                val start = headers["range"]
                    ?.removePrefix("bytes=")
                    ?.removeSuffix("-")
                    ?.toIntOrNull()
                    ?: 0
                writeHead(out, 206, (data.size - start).toLong())
                out.write(data, start, data.size - start)
            }
            out.flush()
        }
        server.start()
        val target = File.createTempFile("dl-resume", ".tmp")
        try {
            var lastDownloaded = 0L
            val bytes = Upnp.downloadToFile(
                url = "http://127.0.0.1:${server.port}/video.mp4",
                target = target,
                stallTimeoutMs = 5_000,
                maxAttempts = 3,
                onProgress = { downloaded, _ -> lastDownloaded = downloaded },
            )

            assertEquals(data.size.toLong(), bytes)
            assertEquals(data.size.toLong(), target.length())
            assertTrue("文件内容应与源数据一致", target.readBytes().contentEquals(data))
            assertEquals(data.size.toLong(), lastDownloaded)
            assertTrue("应发生至少 2 次请求（首段 + 续传），实际 ${requests.size}", requests.size >= 2)
        } finally {
            server.close()
            target.delete()
        }
    }

    /** 取消后应立刻抛出 DownloadCancelledException，不再继续下载。 */
    @Test
    fun cancelThrowsDownloadCancelledException() {
        val data = ByteArray(400_000) { (it % 199).toByte() }
        val server = RawHttpServer { _, _, out ->
            writeHead(out, 200, data.size.toLong())
            var offset = 0
            val step = 8 * 1024
            while (offset < data.size) {
                out.write(data, offset, step)
                out.flush()
                offset += step
                Thread.sleep(20)
            }
            out.flush()
        }
        server.start()
        val target = File.createTempFile("dl-cancel", ".tmp")
        try {
            val startedAt = System.currentTimeMillis()
            var cancelled = false
            try {
                Upnp.downloadToFile(
                    url = "http://127.0.0.1:${server.port}/slow.mp4",
                    target = target,
                    stallTimeoutMs = 5_000,
                    maxAttempts = 1,
                    shouldCancel = { System.currentTimeMillis() - startedAt > 150 },
                )
            } catch (e: DownloadCancelledException) {
                cancelled = true
            }
            assertTrue("应抛 DownloadCancelledException 取消下载", cancelled)
            assertTrue(
                "取消时不应收完整个文件（实际 ${target.length()} / ${data.size}）",
                target.length() < data.size,
            )
        } finally {
            server.close()
            target.delete()
        }
    }

    private fun writeHead(out: OutputStream, code: Int, contentLength: Long) {
        val reason = if (code == 206) "Partial Content" else "OK"
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Length: $contentLength\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    /** 极简 HTTP/1.1 服务：GET 请求，按请求序号把 socket 交给 handler 自行写响应。 */
    private class RawHttpServer(
        private val handler: (index: Int, headers: Map<String, String>, out: OutputStream) -> Unit,
    ) {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val lock = Object()
        private var accepted = 0

        @Volatile
        private var closed = false

        val port: Int get() = socket.localPort

        fun start() {
            Thread {
                while (!closed) {
                    val client = try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    val index = synchronized(lock) { ++accepted }
                    Thread {
                        runCatching { serve(index, client) }
                    }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        private fun serve(index: Int, client: java.net.Socket) {
            client.use { c ->
                val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
                reader.readLine() ?: return
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                }
                val out = BufferedOutputStream(c.getOutputStream())
                handler(index, Collections.unmodifiableMap(headers), out)
                out.flush()
            }
        }

        fun close() {
            closed = true
            runCatching { socket.close() }
        }
    }
}
