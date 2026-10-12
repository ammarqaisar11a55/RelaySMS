package io.github.ammarqaisar11a55.relaysms.core.model

/** The two ways one installation of RelaySMS can be configured. */
enum class DeviceRole(val wireName: String) {
    /** Phone that holds the SIM and forwards a copy of its incoming SMS. */
    SOURCE("source"),

    /** Phone that receives, decrypts and displays the forwarded messages. */
    COMPANION("companion");

    companion object {
        fun fromWireName(value: String?): DeviceRole? = entries.firstOrNull { it.wireName == value }
    }
}

enum class ThemeMode(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromKey(value: String?): ThemeMode = entries.firstOrNull { it.key == value } ?: SYSTEM
    }
}
