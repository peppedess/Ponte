package it.giuseppe.ponte.launch

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import it.giuseppe.ponte.Prefs
import it.giuseppe.ponte.session.SessionManager
import it.giuseppe.ponte.ui.DexActivity
import it.giuseppe.ponte.ui.MainActivity

/** Riquadro nelle Impostazioni rapide: avvia il desktop (o ci torna) con un tocco. */
class DesktopTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val prefs = Prefs.get(this)
        tile.state = when {
            !prefs.isPaired -> Tile.STATE_UNAVAILABLE
            SessionManager.isActive -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = if (prefs.isGenericDesktop) "Desktop" else "DeX"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = prefs.current?.name?.ifEmpty { null } ?: "Ponte"
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val target = if (Prefs.get(this).isPaired) DexActivity::class.java else MainActivity::class.java
        val intent = Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
