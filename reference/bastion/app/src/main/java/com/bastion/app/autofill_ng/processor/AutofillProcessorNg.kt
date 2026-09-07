package com.bastion.app.autofill_ng.processor

import android.content.Context
import android.service.autofill.FillResponse
import android.view.inputmethod.InlineSuggestionsRequest
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.ParsedItem
import com.bastion.app.autofill_ng.builder.FillResponseBuilderNg
import com.bastion.app.autofill_ng.builder.FilledDataBuilderNg
import com.bastion.app.autofill_ng.model.AutofillRequest
import com.bastion.app.autofill_ng.parser.AutofillParserNg
import com.bastion.app.data.PasswordEntry
import com.bastion.app.security.SecurityManager

class AutofillProcessorNg(
    private val context: Context,
    private val parser: AutofillParserNg = AutofillParserNg(),
    private val filledDataBuilder: FilledDataBuilderNg =
        FilledDataBuilderNg(
            context = context.applicationContext,
            securityManager = SecurityManager(context.applicationContext)
        ),
    private val fillResponseBuilder: FillResponseBuilderNg = FillResponseBuilderNg(context),
) {

    fun process(
        packageName: String,
        uri: String?,
        fillableTargets: List<ParsedItem>,
        inlineRequest: InlineSuggestionsRequest?,
        isCompatMode: Boolean,
        passwords: List<PasswordEntry>,
        fieldSignatureKey: String? = null,
        preferDirectAutoFill: Boolean = false,
        passwordSuggestionEnabled: Boolean = true,
        requireAuthentication: Boolean = true,
        forceDatasetAuthForWeb: Boolean = false,
    ): FillResponse? {
        val request = parser.parse(
            packageName = packageName,
            uri = uri,
            fillableTargets = fillableTargets,
            inlineRequest = inlineRequest,
            fieldSignatureKey = fieldSignatureKey,
            isCompatMode = isCompatMode,
            preferDirectAutoFill = preferDirectAutoFill,
        )

        if (request !is AutofillRequest.Fillable) return null

        val filledData = filledDataBuilder.build(
            request = request,
            passwords = passwords,
            requireAuthentication = requireAuthentication,
            forceDatasetAuthForWeb = forceDatasetAuthForWeb,
        )
        return fillResponseBuilder.build(
            request = request,
            filledData = filledData,
            passwordSuggestionEnabled = passwordSuggestionEnabled,
            requireAuthentication = requireAuthentication,
            matchedPasswords = passwords,
        )
    }
}

