// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.valhalla.thor.R

enum class FreezeProfileIcon(
    val key: String,
    @StringRes val label: Int,
    @DrawableRes val drawable: Int,
    @ColorRes val background: Int,
) {
    FREEZER("freezer", R.string.freezer, R.drawable.profile_icon_freezer, R.color.profile_icon_freezer_background),
    GAMES("games", R.string.profile_icon_games, R.drawable.profile_icon_games, R.color.profile_icon_games_background),
    OFFICE("office", R.string.profile_icon_office, R.drawable.profile_icon_office, R.color.profile_icon_office_background),
    ENTERTAINMENT("entertainment", R.string.profile_icon_entertainment, R.drawable.profile_icon_entertainment, R.color.profile_icon_entertainment_background),
    KIDS("kids", R.string.profile_icon_kids, R.drawable.profile_icon_kids, R.color.profile_icon_kids_background),
    SOCIAL("social", R.string.profile_icon_social, R.drawable.profile_icon_social, R.color.profile_icon_social_background),
    COMMUNICATION("communication", R.string.profile_icon_communication, R.drawable.profile_icon_communication, R.color.profile_icon_communication_background),
    MUSIC("music", R.string.profile_icon_music, R.drawable.profile_icon_music, R.color.profile_icon_music_background);

    companion object {
        fun fromKey(key: String?): FreezeProfileIcon? = entries.find { it.key == key }
    }
}
