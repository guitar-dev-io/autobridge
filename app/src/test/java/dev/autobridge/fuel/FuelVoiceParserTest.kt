package dev.autobridge.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelVoiceParserTest {
    private fun parse(text: String) = FuelVoiceParser.parse(text)

    @Test fun thaiLitresAndBaht() {
        val v = parse("เติม 40 ลิตร 1,400 บาท")!!
        assertEquals(40.0, v.amount, 0.001)
        assertEquals(1400.0, v.baht, 0.001)
        assertFalse(v.electric)
    }

    @Test fun decimalsAndEnglish() {
        val v = parse("filled 32.5 liters for 1150 baht")!!
        assertEquals(32.5, v.amount, 0.001)
        assertEquals(1150.0, v.baht, 0.001)
    }

    @Test fun thePriceMayComeFirst() {
        val v = parse("1400 บาท 40 ลิตร")!!
        assertEquals(40.0, v.amount, 0.001)
        assertEquals(1400.0, v.baht, 0.001)
    }

    @Test fun aPriceWordBeforeTheFigure() {
        assertEquals(1400.0, parse("เติมน้ำมัน 40 ลิตร ราคา 1400")!!.baht, 0.001)
        assertEquals(1400.0, parse("40 liters total 1400")!!.baht, 0.001)
    }

    @Test fun anEvChargeInKwh() {
        val v = parse("ชาร์จ 30 kWh 200 บาท")!!
        assertEquals(30.0, v.amount, 0.001)
        assertEquals(200.0, v.baht, 0.001)
        assertTrue(v.electric)
        assertTrue(parse("ชาร์จไฟ 25 หน่วย 180 บาท")!!.electric)
    }

    @Test fun thaiDigitsAreRead() {
        val v = parse("เติม ๔๐ ลิตร ๑๔๐๐ บาท")!!
        assertEquals(40.0, v.amount, 0.001)
        assertEquals(1400.0, v.baht, 0.001)
    }

    @Test fun onlyOneHalfIsNotAFillUp() {
        assertNull(parse("เติม 40 ลิตร"))
        assertNull(parse("1400 บาท"))
        assertNull(parse("เปิดยูทูป"))
    }

    @Test fun litresAndKwhTogetherIsRefused() {
        assertNull(parse("40 ลิตร 30 kWh 1400 บาท"))
    }

    @Test fun absurdFiguresAreMisheardNumbers() {
        assertNull(parse("เติม 4000 ลิตร 1400 บาท"))
        assertNull(parse("เติม 40 ลิตร 9999999 บาท"))
        assertNull(parse("เติม 0 ลิตร 100 บาท"))
    }

    @Test fun anOrdinarySpokenRequestIsLeftAlone() {
        assertNull(parse("ค้นหาปั๊มน้ำมัน"))
        assertNull(parse("open youtube"))
    }
}
