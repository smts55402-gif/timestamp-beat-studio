package com.timestampbeatstudio.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.timestampbeatstudio.app.ui.AppNav
import com.timestampbeatstudio.app.ui.TimestampBeatStudioTheme

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = AppContainer(this)
        setContent {
            TimestampBeatStudioTheme {
                AppNav(container)
            }
        }
    }
}
