package com.bastion.app.autofill_ng

import com.bastion.app.data.AppSettings

internal data object AutofillSaveInitialTarget

internal fun resolveAutofillSaveInitialTarget(
    settings: AppSettings
): AutofillSaveInitialTarget = AutofillSaveInitialTarget
