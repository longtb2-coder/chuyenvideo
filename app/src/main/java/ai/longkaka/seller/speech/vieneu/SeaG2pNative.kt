package ai.longkaka.seller.speech.vieneu

/**
 * Small JNI bridge to the upstream SEA-G2P C ABI.
 * A handle is owned by one synthesis worker and must not be used concurrently.
 */
internal class SeaG2pNative {
    companion object {
        init {
            System.loadLibrary("sea_g2p_rs")
            System.loadLibrary("sea_g2p_jni")
        }
    }

    external fun nativeOpen(dictionaryPath: String): Long
    external fun nativeClose(handle: Long)
    external fun nativePhonemize(handle: Long, text: String, punctuationNormalize: Boolean): String?
    external fun nativeNormalize(handle: Long, text: String, punctuationNormalize: Boolean): String?
    external fun nativePunctuationNormalize(text: String): String?
    external fun nativeLastError(): String?
}
