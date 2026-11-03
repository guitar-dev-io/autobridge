package dev.autobridge.obd.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FormulaTest {
    private fun eval(text: String, vararg data: Int): Float? = Formula.parse(text)?.evaluate(data)

    @Test fun theObdFormulasWeUse() {
        assertEquals(1250f, eval("(A*256+B)/4", 0x13, 0x88)!!, 0.01f)
        assertEquals(85f, eval("A-40", 125)!!, 0f)
        assertEquals(72f, eval("(A*256+B)*5/72-18", 0x05, 0x10)!!, 0.01f)
        assertEquals(90f, eval("(256*A+B)/16", 0x05, 0xA0)!!, 0.01f)
        assertEquals(14.2f, eval("(A*256+B)/1000", 0x37, 0x78)!!, 0.001f)
        assertEquals(50f, eval("A*100/255", 127)!!, 0.2f)
    }

    @Test fun precedenceAndBrackets() {
        assertEquals(14f, eval("2+3*4")!!, 0f)
        assertEquals(20f, eval("(2+3)*4")!!, 0f)
        assertEquals(1f, eval("10-3*3")!!, 0f)
        assertEquals(-5f, eval("-A", 5)!!, 0f)
        assertEquals(7f, eval("10 - -A / 5 - 4", 5)!!, 0f)
        assertEquals(2.5f, eval("A/2", 5)!!, 0f)
        assertEquals(0.5f, eval(".5")!!, 0f)
    }

    @Test fun variablesNeedTheirBytes() {
        val formula = Formula.parse("A+B+C")!!
        assertEquals(3, formula.bytesNeeded)
        assertNull(formula.evaluate(intArrayOf(1, 2)))
        assertEquals(6f, formula.evaluate(intArrayOf(1, 2, 3))!!, 0f)
        assertEquals(0, Formula.parse("42")!!.bytesNeeded)
    }

    @Test fun divisionByZeroIsNoValueNotACrash() {
        assertNull(eval("A/0", 5))
        assertNull(eval("1/(A-A)", 5))
    }

    @Test fun anythingBeyondArithmeticIsRefused() {
        for (bad in listOf(
            "", "  ", "A+", "(A", "A)", "A B", "sqrt(A)", "A^2", "A%2", "A&1", "A>>1", "1e3", "x", "AA",
            "Math.max(A,1)", "A;B", "A,B", "A==B", "\$A", "A+(", "()",
        )) {
            assertNull("'$bad' should not parse", Formula.parse(bad))
        }
    }

    @Test fun lengthAndNestingAreCapped() {
        assertNull(Formula.parse("1+".repeat(Formula.MAX_LENGTH) + "1"))
        assertNull(Formula.parse("(".repeat(60) + "A" + ")".repeat(60)))
        assertNotNull(Formula.parse("(".repeat(10) + "A" + ")".repeat(10)))
    }

    @Test fun variablesStopAtH() {
        assertNotNull(Formula.parse("A+B+C+D+E+F+G+H"))
        assertNull(Formula.parse("I"))
        assertEquals(8, Formula.parse("H")!!.bytesNeeded)
    }
}
