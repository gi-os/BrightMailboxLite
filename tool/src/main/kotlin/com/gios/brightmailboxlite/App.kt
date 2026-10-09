package com.gios.brightmailboxlite

import com.gios.brightmailboxlite.data.Mailbox
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

const val SYNC_JOB = "mail-sync"

/**
 * The background check, every 15 minutes or so (the SDK's minimum; the system may wait
 * longer). It fetches and sorts; it cannot notify, because the SDK gives tools no
 * notification API, so new Letters are simply there the next time Mailbox is opened.
 */
@LightJob("mail-sync")
val mailSync: LightJobHandler = { ctx, _ ->
    Mailbox.load(ctx)
    if (Mailbox.accounts.value.isEmpty()) {
        LightJobResult.Success(emptyMap(), null, null)
    } else if (Mailbox.sync(ctx)) {
        LightJobResult.Success(emptyMap(), null, "checked")
    } else {
        LightJobResult.Retry
    }
}

/** No push and no server; the object exists so the SDK's processor finds an entry point. */
@EntryPoint
object MailboxEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) = Unit
}
