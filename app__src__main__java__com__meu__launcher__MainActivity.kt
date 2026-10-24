package com.meu.launcher

import android.app.Activity
import android.content.*
import android.graphics.*
import android.graphics.drawable.*
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.*
import java.io.File
import java.net.URL
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : Activity() {
    // ===== LOCAL EXATO DO CLIMA =====
    // No Google Maps: toque e segure no seu endereço e copie os números (ex: -23.55052, -46.633308)
    private val LAT: Double? = -18.9574560
    private val LON: Double? = -42.2448708
    private val NOME_LOCAL = "Vila Nova Floresta"
    data class App(val label: String, val pkg: String, val intent: Intent, val icon: Drawable, val banner: Boolean)

    private val apps = ArrayList<App>()
    private lateinit var adapter: Adapter
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var bgA: ImageView
    private lateinit var bgB: ImageView
    private lateinit var wIcon: TextView
    private lateinit var wTemp: TextView
    private lateinit var wCity: TextView
    private lateinit var wDesc: TextView
    private var front = true
    private var wallIdx = 0
    private var walls: List<Drawable> = emptyList()
    private val h = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = loadApps()   // app novo/removido -> atualiza sozinho
    }
    private val wallTick = object : Runnable {
        override fun run() { nextWall(); h.postDelayed(this, 12000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val root = FrameLayout(this)
        bgA = bg(); bgB = bg(); bgB.alpha = 0f
        root.addView(bgA); root.addView(bgB)
        root.addView(View(this).apply { setBackgroundColor(0x66000814) })

        // Relógio e data
        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = 54f; setTextColor(Color.WHITE)
        }
        val date = TextClock(this).apply {
            format24Hour = "EEE, d 'de' MMMM"; format12Hour = "EEE, d 'de' MMMM"; textSize = 18f; setTextColor(0xCCFFFFFF.toInt())
        }
        // Menu lateral
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
            background = round(0x44FFFFFF, 24)
        }
        listOf("⌂  Início", "▦  Apps", "⚙  Configurações").forEach { t ->
            menu.addView(TextView(this).apply {
                text = t; textSize = 17f; setTextColor(Color.WHITE); setPadding(dp(12), dp(10), dp(40), dp(10))
                if (t.contains("Início")) background = round(0x55FFFFFF, 16)
                if (t.contains("Config")) { isFocusable = true; setOnClickListener { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
            })
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(clock); addView(date)
            addView(menu, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) }) }
        root.addView(left, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(40); topMargin = dp(30) })

        // Destaque do app focado
        title = TextView(this).apply { textSize = 44f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) }
        sub = TextView(this).apply { text = "OK  Abrir"; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(dp(18), dp(8), dp(18), dp(8)); background = round(0x66FFFFFF, 30) }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(28), dp(32), dp(28))
            background = round(0x33FFFFFF, 28); addView(TextView(context).apply { text = "SEUS APLICATIVOS"; textSize = 13f; setTextColor(0xCCFFFFFF.toInt()) })
            addView(title); addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        }
        root.addView(hero, FrameLayout.LayoutParams(dp(640), dp(190)).apply { leftMargin = dp(250); topMargin = dp(40) })


        // Card de clima (Open-Meteo + localização por IP, sem chave de API)
        wIcon = TextView(this).apply { text = "☀"; textSize = 44f; setTextColor(Color.WHITE) }
        wTemp = TextView(this).apply { text = "--°"; textSize = 40f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) }
        wCity = TextView(this).apply { text = "Carregando..."; textSize = 14f; setTextColor(0xCCFFFFFF.toInt()); maxLines = 1 }
        wDesc = TextView(this).apply { textSize = 14f; setTextColor(0xCCFFFFFF.toInt()); maxLines = 1 }
        val wTxt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(wTemp); addView(wCity); addView(wDesc) }
        val weather = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16)); background = round(0x33FFFFFF, 28)
            addView(wIcon, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(16) }); addView(wTxt)
        }
        root.addView(weather, FrameLayout.LayoutParams(dp(260), dp(190), Gravity.END).apply { rightMargin = dp(40); topMargin = dp(40) })

        // Grade de apps
        adapter = Adapter()
        val rv = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(context, 6); this.adapter = this@MainActivity.adapter
            clipChildren = false; clipToPadding = false; setPadding(dp(24), dp(16), dp(24), dp(16))
            isFocusable = false
        }
        root.addView(rv, FrameLayout.LayoutParams(dp(1000), dp(290), Gravity.BOTTOM).apply { leftMargin = dp(230); bottomMargin = dp(10) })
        setContentView(root)
        loadWalls(); loadApps()
    }

    private fun weatherInfo(c: Int): Pair<String, String> = when (c) {
        0 -> "☀" to "Céu limpo"; 1, 2 -> "⛅" to "Parcialmente nublado"; 3 -> "☁" to "Nublado"
        45, 48 -> "🌫" to "Neblina"; in 51..57 -> "🌦" to "Garoa"; in 61..67, in 80..82 -> "🌧" to "Chuva"
        in 71..77, 85, 86 -> "❄" to "Neve"; in 95..99 -> "⛈" to "Tempestade"; else -> "☁" to "Nublado"
    }

    private fun loadWeather() = thread {
        try {
            val lat: Double; val lon: Double; val city: String
            if (LAT != null && LON != null) { lat = LAT; lon = LON; city = NOME_LOCAL.ifEmpty { "Meu local" } }
            else { val loc = JSONObject(URL("https://ipwho.is/").readText())
                lat = loc.getDouble("latitude"); lon = loc.getDouble("longitude"); city = NOME_LOCAL.ifEmpty { loc.optString("city", "Sua cidade") } }
            val w = JSONObject(URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto").readText()).getJSONObject("current")
            val (ic, desc) = weatherInfo(w.getInt("weather_code"))
            runOnUiThread { wIcon.text = ic; wTemp.text = "${Math.round(w.getDouble("temperature_2m"))}°"; wCity.text = city; wDesc.text = desc }
        } catch (e: Exception) { runOnUiThread { wCity.text = "Clima indisponível" } }
    }
    private val weatherTick = object : Runnable { override fun run() { loadWeather(); h.postDelayed(this, 30 * 60 * 1000L) } }

    private fun bg() = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; scaleX = 1.15; scaleY = 1.15 }
    private fun round(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }

    // Papéis de parede: coloque .jpg/.png em Android/data/com.meu.launcher/files/wallpapers/
    private fun loadWalls() {
        val dir = File(getExternalFilesDir(null), "wallpapers").apply { mkdirs() }
        val files = dir.listFiles { f -> f.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp") }?.sorted() ?: emptyList()
        walls = files.mapNotNull { f ->
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })?.let { BitmapDrawable(resources, it) }
        }
        if (walls.isEmpty()) walls = listOf(0xFF1B2A6B to 0xFFE0703A, 0xFF0B3D4F to 0xFF7B2D8E, 0xFF1A1A40 to 0xFF2E8B8B).map { (a, c) ->
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a.toInt(), c.toInt())) }
        bgA.setImageDrawable(walls[0]); drift(bgA)
    }

    private fun drift(v: ImageView) {   // movimento lento estilo parallax
        v.animate().cancel(); v.translationX = dp(30).toFloat()
        v.animate().translationX(-dp(30).toFloat()).setDuration(16000).start()
    }

    private fun nextWall() {
        if (walls.size < 2 && front.not()) return
        wallIdx = (wallIdx + 1) % walls.size
        val inV = if (front) bgB else bgA; val outV = if (front) bgA else bgB
        inV.setImageDrawable(walls[wallIdx]); drift(inV)
        inV.animate().alpha(1f).setDuration(1800).start(); outV.animate().alpha(0f).setDuration(1800).start()
        front = !front
    }

    private fun loadApps() {
        val pm = packageManager; val seen = HashSet<String>(); val list = ArrayList<App>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val p = ri.activityInfo.packageName
                if (p == packageName || !seen.add(p)) continue
                val launch = pm.getLeanbackLaunchIntentForPackage(p) ?: pm.getLaunchIntentForPackage(p) ?: continue
                val banner = ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm)
                list.add(App(ri.loadLabel(pm).toString(), p, launch, banner ?: ri.loadIcon(pm), banner != null))
            }
        }
        apps.clear(); apps.addAll(list.sortedBy { it.label.lowercase() })
        adapter.notifyDataSetChanged()
        if (apps.isNotEmpty() && title.text.isEmpty()) title.text = apps[0].label
    }

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val box: FrameLayout, val img: ImageView, val name: TextView) : RecyclerView.ViewHolder(box)
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val box = FrameLayout(p.context).apply { isFocusable = true; isClickable = true; clipChildren = false
                layoutParams = RecyclerView.LayoutParams(dp(160), dp(120)).apply { setMargins(dp(8), dp(6), dp(8), dp(6)) } }
            val img = ImageView(p.context).apply { background = round(0x55FFFFFF, 16); setPadding(dp(4), dp(4), dp(4), dp(4)) }
            val name = TextView(p.context).apply { textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1 }
            box.addView(img, FrameLayout.LayoutParams(dp(150), dp(84), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            box.addView(name, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            return VH(box, img, name)
        }
        override fun getItemCount() = apps.size
        override fun onBindViewHolder(vh: VH, i: Int) {
            val a = apps[i]
            vh.img.setImageDrawable(a.icon); vh.img.scaleType = if (a.banner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            vh.name.text = a.label
            vh.box.setOnClickListener { try { startActivity(a.intent) } catch (e: Exception) {} }
            vh.box.setOnFocusChangeListener { v, f ->
                v.animate().scaleX(if (f) 1.18f else 1f).scaleY(if (f) 1.18f else 1f).setDuration(180).start()
                v.elevation = if (f) 12f else 0f
                vh.img.background = round(if (f) 0xCCFFFFFF.toInt() else 0x55FFFFFF, 16)
                if (f) {
                    title.animate().alpha(0f).setDuration(90).withEndAction { title.text = a.label; title.animate().alpha(1f).setDuration(200).start() }.start()
                    bgA.parent.let { (bgA).translationX = bgA.translationX; }
                    val shift = -(i % 6) * dp(8).toFloat()   // parallax acompanha a coluna selecionada
                    bgA.animate().translationX(shift).setDuration(400).start(); bgB.animate().translationX(shift).setDuration(400).start()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package") })
        loadApps(); h.postDelayed(wallTick, 12000); h.post(weatherTick)
    }
    override fun onStop() { super.onStop(); unregisterReceiver(pkgReceiver); h.removeCallbacks(wallTick); h.removeCallbacks(weatherTick) }
    override fun onBackPressed() {}   // Home não deve fechar
}
