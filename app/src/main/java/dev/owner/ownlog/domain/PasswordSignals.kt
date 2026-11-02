package dev.owner.ownlog.domain

/**
 * Password detection using the same bit masks as android.text.InputType.
 * Kept free of Android imports so unit tests can run on a plain JVM.
 *
 * TYPE_TEXT_VARIATION_URI and TYPE_NUMBER_VARIATION_PASSWORD share the variation
 * bits 0x10. The class bits decide which one it is, otherwise URLs would be dropped.
 */
object PasswordSignals {
    const val TYPE_MASK_CLASS = 0x0000000f
    const val TYPE_MASK_VARIATION = 0x00000ff0
    const val TYPE_CLASS_TEXT = 0x00000001
    const val TYPE_CLASS_NUMBER = 0x00000002
    const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010
    const val TYPE_TEXT_VARIATION_URI = 0x00000010
    const val TYPE_TEXT_FLAG_NO_SUGGESTIONS = 0x00080000

    fun isPassword(isPasswordFlag: Boolean, inputType: Int): Boolean {
        if (isPasswordFlag) return true
        return isPasswordInputType(inputType)
    }

    fun isPasswordInputType(inputType: Int): Boolean {
        if (inputType == 0) return false
        val klass = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        return when (klass) {
            TYPE_CLASS_TEXT ->
                variation == TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == TYPE_TEXT_VARIATION_WEB_PASSWORD
            TYPE_CLASS_NUMBER -> variation == TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
