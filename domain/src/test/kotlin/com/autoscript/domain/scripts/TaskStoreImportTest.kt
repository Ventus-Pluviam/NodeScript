package com.autoscript.domain.scripts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [TaskStoreImport] 的解析判据（§8.6 迁移面）。
 *
 * **喂的是真 jsonl 文本**（手写，与 `FileTaskStore.Codec.put` 逐字同形）—— 迁���解析读的是
 * 老设备上那些文件，形状对不对只有对着真文本验。借宿主 `FileTaskStore` 的 `Codec` 拼文本
 * 反而是「用被测的兄弟实现给被测的解析器造输入」（那份 Codec 本身也是被测对象）。
 */
class TaskStoreImportTest {

    private fun putLine(
        id: String = "t1",
        kind: String = "once",
        a: String = "60",
        b: String = "",
    ) = """{"op":"put","id":"$id","name":"n","project":"p","script":"s.js",""" +
        """"kind":"$kind","a":"$a","b":"$b","screen":"ANY","argsN":0,""" +
        """"timeout":null,"tz":"UTC","enabled":1}"""

    @Test
    fun `put 行往返全部字段`() {
        val rows = TaskStoreImport.parse(putLine() + "\n")
        assertEquals(1, rows.size)
        val t = rows.single().task
        assertEquals("t1", t.id)
        assertEquals(TimedSchedule.Once(60), t.schedule)
        assertEquals(ScreenGuarantee.ANY, t.screen)
        assertNull(rows.single().revokedAtMillis, "存活行没有墓碑时刻")
    }

    @Test
    fun `daily 与 cron 形态都还原`() {
        val text = putLine(id = "d", kind = "daily", a = "9", b = "30") + "\n" +
            putLine(id = "c", kind = "cron", a = "0 9 * * 1") + "\n"
        val byId = TaskStoreImport.parse(text).associateBy { it.task.id }
        assertEquals(TimedSchedule.Daily(9, 30), byId["d"]!!.task.schedule)
        assertEquals(TimedSchedule.Cron("0 9 * * 1"), byId["c"]!!.task.schedule)
    }

    @Test
    fun `args 展平数组还原`() {
        val line = """{"op":"put","id":"t1","name":"n","project":"p","script":"s.js",""" +
            """"kind":"once","a":"60","b":"","screen":"SCREEN_ON","argsN":2,"arg0":"--fast",""" +
            """"arg1":"带 空格\"引号","timeout":5000,"tz":"Asia/Shanghai","enabled":1}""" + "\n"
        val t = TaskStoreImport.parse(line).single().task
        assertEquals(listOf("--fast", "带 空格\"引号"), t.args, "引号与空格逐字往返")
        assertEquals(ScreenGuarantee.SCREEN_ON, t.screen)
        assertEquals(5_000L, t.scriptTimeoutMillis)
        assertEquals("Asia/Shanghai", t.timezone.id)
    }

    @Test
    fun `同 id 多次 put 后者胜`() {
        val text = putLine(a = "60") + "\n" + putLine(a = "600") + "\n"
        val rows = TaskStoreImport.parse(text)
        assertEquals(1, rows.size, "同 id 收敛成一条")
        assertEquals(TimedSchedule.Once(600), rows.single().task.schedule)
    }

    @Test
    fun `del 落墓碑且不复活（迁移最易漏的一半）`() {
        val text = putLine(id = "t1") + "\n" + putLine(id = "t2") + "\n" +
            """{"op":"del","id":"t1"}""" + "\n"
        val rows = TaskStoreImport.parse(text)
        val byId = rows.associateBy { it.task.id }
        assertEquals(2, rows.size, "墓碑也是一行（要导进去，否则迁移后 t1 复活）")
        assertNotNullMarker(byId["t1"]!!.revokedAtMillis, "t1 只剩墓碑")
        assertNull(byId["t2"]!!.revokedAtMillis, "t2 仍是存活行")
    }

    @Test
    fun `del 之后又 put 即复活`() {
        val text = putLine(id = "t1") + "\n" + """{"op":"del","id":"t1"}""" + "\n" + putLine(id = "t1", a = "120") + "\n"
        val row = TaskStoreImport.parse(text).single()
        assertNull(row.revokedAtMillis, "后来的 put 胜，墓碑清掉")
        assertEquals(TimedSchedule.Once(120), row.task.schedule)
    }

    @Test
    fun `尾部半行丢弃（写中断的残行等于没发生）`() {
        val text = putLine() + "\n" + """{"op":"put","id":"t-half"""
        val rows = TaskStoreImport.parse(text)
        assertEquals(listOf("t1"), rows.map { it.task.id }, "半行丢弃，此前行不受影响")
    }

    @Test
    fun `行内损坏响亮失败 不静默跳过`() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskStoreImport.parse("""{"op":"put","id":"t1","kind":"once","a":""" + "\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskStoreImport.parse("""{"op":"put","id":"t1","kind":"weekly","a":"1","b":"","name":"n",""" +
                """"project":"p","script":"s.js","argsN":0,"screen":"ANY","timeout":null,"tz":"UTC","enabled":1}""" + "\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskStoreImport.parse("""{"op":"nope","id":"t1"}""" + "\n")
        }
    }

    @Test
    fun `enabled=0 如实还原`() {
        val line = putLine().replace(""","enabled":1""", ""","enabled":0""")
        assertTrue(!TaskStoreImport.parse(line + "\n").single().task.enabled)
    }

    private fun assertNotNullMarker(value: Long?, what: String) {
        assertTrue(value != null, what)
    }
}
