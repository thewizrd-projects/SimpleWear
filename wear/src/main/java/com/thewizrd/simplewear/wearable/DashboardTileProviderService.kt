package com.thewizrd.simplewear.wearable

import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log
import android.widget.RemoteViews
import com.google.android.clockwork.tiles.TileData
import com.google.android.clockwork.tiles.TileProviderService
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.*
import com.google.android.gms.wearable.CapabilityClient.OnCapabilityChangedListener
import com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener
import com.thewizrd.shared_resources.actions.*
import com.thewizrd.shared_resources.helpers.WearConnectionStatus
import com.thewizrd.shared_resources.helpers.WearableHelper
import com.thewizrd.shared_resources.utils.JSONParser.deserializer
import com.thewizrd.shared_resources.utils.JSONParser.serializer
import com.thewizrd.shared_resources.utils.Logger.writeLine
import com.thewizrd.shared_resources.utils.bytesToString
import com.thewizrd.shared_resources.utils.stringToBytes
import com.thewizrd.simplewear.LaunchActivity
import com.thewizrd.simplewear.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.*
import java.util.concurrent.ExecutionException

class DashboardTileProviderService : TileProviderService(), OnMessageReceivedListener, OnCapabilityChangedListener {
    companion object {
        private const val TAG = "DashTileProviderService"

        /*
         * There should only ever be one phone in a node set (much less w/ the correct capability), so
         * I am just grabbing the first one (which should be the only one).
        */
        protected fun pickBestNodeId(nodes: Collection<Node>): Node? {
            var bestNode: Node? = null

            // Find a nearby node/phone or pick one arbitrarily. Realistically, there is only one phone.
            for (node in nodes) {
                if (node.isNearby) {
                    return node
                }
                bestNode = node
            }
            return bestNode
        }
    }

    private var mInFocus = false
    private var id = -1

    override fun onDestroy() {
        Timber.d("destroying service...")
        super.onDestroy()
    }

    override fun onTileUpdate(tileId: Int) {
        Timber.d("$TAG: onTileUpdate called with: tileId = $tileId")

        if (!isIdForDummyData(tileId)) {
            id = tileId
            sendRemoteViews()
        }
    }

    override fun onTileFocus(tileId: Int) {
        super.onTileFocus(tileId)

        Timber.d("$TAG: onTileFocus called with: tileId = $tileId")
        if (!isIdForDummyData(tileId)) {
            id = tileId
            mInFocus = true
            sendRemoteViews()

            Wearable.getCapabilityClient(applicationContext).addListener(this, WearableHelper.CAPABILITY_PHONE_APP)
            Wearable.getMessageClient(applicationContext).addListener(this)

            GlobalScope.launch(Dispatchers.Unconfined) {
                checkConnectionStatus()
                requestUpdate()
            }
        }
    }

    override fun onTileBlur(tileId: Int) {
        super.onTileBlur(tileId)

        Timber.d("$TAG: onTileBlur called with: tileId = $tileId")
        if (!isIdForDummyData(tileId)) {
            mInFocus = false

            Wearable.getCapabilityClient(applicationContext).removeListener(this, WearableHelper.CAPABILITY_PHONE_APP)
            Wearable.getMessageClient(applicationContext).removeListener(this)
        }
    }

    private fun sendRemoteViews() {
        Timber.d("$TAG: sendRemoteViews")
        GlobalScope.launch(Dispatchers.Unconfined) {
            val updateViews = buildUpdate()

            val tileData = TileData.Builder()
                    .setRemoteViews(updateViews)
                    .build()

            sendData(id, tileData)
        }
    }

    private fun buildUpdate(): RemoteViews {
        val views: RemoteViews

        if (mConnectionStatus !== WearConnectionStatus.CONNECTED) {
            views = RemoteViews(applicationContext.packageName, R.layout.tile_disconnected)
            views.setOnClickPendingIntent(R.id.tile, getTapIntent(applicationContext))
            return views
        }

        views = RemoteViews(applicationContext!!.packageName, R.layout.tile_layout_dashboard)
        views.setOnClickPendingIntent(R.id.tile, getTapIntent(applicationContext))

        if (battStatus != null) {
            val battValue = String.format(Locale.ROOT, "%d%%, %s", battStatus!!.batteryLevel,
                    if (battStatus!!.isCharging) applicationContext.getString(R.string.batt_state_charging) else applicationContext.getString(R.string.batt_state_discharging))
            views.setTextViewText(R.id.batt_stat_text, battValue)
        }

        if (wifiAction != null) {
            views.setImageViewResource(R.id.wifi_toggle, if (wifiAction!!.isEnabled) R.drawable.ic_network_wifi_white_24dp else R.drawable.ic_signal_wifi_off_white_24dp)
            views.setInt(R.id.wifi_toggle, "setBackgroundResource", if (wifiAction!!.isEnabled) R.drawable.round_button_enabled else R.drawable.round_button_disabled)
            views.setOnClickPendingIntent(R.id.wifi_toggle, getActionClickIntent(applicationContext, Actions.WIFI))
        }

        if (btAction != null) {
            views.setImageViewResource(R.id.bt_toggle, if (btAction!!.isEnabled) R.drawable.ic_bluetooth_white_24dp else R.drawable.ic_bluetooth_disabled_white_24dp)
            views.setInt(R.id.bt_toggle, "setBackgroundResource", if (btAction!!.isEnabled) R.drawable.round_button_enabled else R.drawable.round_button_disabled)
            views.setOnClickPendingIntent(R.id.bt_toggle, getActionClickIntent(applicationContext, Actions.BLUETOOTH))
        }

        views.setOnClickPendingIntent(R.id.lock_toggle, getActionClickIntent(applicationContext, Actions.LOCKSCREEN))

        if (dndAction != null) {
            val dndChoice = if (dndAction is ToggleAction) {
                if ((dndAction as ToggleAction).isEnabled) DNDChoice.PRIORITY else DNDChoice.OFF
            } else {
                DNDChoice.valueOf((dndAction as MultiChoiceAction).choice)
            }
            var mDrawableID = R.drawable.ic_do_not_disturb_off_white_24dp

            when (dndChoice) {
                DNDChoice.OFF -> mDrawableID = R.drawable.ic_do_not_disturb_off_white_24dp
                DNDChoice.PRIORITY -> mDrawableID = R.drawable.ic_error_white_24dp
                DNDChoice.ALARMS -> mDrawableID = R.drawable.ic_alarm_white_24dp
                DNDChoice.SILENCE -> mDrawableID = R.drawable.ic_notifications_off_white_24dp
            }

            views.setImageViewResource(R.id.dnd_toggle, mDrawableID)
            views.setInt(R.id.dnd_toggle, "setBackgroundResource", if (dndChoice != DNDChoice.OFF) R.drawable.round_button_enabled else R.drawable.round_button_disabled)
            views.setOnClickPendingIntent(R.id.dnd_toggle, getActionClickIntent(applicationContext, Actions.DONOTDISTURB))
        }

        if (ringerAction != null) {
            val ringerChoice = RingerChoice.valueOf(ringerAction!!.choice)
            var mDrawableID = R.drawable.ic_vibration_white_24dp

            when (ringerChoice) {
                RingerChoice.VIBRATION -> mDrawableID = R.drawable.ic_vibration_white_24dp
                RingerChoice.SOUND -> mDrawableID = R.drawable.ic_notifications_active_white_24dp
                RingerChoice.SILENT -> mDrawableID = R.drawable.ic_volume_off_white_24dp
            }

            views.setImageViewResource(R.id.ringer_toggle, mDrawableID)
            views.setInt(R.id.ringer_toggle, "setBackgroundResource", if (ringerChoice != RingerChoice.SILENT) R.drawable.round_button_enabled else R.drawable.round_button_disabled)
            views.setOnClickPendingIntent(R.id.ringer_toggle, getActionClickIntent(applicationContext, Actions.RINGER))
        }

        if (torchAction != null) {
            views.setInt(R.id.torch_toggle, "setBackgroundResource", if (torchAction!!.isEnabled) R.drawable.round_button_enabled else R.drawable.round_button_disabled)
            views.setOnClickPendingIntent(R.id.torch_toggle, getActionClickIntent(applicationContext, Actions.TORCH))
        }

        return views
    }

    private fun getTapIntent(context: Context?): PendingIntent {
        val onClickIntent = Intent(context!!.applicationContext, LaunchActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(context, 0, onClickIntent, 0)
    }

    private fun getActionClickIntent(context: Context?, action: Actions): PendingIntent {
        val onClickIntent = Intent(context!!.applicationContext, DashboardTileProviderService::class.java)
                .setAction(action.name)
        return PendingIntent.getService(context, action.value, onClickIntent, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != null) {
            val action = Actions.valueOf(intent.action!!)
            when (action) {
                Actions.WIFI -> run {
                    if (wifiAction == null) {
                        requestUpdate()
                        return@run
                    }

                    requestAction(ToggleAction(Actions.WIFI, !wifiAction!!.isEnabled))
                }
                Actions.BLUETOOTH -> run {
                    if (btAction == null) {
                        requestUpdate()
                        return@run
                    }

                    requestAction(ToggleAction(Actions.BLUETOOTH, !btAction!!.isEnabled))
                }
                Actions.LOCKSCREEN -> requestAction(NormalAction(Actions.LOCKSCREEN))
                Actions.DONOTDISTURB -> run {
                    if (dndAction == null) {
                        requestUpdate()
                        return@run
                    }

                    requestAction(
                            if (dndAction is ToggleAction) {
                                ToggleAction(Actions.DONOTDISTURB, !(dndAction as ToggleAction).isEnabled)
                            } else {
                                MultiChoiceAction(Actions.DONOTDISTURB, (dndAction as MultiChoiceAction).choice + 1)
                            }
                    )
                }
                Actions.RINGER -> run {
                    if (ringerAction == null) {
                        requestUpdate()
                        return@run
                    }

                    requestAction(MultiChoiceAction(Actions.RINGER, ringerAction!!.choice + 1))
                }
                Actions.TORCH -> run {
                    if (torchAction == null) {
                        requestUpdate()
                        return@run
                    }

                    requestAction(ToggleAction(Actions.TORCH, !torchAction!!.isEnabled))
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    @Volatile
    protected var mPhoneNodeWithApp: Node? = null
    protected var mConnectionStatus = WearConnectionStatus.DISCONNECTED

    private var battStatus: BatteryStatus? = null
    private var wifiAction: ToggleAction? = null
    private var btAction: ToggleAction? = null
    private var dndAction: Action? = null
    private var ringerAction: MultiChoiceAction? = null
    private var torchAction: ToggleAction? = null

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val data = messageEvent.data ?: return

        GlobalScope.launch(Dispatchers.Unconfined) {
            if (messageEvent.path.contains(WearableHelper.WifiPath)) {
                val wifiStatus = data[0].toInt()
                var enabled = false
                when (wifiStatus) {
                    WifiManager.WIFI_STATE_DISABLING,
                    WifiManager.WIFI_STATE_DISABLED,
                    WifiManager.WIFI_STATE_UNKNOWN -> enabled = false
                    WifiManager.WIFI_STATE_ENABLING,
                    WifiManager.WIFI_STATE_ENABLED -> enabled = true
                }

                wifiAction = ToggleAction(Actions.WIFI, enabled)
            } else if (messageEvent.path.contains(WearableHelper.BluetoothPath)) {
                val bt_status = data[0].toInt()
                var enabled = false

                when (bt_status) {
                    BluetoothAdapter.STATE_OFF,
                    BluetoothAdapter.STATE_TURNING_OFF -> enabled = false
                    BluetoothAdapter.STATE_ON,
                    BluetoothAdapter.STATE_TURNING_ON -> enabled = true
                }

                btAction = ToggleAction(Actions.BLUETOOTH, enabled)
            } else if (messageEvent.path == WearableHelper.BatteryPath) {
                val jsonData: String = data.bytesToString()
                battStatus = deserializer(jsonData, BatteryStatus::class.java)
            } else if (messageEvent.path == WearableHelper.ActionsPath) {
                val jsonData: String = data.bytesToString()
                val action = deserializer(jsonData, Action::class.java)!!
                when (action.actionType) {
                    Actions.WIFI -> wifiAction = action as ToggleAction
                    Actions.BLUETOOTH -> btAction = action as ToggleAction
                    Actions.TORCH -> torchAction = action as ToggleAction
                    Actions.DONOTDISTURB -> dndAction = if (action is ToggleAction) action else action as MultiChoiceAction
                    Actions.RINGER -> ringerAction = action as MultiChoiceAction
                }
            }

            // Send update if tile is in focus
            if (mInFocus && !isIdForDummyData(id)) {
                sendRemoteViews()
            }
        }
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        GlobalScope.launch(Dispatchers.Unconfined) {
            mPhoneNodeWithApp = pickBestNodeId(capabilityInfo.nodes)

            mConnectionStatus = if (mPhoneNodeWithApp == null) {
                WearConnectionStatus.DISCONNECTED
            } else {
                WearConnectionStatus.CONNECTED
            }

            if (mInFocus && !isIdForDummyData(id)) {
                sendRemoteViews()
            }
        }
    }

    protected suspend fun checkConnectionStatus() {
        mPhoneNodeWithApp = checkIfPhoneHasApp()

        mConnectionStatus = if (mPhoneNodeWithApp == null) {
            WearConnectionStatus.DISCONNECTED
        } else {
            WearConnectionStatus.CONNECTED
        }

        if (mInFocus && !isIdForDummyData(id)) {
            sendRemoteViews()
        }
    }

    protected suspend fun checkIfPhoneHasApp(): Node? {
        var node: Node? = null

        try {
            val capabilityInfo = Wearable.getCapabilityClient(this@DashboardTileProviderService)
                    .getCapability(WearableHelper.CAPABILITY_PHONE_APP, CapabilityClient.FILTER_REACHABLE)
                    .await()
            node = pickBestNodeId(capabilityInfo.nodes)
        } catch (e: ExecutionException) {
            writeLine(Log.ERROR, e)
        } catch (e: InterruptedException) {
            writeLine(Log.ERROR, e)
        }

        return node
    }

    protected suspend fun connect(): Boolean {
        if (mPhoneNodeWithApp == null)
            mPhoneNodeWithApp = checkIfPhoneHasApp()

        return mPhoneNodeWithApp != null
    }

    protected fun requestUpdate() {
        GlobalScope.launch(Dispatchers.Unconfined) {
            if (connect()) {
                sendMessage(mPhoneNodeWithApp!!.id, WearableHelper.UpdatePath, null)
            }
        }
    }

    protected fun requestAction(action: Action) {
        requestAction(serializer(action, Action::class.java))
    }

    protected fun requestAction(actionJSONString: String) {
        GlobalScope.launch(Dispatchers.Unconfined) {
            if (connect()) {
                sendMessage(mPhoneNodeWithApp!!.id, WearableHelper.ActionsPath, actionJSONString.stringToBytes())
            }
        }
    }

    protected suspend fun sendMessage(nodeID: String, path: String, data: ByteArray?) {
        try {
            Wearable.getMessageClient(this@DashboardTileProviderService)
                    .sendMessage(nodeID, path, data)
                    .await()
        } catch (ex: ExecutionException) {
            if (ex.cause is ApiException) {
                val apiEx = ex.cause as ApiException
                if (apiEx.statusCode == WearableStatusCodes.TARGET_NODE_NOT_CONNECTED) {
                    mConnectionStatus = WearConnectionStatus.DISCONNECTED

                    if (mInFocus && !isIdForDummyData(id)) {
                        sendRemoteViews()
                    }
                }
            }

            writeLine(Log.ERROR, ex)
        } catch (e: Exception) {
            writeLine(Log.ERROR, e)
        }
    }
}