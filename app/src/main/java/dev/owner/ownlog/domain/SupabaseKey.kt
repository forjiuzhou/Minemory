package dev.owner.ownlog.domain

const val SUPABASE_SECRET_KEY_ERROR =
    "这是 secret key，不能保存。手机里只用 publishable key（sb_publishable_...）或旧的 anon key。不要使用 sb_secret_ 或 service_role。"

fun isSupabaseSecretKey(key: String): Boolean = key.trim().startsWith("sb_secret_")

/**
 * New sb_* keys are not JWTs. Supabase's gateway rejects them in Authorization: Bearer,
 * so those requests send only the apikey header. Legacy JWT anon keys (eyJ...) still send both.
 */
fun supabaseAuthorizationHeader(key: String): String? {
    val trimmed = key.trim()
    return if (trimmed.startsWith("sb_")) null else "Bearer $trimmed"
}
