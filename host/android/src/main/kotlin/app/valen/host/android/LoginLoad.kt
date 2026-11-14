/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from Orpheon (https://github.com/kota-zo/Orpheon), base/host-android, `LoginLoad.kt`.
 * See NOTICE.
 */
package app.valen.host.android

/**
 * The sign-in page as the user meets it while it opens: the app's own waiting screen covers the
 * page until it has content ([LOADING]), and an error with "Try again" replaces a page that did not
 * come ([FAILED]). [WebPlayerHost] feeds this what the WebView reports; the rules below are pure.
 */
enum class LoginLoad {
    /** No sign-in page is being opened (or it is past this stage: signed in, or the player is back). */
    NONE,
    /** Asked for, no content yet: the waiting screen shows, the page is kept out of sight. */
    LOADING,
    /** Spotify's page has content: it is shown. */
    SHOWN,
    /** It did not load, or stayed blank: the error shows. */
    FAILED;

    /** What to do after a probe found the page still without content. */
    enum class Blank { WAIT, RELOAD, FAIL }

    companion object {
        /** From the request to a page with content; past it the load is given up. */
        const val TIMEOUT_MS = 25_000L

        /** The page is looked at this long after it finished loading, then every [PROBE_EVERY_MS]. */
        const val FIRST_PROBE_MS = 500L
        const val PROBE_EVERY_MS = 1_000L

        /** Probes of a page that finished but stays empty: loaded again once, then given up. */
        private const val RELOAD_AFTER = 6
        private const val FAIL_AFTER = 10

        /**
         * Read in the page: how much it shows (its text and its fields, buttons and links). Spotify's
         * login is a script-built page, so an empty body after "finished" is a page that did not run.
         */
        const val PAINT_JS = "(function(){var b=document.body;if(!b)return 0;" +
            "return (b.innerText||'').trim().length+b.querySelectorAll('input,button,a').length})()"

        /** [PAINT_JS]'s answer as evaluateJavascript hands it back ("42", "0", "null"). */
        fun painted(answer: String?): Boolean = (answer?.trim()?.trim('"')?.toDoubleOrNull() ?: 0.0) > 0.0

        /** [probes] empty looks so far at this page; [reloaded]: it was already loaded again once. */
        fun blank(probes: Int, reloaded: Boolean): Blank = when {
            probes < RELOAD_AFTER -> Blank.WAIT
            !reloaded -> Blank.RELOAD
            probes < FAIL_AFTER -> Blank.WAIT
            else -> Blank.FAIL
        }

        /** Spotify's own account pages: the one whose failure is the sign-in's failure. */
        fun isSpotifyAccounts(url: String?): Boolean = hostOf(url) == "accounts.spotify.com"

        /**
         * A page of the sign-in: Spotify's account and challenge pages, a `/login` path anywhere,
         * and the "Continue with Google / Facebook / Apple" pages. Judged on the host and the path,
         * not on the whole address: a player address that merely carries one of these names in its
         * query (a `continue=` back to the login) is not a sign-in page.
         */
        fun isLoginUrl(url: String?): Boolean {
            val uri = runCatching { java.net.URI((url ?: return false).substringBefore('#').substringBefore('?')) }.getOrNull() ?: return false
            val host = uri.host?.lowercase() ?: return false
            if (host == "accounts.spotify.com" || host == "challenge.spotify.com") return true
            if (uri.path?.contains("/login") == true) return true
            return host == "accounts.google.com" || host == "facebook.com" || host.endsWith(".facebook.com") || host == "appleid.apple.com"
        }

        private fun hostOf(url: String?): String? =
            runCatching { java.net.URI((url ?: return null).substringBefore('#').substringBefore('?')).host?.lowercase() }.getOrNull()
    }
}
