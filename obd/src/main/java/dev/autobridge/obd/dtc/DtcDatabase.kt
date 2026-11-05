package dev.autobridge.obd.dtc

/** Plain-language text for a code, in the two languages the app ships. */
data class DtcText(val en: String, val th: String)

/**
 * Descriptions for the generic (SAE J2012) codes seen most often. Written by hand from the public
 * standard's wording, deliberately small: a code not listed still gets a [describeFallback] line
 * naming its system, so nothing is blank. Manufacturer codes (P1xxx, most B/C/U1xxx) come with
 * the per-vehicle profiles of docs/OBD_PLAN.md phase 2, not from here.
 */
object DtcDatabase {
    private val table: Map<String, DtcText> = mapOf(
        "P0011" to DtcText("Intake camshaft timing over-advanced (bank 1)", "ไทมิ่งแคมชาฟต์ไอดีเลื่อนล่วงหน้ามากเกิน (แบงก์ 1)"),
        "P0016" to DtcText("Crankshaft / camshaft position correlation (bank 1)", "ตำแหน่งเพลาข้อเหวี่ยงกับแคมชาฟต์ไม่สัมพันธ์กัน (แบงก์ 1)"),
        "P0087" to DtcText("Fuel rail pressure too low", "แรงดันรางหัวฉีดต่ำเกินไป"),
        "P0100" to DtcText("Mass air flow circuit malfunction", "วงจรเซ็นเซอร์วัดอากาศ (MAF) ผิดปกติ"),
        "P0101" to DtcText("Mass air flow circuit range/performance", "ค่าเซ็นเซอร์วัดอากาศ (MAF) ผิดช่วง"),
        "P0102" to DtcText("Mass air flow circuit low input", "สัญญาณเซ็นเซอร์ MAF ต่ำ"),
        "P0103" to DtcText("Mass air flow circuit high input", "สัญญาณเซ็นเซอร์ MAF สูง"),
        "P0105" to DtcText("Manifold pressure circuit malfunction", "วงจรเซ็นเซอร์แรงดันท่อร่วมไอดี (MAP) ผิดปกติ"),
        "P0106" to DtcText("Manifold pressure range/performance", "ค่าเซ็นเซอร์ MAP ผิดช่วง"),
        "P0110" to DtcText("Intake air temperature circuit malfunction", "วงจรเซ็นเซอร์อุณหภูมิอากาศไอดีผิดปกติ"),
        "P0115" to DtcText("Engine coolant temperature circuit malfunction", "วงจรเซ็นเซอร์อุณหภูมิน้ำหล่อเย็นผิดปกติ"),
        "P0116" to DtcText("Engine coolant temperature range/performance", "ค่าอุณหภูมิน้ำหล่อเย็นผิดช่วง"),
        "P0117" to DtcText("Engine coolant temperature circuit low", "สัญญาณอุณหภูมิน้ำหล่อเย็นต่ำ"),
        "P0118" to DtcText("Engine coolant temperature circuit high", "สัญญาณอุณหภูมิน้ำหล่อเย็นสูง"),
        "P0120" to DtcText("Throttle position sensor circuit malfunction", "วงจรเซ็นเซอร์ตำแหน่งลิ้นเร่งผิดปกติ"),
        "P0121" to DtcText("Throttle position sensor range/performance", "ค่าเซ็นเซอร์ลิ้นเร่งผิดช่วง"),
        "P0125" to DtcText("Insufficient coolant temperature for closed-loop fuel control", "อุณหภูมิเครื่องไม่ถึงเกณฑ์ควบคุมน้ำมันแบบวงปิด"),
        "P0128" to DtcText("Coolant thermostat below regulating temperature", "เทอร์โมสตัท/น้ำหล่อเย็นไม่ถึงอุณหภูมิทำงาน"),
        "P0130" to DtcText("O2 sensor circuit malfunction (bank 1 sensor 1)", "วงจรเซ็นเซอร์ออกซิเจนผิดปกติ (แบงก์ 1 ตัวที่ 1)"),
        "P0133" to DtcText("O2 sensor slow response (bank 1 sensor 1)", "เซ็นเซอร์ออกซิเจนตอบสนองช้า (แบงก์ 1 ตัวที่ 1)"),
        "P0134" to DtcText("O2 sensor no activity (bank 1 sensor 1)", "เซ็นเซอร์ออกซิเจนไม่มีสัญญาณ (แบงก์ 1 ตัวที่ 1)"),
        "P0135" to DtcText("O2 sensor heater circuit (bank 1 sensor 1)", "วงจรฮีตเตอร์เซ็นเซอร์ออกซิเจน (แบงก์ 1 ตัวที่ 1)"),
        "P0141" to DtcText("O2 sensor heater circuit (bank 1 sensor 2)", "วงจรฮีตเตอร์เซ็นเซอร์ออกซิเจน (แบงก์ 1 ตัวที่ 2)"),
        "P0171" to DtcText("System too lean (bank 1)", "ส่วนผสมบางเกินไป (แบงก์ 1)"),
        "P0172" to DtcText("System too rich (bank 1)", "ส่วนผสมหนาเกินไป (แบงก์ 1)"),
        "P0174" to DtcText("System too lean (bank 2)", "ส่วนผสมบางเกินไป (แบงก์ 2)"),
        "P0175" to DtcText("System too rich (bank 2)", "ส่วนผสมหนาเกินไป (แบงก์ 2)"),
        "P0191" to DtcText("Fuel rail pressure sensor range/performance", "ค่าเซ็นเซอร์แรงดันรางหัวฉีดผิดช่วง"),
        "P0201" to DtcText("Injector circuit malfunction, cylinder 1", "วงจรหัวฉีดสูบ 1 ผิดปกติ"),
        "P0202" to DtcText("Injector circuit malfunction, cylinder 2", "วงจรหัวฉีดสูบ 2 ผิดปกติ"),
        "P0203" to DtcText("Injector circuit malfunction, cylinder 3", "วงจรหัวฉีดสูบ 3 ผิดปกติ"),
        "P0204" to DtcText("Injector circuit malfunction, cylinder 4", "วงจรหัวฉีดสูบ 4 ผิดปกติ"),
        "P0221" to DtcText("Throttle pedal sensor B range/performance", "ค่าเซ็นเซอร์คันเร่ง B ผิดช่วง"),
        "P0300" to DtcText("Random/multiple cylinder misfire detected", "ตรวจพบการจุดระเบิดพลาดหลายสูบ/สุ่มสูบ"),
        "P0301" to DtcText("Cylinder 1 misfire detected", "สูบ 1 จุดระเบิดพลาด"),
        "P0302" to DtcText("Cylinder 2 misfire detected", "สูบ 2 จุดระเบิดพลาด"),
        "P0303" to DtcText("Cylinder 3 misfire detected", "สูบ 3 จุดระเบิดพลาด"),
        "P0304" to DtcText("Cylinder 4 misfire detected", "สูบ 4 จุดระเบิดพลาด"),
        "P0325" to DtcText("Knock sensor 1 circuit malfunction", "วงจรเซ็นเซอร์น็อกตัวที่ 1 ผิดปกติ"),
        "P0335" to DtcText("Crankshaft position sensor circuit malfunction", "วงจรเซ็นเซอร์ตำแหน่งเพลาข้อเหวี่ยงผิดปกติ"),
        "P0340" to DtcText("Camshaft position sensor circuit malfunction", "วงจรเซ็นเซอร์ตำแหน่งแคมชาฟต์ผิดปกติ"),
        "P0401" to DtcText("EGR flow insufficient", "การไหลของ EGR น้อยเกินไป"),
        "P0420" to DtcText("Catalyst efficiency below threshold (bank 1)", "ประสิทธิภาพแคทาลิสต์ต่ำกว่าเกณฑ์ (แบงก์ 1)"),
        "P0430" to DtcText("Catalyst efficiency below threshold (bank 2)", "ประสิทธิภาพแคทาลิสต์ต่ำกว่าเกณฑ์ (แบงก์ 2)"),
        "P0440" to DtcText("Evaporative emission system malfunction", "ระบบไอระเหยน้ำมัน (EVAP) ผิดปกติ"),
        "P0441" to DtcText("EVAP incorrect purge flow", "การไหลของ EVAP purge ไม่ถูกต้อง"),
        "P0442" to DtcText("EVAP system small leak detected", "ตรวจพบรั่วเล็กน้อยในระบบ EVAP"),
        "P0455" to DtcText("EVAP system large leak detected", "ตรวจพบรั่วมากในระบบ EVAP (ตรวจฝาถังน้ำมัน)"),
        "P0456" to DtcText("EVAP system very small leak detected", "ตรวจพบรั่วเล็กมากในระบบ EVAP"),
        "P0500" to DtcText("Vehicle speed sensor malfunction", "เซ็นเซอร์ความเร็วรถผิดปกติ"),
        "P0505" to DtcText("Idle control system malfunction", "ระบบควบคุมรอบเดินเบาผิดปกติ"),
        "P0507" to DtcText("Idle speed higher than expected", "รอบเดินเบาสูงกว่าปกติ"),
        "P0562" to DtcText("System voltage low", "แรงดันไฟระบบต่ำ (ตรวจแบตเตอรี่/ไดชาร์จ)"),
        "P0563" to DtcText("System voltage high", "แรงดันไฟระบบสูง"),
        "P0601" to DtcText("Control module memory checksum error", "หน่วยความจำกล่องควบคุมผิดพลาด (checksum)"),
        "P0605" to DtcText("Control module ROM error", "ROM ของกล่องควบคุมผิดพลาด"),
        "P0700" to DtcText("Transmission control system malfunction", "ระบบควบคุมเกียร์ผิดปกติ"),
        "P0705" to DtcText("Transmission range sensor circuit malfunction", "วงจรเซ็นเซอร์ตำแหน่งเกียร์ผิดปกติ"),
        "P0715" to DtcText("Turbine/input speed sensor circuit malfunction", "วงจรเซ็นเซอร์ความเร็วเทอร์ไบน์เกียร์ผิดปกติ"),
        "P0740" to DtcText("Torque converter clutch circuit malfunction", "วงจรคลัตช์ทอร์คคอนเวอร์เตอร์ผิดปกติ"),
        "P2096" to DtcText("Post-catalyst fuel trim too lean (bank 1)", "ค่าปรับน้ำมันหลังแคทาลิสต์บางเกินไป (แบงก์ 1)"),
        "P2101" to DtcText("Throttle actuator control motor circuit", "วงจรมอเตอร์ลิ้นเร่งไฟฟ้าผิดปกติ"),
        "P2135" to DtcText("Throttle position sensor A/B correlation", "ค่าเซ็นเซอร์ลิ้นเร่ง A/B ไม่สัมพันธ์กัน"),
        "U0100" to DtcText("Lost communication with ECM/PCM", "สื่อสารกับกล่องควบคุมเครื่องยนต์ไม่ได้"),
        "U0121" to DtcText("Lost communication with ABS control module", "สื่อสารกับกล่อง ABS ไม่ได้"),
        "U0140" to DtcText("Lost communication with body control module", "สื่อสารกับกล่อง BCM ไม่ได้"),
        "C0035" to DtcText("Left front wheel speed sensor circuit", "วงจรเซ็นเซอร์ความเร็วล้อหน้าซ้าย"),
        "C0040" to DtcText("Right front wheel speed sensor circuit", "วงจรเซ็นเซอร์ความเร็วล้อหน้าขวา"),
        "B0001" to DtcText("Driver frontal stage 1 deployment control", "วงจรควบคุมถุงลมคนขับ"),
    )

    fun lookup(code: DtcCode): DtcText? = table[code.code]

    /** Never blank: a listed description if there is one, else the system and kind of code. */
    fun describe(code: DtcCode, thai: Boolean): String {
        lookup(code)?.let { return if (thai) it.th else it.en }
        return describeFallback(code, thai)
    }

    fun describeFallback(code: DtcCode, thai: Boolean): String {
        val system = when (code.system) {
            'P' -> if (thai) "ระบบส่งกำลัง (เครื่องยนต์/เกียร์)" else "Powertrain (engine/transmission)"
            'C' -> if (thai) "ช่วงล่าง/เบรก" else "Chassis"
            'B' -> if (thai) "ตัวถัง/ถุงลม" else "Body"
            else -> if (thai) "เครือข่ายสื่อสาร" else "Network communication"
        }
        val kind = if (code.isGeneric) (if (thai) "รหัสมาตรฐาน" else "generic code") else (if (thai) "รหัสเฉพาะยี่ห้อ" else "manufacturer-specific code")
        return "$system · $kind"
    }
}
