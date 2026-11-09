package com.protvirto.capri

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONObject
import java.util.Locale

/** What kind of app is in front, and opening the ones you ask her for. */
class Apps(private val context: Context) {
    private val pm = context.packageManager
    private val labels = HashMap<String, String>()

    data class Info(val pkg: String, val label: String, val kind: String, val dualScreen: Boolean, val rival: String?)

    fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    fun info(pkg: String): Info {
        val label = label(pkg)
        val kind = when {
            pkg == ESDE || pkg.startsWith("org.es_de") -> "esde"
            pkg == "com.protvirto.redline" -> "redline"
            STREAM.any { pkg.startsWith(it) } -> "stream"
            EMULATORS.any { pkg.startsWith(it) } -> "emulator"
            VIDEO.any { pkg.startsWith(it) } -> "video"
            SOCIAL.any { pkg.startsWith(it) } -> "social"
            STORES.any { pkg.startsWith(it) } -> "store"
            BROWSERS.any { pkg.startsWith(it) } -> "browser"
            isLauncher(pkg) -> "home"
            else -> when (runCatching { pm.getApplicationInfo(pkg, 0).category }.getOrDefault(-1)) {
                ApplicationInfo.CATEGORY_GAME -> "game"
                ApplicationInfo.CATEGORY_VIDEO -> "video"
                ApplicationInfo.CATEGORY_SOCIAL -> "social"
                else -> "other"
            }
        }
        return Info(pkg, label, kind, DUAL_SCREEN.any { pkg.startsWith(it) }, RIVALS[pkg])
    }

    private var launchers: Set<String>? = null
    private fun isLauncher(pkg: String): Boolean {
        val set = launchers ?: pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet().also { launchers = it }
        return pkg in set
    }

    // ---- opening things ---------------------------------------------------------------------------
    private fun launchable(): List<Pair<String, String>> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }

    /** "open retroarch" -> the app whose name fits best, as her chat expects it. */
    fun find(wanted: String): JSONObject? {
        val q = norm(wanted).removePrefix("the ").trim()
        if (q.isBlank()) return null
        val apps = launchable()
        val hit = apps.firstOrNull { norm(it.second) == q }
            ?: apps.firstOrNull { norm(it.second).startsWith(q) }
            ?: apps.firstOrNull { q in norm(it.second) }
            ?: apps.firstOrNull { norm(it.second) in q && norm(it.second).length >= 4 }
            ?: return null
        return JSONObject().put("kind", "app").put("name", hit.second).put("pkg", hit.first)
    }

    fun launch(item: JSONObject): JSONObject {
        val pkg = item.optString("pkg")
        val intent = pm.getLaunchIntentForPackage(pkg)
            ?: return JSONObject().put("ok", false).put("error", "not installed")
        return start(intent)
    }

    fun open(uri: String): Boolean = start(Intent(Intent.ACTION_VIEW, Uri.parse(uri))).optBoolean("ok")

    fun start(intent: Intent): JSONObject = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        JSONObject().put("ok", true)
    } catch (e: ActivityNotFoundException) {
        JSONObject().put("ok", false).put("error", "nothing can open that")
    } catch (e: SecurityException) {
        JSONObject().put("ok", false).put("error", e.message ?: "not allowed")
    }

    fun installed(pkg: String): Boolean = runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    companion object {
        const val ESDE = "org.es_de.frontend"

        val EMULATORS = listOf(
            "com.retroarch", "org.dolphinemu", "org.ppsspp", "xyz.aethersx2", "com.nethersx2", "xyz.nethersx2",
            "com.github.stenzek.duckstation", "com.flycast", "me.magnum.melonds", "com.dsemu.drastic", "org.citra",
            "io.github.lime3ds", "org.azahar_emu", "org.yuzu", "dev.eden", "org.citron", "dev.suyu", "org.sudachi",
            "skyline.emu", "org.vita3k", "info.cemu", "com.explusalpha", "com.fms.", "com.seleuco.mame4droid",
            "com.winlator", "net.rpcs3", "com.github.mupen64plus", "org.mupen64plusae",
            "com.emulator.", "it.dbtecno.pizzaboy", "com.retroarch.aarch64", "com.mikeage", "io.github.azahar",
            "org.mm.jr", "ru.vastness.altmer", "com.xiaoji.gamesirnsemulator", "com.termux.x11", "com.gamehub",
            "com.idbg.eden",
        )
        // emulators that use both of the Thor's screens: she gets out of the way entirely
        val DUAL_SCREEN = listOf("me.magnum.melonds", "com.dsemu.drastic", "org.citra", "io.github.lime3ds", "org.azahar_emu", "io.github.azahar", "info.cemu")
        val STREAM = listOf(
            "com.limelight", "com.valvesoftware.steamlink", "com.microsoft.xcloud", "com.gamepass", "com.nvidia.geforcenow",
            "com.metallic.chiaki", "io.github.streetpea.chiaki", "com.playstation.remoteplay", "com.parsec.app", "com.artemis",
        )
        val VIDEO = listOf(
            "com.google.android.youtube", "app.revanced.android.youtube", "app.rvx", "com.netflix", "tv.twitch", "com.zhiliaoapp.musically",
            "com.ss.android.ugc", "com.disney", "com.amazon.avod", "com.crunchyroll", "org.schabi.newpipe", "com.google.android.apps.youtube",
        )
        val SOCIAL = listOf(
            "com.reddit", "com.instagram", "com.twitter.android", "com.facebook", "com.discord", "org.telegram", "com.snapchat",
            "com.whatsapp", "com.bluesky", "xyz.blueskyweb",
        )
        val STORES = listOf("com.android.vending", "com.valvesoftware.android.steam", "com.epicgames", "com.aurora.store")
        val BROWSERS = listOf("com.android.chrome", "org.mozilla", "com.microsoft.emmx", "com.brave", "com.opera", "com.vivaldi", "com.sec.android.app.sbrowser")

        // her rivals (bond.js RIVALS): she gets jealous when you open one
        val RIVALS = mapOf(
            "com.openai.chatgpt" to "chatgpt",
            "com.anthropic.claude" to "claude",
            "com.google.android.apps.bard" to "gemini",
            "com.microsoft.copilot" to "copilot",
            "ai.perplexity.app.android" to "perplexity",
            "com.deepseek.chat" to "deepseek",
            "ai.x.grok" to "grok",
            "ai.mistral.chat" to "lechat",
            "ai.character.app" to "characterai",
        )
    }
}
