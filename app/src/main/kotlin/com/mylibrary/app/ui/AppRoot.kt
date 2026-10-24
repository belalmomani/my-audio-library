package com.mylibrary.app.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mylibrary.app.di.ServiceLocator
import com.mylibrary.core.JobStage

object Routes {
    const val HOME = "home"
    const val JOBS = "jobs"
    const val LIBS = "libraries"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val AUTH = "auth"

    fun editor(mode: String, sourceId: String, start: Long, end: Long, libraryId: String, wordId: String, reviewId: String) =
        "editor?mode=$mode&sourceId=${e(sourceId)}&start=$start&end=$end&libraryId=${e(libraryId)}&wordId=${e(wordId)}&reviewId=${e(reviewId)}"

    // قيم مسار التنقل لا تقبل الفراغ؛ نرمز له بـ "_" ثم نفكّه في AppRoot
    private fun e(v: String) = v.ifEmpty { "_" }
}

data class EditorArgs(
    val mode: String, val sourceId: String, val start: Long, val end: Long,
    val libraryId: String, val wordId: String, val reviewId: String,
)

@Composable
fun AppRoot(openJobId: String? = null) {
    val nav = rememberNavController()

    // فتح المهمة من إشعار «جاهز للمراجعة»
    LaunchedEffect(openJobId) {
        if (openJobId != null) {
            val j = ServiceLocator.db.jobs().get(openJobId)
            if (j?.stage == JobStage.AWAITING_SPEAKER_REVIEW.name) nav.navigate("job/$openJobId/speakers") else nav.navigate(Routes.JOBS)
        }
    }

    Surface(Modifier) {
        NavHost(nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) { HomeScreen(nav) }
            composable("import/{video}", listOf(navArgument("video") { type = NavType.BoolType })) {
                ImportScreen(nav, it.arguments?.getBoolean("video") ?: false)
            }
            composable(Routes.JOBS) { JobsScreen(nav) }
            composable("job/{jobId}/speakers") { SpeakerReviewScreen(nav, it.arguments?.getString("jobId").orEmpty()) }
            composable(Routes.LIBS) { LibraryListScreen(nav) }
            composable("library/{id}") { LibraryDetailScreen(nav, it.arguments?.getString("id").orEmpty()) }
            composable(
                "reviews?libraryId={libraryId}",
                listOf(navArgument("libraryId") { type = NavType.StringType; defaultValue = "" }),
            ) { ReviewListScreen(nav, it.arguments?.getString("libraryId").orEmpty()) }
            composable(
                "editor?mode={mode}&sourceId={sourceId}&start={start}&end={end}&libraryId={libraryId}&wordId={wordId}&reviewId={reviewId}",
                listOf("mode", "sourceId", "start", "end", "libraryId", "wordId", "reviewId").map {
                    navArgument(it) { type = NavType.StringType; defaultValue = "" }
                },
            ) { e ->
                val g = { k: String -> e.arguments?.getString(k).orEmpty().let { v -> if (v == "_") "" else v } }
                ClipEditorScreen(
                    nav,
                    EditorArgs(g("mode").ifEmpty { "ADD" }, g("sourceId"), g("start").toLongOrNull() ?: 0, g("end").toLongOrNull() ?: 2000,
                        g("libraryId"), g("wordId"), g("reviewId")),
                )
            }
            composable(Routes.SEARCH) { SearchScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.AUTH) { AuthScreen(nav) }
        }
    }
}
