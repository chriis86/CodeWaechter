package de.codewaechter

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

const val SEITE = "https://wardrobes.gg/codes"
const val KANAL = "neue_codes"
const val STUNDEN = 3L

// ---------------------------------------------------------------
// Holt die Seite, sucht die Codes und merkt sich, welche schon bekannt sind
// ---------------------------------------------------------------
object CodeChecker {
    private const val PREFS = "codes"

    data class Ergebnis(val neu: List<String>, val alle: Set<String>, val ersterLauf: Boolean)

    private fun ladeSeite(): String {
        val con = URL(SEITE).openConnection() as HttpURLConnection
        con.connectTimeout = 15000
        con.readTimeout = 15000
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) CodeWaechter/1.0")
        try {
            if (con.responseCode != 200) throw Exception("Seite antwortet mit Fehler ${con.responseCode}")
            return con.inputStream.bufferedReader().use { it.readText() }
        } finally {
            con.disconnect()
        }
    }

    fun findeCodes(html: String): Set<String> {
        val codes = sortedSetOf<String>()
        // 1) Fett oder als Code markierte Wörter, z. B. <strong>SPARKUNIVERSE</strong>
        Regex("<(strong|b|code)[^>]*>\\s*([A-Za-z0-9_-]{3,30})\\s*:?\\s*</\\1>", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { codes.add(it.groupValues[2]) }
        // 2) Codes in eingebetteten Daten, z. B. "code":"SPARKUNIVERSE"
        Regex("\"code\"\\s*:\\s*\"([A-Za-z0-9_-]{2,30})\"")
            .findAll(html).forEach { codes.add(it.groupValues[1]) }
        // 3) Notlösung: Wörter in GROSSBUCHSTABEN im sichtbaren Text
        if (codes.isEmpty()) {
            val text = html
                .replace(Regex("(?s)<script.*?</script>"), " ")
                .replace(Regex("(?s)<style.*?</style>"), " ")
                .replace(Regex("<[^>]+>"), " ")
            Regex("\\b[A-Z][A-Z0-9]{3,29}\\b").findAll(text).forEach { codes.add(it.value) }
        }
        return codes
    }

    // Sucht zu jedem Code die Beschreibung, z. B. "SPARKUNIVERSE: 100 coins, never expires"
    fun findeDetails(html: String, codes: Set<String>): Map<String, String> {
        val text = html
            .replace(Regex("(?s)<script.*?</script>"), " ")
            .replace(Regex("(?s)<style.*?</style>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</(li|p|div|tr|h\\d)>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&")
        val details = mutableMapOf<String, String>()
        for (zeile in text.lines()) {
            val m = Regex("^\\s*([A-Za-z0-9_-]{1,30})\\s*[:–]\\s*(.+?)\\s*$").find(zeile) ?: continue
            val code = m.groupValues[1]
            if (code in codes && code !in details) details[code] = uebersetze(m.groupValues[2])
        }
        return details
    }

    private fun uebersetze(s: String): String = s
        .replace(Regex("\\s+"), " ")
        .replace("coins", "Coins")
        .replace("never expires", "läuft nie ab")
        .replace("ended", "abgelaufen")
        .replace("expired", "abgelaufen")

    fun istAbgelaufen(details: String): Boolean = details.contains("abgelaufen")

    fun details(ctx: Context): Map<String, String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("details", emptySet())!!
            .mapNotNull { eintrag ->
                val teile = eintrag.split('\t', limit = 2)
                if (teile.size == 2) teile[0] to teile[1] else null
            }.toMap()

    fun pruefe(ctx: Context): Ergebnis {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val html = ladeSeite()
        val alle = findeCodes(html)
        val infos = findeDetails(html, alle)
        if (alle.isEmpty()) throw Exception("Keine Codes auf der Seite gefunden")
        val bekannt: Set<String>? = prefs.getStringSet("bekannt", null)
        val ersterLauf = bekannt == null
        val neu = if (bekannt == null) emptyList() else alle.filter { it !in bekannt }
        val editor = prefs.edit()
            .putStringSet("bekannt", HashSet(bekannt.orEmpty() + alle))
            .putStringSet("aktuell", HashSet(alle))
            .putStringSet("details", HashSet(infos.map { "${it.key}\t${it.value}" }))
            .putLong("zuletzt", System.currentTimeMillis())
        if (neu.isNotEmpty()) editor.putStringSet("neueste", HashSet(neu))
        editor.apply()
        if (neu.isNotEmpty()) benachrichtige(ctx, neu)
        return Ergebnis(neu, alle, ersterLauf)
    }

    fun aktuelle(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("aktuell", emptySet())!!.sorted()

    fun neueste(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("neueste", emptySet())!!

    fun zuletzt(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("zuletzt", 0L)

    fun kanalAnlegen(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val kanal = NotificationChannel(KANAL, "Neue Codes", NotificationManager.IMPORTANCE_HIGH)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(kanal)
        }
    }

    private fun benachrichtige(ctx: Context, neu: List<String>) {
        kanalAnlegen(ctx)
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val oeffnen = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = neu.joinToString(", ")
        val nachricht = NotificationCompat.Builder(ctx, KANAL)
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle(if (neu.size == 1) "Neuer Essential-Code!" else "${neu.size} neue Essential-Codes!")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\nAntippen zum Öffnen und Kopieren."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(oeffnen)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(1, nachricht)
        } catch (e: SecurityException) {
            // Benachrichtigungen wurden nicht erlaubt
        }
    }

    fun planen(ctx: Context) {
        val auftrag = PeriodicWorkRequestBuilder<PruefWorker>(STUNDEN, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork("codecheck", ExistingPeriodicWorkPolicy.KEEP, auftrag)
    }
}

// ---------------------------------------------------------------
// Läuft im Hintergrund alle paar Stunden (auch nach Neustart des Handys)
// ---------------------------------------------------------------
class PruefWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = try {
        CodeChecker.pruefe(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}

// ---------------------------------------------------------------
// Der Bildschirm der App
// ---------------------------------------------------------------
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var liste: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val inhalt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        inhalt.addView(TextView(this).apply {
            text = "Essential Code-Wächter"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
        })
        inhalt.addView(TextView(this).apply {
            text = "Prüft alle $STUNDEN Stunden wardrobes.gg/codes und meldet sich, wenn ein neuer Code auftaucht."
            setPadding(0, pad / 2, 0, pad)
        })
        status = TextView(this).apply { setPadding(0, 0, 0, pad / 2) }
        inhalt.addView(status)

        inhalt.addView(Button(this).apply {
            text = "Jetzt prüfen"
            setOnClickListener { jetztPruefen() }
        })
        inhalt.addView(Button(this).apply {
            text = "Code-Seite öffnen"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SEITE))) }
        })
        inhalt.addView(TextView(this).apply {
            text = "Einlösen: Minecraft → Essential-Garderobe → „Redeem“ neben deinen Coins → Code einfügen."
            setTypeface(null, Typeface.BOLD)
            setPadding(0, pad, 0, pad / 2)
        })
        liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inhalt.addView(liste)

        setContentView(ScrollView(this).apply { addView(inhalt) })

        CodeChecker.kanalAnlegen(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        CodeChecker.planen(this)

        if (CodeChecker.zuletzt(this) == 0L) jetztPruefen() else aktualisieren(null)
    }

    private fun jetztPruefen() {
        status.text = "Prüfe …"
        Thread {
            val meldung = try {
                val e = CodeChecker.pruefe(this)
                when {
                    e.ersterLauf -> "Erste Prüfung: ${e.alle.size} Codes gespeichert. Ab jetzt meldet sich die App nur bei neuen."
                    e.neu.isEmpty() -> "Keine neuen Codes."
                    else -> "Neu: ${e.neu.joinToString(", ")}"
                }
            } catch (ex: Exception) {
                "Fehler: ${ex.message ?: "keine Verbindung"}"
            }
            runOnUiThread { aktualisieren(meldung) }
        }.start()
    }

    private fun aktualisieren(meldung: String?) {
        val zeit = CodeChecker.zuletzt(this)
        val zuletzt = if (zeit == 0L) "noch nie" else
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(zeit))
        status.text = (if (meldung != null) "$meldung\n" else "") + "Zuletzt geprüft: $zuletzt"

        liste.removeAllViews()
        val neu = CodeChecker.neueste(this)
        val infos = CodeChecker.details(this)
        val codes = CodeChecker.aktuelle(this)
        val (abgelaufen, gueltig) = codes.partition { CodeChecker.istAbgelaufen(infos[it] ?: "") }
        // Neue Codes zuerst
        val gueltigSortiert = gueltig.sortedBy { if (it in neu) 0 else 1 }

        if (codes.isEmpty()) {
            liste.addView(TextView(this).apply { text = "Noch keine Codes – tippe auf „Jetzt prüfen“." })
            return
        }
        ueberschrift("✅ Gültige Codes (${gueltig.size})")
        gueltigSortiert.forEach { codeZeile(it, infos[it], it in neu, false) }
        if (abgelaufen.isNotEmpty()) {
            ueberschrift("⌛ Abgelaufen (${abgelaufen.size})")
            abgelaufen.forEach { codeZeile(it, infos[it], false, true) }
        }
    }

    private fun ueberschrift(t: String) {
        val pad = (12 * resources.displayMetrics.density).toInt()
        liste.addView(TextView(this).apply {
            text = t
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, pad, 0, pad / 3)
        })
    }

    private fun codeZeile(code: String, info: String?, istNeu: Boolean, istAbgelaufen: Boolean) {
        val pad = (8 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            if (istAbgelaufen) alpha = 0.45f
            if (istNeu) setBackgroundColor(0x33FFC107)
        }
        box.addView(TextView(this).apply {
            text = if (istNeu) "⭐ $code  (NEU)" else code
            textSize = 20f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = (info ?: "Belohnung unbekannt") + if (!istAbgelaufen) "   · antippen zum Kopieren" else ""
            textSize = 14f
        })
        if (!istAbgelaufen) {
            box.setOnClickListener {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Code", code))
                Toast.makeText(this@MainActivity, "$code kopiert – jetzt in Essential einlösen", Toast.LENGTH_SHORT).show()
            }
        }
        liste.addView(box)
    }
}
