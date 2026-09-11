package net.jurgensen.vw270telemetry.collectors

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import net.jurgensen.vw270telemetry.Runtime
import net.jurgensen.vw270telemetry.data.TelemetryEvent

/**
 * 0.1.3 phone-side Android Auto probe.
 *
 * The 0.1.2 real-car capture ruled out generic network, input-device, audio and media-route
 * snapshots as useful vehicle-telemetry surfaces. This collector therefore concentrates on the
 * exported Android Auto content providers that are reachable from the phone process.
 *
 * Every operation here is read-only: package metadata, getType() and query(). It never calls
 * insert/update/delete/call/openFile and never opens the Android Auto USB accessory.
 */
class ProjectionProbeCollector(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val resolver = context.contentResolver

    @Volatile private var running = false
    private var sequence = 0L
    private var providerInfoByAuthority: Map<String, ProviderInfo> = emptyMap()

    private val periodic = object : Runnable {
        override fun run() {
            if (!running) return
            snapshotAsync("periodic")
            handler.postDelayed(this, PERIOD_MS)
        }
    }

    fun start(reason: String = "projection") {
        if (running) return
        running = true
        Runtime.hub.emit(
            TelemetryEvent("probe", "collector", "started", attributes = mapOf("reason" to reason)),
            mqtt = false,
        )

        Thread({
            snapshotProviderInventory(reason)
            snapshotOnce("start")
        }, "vw270-provider-start").start()

        handler.postDelayed({ snapshotAsync("t_plus_5s") }, 5_000L)
        handler.postDelayed({ snapshotAsync("t_plus_15s") }, 15_000L)
        handler.postDelayed(periodic, PERIOD_MS)
    }

    fun stop(reason: String = "disconnect") {
        if (!running) return
        snapshotOnce("before_stop:$reason")
        running = false
        handler.removeCallbacksAndMessages(null)
        Runtime.hub.emit(
            TelemetryEvent("probe", "collector", "stopped", attributes = mapOf("reason" to reason)),
            mqtt = false,
        )
    }

    fun snapshotOnce(reason: String = "manual") {
        val seq = ++sequence
        if (providerInfoByAuthority.isEmpty()) snapshotProviderInventory(reason)

        TARGETS.forEach { target ->
            probeProvider(target, reason, seq)
        }

        Runtime.hub.emit(
            TelemetryEvent(
                "probe",
                "provider_pass",
                mapOf(
                    "sequence" to seq,
                    "reason" to reason,
                    "targets" to TARGETS.map { it.authority },
                ),
            ),
            mqtt = false,
        )
    }

    private fun snapshotAsync(reason: String) {
        Thread({ snapshotOnce(reason) }, "vw270-provider-probe").start()
    }

    private fun snapshotProviderInventory(reason: String) {
        try {
            val pm = context.packageManager
            val flags = PackageManager.GET_PROVIDERS or
                PackageManager.GET_META_DATA or
                PackageManager.GET_URI_PERMISSION_PATTERNS
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(AA_PACKAGE, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(AA_PACKAGE, flags)
            }

            val providers = pi.providers.orEmpty()
            providerInfoByAuthority = providers
                .flatMap { provider -> splitAuthorities(provider.authority).map { it to provider } }
                .toMap()

            val exported = providers.filter { it.exported }.map { providerInfo(it) }
            Runtime.hub.emit(
                TelemetryEvent(
                    "probe",
                    "provider_inventory",
                    mapOf(
                        "android_auto_version_name" to pi.versionName,
                        "android_auto_version_code" to pi.longVersionCode,
                        "exported" to exported,
                        "targets" to TARGETS.map { target ->
                            mapOf(
                                "authority" to target.authority,
                                "purpose" to target.purpose,
                                "present" to providerInfoByAuthority.containsKey(target.authority),
                            )
                        },
                        "explicitly_skipped" to SKIPPED_AUTHORITIES,
                    ),
                    attributes = mapOf("reason" to reason),
                ),
                mqtt = false,
            )
        } catch (t: Throwable) {
            emitError("provider_inventory", t, reason)
        }
    }

    private fun providerInfo(p: ProviderInfo): Map<String, Any?> = mapOf(
        "name" to p.name,
        "authority" to p.authority,
        "process" to p.processName,
        "exported" to p.exported,
        "enabled" to p.enabled,
        "grant_uri_permissions" to p.grantUriPermissions,
        "multiprocess" to p.multiprocess,
        "init_order" to p.initOrder,
        "read_permission" to p.readPermission,
        "write_permission" to p.writePermission,
        "direct_boot_aware" to p.directBootAware,
        "metadata_keys" to p.metaData?.keySet()?.sorted().orEmpty(),
        "path_permissions" to p.pathPermissions.orEmpty().map { permission ->
            mapOf(
                "path" to permission.path,
                "pattern_type" to permission.type,
                "read_permission" to permission.readPermission,
                "write_permission" to permission.writePermission,
            )
        },
        "uri_permission_patterns" to p.uriPermissionPatterns.orEmpty().map { pattern ->
            mapOf("path" to pattern.path, "pattern_type" to pattern.type)
        },
    )

    private fun probeProvider(target: ProviderTarget, reason: String, sequence: Long) {
        val provider = providerInfoByAuthority[target.authority]
        if (provider == null) {
            emitProvider(
                target,
                null,
                "unavailable",
                reason,
                sequence,
                mapOf("error" to "authority_not_present"),
            )
            return
        }
        if (!provider.exported) {
            emitProvider(
                target,
                null,
                "unavailable",
                reason,
                sequence,
                mapOf("error" to "provider_not_exported"),
            )
            return
        }

        val uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(target.authority).build()
        val typeResult = runCatching { resolver.getType(uri) }
        val typeValue = typeResult.getOrNull()
        val typeError = typeResult.exceptionOrNull()?.let(::errorText)

        try {
            val queryArgs = Bundle().apply { putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_ROWS) }
            val cursor = resolver.query(uri, target.projection?.toTypedArray(), queryArgs, null)
            if (cursor == null) {
                emitProvider(
                    target,
                    mapOf("uri" to uri.toString(), "mime_type" to typeValue, "rows" to emptyList<Any>()),
                    "unavailable",
                    reason,
                    sequence,
                    mapOf("error" to "query_returned_null", "get_type_error" to typeError),
                )
                return
            }

            cursor.use { c ->
                val result = captureCursor(c, target)
                emitProvider(
                    target,
                    mapOf(
                        "uri" to uri.toString(),
                        "mime_type" to typeValue,
                        "columns" to result.columns,
                        "rows" to result.rows,
                        "captured_rows" to result.rows.size,
                        "cursor_count" to runCatching { c.count }.getOrNull(),
                    ),
                    if (result.rows.isEmpty()) "empty" else "success",
                    reason,
                    sequence,
                    mapOf("get_type_error" to typeError),
                )
            }
        } catch (t: SecurityException) {
            emitProvider(target, null, "permission_denied", reason, sequence, mapOf("error" to errorText(t), "mime_type" to typeValue))
        } catch (t: UnsupportedOperationException) {
            emitProvider(target, null, "unsupported", reason, sequence, mapOf("error" to errorText(t), "mime_type" to typeValue))
        } catch (t: IllegalArgumentException) {
            emitProvider(target, null, "unsupported", reason, sequence, mapOf("error" to errorText(t), "mime_type" to typeValue))
        } catch (t: Throwable) {
            emitProvider(target, null, "error", reason, sequence, mapOf("error" to errorText(t), "mime_type" to typeValue))
        }
    }

    private fun captureCursor(cursor: Cursor, target: ProviderTarget): CursorCapture {
        val allColumns = cursor.columnNames.toList()
        val columns = allColumns.take(MAX_COLUMNS)
        val indexes = columns.map { cursor.getColumnIndex(it) }
        val rows = mutableListOf<Map<String, Any?>>()

        while (rows.size < MAX_ROWS && cursor.moveToNext()) {
            val keyHint = findKeyHint(cursor, columns, indexes)
            val row = linkedMapOf<String, Any?>()
            columns.forEachIndexed { position, column ->
                val index = indexes[position]
                if (index < 0) return@forEachIndexed
                row[column] = safeCursorValue(cursor, index, column, keyHint, target)
            }
            rows += row
        }
        return CursorCapture(columns, rows)
    }

    private fun findKeyHint(cursor: Cursor, columns: List<String>, indexes: List<Int>): String? {
        columns.forEachIndexed { position, column ->
            if (column.lowercase() !in KEY_COLUMNS) return@forEachIndexed
            val index = indexes[position]
            if (index < 0 || cursor.isNull(index)) return@forEachIndexed
            return runCatching { cursor.getString(index) }.getOrNull()
        }
        return null
    }

    private fun safeCursorValue(
        cursor: Cursor,
        index: Int,
        column: String,
        keyHint: String?,
        target: ProviderTarget,
    ): Any? {
        if (cursor.isNull(index)) return null
        val lowerColumn = column.lowercase()
        val sensitive = isSensitive(lowerColumn) ||
            (lowerColumn in VALUE_COLUMNS && keyHint?.let(::isSensitive) == true)
        if (sensitive) return "<redacted>"

        return when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
            Cursor.FIELD_TYPE_BLOB -> "<blob:${cursor.getBlob(index)?.size ?: 0} bytes>"
            Cursor.FIELD_TYPE_STRING -> sanitizeString(cursor.getString(index), target)
            else -> null
        }
    }

    private fun sanitizeString(value: String?, target: ProviderTarget): String? {
        if (value == null) return null
        val trimmed = value.replace('\n', ' ').replace('\r', ' ')
        if (looksCredentialLike(trimmed)) return "<redacted:opaque:${trimmed.length}>"
        return trimmed.take(if (target.authority == PROJECTION_AUTHORITY) 120 else MAX_STRING)
    }

    private fun looksCredentialLike(value: String): Boolean {
        if (value.startsWith("eyJ") && value.count { it == '.' } >= 2) return true
        if (value.length >= 96 && value.none { it.isWhitespace() } && value.count { it == ':' || it == '/' } < 3) return true
        return false
    }

    private fun isSensitive(value: String): Boolean {
        val lower = value.lowercase()
        return SENSITIVE_TOKENS.any(lower::contains)
    }

    private fun emitProvider(
        target: ProviderTarget,
        value: Any?,
        status: String,
        reason: String,
        sequence: Long,
        attributes: Map<String, Any?> = emptyMap(),
    ) {
        Runtime.hub.emit(
            TelemetryEvent(
                "aa_provider",
                target.key,
                value,
                status,
                attributes = attributes + mapOf(
                    "authority" to target.authority,
                    "purpose" to target.purpose,
                    "reason" to reason,
                    "sequence" to sequence,
                ),
            ),
            mqtt = false,
        )
    }

    private fun emitError(key: String, t: Throwable, reason: String) {
        Runtime.hub.emit(
            TelemetryEvent(
                "probe",
                key,
                null,
                "error",
                attributes = mapOf("error" to errorText(t), "reason" to reason),
            ),
            mqtt = false,
        )
    }

    private fun splitAuthorities(authority: String?): List<String> =
        authority?.split(';')?.map(String::trim)?.filter(String::isNotBlank).orEmpty()

    private fun errorText(t: Throwable) = "${t.javaClass.simpleName}: ${t.message ?: ""}".take(300)

    private data class ProviderTarget(
        val key: String,
        val authority: String,
        val purpose: String,
        val projection: List<String>? = null,
    )

    private data class CursorCapture(
        val columns: List<String>,
        val rows: List<Map<String, Any?>>,
    )

    companion object {
        private const val AA_PACKAGE = "com.google.android.projection.gearhead"
        private const val PERIOD_MS = 30_000L
        private const val MAX_ROWS = 20
        private const val MAX_COLUMNS = 32
        private const val MAX_STRING = 240
        private const val PROJECTION_AUTHORITY = "androidx.car.app.connection"

        private val TARGETS = listOf(
            ProviderTarget(
                key = "projection_state",
                authority = PROJECTION_AUTHORITY,
                purpose = "official Android Auto projection state",
                projection = listOf("CarConnectionState"),
            ),
            ProviderTarget(
                key = "shared_preferences",
                authority = "com.google.android.gearhead.shared_preferences_provider",
                purpose = "Android Auto shared/session configuration exposed by provider",
            ),
            ProviderTarget(
                key = "troubleshooter",
                authority = "com.google.android.projection.gearhead.troubleshooter_provider",
                purpose = "Android Auto troubleshooting/session diagnostics",
            ),
            ProviderTarget(
                key = "coolwalk_colors",
                authority = "com.google.android.projection.gearhead.color_provider",
                purpose = "small exported provider used as a control for projection-side access",
            ),
        )

        // Explicitly not queried. Microphone/file providers are unrelated to vehicle telemetry or
        // could expose media/files rather than structured session state.
        private val SKIPPED_AUTHORITIES = listOf(
            "com.google.android.projection.gearhead.provider",
            "com.google.android.projection.gearhead.devsettings.fileprovider",
            "com.google.android.apps.auto.components.bugreport.fileprovider",
            "com.google.android.projection.gearhead.icons",
        )

        private val KEY_COLUMNS = setOf("key", "name", "preference", "preference_key", "pref_key")
        private val VALUE_COLUMNS = setOf("value", "data", "string_value", "pref_value")
        private val SENSITIVE_TOKENS = listOf(
            "password", "passwd", "secret", "token", "credential", "cookie", "authorization",
            "account", "email", "phone_number", "contact", "message_body",
        )
    }
}
