package com.ashim.ashforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ashim.ashforge.ui.MainViewModel
import com.ashim.ashforge.ui.AshForgeApp
import com.ashim.ashforge.ui.theme.PocketTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            PocketTheme(themeMode = state.themeMode) {
                AshForgeApp(vm)
            }
        }
    }
}
