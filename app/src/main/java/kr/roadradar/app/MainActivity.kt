package kr.roadradar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.roadradar.app.ui.MainScreen
import kr.roadradar.app.ui.RoadRadarViewModel

class MainActivity : ComponentActivity() {
    private val vm: RoadRadarViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by vm.ui.collectAsStateWithLifecycle()
                MainScreen(state, vm)
            }
        }
    }
}
