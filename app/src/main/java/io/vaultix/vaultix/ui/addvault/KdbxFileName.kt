package io.vaultix.vaultix.ui.addvault

/**
 * 新建 KDBX 时预填给系统文件面板的文件名，由**用户填写的库名**推导。
 *
 * ## 为什么不再用固定名
 *
 * 原先这里始终传 `vault.kdbx`（用户看到的是 `vaultix.kdbx`），于是：
 *
 * 1. **在文件管理器里分不清**。建了三个库，磁盘上是三个同名文件，
 *    只有打开后才看得到"库名"——而那正是用户抱怨的点。
 * 2. **同目录第二个库就撞名**。`CreateDocument` 遇到重名会自作主张加 `(1)`，
 *    变成 `vaultix(1).kdbx`；再往后是 `(2)`、`(3)`，一眼看不出谁是谁。
 * 3. 撞上**非空**同名文件时直接被 `SafKdbxFileSource` 拒掉
 *    （`failIfExists` 的判据是"有字节"，见 `SafCreatePolicy`），
 *    用户得到的是"该位置已存在同名文件"——而他明明只是想新建一个。
 *
 * ⇒ 文件名跟着库名走，是这三件事的共同解。
 *
 * ## 这**只是预填值**
 *
 * `CreateDocument` 的文件名输入框是可编辑的：用户想让文件叫别的名字，
 * 在面板里改即可，不会被这段逻辑限制。
 *
 * ## 为什么按 Windows 规则清洗
 *
 * 保存位置可能是手机本地（ext4/f2fs，只禁 `/`）、OneDrive（等同 Windows 规则）
 * 或 WebDAV（取决于服务端）。取交集 ⇒ 按最严的 Windows 来：
 * `\ / : * ? " < > |` 与控制字符替换成 `_`，并去掉首尾空白与末尾的点。
 * 否则会出现"名字在本地建得出来、传到 OneDrive 就失败"。
 *
 * @return 始终以 `.kdbx` 结尾、且可安全用作文件名的字符串。
 */
internal fun suggestedKdbxFileName(vaultName: String): String {
    val base = sanitizeKdbxFileBase(vaultName)
    return if (base.isEmpty()) DEFAULT_KDBX_FILE_NAME else base + KDBX_SUFFIX
}

/** 库名为空（表单校验会拦，但本函数必须自洽）时的兜底文件名。 */
internal const val DEFAULT_KDBX_FILE_NAME = "vaultix.kdbx"

private const val KDBX_SUFFIX = ".kdbx"

/**
 * 文件名主体的长度上限，按 **Unicode 码点** 计（不是字节，也不是 `Char`）。
 *
 * 留足余量：多数文件系统的单段上限是 255 **字节**，UTF-8 中文占 3 字节
 * ⇒ 64 个汉字 = 192 字节，加上 `.kdbx` 仍然安全。再长的名字在面板里也显示不全。
 */
private const val MAX_BASE_CODE_POINTS = 64

/**
 * Windows/OneDrive 不接受的文件名字符，外加 C0 控制字符（含 `\n` `\t`）。
 *
 * 换成 `_` 而不是直接删除：`我的/库` → `我的_库` 还能看出原意，
 * 删成 `我的库` 则会让用户以为是自己少打了字。
 */
private val ILLEGAL_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

private fun sanitizeKdbxFileBase(vaultName: String): String {
    // ① 先 trim：全角空格（U+3000）也算空白，用 isWhitespace 而非 trim(' ')
    var name = vaultName.trim()

    // ② 去掉用户可能自己带的 `.kdbx`，避免 `unit.kdbx.kdbx`。
    //    这里**不加长度守卫**：只填 `.kdbx` 时去掉就是空串，交给 ⑥ 的兜底处理。
    //    （初版加了 `length > suffix.length` 的守卫，反而让 `.kdbx` 原样留下、
    //     拼成 `.kdbx.kdbx` —— 单测 `falls back when only suffix is given` 抓到的。）
    if (name.endsWith(KDBX_SUFFIX, ignoreCase = true)) {
        name = name.dropLast(KDBX_SUFFIX.length)
    }

    // ③ 非法字符 → `_`
    name = name.replace(ILLEGAL_FILE_NAME_CHARS, "_")

    // ④ Windows 不允许文件名以空格或点结尾（会被静默去掉，导致"名字对不上"）
    name = name.trimEnd(' ', '.')

    // ⑤ 超长截断。必须在加后缀**之前**做，否则后缀会挤掉最后一个字符；
    //    用码点截断而不是 `take()`，否则 emoji 会被切成半个（半个代理对是无效 UTF-16）。
    name = truncateToCodePoints(name, MAX_BASE_CODE_POINTS)

    // ⑥ 截断后可能又落在空格上，再收一次
    return name.trim()
}

/**
 * 按 Unicode 码点截断。
 *
 * `String.take(n)` 数的是 `Char`（UTF-16 码元）——对 emoji 这类代理对，
 * `take(n)` 可能正好切在中间，产出**无效的孤立代理**，写进文件名会变成 `?`。
 * `offsetByCodePoints` 按真正的字符走，切在边界上。
 */
private fun truncateToCodePoints(value: String, maxCodePoints: Int): String {
    if (value.codePointCount(0, value.length) <= maxCodePoints) return value
    return value.substring(0, value.offsetByCodePoints(0, maxCodePoints))
}
