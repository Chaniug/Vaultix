@file:Suppress("LocalContextGetResourceValueCall")
package com.bastion.app.ui.screens

import com.bastion.app.logging.runCatchingObserved
import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.bastion.app.R
import com.bastion.app.data.PasswordDatabase
import com.bastion.app.ui.components.PasswordEntryPickerBottomSheet
import com.bastion.app.util.DataExportImportManager
import com.bastion.app.util.FileOperationHelper
import com.bastion.app.utils.KeePassOperationException
import com.bastion.app.viewmodel.DataExportImportViewModel
import com.bastion.app.ui.components.OutlinedTextField

/**
 * 数据导入界面 - M3 Expressive 设计
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportDataScreen(
    onNavigateBack: () -> Unit,
    onImport: suspend (Uri) -> Result<Int>,  // 普通数据导入
    onImportAegis: suspend (Uri) -> Result<Int>,  // Aegis JSON导入
    onImportEncryptedAegis: suspend (Uri, String) -> Result<Int>,  // 加密的Aegis JSON导入
    onImportStratum: suspend (Uri, String?) -> Result<Int> = { _, _ -> Result.failure(Exception("Not implemented")) }, // Stratum 导入
    onImportZip: suspend (Uri, String?) -> Result<Int>,  // Bastion ZIP导入
    onImportKdbx: suspend (Uri, String, Uri?) -> Result<Int> = { _, _, _ -> Result.failure(Exception("Not implemented")) },  // KDBX导入
    onImportKeePassCsv: suspend (Uri) -> Result<Int> = onImport,  // KeePass CSV导入
    onImportBitwardenCsv: suspend (Uri) -> Result<Int> = onImport,  // Bitwarden CSV导入
    onImportBitwardenJson: suspend (Uri) -> Result<Int> = onImport,  // Bitwarden JSON导入（明文）
    onImportEncryptedBitwardenJson: suspend (Uri, String) -> Result<Int> = { _, _ -> Result.failure(Exception("Not implemented")) },  // Bitwarden 加密JSON导入
    onIsEncryptedBitwarden: suspend (Uri) -> Boolean = { false },  // 判断 Bitwarden JSON 是否加密
    onImportProtonPassCsv: suspend (Uri) -> Result<Int> = onImport,  // Proton Pass CSV导入
    onImportChromeCsv: suspend (Uri) -> Result<Int> = onImport,  // Chrome CSV导入
    onImportPasswordKeyboardCsv: suspend (
        Uri,
        DataExportImportManager.PasswordKeyboardTagHandling
    ) -> Result<Int> = { _, _ -> Result.failure(Exception("Not implemented")) } // 密码键盘软件 CSV导入
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var importType by remember { mutableStateOf("bastion_zip") } // 默认选择 ZIP 备份
    var csvImportType by remember { mutableStateOf("normal") } // CSV子类型
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showZipRestoreConfirmDialog by remember { mutableStateOf(false) }
    var showPasswordKeyboardTagDialog by remember { mutableStateOf(false) }
    var aegisPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    
    // KDBX 导入密码
    var showKdbxPasswordDialog by remember { mutableStateOf(false) }
    var kdbxPassword by remember { mutableStateOf("") }
    var kdbxPasswordVisible by remember { mutableStateOf(false) }
    var kdbxKeyFileUri by remember { mutableStateOf<Uri?>(null) }
    var kdbxKeyFileName by remember { mutableStateOf("") }
    var keepassImportError by remember { mutableStateOf<KeePassOperationException?>(null) }
    var showKeepassImportErrorDialog by remember { mutableStateOf(false) }
    val kdbxKeyFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { selectedUri: Uri? ->
        selectedUri?.let {
            kdbxKeyFileUri = it
            kdbxKeyFileName = it.lastPathSegment?.substringAfterLast("/") ?: "keyfile"
        }
    }

    val pickerSecurityManager = remember { com.bastion.app.security.SecurityManager(context) }
    val passwordDatabase = remember(context) { PasswordDatabase.getDatabase(context) }
    val passwordEntriesForPicker by passwordDatabase.passwordEntryDao()
        .getAllPasswordEntries()
        .collectAsState(initial = emptyList())
    
    val importTypes = importTypeOptions()
    val csvImportTypes = csvImportTypeOptions()

    val effectiveImportType = if (importType == "csv_group") csvImportType else importType
    
    val currentTypeInfo = if (importType == "csv_group") {
        csvImportTypes.find { it.key == csvImportType } ?: csvImportTypes[0]
    } else {
        importTypes.find { it.key == importType } ?: importTypes[0]
    }
    
    // 设置文件操作回调
    LaunchedEffect(Unit) {
        FileOperationHelper.setCallback(object : FileOperationHelper.FileOperationCallback {
            override fun onExportFileSelected(uri: Uri?) {
                // 导入界面不需要处理导出文件选择
            }
            
            override fun onImportFileSelected(uri: Uri?) {
                uri?.let { safeUri ->
                    scope.launch {
                        try {
                            // 获取持久化权限
                            try {
                                context.contentResolver.takePersistableUriPermission(
                                    safeUri,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            } catch (e: SecurityException) {
                                // 某些URI可能不支持持久化权限,忽略这个错误
                                android.util.Log.w("ImportDataScreen", "无法获取持久化权限", e)
                            }
                            
                            selectedFileUri = safeUri
                            // 尝试获取文件名
                            selectedFileName = try {
                                safeUri.lastPathSegment?.substringAfterLast('/')
                                    ?: context.getString(R.string.import_data_file_selected)
                            } catch (e: Exception) {
                                context.getString(R.string.import_data_file_selected)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ImportDataScreen", "文件选择异常", e)
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.import_data_file_select_failed,
                                    e.message ?: context.getString(R.string.import_data_unknown_error)
                                )
                            )
                        }
                    }
                }
            }
        })
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_data_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 导入按钮
                    Button(
                        onClick = {
                            selectedFileUri?.let { uri ->
                                scope.launch {
                                    isImporting = true
                                    try {
                                        when (effectiveImportType) {
                                                "bastion_zip" -> {
                                                    isImporting = false
                                                    showZipRestoreConfirmDialog = true
                                                    return@launch
                                                }
                                                "aegis" -> {
                                                    // Aegis导入类型，先检查是否为加密文件
                                                    val isEncryptedResult = DataExportImportManager(context).isEncryptedAegisFile(uri)
                                                    val isEncrypted = isEncryptedResult.getOrDefault(false)
                                                    if (isEncrypted) {
                                                        // 是加密文件，显示密码输入对话框
                                                        isImporting = false
                                                        showPasswordDialog = true
                                                        passwordError = null
                                                        aegisPassword = ""
                                                        return@launch
                                                    } else {
                                                        // 不是加密文件，直接导入
                                                        val result = onImportAegis(uri)
                                                        handleImportResult(result, context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                    }
                                                }
                                                "stratum" -> {
                                                    val result = onImportStratum(uri, null)
                                                    result.onSuccess { count ->
                                                        handleImportResult(Result.success(count), context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                    }.onFailure { error ->
                                                        val errorMsg = error.message ?: ""
                                                        if (isPasswordRequiredError(errorMsg)) {
                                                            isImporting = false
                                                            showPasswordDialog = true
                                                            passwordError = null
                                                            aegisPassword = ""
                                                        } else {
                                                            handleImportResult(Result.failure(error), context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                        }
                                                    }
                                                }
                                                "bitwarden_json" -> {
                                                    // Bitwarden JSON 导入：先判断是否加密，加密则弹密码框
                                                    val isEncrypted = onIsEncryptedBitwarden(uri)
                                                    if (isEncrypted) {
                                                        isImporting = false
                                                        showPasswordDialog = true
                                                        passwordError = null
                                                        aegisPassword = ""
                                                        return@launch
                                                    } else {
                                                        val result = onImportBitwardenJson(uri)
                                                        handleImportResult(result, context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                    }
                                                }
                                                "kdbx" -> {
                                                    // KDBX 导入需要密码
                                                    if (isLikelyLegacyKdbFile(selectedFileName, uri)) {
                                                        snackbarHostState.showSnackbar(
                                                            context.getString(R.string.import_data_keepass_legacy_kdb_unsupported)
                                                        )
                                                        return@launch
                                                    }
                                                    isImporting = false
                                                    showKdbxPasswordDialog = true
                                                    kdbxPassword = ""
                                                    kdbxKeyFileUri = null
                                                    kdbxKeyFileName = ""
                                                }
                                                "keepass_csv" -> {
                                                    val result = onImportKeePassCsv(uri)
                                                    handleImportResult(result, context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                }
                                                "bitwarden_csv" -> {
                                                    val result = onImportBitwardenCsv(uri)
                                                    handleImportResult(result, context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                }
                                                "proton_pass_csv" -> {
                                                    val result = onImportProtonPassCsv(uri)
                                                    handleImportResult(result, context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                }
                                                "chrome_csv" -> {
                                                    val result = onImportChromeCsv(uri)
                                                    handleImportResult(result, context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                }
                                                "password_keyboard_csv" -> {
                                                    val shouldPrompt = shouldPromptPasswordKeyboardTagDialog(context, uri)
                                                    if (shouldPrompt) {
                                                        isImporting = false
                                                        showPasswordKeyboardTagDialog = true
                                                        return@launch
                                                    }

                                                    val result = onImportPasswordKeyboardCsv(
                                                        uri,
                                                        DataExportImportManager.PasswordKeyboardTagHandling.CONVERT_TO_CUSTOM_FIELD
                                                    )
                                                    handleImportResult(
                                                        result,
                                                        context,
                                                        snackbarHostState,
                                                        effectiveImportType,
                                                        onNavigateBack
                                                    )
                                                }
                                                else -> {
                                                    // 普通CSV导入
                                                    val result = onImport(uri)
                                                    handleImportResult(result, context, snackbarHostState, effectiveImportType, onNavigateBack)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("ImportDataScreen", "导入异常", e)
                                            snackbarHostState.showSnackbar(
                                                context.getString(
                                                    R.string.import_data_error_exception,
                                                    e.message ?: context.getString(R.string.import_data_unknown_error)
                                                )
                                            )
                                        } finally {
                                            isImporting = false
                                        }
                                    }
                                }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = selectedFileUri != null && !isImporting,
                        shape = MaterialTheme.shapes.large
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.importing), style = MaterialTheme.typography.titleMedium)
                        } else {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.start_import),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    
                    // 说明文字
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.import_data_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 选择导入类型标题
            Text(
                stringResource(R.string.import_data_select_type),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            
            // 导入类型卡片列表 - 垂直排列，适配各种屏幕尺寸
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                importTypes.forEach { typeInfo ->
                    ImportTypeCard(
                        info = typeInfo,
                        selected = importType == typeInfo.key,
                        onClick = { 
                            importType = typeInfo.key
                            // 切换类型时清除已选文件
                            selectedFileUri = null
                            selectedFileName = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (importType == "csv_group") {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.import_type_csv_source_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        csvImportTypes.forEach { csvTypeInfo ->
                            ImportTypeCard(
                                info = csvTypeInfo,
                                selected = csvImportType == csvTypeInfo.key,
                                onClick = {
                                    csvImportType = csvTypeInfo.key
                                    selectedFileUri = null
                                    selectedFileName = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            // 文件选择区域
            Text(
                stringResource(R.string.import_data_select_file),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            // 选择文件卡片
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    activity?.let { act ->
                        // 根据导入类型选择不同的文件过滤器
                        when (effectiveImportType) {
                            "bastion_zip" -> FileOperationHelper.importFromZip(act)
                            "kdbx" -> FileOperationHelper.importFromKdbx(act)
                            "keepass_csv" -> FileOperationHelper.importFromCsv(act)
                            "bitwarden_csv" -> FileOperationHelper.importFromCsv(act)
                            "proton_pass_csv" -> FileOperationHelper.importFromCsv(act)
                            "chrome_csv" -> FileOperationHelper.importFromCsv(act)
                            "password_keyboard_csv" -> FileOperationHelper.importFromCsv(act)
                            "aegis" -> FileOperationHelper.importFromJson(act)
                            "stratum" -> FileOperationHelper.importFromStratum(act)
                            "bitwarden_json" -> FileOperationHelper.importFromJson(act)
                            else -> FileOperationHelper.importFromCsv(act)
                        }
                    } ?: run {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.error_launch_export,
                                    context.getString(R.string.import_data_operation_unavailable)
                                )
                            )
                        }
                    }
                },
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (selectedFileUri != null)
                        MaterialTheme.colorScheme.secondaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 文件图标
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (selectedFileUri != null)
                            MaterialTheme.colorScheme.secondary
                        else
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (selectedFileUri != null) Icons.Default.InsertDriveFile else Icons.Default.FileOpen,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = if (selectedFileUri != null)
                                    MaterialTheme.colorScheme.onSecondary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (selectedFileUri != null) {
                                stringResource(R.string.import_data_file_selected)
                            } else {
                                stringResource(R.string.import_data_tap_to_select_file)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (selectedFileUri != null)
                                MaterialTheme.colorScheme.onSecondaryContainer
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            selectedFileName ?: currentTypeInfo.fileHint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selectedFileUri != null)
                                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = if (selectedFileUri != null)
                            MaterialTheme.colorScheme.onSecondaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // 底部留白，避免被底部栏遮挡
            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    if (showPasswordKeyboardTagDialog) {
        PasswordKeyboardTagDialog(
            onDismiss = { showPasswordKeyboardTagDialog = false },
            onConvert = {
                val uri = selectedFileUri
                if (uri == null) {
                    showPasswordKeyboardTagDialog = false
                } else {
                    showPasswordKeyboardTagDialog = false
                    scope.launch {
                        isImporting = true
                        try {
                            val result = onImportPasswordKeyboardCsv(
                                uri,
                                DataExportImportManager.PasswordKeyboardTagHandling.CONVERT_TO_CUSTOM_FIELD
                            )
                            handleImportResult(
                                result,
                                context,
                                snackbarHostState,
                                effectiveImportType,
                                onNavigateBack
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("ImportDataScreen", "密码键盘 CSV 导入异常", e)
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.import_data_error_exception,
                                    e.message ?: context.getString(R.string.import_data_unknown_error)
                                )
                            )
                        } finally {
                            isImporting = false
                        }
                    }
                }
            },
            onDrop = {
                val uri = selectedFileUri
                if (uri == null) {
                    showPasswordKeyboardTagDialog = false
                } else {
                    showPasswordKeyboardTagDialog = false
                    scope.launch {
                        isImporting = true
                        try {
                            val result = onImportPasswordKeyboardCsv(
                                uri,
                                DataExportImportManager.PasswordKeyboardTagHandling.DROP
                            )
                            handleImportResult(
                                result,
                                context,
                                snackbarHostState,
                                effectiveImportType,
                                onNavigateBack
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("ImportDataScreen", "密码键盘 CSV 导入异常", e)
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.import_data_error_exception,
                                    e.message ?: context.getString(R.string.import_data_unknown_error)
                                )
                            )
                        } finally {
                            isImporting = false
                        }
                    }
                }
            }
        )
    }

    if (showZipRestoreConfirmDialog) {
        ZipRestoreConfirmDialog(
            selectedFileName = selectedFileName,
            isImporting = isImporting,
            onDismiss = { showZipRestoreConfirmDialog = false },
            onConfirm = {
                val uri = selectedFileUri
                if (uri != null) {
                    showZipRestoreConfirmDialog = false
                    scope.launch {
                        isImporting = true
                        try {
                            val result = onImportZip(uri, null)
                            result.onSuccess { count ->
                                handleImportResult(
                                    Result.success(count),
                                    context,
                                    snackbarHostState,
                                    effectiveImportType,
                                    onNavigateBack
                                )
                            }.onFailure { error ->
                                if (error is com.bastion.app.utils.WebDavHelper.PasswordRequiredException) {
                                    isImporting = false
                                    showPasswordDialog = true
                                    passwordError = null
                                    aegisPassword = ""
                                } else {
                                    handleImportResult(
                                        Result.failure(error),
                                        context,
                                        snackbarHostState,
                                        effectiveImportType,
                                        onNavigateBack
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ImportDataScreen", "ZIP导入异常", e)
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.import_data_error_exception,
                                    e.message ?: context.getString(R.string.import_data_unknown_error)
                                )
                            )
                        } finally {
                            isImporting = false
                        }
                    }
                }
            }
        )
    }

    // 密码输入对话框
    if (showPasswordDialog) {
        EncryptedImportPasswordDialog(
            importType = importType,
            password = aegisPassword,
            passwordError = passwordError,
            isImporting = isImporting,
            onPasswordChange = {
                aegisPassword = it
                passwordError = null
            },
            onDismiss = {
                showPasswordDialog = false
                passwordError = null
            },
            onConfirm = {
                if (aegisPassword.isBlank()) {
                    passwordError = context.getString(R.string.import_data_password_cannot_be_empty)
                } else {
                    scope.launch {
                        isImporting = true
                        showPasswordDialog = false
                        try {
                            selectedFileUri?.let { uri ->
                                // 使用加密导入回调
                                val result = when (importType) {
                                    "bastion_zip" -> onImportZip(uri, aegisPassword)
                                    "stratum" -> onImportStratum(uri, aegisPassword)
                                    "bitwarden_json" -> onImportEncryptedBitwardenJson(uri, aegisPassword)
                                    else -> onImportEncryptedAegis(uri, aegisPassword)
                                }

                                result.onSuccess { count ->
                                    val message = when (importType) {
                                        "bastion_zip" -> context.getString(R.string.import_data_zip_restore_success_count, count)
                                        "stratum" -> context.getString(R.string.import_data_stratum_import_success_count, count)
                                        "bitwarden_json" -> context.getString(R.string.import_data_bitwarden_json_import_success_count, count)
                                        else -> context.getString(R.string.import_data_aegis_import_success_count, count)
                                    }
                                    snackbarHostState.showSnackbar(message)
                                    onNavigateBack()
                                }.onFailure { error ->
                                    val errorMsg = error.message ?: context.getString(R.string.import_data_unknown_error)
                                    if (isPasswordDecryptError(errorMsg)) {
                                        passwordError = context.getString(R.string.import_data_password_incorrect_retry)
                                        showPasswordDialog = true
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.import_data_failed_with_reason, errorMsg)
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ImportDataScreen", "加密导入异常", e)
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.import_data_failed_with_reason,
                                    e.message ?: context.getString(R.string.import_data_unknown_error)
                                )
                            )
                        } finally {
                            isImporting = false
                        }
                    }
                }
            }
        )
    }
    
    // KDBX 密码输入对话框
    if (showKdbxPasswordDialog) {
        KdbxImportPasswordDialog(
            password = kdbxPassword,
            passwordVisible = kdbxPasswordVisible,
            keyFileName = kdbxKeyFileName,
            hasKeyFile = kdbxKeyFileUri != null,
            isImporting = isImporting,
            onPasswordChange = { kdbxPassword = it },
            onTogglePasswordVisible = { kdbxPasswordVisible = !kdbxPasswordVisible },
            onPickKeyFile = { kdbxKeyFilePickerLauncher.launch(arrayOf("*/*")) },
            onDismiss = {
                showKdbxPasswordDialog = false
                kdbxPassword = ""
                kdbxKeyFileUri = null
                kdbxKeyFileName = ""
            },
            onConfirm = {
                showKdbxPasswordDialog = false
                selectedFileUri?.let { uri ->
                    scope.launch {
                        isImporting = true
                        try {
                            val result = onImportKdbx(uri, kdbxPassword, kdbxKeyFileUri)
                            result.onSuccess { count ->
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.import_data_kdbx_import_success_count, count)
                                )
                                onNavigateBack()
                            }.onFailure { error ->
                                if (error is KeePassOperationException) {
                                    keepassImportError = error
                                    showKeepassImportErrorDialog = true
                                } else {
                                    snackbarHostState.showSnackbar(
                                        formatImportErrorMessage(
                                            error,
                                            context.getString(R.string.import_data_error)
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            if (e is KeePassOperationException) {
                                keepassImportError = e
                                showKeepassImportErrorDialog = true
                            } else {
                                snackbarHostState.showSnackbar(
                                    formatImportErrorMessage(
                                        e,
                                        context.getString(
                                            R.string.import_data_error_exception,
                                            e.message ?: context.getString(R.string.import_data_unknown_error)
                                        )
                                    )
                                )
                            }
                        } finally {
                            isImporting = false
                            kdbxPassword = ""
                            kdbxKeyFileUri = null
                            kdbxKeyFileName = ""
                        }
                    }
                }
            }
        )
    }

    if (showKeepassImportErrorDialog && keepassImportError != null) {
        val error = keepassImportError!!
        KeepassImportErrorDialog(
            error = error,
            onDismiss = {
                showKeepassImportErrorDialog = false
                keepassImportError = null
            }
        )
    }
}

// 处理导入结果的辅助函数
private suspend fun handleImportResult(
    result: Result<Int>,
    context: android.content.Context,
    snackbarHostState: SnackbarHostState,
    importType: String,
    onNavigateBack: () -> Unit
) {
    result.onSuccess { count ->
        val shouldStayOnImportScreen = importType == "normal" || importType.endsWith("_csv")
        val message = when (importType) {
            "aegis" -> context.getString(R.string.import_data_aegis_import_success_count, count)
            "stratum" -> context.getString(R.string.import_data_stratum_import_success_count, count)
            else -> context.getString(R.string.import_data_success_normal, count)
        }
        snackbarHostState.showSnackbar(message)
        if (!shouldStayOnImportScreen) {
            onNavigateBack()
        }
    }.onFailure { error ->
        snackbarHostState.showSnackbar(
            formatImportErrorMessage(error, context.getString(R.string.import_data_error))
        )
    }
}
