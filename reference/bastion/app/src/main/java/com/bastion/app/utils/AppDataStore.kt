package com.bastion.app.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * 全模块唯一的 "settings" DataStore 委托。
 *
 * DataStore 要求同一个文件在进程内只能有一个实例，重复声明
 * preferencesDataStore(name = "settings") 会在运行时抛出
 * IllegalStateException: There are multiple DataStore active for the same file。
 *
 * 因此所有需要访问 settings 存储的组件都必须复用这里的扩展属性，禁止再各自声明。
 *
 * 注意：这个存储由主进程持有。DataStore 不支持多进程，运行在 :accessibility
 * 独立进程中的组件（如 BastionAccessibilityService）不得读写这里的数据，
 * 它们的配置应当放在各自独立的存储中（例如 AutofillPreferences 的
 * "autofill_settings"）。
 */
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
