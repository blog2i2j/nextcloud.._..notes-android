/*
 * Nextcloud Notes - Android Client
 *
 * SPDX-FileCopyrightText: 2015-2025 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package it.niedermann.owncloud.notes.share.adapter

import android.app.SearchManager
import android.content.Context
import android.database.Cursor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.database.getIntOrNull
import androidx.core.database.getStringOrNull
import androidx.cursoradapter.widget.CursorAdapter
import it.niedermann.owncloud.notes.R
import it.niedermann.owncloud.notes.persistence.entity.Account
import it.niedermann.owncloud.notes.share.helper.AvatarLoader

class SuggestionAdapter(context: Context, cursor: Cursor?, private val account: Account) : CursorAdapter(context, cursor, false) {
    override fun newView(context: Context, cursor: Cursor, parent: ViewGroup): View {
        val inflater = LayoutInflater.from(context)
        return inflater.inflate(R.layout.item_suggestion_adapter, parent, false)
    }

    override fun bindView(view: View, context: Context, cursor: Cursor) {
        val suggestion =
            cursor.getString(cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_TEXT_1))
        view.findViewById<TextView>(R.id.suggestion_text).text = suggestion


        val icon = view.findViewById<ImageView>(R.id.suggestion_icon)
        val iconColumn = cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_ICON_1)

        if (iconColumn != -1) {
            try {
                val iconId = cursor.getIntOrNull(iconColumn)
                if (iconId != null) {
                    icon.setImageDrawable(ContextCompat.getDrawable(context, iconId))
                }
            } catch (_: Exception) {
                try {
                    val username = cursor.getStringOrNull(iconColumn)
                    if (username != null) {
                        AvatarLoader.load(context, icon, account, username)
                    }
                } catch (_: Exception) {
                    icon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_account_circle_grey_24dp))
                }
            }
        }
    }
}
