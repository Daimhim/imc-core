package org.daimhim.imc_core.demo

import org.chromium.net.*
import org.junit.Test
import java.net.URL
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class CronetUnitTest {
    @Test
    fun addition_isCorrect() {
        val newFixedThreadPool = Executors.newFixedThreadPool(3)
        val builder = CronetEngine.Builder(object : ICronetEngineBuilder(){
            override fun addPublicKeyPins(
                p0: String?,
                p1: MutableSet<ByteArray>?,
                p2: Boolean,
                p3: Date?
            ): ICronetEngineBuilder {
                TODO("Not yet implemented")
            }

            override fun addQuicHint(p0: String?, p1: Int, p2: Int): ICronetEngineBuilder {
                TODO("Not yet implemented")
            }

            override fun enableHttp2(p0: Boolean): ICronetEngineBuilder {
                TODO("Not yet implemented")
            }

            override fun enableHttpCache(p0: Int, p1: Long): ICronetEngineBuilder {
                TODO("Not yet implemented")
            }

            override fun enablePublicKeyPinningBypassForLocalTrustAnchors(p0: Boolean): ICronetEngineBuilder {
                TODO("Not yet implemented")
            }

            override fun enableQuic(p0: Boolean): ICronetEngineBuilder {
                TODO("Not yet implemented")
            }

            override fun enableSdch(p0: Boolean): ICronetEngineBuilder {
                TODO("Not yet implemented")
            }

            override fun setExperimentalOptions(p0: String?): ICronetEngineBuilder {
                TODO("Not yet implemented")
            }

            override fun setLibraryLoader(p0: CronetEngine.Builder.LibraryLoader?): ICronetEngineBuilder {
                TODO("Not yet implemented")
            }

            override fun setStoragePath(p0: String?): ICronetEngineBuilder {
                TODO("Not yet implemented")
            }

            override fun setUserAgent(p0: String?): ICronetEngineBuilder {
                TODO("Not yet implemented")
            }

            override fun getDefaultUserAgent(): String {
                TODO("Not yet implemented")
            }

            override fun build(): ExperimentalCronetEngine {
                TODO("Not yet implemented")
            }

        })
        val build = builder.build()
        val request = build.newUrlRequestBuilder(
            "",
            object : UrlRequest.Callback() {
                override fun onRedirectReceived(p0: UrlRequest?, p1: UrlResponseInfo?, p2: String?) {
                    TODO("Not yet implemented")
                }

                override fun onResponseStarted(p0: UrlRequest?, p1: UrlResponseInfo?) {
                    TODO("Not yet implemented")
                }

                override fun onReadCompleted(p0: UrlRequest?, p1: UrlResponseInfo?, p2: ByteBuffer?) {
                    TODO("Not yet implemented")
                }

                override fun onSucceeded(p0: UrlRequest?, p1: UrlResponseInfo?) {
                    TODO("Not yet implemented")
                }

                override fun onFailed(p0: UrlRequest?, p1: UrlResponseInfo?, p2: CronetException?) {
                    TODO("Not yet implemented")
                }

                override fun onCanceled(request: UrlRequest?, info: UrlResponseInfo?) {
                    super.onCanceled(request, info)
                }
            },
            newFixedThreadPool
        ).build()
        request.start()
    }
}