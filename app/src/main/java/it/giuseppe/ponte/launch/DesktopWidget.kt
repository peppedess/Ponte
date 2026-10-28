package it.giuseppe.ponte.launch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import it.giuseppe.ponte.Prefs
import it.giuseppe.ponte.R
import it.giuseppe.ponte.ui.DexActivity

/** Widget per la schermata Home: un pulsante "Avvia DeX / desktop". */
class DesktopWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val prefs = Prefs.get(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, DexActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_desktop).apply {
                setTextViewText(R.id.widget_title, prefs.startLabel)
                setTextViewText(R.id.widget_subtitle, prefs.current?.name?.ifEmpty { null } ?: "Ponte")
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            manager.updateAppWidget(id, views)
        }
    }
}
