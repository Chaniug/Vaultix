/*
 * Vaultix — app:autofill · match
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 等价域名判定，对齐 Bitwarden equivalentDomains（GPL-3.0，Copyright 2025 JoyinJoester）。
 * 同组域名视为同一组织可互填（如 google.com ↔ youtube.com ↔ gmail.com）。
 * v1 内置常见等价域组；组织级等价域可后续按 Bitwarden 口径扩展。
 */
package io.vaultix.vaultix.autofill.match

/** 等价域名判定（同一组织下的不同域名视为可互填）。 */
object EquivalentDomains {

    // 内置等价域组（可填充彼此的域名集合），对齐 Bitwarden 常见等价域。
    private val EQUIVALENCE_GROUPS: Set<Set<String>> = setOf(
        setOf(
            "google.com", "youtube.com", "android.com", "gmail.com", "google.co.uk",
            "google.com.au", "google.de", "google.fr", "google.co.jp", "googleapis.com",
        ),
        setOf(
            "microsoft.com", "live.com", "outlook.com", "office.com", "hotmail.com",
            "xbox.com", "skype.com", "msn.com",
        ),
        setOf(
            "amazon.com", "amazon.co.uk", "amazon.de", "amazon.co.jp", "amazon.in",
            "amazon.fr", "amazon.ca", "amazon.es", "amazon.it",
        ),
        setOf("facebook.com", "instagram.com", "whatsapp.com", "messenger.com", "meta.com"),
        setOf("apple.com", "icloud.com", "me.com", "mac.com"),
        setOf("yahoo.com", "yahoo.co.jp", "flickr.com", "tumblr.com"),
        setOf("twitter.com", "x.com"),
        setOf("linkedin.com"),
        setOf("reddit.com"),
        setOf("github.com"),
        setOf("twitch.tv"),
        setOf("steampowered.com", "steamcommunity.com"),
        setOf("salesforce.com"),
        setOf("dropbox.com"),
        setOf("slack.com"),
        setOf("zoom.us"),
        setOf("netflix.com"),
        setOf("spotify.com"),
        setOf("wordpress.com"),
        setOf("paypal.com"),
    )

    /** 两个主机是否等价（完全相同或同属一个等价域组）。 */
    fun isEquivalent(a: String, b: String): Boolean {
        if (a == b) return true
        return EQUIVALENCE_GROUPS.any { group -> a in group && b in group }
    }
}
