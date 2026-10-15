package com.tonsai.thaihighlight

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val reqCapture = 901
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val fields = linkedMapOf<String, EditText>()
    private lateinit var guideCheck: CheckBox
    private val statusPoll = object : Runnable {
        override fun run() {
            status.text = (if (CaptureState.running) "● กำลังอ่านหน้าจอ\n" else "○ หยุดแล้ว\n") + CaptureState.message
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = RegionConfig.read(this)
        val sc = ScrollView(this).apply { setBackgroundColor(Color.rgb(246, 248, 251)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(30), dp(20), dp(35))
        }
        sc.addView(root)
        setContentView(sc)

        fun title(s: String, size: Float, color: Int = Color.rgb(28, 38, 62)): TextView {
            return TextView(this).apply {
                text = s
                textSize = size
                setTextColor(color)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(5), 0, dp(8))
                root.addView(this)
            }
        }
        fun info(s: String) {
            root.addView(TextView(this).apply {
                text = s
                textSize = 14f
                setTextColor(Color.rgb(83, 91, 109))
                setPadding(0, 0, 0, dp(12))
            })
        }
        title("จับพยัญชนะ ก–ฮ • เรียลไทม์ v3", 24f)
        info("ตรวจช่องตารางอัตโนมัติ • ดูตัวอักษรในช่องขวามือ → เทียบกับ 20 ช่องใหม่ทุกภาพ → วงสีเขียวให้กดเอง ไม่แตะอัตโนมัติ และไม่จำตำแหน่งเดิม")
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(18, 67, 44))
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = rounded(Color.rgb(222, 247, 233), 12)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        fun section(header: String, hint: String, key: String, v: PercentRegion) {
            title(header, 17f)
            info(hint)
            val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val values = listOf("L" to v.left, "T" to v.top, "R" to v.right, "B" to v.bottom)
            values.forEachIndexed { idx, (dir, value) ->
                val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val caption = when (dir) {
                    "L" -> "ซ้าย %"; "T" -> "บน %"; "R" -> "ขวา %"; else -> "ล่าง %"
                }
                wrapper.addView(TextView(this).apply { text = caption; textSize = 12f; setTextColor(Color.DKGRAY) })
                val edit = EditText(this).apply {
                    setSingleLine(true)
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(if (value % 1f == 0f) value.toInt().toString() else value.toString())
                    textSize = 15f
                    setSelectAllOnFocus(true)
                }
                fields[key + dir] = edit
                wrapper.addView(edit, LinearLayout.LayoutParams(-1, dp(48)))
                (if (idx < 2) row1 else row2).addView(wrapper, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12) })
            }
            root.addView(row1)
            root.addView(row2)
        }
        section("1. กรอบตาราง 5×4", "ปรับให้ครอบขอบนอกของตารางทั้ง 20 ช่อง", "board", cfg.board)
        section("2. กรอบเป้าหมายขวามือ", "ปรับให้ครอบเฉพาะพยัญชนะเป้าหมาย (ไม่รวมคะแนน/นาฬิกา)", "cue", cfg.cue)
        title("3. ความมั่นใจขั้นต่ำ", 17f)
        info("เพิ่มค่าเพื่อลดโอกาสวงผิด หรือปรับลดเล็กน้อยหากวงไม่ขึ้น")
        val threshold = EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(cfg.cutoff.toString())
        }
        root.addView(threshold)
        guideCheck = CheckBox(this).apply {
            text = "แสดงกรอบตัวช่วยปรับตำแหน่ง (ฟ้า = ตาราง / ม่วง = ตัวเป้าหมาย)"
            isChecked = cfg.showGuides
            textSize = 13f
        }
        root.addView(guideCheck)

        fun saveConfig(): Boolean {
            try {
                fun value(key: String) = fields.getValue(key).text.toString().toFloat()
                fun reg(s: String) = PercentRegion(value(s+"L"), value(s+"T"), value(s+"R"), value(s+"B"))
                val current = RegionConfig(
                    board = reg("board"), cue = reg("cue"),
                    cutoff = threshold.text.toString().toFloat(),
                    minGap = 0.028f,
                    showGuides = guideCheck.isChecked
                )
                require(current.board.valid() && current.cue.valid()) { "กรอบต้องอยู่ระหว่าง 0–100% และขวา/ล่างต้องมากกว่าซ้าย/บน" }
                require(current.cutoff in 0.4f..0.95f) { "ความมั่นใจควรอยู่ระหว่าง 0.40–0.95" }
                current.save(this)
                return true
            } catch (e: Exception) {
                Toast.makeText(this, "ตรวจการตั้งค่า: ${e.message}", Toast.LENGTH_LONG).show()
                return false
            }
        }
        fun button(textValue: String, bg: Int, onTap: () -> Unit) {
            root.addView(Button(this).apply {
                text = textValue
                isAllCaps = false
                textSize = 15f
                setTextColor(Color.WHITE)
                background = rounded(bg, 13)
                setOnClickListener { onTap() }
            }, LinearLayout.LayoutParams(-1, dp(55)).apply { topMargin = dp(10) })
        }
        button("บันทึกตำแหน่ง", Color.rgb(66, 83, 108)) {
            if (saveConfig()) Toast.makeText(this, "บันทึกแล้ว", Toast.LENGTH_SHORT).show()
        }
        button("▶ เริ่มอ่านหน้าจอ + วงอักษร", Color.rgb(29, 137, 87)) {
            if (!saveConfig()) return@button
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "อนุญาตแสดงทับแอปอื่นก่อน แล้วกลับมากดเริ่มอีกครั้ง", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@button
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 331)
                Toast.makeText(this, "อนุญาตการแจ้งเตือนแล้วกดเริ่มอีกครั้ง", Toast.LENGTH_LONG).show()
                return@button
            }
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
                mgr.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
            } else mgr.createScreenCaptureIntent()
            startActivityForResult(captureIntent, reqCapture)
        }
        button("■ หยุดวงไฮไลต์", Color.rgb(166, 60, 60)) {
            val stop = Intent(this, CaptureService::class.java).apply { action = CaptureService.ACTION_STOP }
            startService(stop)
        }
        info("ระหว่างเกมจะเห็นกรอบเขียวบนช่องที่เหมือนตัวเป้าหมาย เปิดสิทธิ์จับหน้าจอทุกครั้งที่เริ่มใหม่ บางเกมบล็อกภาพหน้าจอ (จอดำ) จึงไม่สามารถอ่านได้")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureService.EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            Toast.makeText(this, "เริ่มแล้ว สลับไปหน้าเกมได้เลย", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() { super.onResume(); handler.post(statusPoll) }
    override fun onPause() { handler.removeCallbacks(statusPoll); super.onPause() }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
}
