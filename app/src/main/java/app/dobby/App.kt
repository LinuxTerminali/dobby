package app.dobby

import android.app.Application
import app.dobby.connectors.ConnectorRegistry
import app.dobby.mcp.McpRouter
import app.dobby.model.ModelDownloader
import app.dobby.model.ModelRegistry
import app.dobby.memory.MemoryStore

class App : Application() {
    lateinit var connectors: ConnectorRegistry; private set
    lateinit var router: McpRouter; private set
    lateinit var models: ModelRegistry; private set
    lateinit var downloader: ModelDownloader; private set
    lateinit var memory: MemoryStore; private set

    override fun onCreate() {
        super.onCreate()
        memory = MemoryStore(this)
        connectors = ConnectorRegistry(this, memory)
        router = McpRouter { connectors.active(this) }
        models = ModelRegistry(this)
        downloader = ModelDownloader(this, models)
        app.dobby.reminders.ReminderScheduler(this).rescheduleAll()
        app.dobby.location.GeofenceManager(this).registerAll()
    }
}
