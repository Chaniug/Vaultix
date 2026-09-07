package com.bastion.app.ui.icons

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.random.Random

const val PASSWORD_ICON_TYPE_NONE = "NONE"
const val PASSWORD_ICON_TYPE_SIMPLE = "SIMPLE_ICON"
const val PASSWORD_ICON_TYPE_UPLOADED = "UPLOADED"
private const val STRATUM_ICON_ASSET_ROOT = "stratum_icons"
private const val STRATUM_ICON_ASSET_MAIN_DIR = "$STRATUM_ICON_ASSET_ROOT/icons"
private const val STRATUM_ICON_ASSET_EXTRA_DIR = "$STRATUM_ICON_ASSET_ROOT/extraicons"

data class SimpleIconOption(
    val slug: String,
    val label: String
)

data class AutoMatchedSimpleIcon(
    val slug: String?,
    val bitmap: ImageBitmap?,
    val resolved: Boolean
)

object SimpleIconCatalog {
    @Volatile
    private var cachedOptions: List<SimpleIconOption>? = null
    @Volatile
    private var cachedSlugs: Set<String>? = null

    fun search(context: Context, query: String): List<SimpleIconOption> {
        val q = query.trim().lowercase(Locale.ROOT)
        val options = getOptions(context)
        if (q.isEmpty()) return options
        return options.filter { option ->
            option.label.lowercase(Locale.ROOT).contains(q) ||
                option.slug.lowercase(Locale.ROOT).contains(q)
        }
    }

    private fun getOptions(context: Context): List<SimpleIconOption> {
        cachedOptions?.let { return it }
        val slugs = LinkedHashSet<String>()
        collectSlugs(context, STRATUM_ICON_ASSET_MAIN_DIR, slugs)
        collectSlugs(context, STRATUM_ICON_ASSET_EXTRA_DIR, slugs)

        val resolved = slugs.map { slug ->
            SimpleIconOption(slug = slug, label = prettyLabel(slug))
        }.sortedBy { it.label.lowercase(Locale.ROOT) }

        cachedOptions = resolved
        return resolved
    }

    fun getSlugs(context: Context): Set<String> {
        cachedSlugs?.let { return it }
        val slugs = getOptions(context).mapTo(LinkedHashSet()) { it.slug }
        cachedSlugs = slugs
        return slugs
    }

    private fun collectSlugs(context: Context, assetDir: String, output: MutableSet<String>) {
        val files = runCatchingObserved { context.assets.list(assetDir).orEmpty() }.getOrDefault(emptyArray())
        files.forEach { name ->
            val raw = when {
                name.endsWith(".webp", ignoreCase = true) -> name.removeSuffix(".webp")
                name.endsWith(".png", ignoreCase = true) -> name.removeSuffix(".png")
                else -> return@forEach
            }
            val normalized = if (raw.endsWith("_dark")) raw.removeSuffix("_dark") else raw
            if (normalized.isNotBlank()) {
                output.add(normalized.lowercase(Locale.ROOT))
            }
        }
    }

    private fun prettyLabel(slug: String): String {
        val parts = slug.replace('_', ' ').replace('-', ' ')
            .split(" ")
            .filter { it.isNotBlank() }
        return parts.joinToString(" ") { part ->
            if (part.length <= 3) {
                part.uppercase(Locale.ROOT)
            } else {
                part.replaceFirstChar { c ->
                    if (c.isLowerCase()) c.titlecase(Locale.ROOT) else c.toString()
                }
            }
        }.ifBlank { slug }
    }
}

private val DOMAIN_ALIAS_TO_ICON_SLUG = mapOf(
    "steampowered" to "steam",
    "steamcommunity" to "steam",
    "office365" to "office",
    "live" to "microsoft",
    "x" to "twitter",
    // 全球服务常用域名变体（指向已打包的 slug）
    "gmail" to "google",
    "googlemail" to "google",
    "outlook" to "microsoft",
    "hotmail" to "microsoft",
    "yahoo" to "yahoo",
    "youtu" to "youtube",
    "fb" to "facebook",
    "fbcdn" to "facebook",
    "whatsapp" to "whatsapp",
    "telegram" to "telegram",
    "t" to "telegram",
    "linkedin" to "linkedin",
    "discord" to "discord",
    "reddit" to "reddit",
    "twitch" to "twitch",
    "spotify" to "spotify",
    "netflix" to "netflix",
    "paypal" to "paypal",
    "amazonaws" to "amazon",
    "aws" to "amazon",
    "dropbox" to "dropbox",
    "slack" to "slack",
    "zoom" to "zoom",
    "uber" to "uber",
    "github" to "github",
    "gitlab" to "gitlab",
    "bitbucket" to "bitbucket",
    "stackoverflow" to "stackoverflow",
    "medium" to "medium",
    "wordpress" to "wordpress",
    "shopify" to "shopify",
    "stripe" to "stripe",
    "ebay" to "ebay",
    "booking" to "booking",
    "airbnb" to "airbnb",
    // 中国服务域名变体（slug 待补充品牌资产后自动生效，见 BastionDocs/icon-coverage-chinese-services.md）
    "qq" to "tencent",
    "qzone" to "tencent",
    "weibo" to "weibo",
    "weixin" to "wechat",
    "alipay" to "alipay",
    "taobao" to "taobao",
    "tmall" to "tmall",
    "jd" to "jd",
    "jingdong" to "jd",
    "baidu" to "baidu",
    "163" to "netease",
    "126" to "netease",
    "bilibili" to "bilibili",
    "meituan" to "meituan",
    "dianping" to "meituan",
    "waimai" to "meituan",
    "didi" to "didi",
    "12306" to "12306",
    "icbc" to "icbc",
    "cmb" to "cmbchina",
    "ccb" to "ccb",
    "abchina" to "abc",
    "bankcomm" to "bankcomm",
    "zhihu" to "zhihu",
    "douyin" to "douyin",
    "tiktok" to "tiktok",
    "xiaohongshu" to "xiaohongshu",
    "kuaishou" to "kuaishou",
    "dingtalk" to "dingtalk",
    "feishu" to "feishu",
    "larksuite" to "feishu"
)

/**
 * 应用包名 -> 图标 slug 映射（Android 自动填充场景）。
 * 指向已打包 slug 的条目可立即生效；指向中国服务 slug 的条目待补充品牌资产后自动生效。
 */
private val PACKAGE_TO_ICON_SLUG = mapOf(
    // 全球应用
    "com.spotify.music" to "spotify",
    "com.facebook.katana" to "facebook",
    "com.facebook.orca" to "facebook",
    "com.instagram.android" to "instagram",
    "com.linkedin.android" to "linkedin",
    "com.twitch.android.app" to "twitch",
    "com.reddit.frontpage" to "reddit",
    "com.dropbox.android" to "dropbox",
    "com.Slack" to "slack",
    "com.discord" to "discord",
    "com.zoom.us" to "zoom",
    "com.sony.playstation.mobile" to "playstation",
    "com.epicgames.portal" to "epicgames",
    "com.ubisoft.mobile.legends" to "ubisoft",
    "com.valvesoftware.android.steam" to "steam",
    "com.amazon.mShop.android.shopping" to "amazon",
    "com.google.android.gm" to "google",
    "com.google.android.youtube" to "youtube",
    "com.microsoft.office.outlook" to "microsoft",
    "com.microsoft.todos" to "microsoft",
    "org.telegram.messenger" to "telegram",
    "com.whatsapp" to "whatsapp",
    "com.netflix.mediaclient" to "netflix",
    "com.ubercab" to "uber",
    "com.github.android" to "github",
    "com.paypal.android.p2pmobile" to "paypal",
    "com.braintreepayments.api.dropin" to "paypal",
    "com.apple.mobilemail" to "apple",
    // 中国应用
    "com.tencent.mm" to "wechat",
    "com.tencent.mobileqq" to "tencent",
    "com.eg.android.AlipayGphone" to "alipay",
    "com.taobao.taobao" to "taobao",
    "com.tmall.wireless" to "tmall",
    "com.jingdong.app.mall" to "jd",
    "com.baidu.searchbox" to "baidu",
    "com.netease.cloudmusic" to "netease",
    "tv.danmaku.bili" to "bilibili",
    "com.sankuai.meituan" to "meituan",
    "com.sankuai.meituan.takeoutnew" to "meituan",
    "com.sdu.didi.psnger" to "didi",
    "com.icbc" to "icbc",
    "com.chinamworld.bank" to "icbc",
    "com.cmbchina.ccd.pluto" to "cmbchina",
    "com.xingin.xhs" to "xiaohongshu",
    "com.ss.android.ugc.aweme" to "douyin",
    "com.smile.gifmaker" to "kuaishou",
    "com.alibaba.android.rimet" to "dingtalk",
    "com.lark.app" to "feishu",
    "com.bytedance.lark" to "feishu"
)

/**
 * 中文应用名 -> 图标 slug 映射（标题自动匹配场景）。
 * 中文标题不会被英文切词命中，故需显式映射。slug 待补充品牌资产后自动生效。
 */
private val CJK_TITLE_TO_SLUG = mapOf(
    "微信" to "wechat",
    "支付宝" to "alipay",
    "淘宝" to "taobao",
    "天猫" to "tmall",
    "京东" to "jd",
    "拼多多" to "pinduoduo",
    "微博" to "weibo",
    "百度" to "baidu",
    "网易" to "netease",
    "网易云音乐" to "netease",
    "QQ" to "tencent",
    "腾讯" to "tencent",
    "腾讯视频" to "tencent",
    "哔哩哔哩" to "bilibili",
    "B站" to "bilibili",
    "美团" to "meituan",
    "大众点评" to "meituan",
    "滴滴" to "didi",
    "滴滴出行" to "didi",
    "12306" to "12306",
    "中国工商银行" to "icbc",
    "工商银行" to "icbc",
    "招商银行" to "cmbchina",
    "中国建设银行" to "ccb",
    "中国农业银行" to "abc",
    "交通银行" to "bankcomm",
    "知乎" to "zhihu",
    "抖音" to "douyin",
    "快手" to "kuaishou",
    "小红书" to "xiaohongshu",
    "钉钉" to "dingtalk",
    "飞书" to "feishu"
)

private val WEB_SCHEMES = setOf("http", "https")

private val GENERIC_HOST_LABELS = setOf(
    "www", "m", "mobile", "login", "auth", "account", "accounts", "secure", "id", "api", "app", "store",
    "com", "net", "org", "io", "co", "dev", "android"
)

private val GENERIC_PACKAGE_PARTS = setOf(
    "com", "net", "org", "io", "co", "dev", "app", "android"
)

private val MULTI_PART_PUBLIC_SUFFIX = setOf(
    "co.uk", "org.uk", "ac.uk", "gov.uk", "com.cn", "com.hk", "co.jp", "com.au", "com.br", "co.in"
)

private val NON_ALNUM_REGEX = Regex("[^a-z0-9]")
private val NON_ALNUM_OR_SPACE_REGEX = Regex("[^a-z0-9 ]")

private data class ParsedWebsite(
    val scheme: String?,
    val host: String
)

private fun normalizeAutoSlugToken(value: String): String {
    return value.trim()
        .lowercase(Locale.ROOT)
        .replace(NON_ALNUM_REGEX, "")
}

private fun parseWebsite(rawWebsite: String): ParsedWebsite {
    // 与 PasswordGrouping.extractHost 同理：website 可能被填成多个地址，只取第一个，
    // 否则整体解析必然抛 URISyntaxException。
    val raw = rawWebsite.trim().substringBefore(',').trim()
    if (raw.isBlank()) return ParsedWebsite(scheme = null, host = "")
    val withScheme = if (raw.contains("://")) raw else "https://$raw"
    // 图标自动匹配是高频纯函数，解析失败属常态，静默处理以免形成日志风暴。
    val parsed = runCatching { URI(withScheme) }.getOrNull()
    return ParsedWebsite(
        scheme = parsed?.scheme?.trim()?.lowercase(Locale.ROOT),
        host = parsed?.host.orEmpty().trim().lowercase(Locale.ROOT)
    )
}

private fun extractRegistrableDomainLabel(host: String): String {
    val labels = host.split('.').filter { it.isNotBlank() }
    if (labels.isEmpty()) return ""
    if (labels.size == 1) return labels.first()

    val lastTwo = labels.takeLast(2).joinToString(".")
    return if (lastTwo in MULTI_PART_PUBLIC_SUFFIX && labels.size >= 3) {
        labels[labels.size - 3]
    } else {
        labels[labels.size - 2]
    }
}

private fun buildAutoMatchCandidates(
    website: String,
    title: String?,
    appPackageName: String?
): List<String> {
    val candidates = LinkedHashSet<String>()

    fun addCandidate(raw: String?) {
        if (raw.isNullOrBlank()) return
        val normalized = normalizeAutoSlugToken(raw)
        if (normalized.isBlank()) return
        candidates.add(normalized)
        DOMAIN_ALIAS_TO_ICON_SLUG[normalized]?.let { alias -> candidates.add(alias) }
    }

    val parsedWebsite = parseWebsite(website)
    val host = parsedWebsite.host
    val isWebScheme = parsedWebsite.scheme == null || parsedWebsite.scheme in WEB_SCHEMES
    if (host.isNotBlank()) {
        val cleanHost = host.removePrefix("www.")
        val labels = cleanHost.split('.').filter { it.isNotBlank() }

        if (isWebScheme) {
            addCandidate(extractRegistrableDomainLabel(cleanHost))
            addCandidate(labels.joinToString(""))
            labels.forEachIndexed { index, label ->
                if (index < labels.lastIndex && label !in GENERIC_HOST_LABELS) {
                    addCandidate(label)
                }
            }
        } else {
            labels.forEach { label ->
                if (label !in GENERIC_HOST_LABELS) {
                    addCandidate(label)
                }
            }
            // Non-web URI (e.g. android://com.example.app) should not prefer a fake "domain" segment.
            if (labels.size >= 2) {
                addCandidate(labels[labels.size - 2])
            } else if (labels.size == 1) {
                addCandidate(labels.first())
            }
        }
    }

    title?.takeIf { it.isNotBlank() }?.let { rawTitle ->
        // 中文标题无法被英文切词命中，需显式映射
        CJK_TITLE_TO_SLUG[rawTitle.trim()]?.let { candidates.add(it) }
        val compactTitle = rawTitle.lowercase(Locale.ROOT).replace(NON_ALNUM_OR_SPACE_REGEX, " ")
        compactTitle.split(' ')
            .asSequence()
            .filter { it.isNotBlank() && it.length > 1 && it !in GENERIC_HOST_LABELS }
            .forEach { token -> addCandidate(token) }
        addCandidate(compactTitle.replace(" ", ""))
    }

    appPackageName?.takeIf { it.isNotBlank() }?.let { pkg ->
        // 整包名直接映射（覆盖非常规命名，如 com.spotify.music -> spotify）
        PACKAGE_TO_ICON_SLUG[pkg.lowercase(Locale.ROOT)]?.let { candidates.add(it) }
        val parts = pkg.lowercase(Locale.ROOT).split('.')
        parts.asReversed()
            .asSequence()
            .filter { it.isNotBlank() && it !in GENERIC_PACKAGE_PARTS }
            .forEach { part -> addCandidate(part) }
    }

    return candidates.toList()
}

fun resolveAutoMatchedSimpleIconSlug(
    context: Context,
    website: String,
    title: String? = null,
    appPackageName: String? = null
): String? {
    val hasAnyInput = website.isNotBlank() || !title.isNullOrBlank() || !appPackageName.isNullOrBlank()
    if (!hasAnyInput) return null
    val availableSlugs = SimpleIconCatalog.getSlugs(context)
    if (availableSlugs.isEmpty()) return null

    val candidates = buildAutoMatchCandidates(
        website = website,
        title = title,
        appPackageName = appPackageName
    )

    return candidates.firstOrNull { candidate -> candidate in availableSlugs }
}

object PasswordCustomIconStore {
    private const val TAG = "PasswordCustomIconStore"
    private const val ICON_DIR = "password_icons"
    private const val MAX_DIMENSION = 384

    fun getIconDir(context: Context): File {
        val dir = File(context.filesDir, ICON_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun resolveIconFile(context: Context, value: String?): File? {
        if (value.isNullOrBlank()) return null
        val safeName = File(value).name
        val file = File(getIconDir(context), safeName)
        return if (file.exists()) file else null
    }

    fun deleteIconFile(context: Context, value: String?): Boolean {
        val file = resolveIconFile(context, value) ?: return false
        return runCatchingObserved { file.delete() }.getOrDefault(false)
    }

    suspend fun importAndCompress(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatchingObserved {
            val decoded = decodeBitmapCompat(context, uri)
                ?: throw IllegalStateException("Unsupported image format")

            val finalBitmap = resizeIfNeeded(decoded, MAX_DIMENSION)
            if (finalBitmap !== decoded) decoded.recycle()

            val fileName = "icon_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}.webp"
            val target = File(getIconDir(context), fileName)
            FileOutputStream(target).use { out ->
                if (!finalBitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)) {
                    throw IllegalStateException("Failed to compress image")
                }
                out.flush()
            }
            finalBitmap.recycle()
            fileName
        }
    }

    private fun decodeBitmapCompat(context: Context, uri: Uri): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            runCatchingObserved {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val sample = calculateSampleSize(info.size.width, info.size.height, MAX_DIMENSION)
                    if (sample > 1) decoder.setTargetSampleSize(sample)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            }.onFailure {
                Log.w(TAG, "ImageDecoder failed, fallback to BitmapFactory", it)
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        var currentWidth = width
        var currentHeight = height
        while (currentWidth > maxDimension || currentHeight > maxDimension) {
            sample *= 2
            currentWidth /= 2
            currentHeight /= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun resizeIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDimension && h <= maxDimension) return bitmap
        val scale = minOf(maxDimension.toFloat() / w, maxDimension.toFloat() / h)
        val targetW = (w * scale).toInt().coerceAtLeast(1)
        val targetH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }
}

private object SimpleIconCache {
    private const val TAG = "SimpleIconCache"
    private const val DISK_DIR = "stratum_icons"
    private const val CACHE_VERSION = "stratum_v1_4_0"
    private const val MAX_ICON_CACHE_BYTES_KB = 4 * 1024 // ~4MB，以 KB 计

    private val memory = object : LruCache<String, ImageBitmap>(MAX_ICON_CACHE_BYTES_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return (value.asAndroidBitmap().allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    suspend fun getIcon(context: Context, slug: String, darkTheme: Boolean): ImageBitmap? {
        val normalizedSlug = normalizeSimpleIconSlug(slug)
        if (normalizedSlug.isEmpty()) return null
        val key = "${normalizedSlug}_${if (darkTheme) "dark" else "light"}_$CACHE_VERSION"

        memory.get(key)?.let { return it }

        val diskDir = File(context.cacheDir, DISK_DIR).also { if (!it.exists()) it.mkdirs() }
        val diskFile = File(diskDir, "$key.png")

        return withContext(Dispatchers.IO) {
        if (diskFile.exists()) {
            decodeSampledIcon(diskFile, MAX_STRATUM_DIM)?.let { bitmap ->
                val image = bitmap.asImageBitmap()
                memory.put(key, image)
                return@withContext image
            }
        }

            runCatchingObserved {
                val bitmap = fetchSimpleIconBitmap(context, normalizedSlug, darkTheme) ?: return@runCatchingObserved null
                FileOutputStream(diskFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                }
                val image = bitmap.asImageBitmap()
                memory.put(key, image)
                image
            }.onFailure { error ->
                Log.w(TAG, "Failed to load stratum icon: slug=$slug", error)
            }.getOrNull()
        }
    }

    private fun decodeSampledIcon(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return BitmapFactory.decodeFile(file.absolutePath)
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = iconSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun fetchSimpleIconBitmap(context: Context, normalizedSlug: String, darkTheme: Boolean): Bitmap? {
        // Icons are stored as lossless WebP (see scripts/convert_stratum_icons_to_webp.sh).
        // A .png fallback is kept so any stray/contributed PNG still resolves.
        val dirs = listOf(STRATUM_ICON_ASSET_MAIN_DIR, STRATUM_ICON_ASSET_EXTRA_DIR)
        val exts = listOf("webp", "png")
        val slugVariants = if (darkTheme) {
            listOf("${normalizedSlug}_dark", normalizedSlug)
        } else {
            listOf(normalizedSlug, "${normalizedSlug}_dark")
        }

        for (slugVariant in slugVariants) {
            for (dir in dirs) {
                for (ext in exts) {
                    val assetPath = "$dir/$slugVariant.$ext"
                    val bitmap = runCatchingObserved {
                        context.assets.open(assetPath).use { stream ->
                            val bytes = stream.readBytes()
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            val opts = BitmapFactory.Options().apply {
                                inSampleSize = iconSampleSize(bounds.outWidth, bounds.outHeight, MAX_STRATUM_DIM)
                            }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        }
                    }.getOrNull()
                    if (bitmap != null) {
                        return bitmap
                    }
                }
            }
        }
        return null
    }
}

private const val MAX_STRATUM_DIM = 256
private const val MAX_UPLOAD_ICON_DIM = 512

private fun iconSampleSize(width: Int, height: Int, maxDim: Int): Int {
    var sample = 1
    var w = width
    var h = height
    while (w > maxDim || h > maxDim) {
        sample *= 2
        w /= 2
        h /= 2
    }
    return sample.coerceAtLeast(1)
}

fun normalizeSimpleIconSlug(input: String): String {
    return input.trim().lowercase(Locale.ROOT).replace(" ", "")
}

@Composable
fun rememberUploadedPasswordIcon(value: String?): ImageBitmap? {
    val context = LocalContext.current
    var icon by remember(value) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(value) {
        if (value.isNullOrBlank()) {
            icon = null
            return@LaunchedEffect
        }
        icon = withContext(Dispatchers.IO) {
            val file = PasswordCustomIconStore.resolveIconFile(context, value) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = iconSampleSize(bounds.outWidth, bounds.outHeight, MAX_UPLOAD_ICON_DIM)
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
        }
    }
    return icon
}

@Composable
@Suppress("UNUSED_PARAMETER")
fun rememberSimpleIconBitmap(slug: String?, tintColor: Color, enabled: Boolean = true): ImageBitmap? {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    var icon by remember(slug, darkTheme, enabled) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(slug, darkTheme, enabled) {
        if (!enabled || slug.isNullOrBlank()) {
            icon = null
            return@LaunchedEffect
        }
        icon = SimpleIconCache.getIcon(context, slug, darkTheme)
    }
    return icon
}

@Composable
fun rememberAutoMatchedSimpleIcon(
    website: String,
    title: String? = null,
    appPackageName: String? = null,
    tintColor: Color,
    enabled: Boolean = true
): AutoMatchedSimpleIcon {
    val context = LocalContext.current
    var matchedSlug by remember(website, title, appPackageName, enabled) { mutableStateOf<String?>(null) }
    var resolved by remember(website, title, appPackageName, enabled) { mutableStateOf(!enabled) }

    LaunchedEffect(website, title, appPackageName, enabled) {
        val hasAnyInput = website.isNotBlank() || !title.isNullOrBlank() || !appPackageName.isNullOrBlank()
        if (!enabled || !hasAnyInput) {
            matchedSlug = null
            resolved = true
            return@LaunchedEffect
        }
        resolved = false
        matchedSlug = withContext(Dispatchers.Default) {
            resolveAutoMatchedSimpleIconSlug(
                context = context,
                website = website,
                title = title,
                appPackageName = appPackageName
            )
        }
        resolved = true
    }

    val bitmap = rememberSimpleIconBitmap(
        slug = matchedSlug,
        tintColor = tintColor,
        enabled = enabled && !matchedSlug.isNullOrBlank()
    )
    return AutoMatchedSimpleIcon(slug = matchedSlug, bitmap = bitmap, resolved = resolved)
}
