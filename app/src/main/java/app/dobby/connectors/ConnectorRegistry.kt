package app.dobby.connectors

import android.content.Context
import app.dobby.memory.MemoryStore

/** The catalog. Add new connectors here, nothing else changes. */
class ConnectorRegistry(context: Context, memory: MemoryStore) {
    val state = ConnectorState(context)
    private val auth = GoogleAuth(context)

    val all: List<Connector> = listOf(
        CallLogConnector(context),
        SmsConnector(context),
        GmailConnector(context, auth),
        DocsConnector(context, auth),
        SheetsConnector(context, auth),
        BrowserConnector(context),
        DeviceConnector(context),
        RemindersConnector(context),
        PlacesConnector(context),
        MemoryConnector(memory),
    )

    fun googleAuth() = auth

    /** Connectors that are switched on and ready: these are what the model sees. */
    fun active(context: Context): List<Connector> =
        all.filter { state.isEnabled(it.id) && it.isReady(context) }
}
