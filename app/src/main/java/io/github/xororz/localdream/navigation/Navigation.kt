package io.github.xororz.localdream.navigation

sealed class Screen(val route: String) {
    object HomePortal : Screen("home_portal")
    object ModelList : Screen("model_list")
    object ModelRun : Screen("model_run/{modelId}") {
        fun createRoute(modelId: String) = "model_run/$modelId"
    }
    object Chat : Screen("chat/{modelId}") {
        fun createRoute(modelId: String) = "chat/$modelId"
    }
    object Voice : Screen("voice")

    object Upscale : Screen("upscale")

    object History : Screen("history")
}
