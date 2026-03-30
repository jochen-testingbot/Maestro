package xcuitest.api

data class LaunchAppRequest(
    val bundleId: String,
    val launchArguments: Map<String, Any>? = null,
)