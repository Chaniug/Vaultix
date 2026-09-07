package com.bastion.app.data.model

import com.bastion.app.data.PasswordEntry

const val LOGIN_TYPE_BARCODE: String = "BARCODE"

fun PasswordEntry.isBarcodeEntry(): Boolean =
    loginType.equals(LOGIN_TYPE_BARCODE, ignoreCase = true)

