package com.mylibrary.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mylibrary.app.di.ServiceLocator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeState(
    val libraries: Int = 0, val speakers: Int = 0, val words: Int = 0,
    val bytes: Long = 0, val active: Int = 0, val reviews: Int = 0,
)

class HomeVm : BaseVm() {
    private val db = ServiceLocator.db
    val state = combine(
        listOf<Flow<Any>>(
            db.libraries().observeCount(), db.speakers().observeCount(), db.words().observeCount(),
            db.words().observeBytes(), db.jobs().observeActiveCount(), db.reviews().observeCount(),
        )
    ) { a ->
        HomeState(a[0] as Int, a[1] as Int, a[2] as Int, a[3] as Long, a[4] as Int, a[5] as Int)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeState())
}

@Composable
fun HomeScreen(nav: NavController) {
    val vm = rememberVm { HomeVm() }
    val st by vm.state.collectAsState()
    ScreenScaffold(
        title = "مكتبتي الصوتية", messages = vm.messages, processing = st.active > 0,
        actions = {
            IconButton(onClick = { nav.navigate(Routes.SEARCH) }) { Icon(Icons.Filled.Search, "بحث") }
            IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, "الإعدادات") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                }
                Column {
                    Text("مكتبتي الصوتية", style = MaterialTheme.typography.headlineSmall)
                    Text("كلمة واحدة بصوت كل متحدث", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("المكتبات", st.libraries.toString(), Modifier.weight(1f))
                StatCard("المتحدثون", st.speakers.toString(), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("الكلمات المحفوظة", st.words.toString(), Modifier.weight(1f))
                StatCard("التخزين", formatBytes(st.bytes), Modifier.weight(1f))
            }
            HomeButton(Icons.Filled.Mic, "استيراد تسجيل") { nav.navigate("import/false") }
            HomeButton(Icons.Filled.Movie, "استيراد فيديو") { nav.navigate("import/true") }
            HomeButton(Icons.Filled.LibraryMusic, "مكتبة الأصوات") { nav.navigate(Routes.LIBS) }
            HomeButton(Icons.Filled.Pending, "المهام الجارية" + if (st.active > 0) " (${st.active})" else "") { nav.navigate(Routes.JOBS) }
            HomeButton(Icons.Filled.Checklist, "مراجعة المقاطع" + if (st.reviews > 0) " (${st.reviews})" else "") { nav.navigate("reviews") }
            HomeButton(Icons.Filled.Search, "البحث عن كلمة") { nav.navigate(Routes.SEARCH) }
            HomeButton(Icons.Filled.Settings, "الإعدادات") { nav.navigate(Routes.SETTINGS) }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeButton(icon: ImageVector, text: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Icon(icon, null)
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f))
    }
}
