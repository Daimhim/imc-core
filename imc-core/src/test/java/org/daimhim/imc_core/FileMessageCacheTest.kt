package org.daimhim.imc_core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataOutputStream
import java.io.File

/**
 * FileMessageCache 的持久化回归(N3)。
 *
 * 该实现已从「每次改动全量重写」改成追加日志:put / pushFirst / remove 各追加一条
 * 记录,重放时按墓碑删,记录数明显超过队列长度时压实。这里钉住:
 *  - 重放一致性(含墓碑、pushFirst 的队首语义)
 *  - 旧 v2 整快照文件仍能读进来,并被压实成 v3
 *  - 压实前后队列内容一致(压实不丢消息 —— 这是消息不丢的关键路径)
 */
class FileMessageCacheTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "imc-fmc-${System.nanoTime()}")
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun newFile(name: String = "cache.bin"): File = File(dir, name)

    private fun text(id: String, body: String = "payload-$id"): CachedMessage =
        CachedMessage.Text(id, body)

    private fun ids(cache: FileMessageCache): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val m = cache.pollFirst() ?: break
            out.add(m.id)
        }
        return out
    }

    // ── 重放一致性 ─────────────────────────────────────────────────────

    @Test
    fun `put 后重开文件队列顺序一致`() {
        val f = newFile()
        FileMessageCache(f, owner = "u1").apply {
            put(text("a")); put(text("b")); put(text("c"))
        }
        val reloaded = FileMessageCache(f, owner = "u1")
        assertEquals(listOf("a", "b", "c"), ids(reloaded))
    }

    @Test
    fun `pollFirst 的墓碑重放后不再复活`() {
        val f = newFile()
        FileMessageCache(f, owner = "u1").apply {
            put(text("a")); put(text("b")); put(text("c"))
            assertEquals("a", pollFirst()?.id)
        }
        val reloaded = FileMessageCache(f, owner = "u1")
        assertEquals(listOf("b", "c"), ids(reloaded))
    }

    @Test
    fun `pushFirst 保持插队最先发的语义`() {
        val f = newFile()
        FileMessageCache(f, owner = "u1").apply {
            put(text("a")); put(text("b"))
            pushFirst(text("urgent"))
        }
        val reloaded = FileMessageCache(f, owner = "u1")
        assertEquals(listOf("urgent", "a", "b"), ids(reloaded))
    }

    @Test
    fun `remove 按 id 删除且重放后保持`() {
        val f = newFile()
        FileMessageCache(f, owner = "u1").apply {
            put(text("a")); put(text("b")); put(text("c"))
            assertTrue(remove("b"))
        }
        val reloaded = FileMessageCache(f, owner = "u1")
        assertEquals(listOf("a", "c"), ids(reloaded))
    }

    @Test
    fun `clear 后重开是空的`() {
        val f = newFile()
        FileMessageCache(f, owner = "u1").apply {
            put(text("a")); put(text("b"))
            clear()
            assertTrue(isEmpty())
        }
        assertTrue(FileMessageCache(f, owner = "u1").isEmpty())
    }

    @Test
    fun `容量淘汰写墓碑 重放不会复活被淘汰的条目`() {
        val f = newFile()
        // 一条 Text 的 sizeBytes 远小于 200,但塞 50 条会越界触发从队首淘汰
        val cache = FileMessageCache(f, owner = "u1", maxBytes = 200)
        repeat(50) { cache.put(text("m$it")) }
        val sizeBefore = cache.size()
        assertTrue("应当发生过淘汰,实际 size=$sizeBefore", sizeBefore < 50)

        // 重开:条目数必须和内存里一致 —— 被淘汰的条目不能靠日志重放复活
        val reloaded = FileMessageCache(f, owner = "u1")
        assertEquals(sizeBefore, reloaded.size())
    }

    // ── 旧 v2 整快照迁移 ───────────────────────────────────────────────

    @Test
    fun `v2 整快照文件能读进来并压实成 v3`() {
        val f = newFile()
        // 手工写一个 v2 快照(与 encodeMessageCacheBlob 同格式)
        val snapshot = listOf(text("a"), text("b"), text("c"))
        DataOutputStream(f.outputStream().buffered()).use { dos ->
            dos.writeInt(FileMessageCache.MAGIC)
            dos.writeByte(FileMessageCache.VERSION.toInt())
            dos.writeUTF("u1")
            dos.writeInt(snapshot.size)
            snapshot.forEach { writeRecord(dos, it) }
        }

        val cache = FileMessageCache(f, owner = "u1")
        assertEquals(listOf("a", "b", "c"), ids(cache))

        // 压实后仍是 v3,重开依旧正确
        val again = FileMessageCache(f, owner = "u1")
        assertEquals(emptyList<String>(), ids(again))
    }

    @Test
    fun `owner 不一致时丢弃旧账号残留`() {
        val f = newFile()
        FileMessageCache(f, owner = "u1").apply { put(text("a")) }
        assertTrue(FileMessageCache(f, owner = "u2").isEmpty())
    }

    // ── 压实不丢消息 ───────────────────────────────────────────────────

    @Test
    fun `大量混合操作后压实不丢消息`() {
        val f = newFile()
        val cache = FileMessageCache(f, owner = "u1")
        // 每 put 4 条 poll 1 条,攒出大量墓碑,一定会越过压实阈值(COMPACT_SLACK=64)
        val expected = ArrayList<String>()
        repeat(300) { i ->
            val id = "m$i"
            cache.put(text(id))
            expected.add(id)
            if (i % 4 == 3) {
                val polled = cache.pollFirst()
                assertEquals(expected.removeAt(0), polled?.id)
            }
        }
        val reloaded = FileMessageCache(f, owner = "u1")
        assertEquals(expected, ids(reloaded))
    }

    @Test
    fun `二进制消息也能正确重放`() {
        val f = newFile()
        val payload = ByteArray(64) { it.toByte() }
        FileMessageCache(f, owner = "u1").apply {
            put(CachedMessage.Binary("bin1", payload))
            put(text("t1"))
        }
        val reloaded = FileMessageCache(f, owner = "u1")
        val m1 = reloaded.pollFirst()
        assertTrue(m1 is CachedMessage.Binary)
        assertEquals("bin1", m1!!.id)
        assertTrue((m1 as CachedMessage.Binary).data.toList() == payload.toList())
        assertEquals("t1", reloaded.pollFirst()?.id)
    }
}
