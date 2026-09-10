package app.consolepocket.model

import androidx.annotation.StringRes
import app.consolepocket.R

/**
 * Alle Console-URLs an einer Stelle. Wichtig fuer die "keine extra Fenster"-Policy
 * (NavigationPolicy) und die Schnellzugriffe.
 */
object ConsoleUrls {
    const val SCHEME_HTTPS = "https"
    const val CONSOLE_HOST = "console.cloud.google.com"
    const val BASE = "https://$CONSOLE_HOST"

    const val DASHBOARD = "$BASE/home/dashboard"
    const val COMPUTE = "$BASE/compute/instances"
    const val GKE = "$BASE/kubernetes/list"
    const val LOGS = "$BASE/logs"
    const val BILLING = "$BASE/billing"
    const val IAM = "$BASE/iam-admin/iam"
    const val BIGQUERY = "$BASE/bigquery"
    const val STORAGE = "$BASE/storage/browser"
    const val MONITORING = "$BASE/monitoring"
    const val CLOUD_SHELL = "$BASE/cloudshell"

    /** Lokale Shell (assets/shell) ueber androidx.webkit.WebViewAssetLoader. */
    const val SHELL_URL = "https://appassets.androidplatform.net/shell/index.html"
    const val SHELL_OFFLINE = "$SHELL_URL?mode=offline"
    const val SHELL_ERROR = "$SHELL_URL?mode=error"

    val quickLinks: List<QuickLink> = listOf(
        QuickLink(R.string.quick_home, DASHBOARD),
        QuickLink(R.string.quick_compute, COMPUTE),
        QuickLink(R.string.quick_gke, GKE),
        QuickLink(R.string.quick_logs, LOGS),
        QuickLink(R.string.quick_billing, BILLING),
        QuickLink(R.string.quick_iam, IAM),
        QuickLink(R.string.quick_bigquery, BIGQUERY),
        QuickLink(R.string.quick_storage, STORAGE),
        QuickLink(R.string.quick_monitoring, MONITORING),
        QuickLink(R.string.quick_shell, CLOUD_SHELL),
    )
}

data class QuickLink(@StringRes val labelRes: Int, val url: String)

/** Ein In-App-Tab. Kein Systemfenster, kein zweiter Task - nur Zustand in dieser App. */
data class TabSession(
    val id: String,
    val url: String,
    val title: String = "",
    val lastAccess: Long = 0L,
)
