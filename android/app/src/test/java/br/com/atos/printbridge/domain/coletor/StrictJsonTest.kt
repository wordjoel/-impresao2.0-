package br.com.atos.printbridge.domain.coletor

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class StrictJsonTest {

    @Test
    fun `le os tipos do JSON`() {
        val v = StrictJson.parse("""{"a":1,"b":-2.5,"c":"x\"\\\/\n\u00e7\ud83d\ude00","d":[true,false,null],"e":{},"f":[],"g":1e2}""")
        assertEquals(
            mapOf("a" to 1L, "b" to -2.5, "c" to "x\"\\/\nç😀", "d" to listOf(true, false, null), "e" to emptyMap<String, Any?>(), "f" to emptyList<Any?>(), "g" to 100.0),
            v,
        )
        assertEquals(listOf("z", "a"), (StrictJson.parse("""{"z":0,"a":0}""") as Map<*, *>).keys.toList())
    }

    @Test
    fun `recusa JSON fora do padrao`() {
        for (bad in listOf("", "{", "{\"a\":1,}", "[1,]", "{\"a\" 1}", "{a:1}", "'x'", "01", "1.", "-", "\"\\x\"", "\"\\u12\"", "\"a\u0001\"", "{} x", "{\"a\":1,\"a\":2}", "tru", "[" .repeat(70))) {
            try {
                StrictJson.parse(bad)
                fail("aceitou: $bad")
            } catch (_: StrictJson.JsonException) {
            }
        }
    }
}
